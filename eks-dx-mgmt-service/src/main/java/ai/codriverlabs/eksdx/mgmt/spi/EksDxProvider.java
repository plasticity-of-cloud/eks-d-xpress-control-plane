package ai.codriverlabs.eksdx.mgmt.spi;

import ai.codriverlabs.eksdx.mgmt.service.DynamoDbAssociationService;
import ai.codriverlabs.eksdx.model.ClusterType;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.inject.Named;

import java.util.List;
import java.util.Map;

/**
 * EKS-DX provider: existing behavior for non-EKS clusters (EKS-D, kubeadm, k3s, Rancher).
 * Stores associations in DynamoDB. Credential exchange via EKS-DX credential-service Lambda.
 * This is the default provider — all existing clusters use this.
 */
@ApplicationScoped
@Named("EKS_DX")
public class EksDxProvider implements PodIdentityProvider {

    @Inject
    DynamoDbAssociationService associationService;

    @Override
    public ClusterType type() {
        return ClusterType.EKS_DX;
    }

    @Override
    public Map<String, String> createAssociation(String clusterName, String namespace,
                                                   String serviceAccount, String roleArn) {
        return associationService.createAssociation(clusterName, namespace, serviceAccount, roleArn);
    }

    @Override
    public List<Map<String, String>> listAssociations(String clusterName,
                                                       String namespace, String serviceAccount) {
        return associationService.listAssociations(clusterName, namespace, serviceAccount);
    }

    @Override
    public Map<String, String> describeAssociation(String clusterName, String associationId) {
        return associationService.describeAssociation(clusterName, associationId);
    }

    @Override
    public void deleteAssociation(String clusterName, String associationId) {
        associationService.deleteAssociation(clusterName, associationId);
    }
}
