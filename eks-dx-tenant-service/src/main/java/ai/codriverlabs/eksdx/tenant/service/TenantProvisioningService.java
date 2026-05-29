package ai.codriverlabs.eksdx.tenant.service;

import ai.codriverlabs.eksdx.tenant.model.TenantItem;
import ai.codriverlabs.eksdx.tenant.model.TenantProgress;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.DeleteItemRequest;
import software.amazon.awssdk.services.dynamodb.model.GetItemRequest;
import software.amazon.awssdk.services.dynamodb.model.GetItemResponse;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;
import software.amazon.awssdk.services.ec2.Ec2Client;
import software.amazon.awssdk.services.ec2.model.CreateKeyPairRequest;
import software.amazon.awssdk.services.ec2.model.CreateKeyPairResponse;
import software.amazon.awssdk.services.ec2.model.DeleteKeyPairRequest;
import software.amazon.awssdk.services.ec2.model.IamInstanceProfileSpecification;
import software.amazon.awssdk.services.ec2.model.LaunchTemplateSpecification;
import software.amazon.awssdk.services.ec2.model.ResourceType;
import software.amazon.awssdk.services.ec2.model.RunInstancesRequest;
import software.amazon.awssdk.services.ec2.model.RunInstancesResponse;
import software.amazon.awssdk.services.ec2.model.Tag;
import software.amazon.awssdk.services.ec2.model.TagSpecification;
import software.amazon.awssdk.services.ec2.model.TerminateInstancesRequest;
import software.amazon.awssdk.services.iam.IamClient;
import software.amazon.awssdk.services.iam.model.AddRoleToInstanceProfileRequest;
import software.amazon.awssdk.services.iam.model.AttachRolePolicyRequest;
import software.amazon.awssdk.services.iam.model.CreateInstanceProfileRequest;
import software.amazon.awssdk.services.iam.model.CreateRoleRequest;
import software.amazon.awssdk.services.iam.model.DeleteRoleRequest;
import software.amazon.awssdk.services.iam.model.DeleteRolePolicyRequest;
import software.amazon.awssdk.services.iam.model.PutRolePolicyRequest;
import software.amazon.awssdk.services.secretsmanager.SecretsManagerClient;
import software.amazon.awssdk.services.secretsmanager.model.CreateSecretRequest;
import software.amazon.awssdk.services.secretsmanager.model.DeleteSecretRequest;
import software.amazon.awssdk.services.secretsmanager.model.GetSecretValueRequest;
import software.amazon.awssdk.services.sts.StsClient;
import software.amazon.awssdk.services.sts.model.GetCallerIdentityRequest;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.cloudwatchevents.CloudWatchEventsClient;
import software.amazon.awssdk.services.cloudwatchevents.model.PutRuleRequest;
import software.amazon.awssdk.services.cloudwatchevents.model.PutTargetsRequest;
import software.amazon.awssdk.services.cloudwatchevents.model.Target;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.time.Instant;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Provisions and deprovisions per-tenant kubeadm clusters.
 *
 * Provisioning flow (all async after 202):
 *   1. Generate RSA-2048 SA signing key → Secrets Manager
 *   2. ec2:CreateKeyPair (SSH) → Secrets Manager
 *   3. iam:CreateRole with least-privilege inline policy
 *   4. ec2:RunInstances via Launch Template
 *   5. DynamoDB.put initial state
 *
 * The EC2 instance user data drives the rest of the state machine
 * and writes progress directly to DynamoDB via its instance profile.
 */
@ApplicationScoped
public class TenantProvisioningService {

    private static final Logger LOG = Logger.getLogger(TenantProvisioningService.class);

    @Inject DynamoDbClient dynamoDb;
    @Inject StsClient sts;
    @Inject IamClient iam;
    @Inject TenantNetworkService networkService;
    @Inject TenantIamService iamService;
    @Inject TenantEc2Service ec2Service;
    @Inject TenantDlmService dlmService;

