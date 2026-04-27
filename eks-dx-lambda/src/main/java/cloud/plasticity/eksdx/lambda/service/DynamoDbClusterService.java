package cloud.plasticity.eksdx.lambda.service;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.*;

import java.util.Map;

/**
 * DynamoDB-backed cluster registration and JWKS storage.
 * Table: eks-dx-clusters (PK: clusterName)
 */
@ApplicationScoped
public class DynamoDbClusterService {

    private static final Logger LOG = Logger.getLogger(DynamoDbClusterService.class);

    @Inject DynamoDbClient dynamoDb;

    @ConfigProperty(name = "eks-dx.clusters-table")
    String tableName;

    public String getJwks(String clusterName) {
        GetItemResponse response = dynamoDb.getItem(GetItemRequest.builder()
            .tableName(tableName)
            .key(Map.of("clusterName", AttributeValue.fromS(clusterName)))
            .build());

        if (response.hasItem() && response.item().containsKey("jwks")) {
            return response.item().get("jwks").s();
        }
        throw new IllegalArgumentException("Cluster not registered: " + clusterName);
    }

    public String getIssuer(String clusterName) {
        GetItemResponse response = dynamoDb.getItem(GetItemRequest.builder()
            .tableName(tableName)
            .key(Map.of("clusterName", AttributeValue.fromS(clusterName)))
            .build());

        if (response.hasItem() && response.item().containsKey("issuer")) {
            return response.item().get("issuer").s();
        }
        throw new IllegalArgumentException("Cluster not registered: " + clusterName);
    }

    // TODO: registerCluster, describeCluster, listClusters, updateJwks, deregisterCluster
}
