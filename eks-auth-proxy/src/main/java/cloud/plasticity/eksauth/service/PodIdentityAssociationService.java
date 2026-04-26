package cloud.plasticity.eksauth.service;

import cloud.plasticity.eksauth.crd.PodIdentityAssociation;
import io.fabric8.kubernetes.client.KubernetesClient;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.Optional;

@ApplicationScoped
public class PodIdentityAssociationService {

    private static final Logger LOG = Logger.getLogger(PodIdentityAssociationService.class);

    @Inject
    KubernetesClient kubernetesClient;

    // For testing
    void setKubernetesClient(KubernetesClient kubernetesClient) { this.kubernetesClient = kubernetesClient; }

    public String getRoleArnForServiceAccount(String clusterName, String namespace, String serviceAccount) {
        // 1. Primary: Kubernetes CRD
        String roleArn = getRoleArnFromCrd(clusterName, namespace, serviceAccount);
        if (roleArn != null) {
            return roleArn;
        }

        // 2. Last resort: generated default
        return getDefaultRoleArn(namespace, serviceAccount);
    }

    private String getRoleArnFromCrd(String clusterName, String namespace, String serviceAccount) {
        try {
            String crdName = clusterName + "-" + serviceAccount;
            var crd = kubernetesClient.resources(PodIdentityAssociation.class)
                .inNamespace(namespace)
                .withName(crdName)
                .get();

            if (crd != null && crd.getSpec() != null) {
                String roleArn = crd.getSpec().getRoleArn();
                LOG.infof("CRD association found for %s/%s/%s -> %s", clusterName, namespace, serviceAccount, roleArn);
                return roleArn;
            }
        } catch (Exception e) {
            LOG.debugf("CRD lookup failed: %s", e.getMessage());
        }
        return null;
    }

    private String getDefaultRoleArn(String namespace, String serviceAccount) {
        String accountId = Optional.ofNullable(System.getenv("AWS_ACCOUNT_ID"))
                .orElse("123456789012");
        return String.format("arn:aws:iam::%s:role/eks-pod-identity-%s-%s", accountId, namespace, serviceAccount);
    }

    public String generateAssociationId(String clusterName, String namespace, String serviceAccount) {
        return String.format("assoc-%s-%s-%s-%d", clusterName, namespace, serviceAccount, System.currentTimeMillis());
    }
}
