package ai.codriverlabs.eksdx.infra;

import software.amazon.awscdk.*;
import software.amazon.awscdk.services.apigateway.*;import software.amazon.awscdk.services.cloudwatch.*;
import software.amazon.awscdk.services.dynamodb.*;
import software.amazon.awscdk.services.iam.*;
import software.amazon.awscdk.services.lambda.*;
import software.amazon.awscdk.services.lambda.Runtime;
import software.amazon.awscdk.services.logs.*;
import software.constructs.Construct;

import java.util.List;
import java.util.Map;

/**
 * CDK stack for EKS-DX Lambda service.
 * Uses REST API v1 (stable) with IAM auth on management endpoints.
 */
public class EksDxStack extends Stack {

    public EksDxStack(Construct scope, String id, StackProps props) {
        super(scope, id, props);

        // --- DynamoDB Tables ---
        Table clustersTable = Table.Builder.create(this, "ClustersTable")
            .tableName("eks-dx-clusters")
            .partitionKey(Attribute.builder().name("clusterName").type(AttributeType.STRING).build())
            .billingMode(BillingMode.PAY_PER_REQUEST)
            .removalPolicy(RemovalPolicy.RETAIN)
            .pointInTimeRecovery(true)
            .build();

        Table associationsTable = Table.Builder.create(this, "AssociationsTable")
            .tableName("eks-dx-associations")
            .partitionKey(Attribute.builder().name("PK").type(AttributeType.STRING).build())
            .sortKey(Attribute.builder().name("SK").type(AttributeType.STRING).build())
            .billingMode(BillingMode.PAY_PER_REQUEST)
            .removalPolicy(RemovalPolicy.RETAIN)
            .pointInTimeRecovery(true)
            .build();

        // --- Lambda Function ---
        Function fn = Function.Builder.create(this, "EksDxFunction")
            .functionName("eks-dx-service")
            .runtime(Runtime.JAVA_21)
            .handler("io.quarkus.amazon.lambda.runtime.QuarkusStreamHandler::handleRequest")
            .code(Code.fromAsset("../eks-dx-lambda/target/function.zip"))
            .memorySize(512)
            .timeout(Duration.seconds(30))
            .environment(Map.of(
                "EKS_DX_CLUSTERS_TABLE", clustersTable.getTableName(),
                "EKS_DX_ASSOCIATIONS_TABLE", associationsTable.getTableName()))
            .build();

        // SnapStart
        CfnFunction cfnFn = (CfnFunction) fn.getNode().getDefaultChild();
        cfnFn.addPropertyOverride("SnapStart", Map.of("ApplyOn", "PublishedVersions"));

        // Permissions
        clustersTable.grantReadWriteData(fn);
        associationsTable.grantReadWriteData(fn);
        fn.addToRolePolicy(PolicyStatement.Builder.create()
            .actions(List.of("sts:AssumeRole", "sts:TagSession"))
            .resources(List.of("arn:aws:iam::*:role/eks-dx-pod-*"))
            .build());

        // --- REST API ---
        LambdaRestApi api = LambdaRestApi.Builder.create(this, "EksDxApi")
            .handler(fn)
            .restApiName("eks-dx")
            .proxy(false)
            .deployOptions(StageOptions.builder()
                .accessLogDestination(new LogGroupLogDestination(
                    LogGroup.Builder.create(this, "ApiAccessLog")
                        .logGroupName("/aws/apigateway/eks-dx")
                        .retention(RetentionDays.ONE_MONTH)
                        .removalPolicy(RemovalPolicy.DESTROY)
                        .build()))
                .accessLogFormat(AccessLogFormat.jsonWithStandardFields())
                .build())
            .build();

        // /clusters — IAM auth
        software.amazon.awscdk.services.apigateway.IResource clusters = api.getRoot().addResource("clusters");
        clusters.addMethod("GET", new LambdaIntegration(fn),
            MethodOptions.builder().authorizationType(AuthorizationType.IAM).build());
        clusters.addMethod("POST", new LambdaIntegration(fn),
            MethodOptions.builder().authorizationType(AuthorizationType.IAM).build());

        // /clusters/{name} — IAM auth
        software.amazon.awscdk.services.apigateway.IResource clusterByName = clusters.addResource("{name}");
        clusterByName.addMethod("GET", new LambdaIntegration(fn),
            MethodOptions.builder().authorizationType(AuthorizationType.IAM).build());
        clusterByName.addMethod("DELETE", new LambdaIntegration(fn),
            MethodOptions.builder().authorizationType(AuthorizationType.IAM).build());

        // /clusters/{name}/jwks — IAM auth
        software.amazon.awscdk.services.apigateway.IResource jwks = clusterByName.addResource("jwks");
        jwks.addMethod("PUT", new LambdaIntegration(fn),
            MethodOptions.builder().authorizationType(AuthorizationType.IAM).build());

        // /clusters/{clusterName}/assets — OPEN (token-authenticated by Lambda)
        software.amazon.awscdk.services.apigateway.IResource assets = clusterByName.addResource("assets");
        assets.addMethod("POST", new LambdaIntegration(fn),
            MethodOptions.builder().authorizationType(AuthorizationType.NONE).build());

        // /clusters/{name}/pod-identity-associations — OPEN (webhook uses SA token)
        software.amazon.awscdk.services.apigateway.IResource associations = clusterByName.addResource("pod-identity-associations");
        associations.addMethod("ANY", new LambdaIntegration(fn));

        software.amazon.awscdk.services.apigateway.IResource associationById = associations.addResource("{id}");
        associationById.addMethod("ANY", new LambdaIntegration(fn));

        // --- CloudWatch Alarms ---
        Alarm.Builder.create(this, "LambdaErrorAlarm")
            .alarmName("eks-dx-lambda-errors")
            .alarmDescription("EKS-DX Lambda errors > 5 in 5 minutes")
            .metric(fn.metricErrors(MetricOptions.builder().period(Duration.minutes(5)).build()))
            .threshold(5)
            .evaluationPeriods(1)
            .treatMissingData(TreatMissingData.NOT_BREACHING)
            .build();

        Alarm.Builder.create(this, "LambdaThrottleAlarm")
            .alarmName("eks-dx-lambda-throttles")
            .metric(fn.metricThrottles(MetricOptions.builder().period(Duration.minutes(5)).build()))
            .threshold(1)
            .evaluationPeriods(1)
            .treatMissingData(TreatMissingData.NOT_BREACHING)
            .build();

        Alarm.Builder.create(this, "LambdaDurationAlarm")
            .alarmName("eks-dx-lambda-p99-duration")
            .metric(fn.metricDuration(MetricOptions.builder()
                .period(Duration.minutes(5)).statistic("p99").build()))
            .threshold(5000)
            .evaluationPeriods(3)
            .treatMissingData(TreatMissingData.NOT_BREACHING)
            .build();

        Alarm.Builder.create(this, "DynamoDbThrottleAlarm")
            .alarmName("eks-dx-dynamodb-throttles")
            .metric(clustersTable.metricThrottledRequestsForOperations(
                OperationsMetricOptions.builder()
                    .period(Duration.minutes(5))
                    .operations(List.of(Operation.GET_ITEM, Operation.PUT_ITEM, Operation.QUERY))
                    .build()))
            .threshold(1)
            .evaluationPeriods(1)
            .treatMissingData(TreatMissingData.NOT_BREACHING)
            .build();

        // --- Outputs ---
        CfnOutput.Builder.create(this, "Endpoint")
            .description("EKS-DX API endpoint")
            .value(api.getUrl())
            .build();

        CfnOutput.Builder.create(this, "ClustersTableName")
            .value(clustersTable.getTableName()).build();

        CfnOutput.Builder.create(this, "AssociationsTableName")
            .value(associationsTable.getTableName()).build();
    }
}
