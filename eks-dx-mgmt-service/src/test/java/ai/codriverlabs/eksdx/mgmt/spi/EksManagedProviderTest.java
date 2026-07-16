package ai.codriverlabs.eksdx.mgmt.spi;

import ai.codriverlabs.eksdx.mgmt.service.DynamoDbClusterService;
import ai.codriverlabs.eksdx.model.ClusterType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;

import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import software.amazon.awssdk.services.eks.EksClient;
import software.amazon.awssdk.services.eks.model.*;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class EksManagedProviderTest {

    @Mock
    EksClient eksClient;

    @Mock
    DynamoDbClusterService clusterService;

    @InjectMocks
    EksManagedProvider provider;

    private void stubClusterResolution(String clusterName, String eksClusterName) {
        when(clusterService.describeCluster(clusterName))
            .thenReturn(Map.of(
                "clusterName", clusterName,
                "eksClusterName", eksClusterName,
                "clusterType", "EKS_MANAGED"
            ));
    }

    @Test
    void shouldReturnEksManagedType() {
        assertEquals(ClusterType.EKS_MANAGED, provider.type());
    }

    @Test
    void shouldCreateAssociationViaNativeEksApi() {
        stubClusterResolution("eks-production", "production");
        var mockAssociation = PodIdentityAssociation.builder()
            .associationId("a-12345")
            .associationArn("arn:aws:eks:us-east-1:123456789012:podidentityassociation/production/a-12345")
            .namespace("payment")
            .serviceAccount("api-sa")
            .roleArn("arn:aws:iam::123456789012:role/payment-role")
            .build();

        when(eksClient.createPodIdentityAssociation(any(CreatePodIdentityAssociationRequest.class)))
            .thenReturn(CreatePodIdentityAssociationResponse.builder()
                .association(mockAssociation)
                .build());

        var result = provider.createAssociation(
            "eks-production", "payment", "api-sa",
            "arn:aws:iam::123456789012:role/payment-role");

        assertEquals("a-12345", result.get("associationId"));
        assertEquals("eks-production", result.get("clusterName"));
        assertEquals("payment", result.get("namespace"));
        assertEquals("api-sa", result.get("serviceAccount"));
        assertEquals("EKS_MANAGED", result.get("type"));

        // Verify the native EKS API was called with the REAL cluster name
        var captor = ArgumentCaptor.forClass(CreatePodIdentityAssociationRequest.class);
        verify(eksClient).createPodIdentityAssociation(captor.capture());
        assertEquals("production", captor.getValue().clusterName()); // Real EKS name, not our registration name
        assertEquals("payment", captor.getValue().namespace());
        assertEquals("api-sa", captor.getValue().serviceAccount());
    }

    @Test
    void shouldListAssociationsViaNativeEksApi() {
        stubClusterResolution("eks-production", "production");
        var mockSummary = PodIdentityAssociationSummary.builder()
            .associationId("a-12345")
            .namespace("payment")
            .serviceAccount("api-sa")
            .build();

        when(eksClient.listPodIdentityAssociations(any(ListPodIdentityAssociationsRequest.class)))
            .thenReturn(ListPodIdentityAssociationsResponse.builder()
                .associations(List.of(mockSummary))
                .build());

        var result = provider.listAssociations("eks-production", "payment", null);

        assertEquals(1, result.size());
        assertEquals("a-12345", result.getFirst().get("associationId"));
        assertEquals("payment", result.getFirst().get("namespace"));
        assertEquals("EKS_MANAGED", result.getFirst().get("type"));
    }

    @Test
    void shouldDeleteAssociationViaNativeEksApi() {
        stubClusterResolution("eks-production", "production");
        when(eksClient.deletePodIdentityAssociation(any(DeletePodIdentityAssociationRequest.class)))
            .thenReturn(DeletePodIdentityAssociationResponse.builder().build());

        provider.deleteAssociation("eks-production", "a-12345");

        var captor = ArgumentCaptor.forClass(DeletePodIdentityAssociationRequest.class);
        verify(eksClient).deletePodIdentityAssociation(captor.capture());
        assertEquals("production", captor.getValue().clusterName());
        assertEquals("a-12345", captor.getValue().associationId());
    }

    @Test
    void shouldThrowWhenEksClusterNameMissing() {
        when(clusterService.describeCluster("bad-cluster"))
            .thenReturn(Map.of("clusterName", "bad-cluster")); // no eksClusterName

        assertThrows(IllegalStateException.class,
            () -> provider.createAssociation("bad-cluster", "ns", "sa", "arn:role"));
    }

    @Test
    void shouldReturnNullForNotFoundAssociation() {
        stubClusterResolution("eks-production", "production");
        when(eksClient.describePodIdentityAssociation(any(DescribePodIdentityAssociationRequest.class)))
            .thenThrow(NotFoundException.builder().message("not found").build());

        var result = provider.describeAssociation("eks-production", "a-nonexistent");

        assertNull(result);
    }
}
