package ai.codriverlabs.eksdx.mgmt.spi;

import ai.codriverlabs.eksdx.mgmt.service.DynamoDbClusterService;
import ai.codriverlabs.eksdx.model.ClusterType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import jakarta.inject.Named;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PodIdentityRouterTest {

    @Mock
    DynamoDbClusterService clusterService;

    @Mock
    @Named("EKS_DX")
    PodIdentityProvider eksDxProvider;

    @Mock
    @Named("EKS_MANAGED")
    PodIdentityProvider eksManagedProvider;

    PodIdentityRouter router;

    @BeforeEach
    void setUp() {
        router = new PodIdentityRouter();
        // Manual inject for test (CDI not available in unit test)
        setField(router, "clusterService", clusterService);
        setField(router, "eksDxProvider", eksDxProvider);
        setField(router, "eksManagedProvider", eksManagedProvider);
    }

    @Test
    void shouldRouteToEksDxProviderForEksDxCluster() {
        when(clusterService.getClusterType("my-eksd-cluster")).thenReturn(ClusterType.EKS_DX);

        var provider = router.resolve("my-eksd-cluster");

        assertSame(eksDxProvider, provider);
    }

    @Test
    void shouldRouteToEksManagedProviderForManagedCluster() {
        when(clusterService.getClusterType("eks-production")).thenReturn(ClusterType.EKS_MANAGED);

        var provider = router.resolve("eks-production");

        assertSame(eksManagedProvider, provider);
    }

    @Test
    void shouldRouteToEksDxProviderForEcsOverlay() {
        // ECS_OVERLAY uses same DynamoDB storage as EKS_DX for now
        when(clusterService.getClusterType("ecs-production")).thenReturn(ClusterType.ECS_OVERLAY);

        var provider = router.resolve("ecs-production");

        assertSame(eksDxProvider, provider);
    }

    @Test
    void shouldThrowWhenClusterNotRegistered() {
        when(clusterService.getClusterType("nonexistent"))
            .thenThrow(new IllegalArgumentException("Cluster not registered: nonexistent"));

        assertThrows(IllegalArgumentException.class, () -> router.resolve("nonexistent"));
    }

    private void setField(Object target, String fieldName, Object value) {
        try {
            var field = target.getClass().getDeclaredField(fieldName);
            field.setAccessible(true);
            field.set(target, value);
        } catch (Exception e) {
            throw new RuntimeException("Failed to set field " + fieldName, e);
        }
    }
}
