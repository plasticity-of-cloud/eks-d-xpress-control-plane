package cloud.plasticity.eksdx.lambda.service;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.*;

import java.util.Map;

/**
 * DynamoDB-backed association lookup and CRUD.
 * Table: eks-dx-associations (PK: CLUSTER#clusterName, SK: namespace#serviceAccount)
 */
@ApplicationScoped
public class DynamoDbAssociationService {

    private static final Logger LOG = Logger.getLogger(DynamoDbAssociationService.class);

    @Inject DynamoDbClient dynamoDb;

    @ConfigProperty(name = "eks-dx.associations-table")
    String tableName;

    public String getRoleArn(String clusterName, String namespace, String serviceAccount) {
        GetItemResponse response = dynamoDb.getItem(GetItemRequest.builder()
            .tableName(tableName)
            .key(Map.of(
                "PK", AttributeValue.fromS("CLUSTER#" + clusterName),
                "SK", AttributeValue.fromS(namespace + "#" + serviceAccount)))
            .build());

        if (response.hasItem()) {
            return response.item().get("roleArn").s();
        }
        return null;
    }

    public String getAssociationId(String clusterName, String namespace, String serviceAccount) {
        GetItemResponse response = dynamoDb.getItem(GetItemRequest.builder()
            .tableName(tableName)
            .key(Map.of(
                "PK", AttributeValue.fromS("CLUSTER#" + clusterName),
                "SK", AttributeValue.fromS(namespace + "#" + serviceAccount)))
            .build());

        if (response.hasItem() && response.item().containsKey("associationId")) {
            return response.item().get("associationId").s();
        }
        return "assoc-" + clusterName + "-" + namespace + "-" + serviceAccount;
    }

    // TODO: createAssociation, listAssociations, describeAssociation, deleteAssociation
}