    private final Ec2Client ec2 = Ec2Client.create();
    private final SecretsManagerClient secretsManager = SecretsManagerClient.create();
    private final SqsClient sqs = SqsClient.create();
    private final CloudWatchEventsClient events = CloudWatchEventsClient.create();

    @ConfigProperty(name = "eks-d-xpress.tenants-table")
    String tenantsTable;

    @ConfigProperty(name = "eks-d-xpress.clusters-table")
    String clustersTable;

    @ConfigProperty(name = "eks-d-xpress.tenant.lt-arm64-ondemand")
    String ltArm64Ondemand;

    @ConfigProperty(name = "eks-d-xpress.tenant.lt-arm64-spot")
    String ltArm64Spot;

    @ConfigProperty(name = "eks-d-xpress.tenant.lt-x86-ondemand")
    String ltX86Ondemand;

    @ConfigProperty(name = "eks-d-xpress.tenant.lt-x86-spot")
    String ltX86Spot;

    @ConfigProperty(name = "eks-d-xpress.tenant.vpc-id")
    String vpcId;

    @ConfigProperty(name = "eks-d-xpress.tenant.availability-zone", defaultValue = "")
    String availabilityZone;

    // -------------------------------------------------------------------------
    // Provision
    // -------------------------------------------------------------------------

    public String provision(String tenantId, String arch, String ec2PricingModel, String k8sVersion, boolean assignElasticIp, int diskSizeGb, String sshCidr) {
        LOG.infof("Provisioning tenant: %s (arch=%s, pricing=%s, k8s=%s)", tenantId, arch, ec2PricingModel, k8sVersion);

        String launchTemplateId = resolveLaunchTemplate(arch, ec2PricingModel);
        String region = System.getenv().getOrDefault("AWS_REGION", "us-east-1");
        String accountId = sts.getCallerIdentity(GetCallerIdentityRequest.builder().build()).account();
        String clusterName = "eks-d-xpress-" + tenantId;

        // 1. Network isolation (per-tenant subnets + security group)
        String az = availabilityZone.isEmpty() ? region + "a" : availabilityZone;
        TenantNetworkService.NetworkResult network = networkService.createTenantNetwork(
            tenantId, clusterName, vpcId, az, sshCidr);

        // 2. Secrets (SA signing key + SSH key pair)
        String signingKeyPem = generateRsaPrivateKeyPem();
        secretsManager.createSecret(CreateSecretRequest.builder()
            .name("eks-d-xpress/tenant/" + tenantId + "/signing-key")
            .secretString(signingKeyPem).build());
        CreateKeyPairResponse keyPairResp = ec2.createKeyPair(CreateKeyPairRequest.builder()
            .keyName("eks-d-xpress-tenant-" + tenantId).build());
        String sshKeyArn = secretsManager.createSecret(CreateSecretRequest.builder()
            .name("eks-d-xpress/tenant/" + tenantId + "/ssh-key")
            .secretString(keyPairResp.keyMaterial()).build()).arn();

        // 3. IAM role + instance profile
        TenantIamService.IamResult iamResult = iamService.createTenantRole(
            tenantId, clusterName, region, accountId);

        // 4. SQS + EventBridge (Karpenter interruption handling)
        String queueArn = createInterruptionQueue(clusterName, region, accountId);
        createEventBridgeRules(clusterName, queueArn);

        // 5. DLM (daily etcd backup)
        dlmService.createEtcdBackupPolicy(tenantId, clusterName);

        // 6. EC2 instance launch
        TenantEc2Service.Ec2Result ec2Result = ec2Service.launchInstance(
            tenantId, clusterName, launchTemplateId,
            network.publicSubnetId(), network.securityGroupId(),
            iamResult.instanceProfileName(), "eks-d-xpress-tenant-" + tenantId,
            region, k8sVersion, assignElasticIp, diskSizeGb);

        // 7. Write initial DynamoDB state
        String now = Instant.now().toString();
        Map<String, AttributeValue> item = new HashMap<>();
        item.put("tenantId", AttributeValue.fromS(tenantId));
        item.put("instanceId", AttributeValue.fromS(ec2Result.instanceId()));
        item.put("state", AttributeValue.fromS("provisioning"));
        item.put("phase", AttributeValue.fromS("EC2 instance launched"));
        item.put("progress", AttributeValue.fromN("0"));
        item.put("sshKeySecretArn", AttributeValue.fromS(sshKeyArn));
        item.put("updatedAt", AttributeValue.fromS(now));
        dynamoDb.putItem(PutItemRequest.builder().tableName(tenantsTable).item(item).build());

        return tenantId;
    }

