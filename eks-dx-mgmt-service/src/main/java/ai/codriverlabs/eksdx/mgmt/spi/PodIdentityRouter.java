package ai.codriverlabs.eksdx.mgmt.spi;

import ai.codriverlabs.eksdx.mgmt.service.DynamoDbClusterService;
import ai.codriverlabs.eksdx.model.ClusterType;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

/**
 * Routes pod identity association operations to the correct provider
 * based on the cluster's registered type.
 *
 * Resolution order:
 * 1. Look up cluster in DynamoDB
 * 2. Read clusterType attribute (defaults to EKS_DX if absent — backward compatible)
 * 3. Return matching provider from the discovered set
 *
 * Providers register themselves via CDI (@ApplicationScoped + @Named).
 * Community repo: EksDxProvider only.
 * PRO repo: adds EksManagedProvider, EcsOverlayProvider.
 */
@ApplicationScoped
public class PodIdentityRouter {

    private static final Logger LOG = Logger.getLogger(PodIdentityRouter.class);

    @Inject
    DynamoDbClusterService clusterService;

    @Inject
    Instance<PodIdentityProvider> providers;

    /**
     * Resolve the correct provider for the given cluster.
     *
     * @param clusterName the registered cluster name
     * @return the provider that handles this cluster's associations
     * @throws IllegalArgumentException if cluster is not registered or no provider found
     */
    public PodIdentityProvider resolve(String clusterName) {
        ClusterType type = clusterService.getClusterType(clusterName);
        LOG.debugf("Resolved cluster type for '%s': %s", clusterName, type);

        for (PodIdentityProvider provider : providers) {
            if (provider.type() == type) {
                return provider;
            }
        }

        // Fallback: EKS_DX provider handles unknown types for backward compatibility
        for (PodIdentityProvider provider : providers) {
            if (provider.type() == ClusterType.EKS_DX) {
                LOG.warnf("No provider for cluster type %s, falling back to EKS_DX", type);
                return provider;
            }
        }

        throw new IllegalStateException("No PodIdentityProvider found for cluster type: " + type);
    }
}
