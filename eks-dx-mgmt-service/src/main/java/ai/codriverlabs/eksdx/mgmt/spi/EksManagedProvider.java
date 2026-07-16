package ai.codriverlabs.eksdx.mgmt.spi;

import ai.codriverlabs.eksdx.mgmt.service.DynamoDbClusterService;
import ai.codriverlabs.eksdx.model.ClusterType;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import org.jboss.logging.Logger;
import software.amazon.awssdk.services.eks.EksClient;
import software.amazon.awssdk.services.eks.model.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * EKS Managed provider: proxies pod identity association operations to the native
 * AWS EKS Pod Identity API (aws eks create-pod-identity-association).
 *
 * Used when clusterType = EKS_MANAGED. The cluster must be registered with its
 * real EKS cluster name stored in the eksClusterName DynamoDB attribute.
 */
@ApplicationScoped
@Named("EKS_MANAGED")
public class EksManagedProvider implements PodIdentityProvider {

    private static final Logger LOG = Logger.getLogger(EksManagedProvider.class);

    @Inject
    EksClient eksClient;

    @Inject
    DynamoDbClusterService clusterService;

    @Override
    public ClusterType type() {
        return ClusterType.EKS_MANAGED;
    }

    @Override
    public Map<String, String> createAssociation(String clusterName, String namespace,
                                                   String serviceAccount, String roleArn) {
        var eksClusterName = resolveEksClusterName(clusterName);

        var response = eksClient.createPodIdentityAssociation(
            CreatePodIdentityAssociationRequest.builder()
                .clusterName(eksClusterName)
                .namespace(namespace)
                .serviceAccount(serviceAccount)
                .roleArn(roleArn)
                .build());

        var association = response.association();
        LOG.infof("Created EKS Pod Identity Association %s on cluster %s: %s/%s -> %s",
            association.associationId(), eksClusterName, namespace, serviceAccount, roleArn);

        var result = new HashMap<String, String>();
        result.put("associationId", association.associationId());
        result.put("clusterName", clusterName);
        result.put("namespace", namespace);
        result.put("serviceAccount", serviceAccount);
        result.put("roleArn", roleArn);
        result.put("type", ClusterType.EKS_MANAGED.name());
        result.put("associationArn", association.associationArn());
        return result;
    }

    @Override
    public List<Map<String, String>> listAssociations(String clusterName,
                                                       String namespace, String serviceAccount) {
        var eksClusterName = resolveEksClusterName(clusterName);

        var requestBuilder = ListPodIdentityAssociationsRequest.builder()
            .clusterName(eksClusterName);

        if (namespace != null && !namespace.isBlank()) {
            requestBuilder.namespace(namespace);
        }
        if (serviceAccount != null && !serviceAccount.isBlank()) {
            requestBuilder.serviceAccount(serviceAccount);
        }

        var response = eksClient.listPodIdentityAssociations(requestBuilder.build());

        return response.associations().stream()
            .map(a -> {
                var map = new HashMap<String, String>();
                map.put("associationId", a.associationId());
                map.put("clusterName", clusterName);
                map.put("namespace", a.namespace());
                map.put("serviceAccount", a.serviceAccount());
                map.put("type", ClusterType.EKS_MANAGED.name());
                return (Map<String, String>) map;
            })
            .toList();
    }

    @Override
    public Map<String, String> describeAssociation(String clusterName, String associationId) {
        var eksClusterName = resolveEksClusterName(clusterName);

        try {
            var response = eksClient.describePodIdentityAssociation(
                DescribePodIdentityAssociationRequest.builder()
                    .clusterName(eksClusterName)
                    .associationId(associationId)
                    .build());

            var a = response.association();
            var result = new HashMap<String, String>();
            result.put("associationId", a.associationId());
            result.put("clusterName", clusterName);
            result.put("namespace", a.namespace());
            result.put("serviceAccount", a.serviceAccount());
            result.put("roleArn", a.roleArn());
            result.put("associationArn", a.associationArn());
            result.put("type", ClusterType.EKS_MANAGED.name());
            return result;
        } catch (NotFoundException e) {
            return null;
        }
    }

    @Override
    public void deleteAssociation(String clusterName, String associationId) {
        var eksClusterName = resolveEksClusterName(clusterName);

        eksClient.deletePodIdentityAssociation(
            DeletePodIdentityAssociationRequest.builder()
                .clusterName(eksClusterName)
                .associationId(associationId)
                .build());

        LOG.infof("Deleted EKS Pod Identity Association %s from cluster %s", associationId, eksClusterName);
    }

    private String resolveEksClusterName(String clusterName) {
        var clusterInfo = clusterService.describeCluster(clusterName);
        var eksClusterName = clusterInfo.get("eksClusterName");
        if (eksClusterName == null || eksClusterName.isBlank()) {
            throw new IllegalStateException(
                "Cluster '" + clusterName + "' is registered as EKS_MANAGED but has no eksClusterName. " +
                "Re-register with: eks-dx register-cluster --name " + clusterName +
                " --type eks-managed --eks-cluster-name <real-cluster-name>");
        }
        return eksClusterName;
    }
}
