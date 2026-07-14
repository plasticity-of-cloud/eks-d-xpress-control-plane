package ai.codriverlabs.eksdx.mgmt.spi;

import ai.codriverlabs.eksdx.mgmt.service.DynamoDbClusterService;
import ai.codriverlabs.eksdx.model.ClusterType;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import org.jboss.logging.Logger;

/**
 * Routes pod identity association operations to the correct provider
 * based on the cluster's registered type.
 *
 * Resolution order:
 * 1. Look up cluster in DynamoDB
 * 2. Read clusterType attribute (defaults to EKS_DX if absent — backward compatible)
 * 3. Return matching provider
 */
@ApplicationScoped
public class PodIdentityRouter {

    private static final Logger LOG = Logger.getLogger(PodIdentityRouter.class);

    @Inject
    DynamoDbClusterService clusterService;

    @Inject
    @Named("EKS_DX")
    PodIdentityProvider eksDxProvider;

    @Inject
    @Named("EKS_MANAGED")
    PodIdentityProvider eksManagedProvider;

    /**
     * Resolve the correct provider for the given cluster.
     *
     * @param clusterName the registered cluster name
     * @return the provider that handles this cluster's associations
     * @throws IllegalArgumentException if cluster is not registered
     */
    public PodIdentityProvider resolve(String clusterName) {
        ClusterType type = clusterService.getClusterType(clusterName);
        LOG.debugf("Resolved provider for cluster '%s': %s", clusterName, type);

        return switch (type) {
            case EKS_DX -> eksDxProvider;
            case EKS_MANAGED -> eksManagedProvider;
            case ECS_OVERLAY -> eksDxProvider; // Uses same DynamoDB storage as EKS_DX for now
        };
    }
}
