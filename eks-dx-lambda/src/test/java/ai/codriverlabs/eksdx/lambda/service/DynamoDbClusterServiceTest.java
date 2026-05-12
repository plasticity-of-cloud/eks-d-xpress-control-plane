package ai.codriverlabs.eksdx.lambda.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.*;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class DynamoDbClusterServiceTest {

    @Mock
    DynamoDbClient dynamoDb;

    DynamoDbClusterService service;

    @BeforeEach
    void setUp() {
        service = new DynamoDbClusterService();
        service.dynamoDb = dynamoDb;
        service.tableName = "test-clusters";
    }

    // --- getJwks ---

    @Test
    void getJwks_returnsJwks_whenClusterExists() {
        mockGetItem(Map.of(
            "clusterName", AttributeValue.fromS("test-cluster"),
            "jwks", AttributeValue.fromS("{\"keys\":[]}")
        ));

        assertEquals("{\"keys\":[]}", service.getJwks("test-cluster"));
    }

    @Test
    void getJwks_throws_whenClusterNotFound() {
        mockGetItemEmpty();

        var ex = assertThrows(IllegalArgumentException.class,
            () -> service.getJwks("missing"));
        assertTrue(ex.getMessage().contains("Cluster not registered"));
    }

    @Test
    void getJwks_throws_whenJwksFieldMissing() {
        mockGetItem(Map.of("clusterName", AttributeValue.fromS("test-cluster")));

        assertThrows(IllegalArgumentException.class,
            () -> service.getJwks("test-cluster"));
    }

    // --- getIssuer ---

    @Test
    void getIssuer_returnsIssuer_whenClusterExists() {
        mockGetItem(Map.of(
            "clusterName", AttributeValue.fromS("test-cluster"),
            "issuer", AttributeValue.fromS("https://oidc.example.com")
        ));

        assertEquals("https://oidc.example.com", service.getIssuer("test-cluster"));
    }

    @Test
    void getIssuer_throws_whenClusterNotFound() {
        mockGetItemEmpty();

        assertThrows(IllegalArgumentException.class,
            () -> service.getIssuer("missing"));
    }

    @Test
    void getIssuer_throws_whenIssuerFieldMissing() {
        mockGetItem(Map.of("clusterName", AttributeValue.fromS("test-cluster")));

        assertThrows(IllegalArgumentException.class,
            () -> service.getIssuer("test-cluster"));
    }

    // --- registerCluster ---

    @Test
    void registerCluster_succeeds_whenClusterDoesNotExist() {
        mockGetItemEmpty();
        when(dynamoDb.putItem(any(PutItemRequest.class)))
            .thenReturn(PutItemResponse.builder().build());

        Map<String, String> result = service.registerCluster("new-cluster",
            "https://oidc.example.com", "{\"keys\":[]}");

        assertEquals("new-cluster", result.get("clusterName"));
        assertEquals("https://oidc.example.com", result.get("issuer"));
        assertNotNull(result.get("createdAt"));

        ArgumentCaptor<PutItemRequest> captor = ArgumentCaptor.forClass(PutItemRequest.class);
        verify(dynamoDb).putItem(captor.capture());
        Map<String, AttributeValue> item = captor.getValue().item();
        assertEquals("new-cluster", item.get("clusterName").s());
        assertEquals("https://oidc.example.com", item.get("issuer").s());
        assertEquals("{\"keys\":[]}", item.get("jwks").s());
        assertNotNull(item.get("createdAt"));
        assertNotNull(item.get("updatedAt"));
    }

    @Test
    void registerCluster_throws_whenClusterAlreadyExists() {
        mockGetItem(Map.of(
            "clusterName", AttributeValue.fromS("existing"),
            "issuer", AttributeValue.fromS("https://oidc.example.com")
        ));

        var ex = assertThrows(IllegalStateException.class,
            () -> service.registerCluster("existing", "https://oidc.example.com", "{\"keys\":[]}"));
        assertTrue(ex.getMessage().contains("already registered"));
    }

    @Test
    void registerCluster_throws_whenNameBlank() {
        assertThrows(IllegalArgumentException.class,
            () -> service.registerCluster("", "https://oidc.example.com", "{\"keys\":[]}"));
    }

    @Test
    void registerCluster_throws_whenNameNull() {
        assertThrows(IllegalArgumentException.class,
            () -> service.registerCluster(null, "https://oidc.example.com", "{\"keys\":[]}"));
    }

    @Test
    void registerCluster_throws_whenIssuerBlank() {
        assertThrows(IllegalArgumentException.class,
            () -> service.registerCluster("cluster", "", "{\"keys\":[]}"));
    }

    @Test
    void registerCluster_throws_whenIssuerNull() {
        assertThrows(IllegalArgumentException.class,
            () -> service.registerCluster("cluster", null, "{\"keys\":[]}"));
    }

    @Test
    void registerCluster_throws_whenJwksBlank() {
        assertThrows(IllegalArgumentException.class,
            () -> service.registerCluster("cluster", "https://oidc.example.com", ""));
    }

    @Test
    void registerCluster_throws_whenJwksNull() {
        assertThrows(IllegalArgumentException.class,
            () -> service.registerCluster("cluster", "https://oidc.example.com", null));
    }

    // --- describeCluster ---

    @Test
    void describeCluster_returnsAllFields() {
        mockGetItem(Map.of(
            "clusterName", AttributeValue.fromS("test-cluster"),
            "issuer", AttributeValue.fromS("https://oidc.example.com"),
            "createdAt", AttributeValue.fromS("2025-01-01T00:00:00Z"),
            "updatedAt", AttributeValue.fromS("2025-01-02T00:00:00Z")
        ));

        Map<String, String> result = service.describeCluster("test-cluster");

        assertEquals("test-cluster", result.get("clusterName"));
        assertEquals("https://oidc.example.com", result.get("issuer"));
        assertEquals("2025-01-01T00:00:00Z", result.get("createdAt"));
        assertEquals("2025-01-02T00:00:00Z", result.get("updatedAt"));
    }

    @Test
    void describeCluster_handlesPartialFields() {
        mockGetItem(Map.of(
            "clusterName", AttributeValue.fromS("test-cluster"),
            "issuer", AttributeValue.fromS("https://oidc.example.com")
        ));

        Map<String, String> result = service.describeCluster("test-cluster");

        assertEquals("test-cluster", result.get("clusterName"));
        assertEquals("https://oidc.example.com", result.get("issuer"));
        assertNull(result.get("createdAt"));
        assertNull(result.get("updatedAt"));
    }

    @Test
    void describeCluster_throws_whenClusterNotFound() {
        mockGetItemEmpty();

        assertThrows(IllegalArgumentException.class,
            () -> service.describeCluster("missing"));
    }

    // --- listClusters ---

    @Test
    void listClusters_returnsAllClusters() {
        when(dynamoDb.scan(any(ScanRequest.class)))
            .thenReturn(ScanResponse.builder().items(
                Map.of(
                    "clusterName", AttributeValue.fromS("cluster-a"),
                    "issuer", AttributeValue.fromS("https://a.example.com"),
                    "createdAt", AttributeValue.fromS("2025-01-01T00:00:00Z")),
                Map.of(
                    "clusterName", AttributeValue.fromS("cluster-b"),
                    "issuer", AttributeValue.fromS("https://b.example.com"),
                    "createdAt", AttributeValue.fromS("2025-01-02T00:00:00Z"))
            ).build());

        List<Map<String, String>> result = service.listClusters();

        assertEquals(2, result.size());
        assertEquals("cluster-a", result.get(0).get("clusterName"));
        assertEquals("https://a.example.com", result.get(0).get("issuer"));
        assertEquals("cluster-b", result.get(1).get("clusterName"));
    }

    @Test
    void listClusters_returnsEmptyList_whenNoClusters() {
        when(dynamoDb.scan(any(ScanRequest.class)))
            .thenReturn(ScanResponse.builder().items(List.of()).build());

        assertTrue(service.listClusters().isEmpty());
    }

    @Test
    void listClusters_usesCorrectTableName() {
        when(dynamoDb.scan(any(ScanRequest.class)))
            .thenReturn(ScanResponse.builder().items(List.of()).build());

        service.listClusters();

        ArgumentCaptor<ScanRequest> captor = ArgumentCaptor.forClass(ScanRequest.class);
        verify(dynamoDb).scan(captor.capture());
        assertEquals("test-clusters", captor.getValue().tableName());
    }

    // --- updateJwks ---

    @Test
    void updateJwks_succeeds_whenClusterExists() {
        mockGetItem(Map.of(
            "clusterName", AttributeValue.fromS("test-cluster"),
            "jwks", AttributeValue.fromS("{\"keys\":[]}")
        ));
        when(dynamoDb.updateItem(any(UpdateItemRequest.class)))
            .thenReturn(UpdateItemResponse.builder().build());

        service.updateJwks("test-cluster", "{\"keys\":[{\"kty\":\"RSA\"}]}");

        ArgumentCaptor<UpdateItemRequest> captor = ArgumentCaptor.forClass(UpdateItemRequest.class);
        verify(dynamoDb).updateItem(captor.capture());
        assertEquals("test-clusters", captor.getValue().tableName());
        assertEquals("{\"keys\":[{\"kty\":\"RSA\"}]}",
            captor.getValue().expressionAttributeValues().get(":j").s());
    }

    @Test
    void updateJwks_throws_whenClusterNotFound() {
        mockGetItemEmpty();

        assertThrows(IllegalArgumentException.class,
            () -> service.updateJwks("missing", "{\"keys\":[]}"));
    }

    @Test
    void updateJwks_throws_whenJwksBlank() {
        assertThrows(IllegalArgumentException.class,
            () -> service.updateJwks("test-cluster", ""));
    }

    @Test
    void updateJwks_throws_whenJwksNull() {
        assertThrows(IllegalArgumentException.class,
            () -> service.updateJwks("test-cluster", null));
    }

    // --- deregisterCluster ---

    @Test
    void deregisterCluster_succeeds_whenClusterExists() {
        mockGetItem(Map.of(
            "clusterName", AttributeValue.fromS("test-cluster"),
            "issuer", AttributeValue.fromS("https://oidc.example.com")
        ));
        when(dynamoDb.deleteItem(any(DeleteItemRequest.class)))
            .thenReturn(DeleteItemResponse.builder().build());

        service.deregisterCluster("test-cluster");

        ArgumentCaptor<DeleteItemRequest> captor = ArgumentCaptor.forClass(DeleteItemRequest.class);
        verify(dynamoDb).deleteItem(captor.capture());
        assertEquals("test-clusters", captor.getValue().tableName());
        assertEquals("test-cluster", captor.getValue().key().get("clusterName").s());
    }

    @Test
    void deregisterCluster_throws_whenClusterNotFound() {
        mockGetItemEmpty();

        assertThrows(IllegalArgumentException.class,
            () -> service.deregisterCluster("missing"));
    }

    // --- helpers ---

    private void mockGetItem(Map<String, AttributeValue> item) {
        when(dynamoDb.getItem(any(GetItemRequest.class)))
            .thenReturn(GetItemResponse.builder().item(item).build());
    }

    private void mockGetItemEmpty() {
        when(dynamoDb.getItem(any(GetItemRequest.class)))
            .thenReturn(GetItemResponse.builder().build());
    }
}
