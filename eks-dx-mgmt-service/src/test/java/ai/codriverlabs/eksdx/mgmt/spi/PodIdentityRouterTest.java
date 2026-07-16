package ai.codriverlabs.eksdx.mgmt.spi;

import ai.codriverlabs.eksdx.mgmt.service.DynamoDbClusterService;
import ai.codriverlabs.eksdx.model.ClusterType;
import jakarta.enterprise.inject.Instance;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PodIdentityRouterTest {

    @Mock
    DynamoDbClusterService clusterService;

    @Mock
    PodIdentityProvider eksDxProvider;

    @Mock
    PodIdentityProvider eksManagedProvider;

    @Mock
    Instance<PodIdentityProvider> providers;

    PodIdentityRouter router;

    @BeforeEach
    void setUp() {
        when(eksDxProvider.type()).thenReturn(ClusterType.EKS_DX);
        when(eksManagedProvider.type()).thenReturn(ClusterType.EKS_MANAGED);

        // Simulate CDI Instance<> iteration
        when(providers.iterator()).thenAnswer(inv ->
            List.of(eksDxProvider, eksManagedProvider).iterator());

        router = new PodIdentityRouter();
        setField(router, "clusterService", clusterService);
        setField(router, "providers", providers);
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
    void shouldFallBackToEksDxForUnknownType() {
        // ECS_OVERLAY not registered — should fall back to EKS_DX
        when(clusterService.getClusterType("ecs-cluster")).thenReturn(ClusterType.ECS_OVERLAY);

        var provider = router.resolve("ecs-cluster");

        assertSame(eksDxProvider, provider);
    }

    @Test
    void shouldRouteWithOnlyEksDxProvider() {
        // Community mode: only EKS_DX provider available
        when(providers.iterator()).thenAnswer(inv ->
            List.of(eksDxProvider).iterator());
        when(clusterService.getClusterType("my-cluster")).thenReturn(ClusterType.EKS_DX);

        var provider = router.resolve("my-cluster");

        assertSame(eksDxProvider, provider);
    }

    @Test
    void shouldFallBackWhenRequestedProviderNotAvailable() {
        // Community mode: only EKS_DX, but cluster claims EKS_MANAGED → fallback
        when(providers.iterator()).thenAnswer(inv ->
            List.of(eksDxProvider).iterator());
        when(clusterService.getClusterType("eks-cluster")).thenReturn(ClusterType.EKS_MANAGED);

        var provider = router.resolve("eks-cluster");

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
