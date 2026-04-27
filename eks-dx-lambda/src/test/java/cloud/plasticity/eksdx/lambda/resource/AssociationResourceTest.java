package cloud.plasticity.eksdx.lambda.resource;

import cloud.plasticity.eksdx.lambda.service.DynamoDbAssociationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import jakarta.ws.rs.core.Response;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AssociationResourceTest {

    @Mock
    DynamoDbAssociationService associationService;

    AssociationResource resource;

    @BeforeEach
    void setUp() {
        resource = new AssociationResource();
        resource.associationService = associationService;
    }

    // --- createAssociation ---

    @Test
    void createAssociation_returns201_onSuccess() {
        when(associationService.createAssociation("cluster", "default", "my-sa",
            "arn:aws:iam::123456789012:role/test-role"))
            .thenReturn(Map.of("associationId", "assoc-abc", "clusterName", "cluster"));

        var req = new AssociationResource.CreateAssociationRequest();
        req.namespace = "default";
        req.serviceAccount = "my-sa";
        req.roleArn = "arn:aws:iam::123456789012:role/test-role";

        try (Response resp = resource.createAssociation("cluster", req)) {
            assertEquals(201, resp.getStatus());
            @SuppressWarnings("unchecked")
            Map<String, String> body = (Map<String, String>) resp.getEntity();
            assertEquals("assoc-abc", body.get("associationId"));
        }
    }

    @Test
    void createAssociation_returns400_whenNullRequest() {
        try (Response resp = resource.createAssociation("cluster", null)) {
            assertEquals(400, resp.getStatus());
        }
    }

    @Test
    void createAssociation_returns400_onInvalidInput() {
        when(associationService.createAssociation("cluster", null, null, null))
            .thenThrow(new IllegalArgumentException("namespace is required"));

        var req = new AssociationResource.CreateAssociationRequest();
        try (Response resp = resource.createAssociation("cluster", req)) {
            assertEquals(400, resp.getStatus());
        }
    }

    @Test
    void createAssociation_returns409_onDuplicate() {
        when(associationService.createAssociation("cluster", "default", "my-sa",
            "arn:aws:iam::123456789012:role/test-role"))
            .thenThrow(new IllegalStateException("Association already exists"));

        var req = new AssociationResource.CreateAssociationRequest();
        req.namespace = "default";
        req.serviceAccount = "my-sa";
        req.roleArn = "arn:aws:iam::123456789012:role/test-role";

        try (Response resp = resource.createAssociation("cluster", req)) {
            assertEquals(409, resp.getStatus());
        }
    }

    @Test
    void createAssociation_returns500_onUnexpectedError() {
        when(associationService.createAssociation("cluster", "default", "my-sa",
            "arn:aws:iam::123456789012:role/test-role"))
            .thenThrow(new RuntimeException("DynamoDB unavailable"));

        var req = new AssociationResource.CreateAssociationRequest();
        req.namespace = "default";
        req.serviceAccount = "my-sa";
        req.roleArn = "arn:aws:iam::123456789012:role/test-role";

        try (Response resp = resource.createAssociation("cluster", req)) {
            assertEquals(500, resp.getStatus());
        }
    }

    // --- listAssociations ---

    @Test
    void listAssociations_returns200_withResults() {
        when(associationService.listAssociations("cluster", null, null))
            .thenReturn(List.of(
                Map.of("associationId", "assoc-1"),
                Map.of("associationId", "assoc-2")));

        try (Response resp = resource.listAssociations("cluster", null, null)) {
            assertEquals(200, resp.getStatus());
            @SuppressWarnings("unchecked")
            Map<String, Object> body = (Map<String, Object>) resp.getEntity();
            @SuppressWarnings("unchecked")
            List<Map<String, String>> assocs = (List<Map<String, String>>) body.get("associations");
            assertEquals(2, assocs.size());
        }
    }

    @Test
    void listAssociations_passesFilters() {
        when(associationService.listAssociations("cluster", "kube-system", "webhook"))
            .thenReturn(List.of());

        try (Response resp = resource.listAssociations("cluster", "kube-system", "webhook")) {
            assertEquals(200, resp.getStatus());
        }
        verify(associationService).listAssociations("cluster", "kube-system", "webhook");
    }

    @Test
    void listAssociations_returns200_whenEmpty() {
        when(associationService.listAssociations("cluster", null, null))
            .thenReturn(List.of());

        try (Response resp = resource.listAssociations("cluster", null, null)) {
            assertEquals(200, resp.getStatus());
        }
    }

    // --- describeAssociation ---

    @Test
    void describeAssociation_returns200_whenFound() {
        when(associationService.describeAssociation("cluster", "assoc-abc"))
            .thenReturn(Map.of("associationId", "assoc-abc", "namespace", "default"));

        try (Response resp = resource.describeAssociation("cluster", "assoc-abc")) {
            assertEquals(200, resp.getStatus());
            @SuppressWarnings("unchecked")
            Map<String, String> body = (Map<String, String>) resp.getEntity();
            assertEquals("assoc-abc", body.get("associationId"));
        }
    }

    @Test
    void describeAssociation_returns404_whenNotFound() {
        when(associationService.describeAssociation("cluster", "assoc-missing"))
            .thenReturn(null);

        try (Response resp = resource.describeAssociation("cluster", "assoc-missing")) {
            assertEquals(404, resp.getStatus());
        }
    }

    // --- deleteAssociation ---

    @Test
    void deleteAssociation_returns204_onSuccess() {
        doNothing().when(associationService).deleteAssociation("cluster", "assoc-abc");

        try (Response resp = resource.deleteAssociation("cluster", "assoc-abc")) {
            assertEquals(204, resp.getStatus());
        }
    }

    @Test
    void deleteAssociation_returns404_whenNotFound() {
        doThrow(new IllegalArgumentException("Association not found: assoc-missing"))
            .when(associationService).deleteAssociation("cluster", "assoc-missing");

        try (Response resp = resource.deleteAssociation("cluster", "assoc-missing")) {
            assertEquals(404, resp.getStatus());
        }
    }

    @Test
    void deleteAssociation_returns500_onUnexpectedError() {
        doThrow(new RuntimeException("DynamoDB unavailable"))
            .when(associationService).deleteAssociation("cluster", "assoc-abc");

        try (Response resp = resource.deleteAssociation("cluster", "assoc-abc")) {
            assertEquals(500, resp.getStatus());
        }
    }
}