    // -------------------------------------------------------------------------
    // Get state
    // -------------------------------------------------------------------------

    public TenantItem getState(String tenantId) {
        GetItemResponse resp = dynamoDb.getItem(GetItemRequest.builder()
            .tableName(tenantsTable)
            .key(Map.of("tenantId", AttributeValue.fromS(tenantId)))
            .build());
        if (!resp.hasItem() || resp.item().isEmpty())
            throw new IllegalArgumentException("Tenant not found: " + tenantId);
        return itemToTenant(resp.item());
    }

    public TenantProgress getProgress(String tenantId) {
        TenantItem item = getState(tenantId);
        long elapsed = Instant.now().getEpochSecond()
            - Instant.parse(item.updatedAt()).getEpochSecond();
        String sshPrivateKey = null;
        if ("ready".equals(item.state()) && item.sshKeySecretArn() != null) {
            sshPrivateKey = secretsManager.getSecretValue(
                GetSecretValueRequest.builder().secretId(item.sshKeySecretArn()).build()
            ).secretString();
        }
        return new TenantProgress(item.state(), item.phase(), item.progress(),
            item.publicIp(), elapsed, item.error(), sshPrivateKey);
    }

    // -------------------------------------------------------------------------
    // Deprovision
    // -------------------------------------------------------------------------

    public void deprovision(String tenantId) {
        LOG.infof("Deprovisioning tenant: %s", tenantId);
        TenantItem tenant = getState(tenantId);

        // 1. Terminate EC2 instance
        if (tenant.instanceId() != null) {
            ec2.terminateInstances(TerminateInstancesRequest.builder()
                .instanceIds(tenant.instanceId()).build());
            LOG.infof("Terminated instance %s", tenant.instanceId());
        }

        // 2. Remove cluster registration from eks-dx-clusters table
        dynamoDb.deleteItem(DeleteItemRequest.builder()
            .tableName(clustersTable)
            .key(Map.of("clusterName", AttributeValue.fromS(tenantId)))
            .build());

        // 3. Delete secrets
        deleteSecretIfExists("eks-d-xpress/tenant/" + tenantId + "/signing-key");
        deleteSecretIfExists("eks-d-xpress/tenant/" + tenantId + "/ssh-key");

        // 4. Delete EC2 key pair
        try {
            ec2.deleteKeyPair(DeleteKeyPairRequest.builder()
                .keyName("eks-d-xpress-tenant-" + tenantId).build());
        } catch (Exception e) {
            LOG.warnf("Could not delete key pair for tenant %s: %s", tenantId, e.getMessage());
        }

        // 5. Delete IAM role
        String roleName = "eks-d-xpress-tenant-" + tenantId + "-instance-role";
        try {
            iam.deleteRolePolicy(DeleteRolePolicyRequest.builder()
                .roleName(roleName).policyName("eks-dx-tenant-policy").build());
            iam.deleteRole(DeleteRoleRequest.builder().roleName(roleName).build());
            LOG.infof("Deleted IAM role %s", roleName);
        } catch (Exception e) {
            LOG.warnf("Could not delete IAM role %s: %s", roleName, e.getMessage());
        }

        // 6. Delete tenant DynamoDB item
        dynamoDb.deleteItem(DeleteItemRequest.builder()
            .tableName(tenantsTable)
            .key(Map.of("tenantId", AttributeValue.fromS(tenantId)))
            .build());

        LOG.infof("Deprovisioned tenant: %s", tenantId);
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private void deleteSecretIfExists(String secretId) {
        try {
            secretsManager.deleteSecret(DeleteSecretRequest.builder()
                .secretId(secretId)
                .forceDeleteWithoutRecovery(true)
                .build());
        } catch (Exception e) {
            LOG.warnf("Could not delete secret %s: %s", secretId, e.getMessage());
        }
    }

    private String createInterruptionQueue(String clusterName, String region, String accountId) {
        sqs.createQueue(software.amazon.awssdk.services.sqs.model.CreateQueueRequest.builder()
            .queueName(clusterName)
            .attributes(Map.of(
                software.amazon.awssdk.services.sqs.model.QueueAttributeName.MESSAGE_RETENTION_PERIOD, "300"))
            .build());
        String queueArn = "arn:aws:sqs:" + region + ":" + accountId + ":" + clusterName;
        LOG.infof("Created SQS queue %s", clusterName);
        return queueArn;
    }

    private void createEventBridgeRules(String clusterName, String queueArn) {
        createEventBridgeRule(clusterName + "-spot-interruption",
            "{\"source\":[\"aws.ec2\"],\"detail-type\":[\"EC2 Spot Instance Interruption Warning\"]}", queueArn);
        createEventBridgeRule(clusterName + "-instance-state-change",
            "{\"source\":[\"aws.ec2\"],\"detail-type\":[\"EC2 Instance State-change Notification\"]}", queueArn);
        createEventBridgeRule(clusterName + "-instance-rebalance",
            "{\"source\":[\"aws.ec2\"],\"detail-type\":[\"EC2 Instance Rebalance Recommendation\"]}", queueArn);
        LOG.infof("Created EventBridge rules for %s", clusterName);
    }

    private void createEventBridgeRule(String ruleName, String eventPattern, String targetArn) {
        events.putRule(PutRuleRequest.builder()
            .name(ruleName)
            .eventPattern(eventPattern)
            .state("ENABLED")
            .build());
        events.putTargets(PutTargetsRequest.builder()
            .rule(ruleName)
            .targets(Target.builder().id("sqs").arn(targetArn).build())
            .build());
    }

    private String resolveLaunchTemplate(String arch, String pricingModel) {
        return switch (arch + "/" + pricingModel) {
            case "arm64/ondemand" -> ltArm64Ondemand;
            case "arm64/spot" -> ltArm64Spot;
            case "x86_64/ondemand" -> ltX86Ondemand;
            case "x86_64/spot" -> ltX86Spot;
            default -> throw new IllegalArgumentException("Invalid arch/pricing: " + arch + "/" + pricingModel);
        };
    }

    private String generateRsaPrivateKeyPem() {
        try {
            KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
            gen.initialize(2048);
            KeyPair kp = gen.generateKeyPair();
            byte[] encoded = kp.getPrivate().getEncoded();
            return "-----BEGIN PRIVATE KEY-----\n"
                + Base64.getMimeEncoder(64, new byte[]{'\n'}).encodeToString(encoded)
                + "\n-----END PRIVATE KEY-----\n";
        } catch (Exception e) {
            throw new RuntimeException("Failed to generate RSA key pair", e);
        }
    }

    private TenantItem itemToTenant(Map<String, AttributeValue> item) {
        return new TenantItem(
            s(item, "tenantId"),
            s(item, "instanceId"),
            s(item, "state"),
            s(item, "phase"),
            item.containsKey("progress") ? Integer.parseInt(item.get("progress").n()) : 0,
            s(item, "publicIp"),
            s(item, "sshKeySecretArn"),
            s(item, "updatedAt"),
            s(item, "error")
        );
    }

    private String s(Map<String, AttributeValue> item, String key) {
        return item.containsKey(key) ? item.get(key).s() : null;
    }
}
