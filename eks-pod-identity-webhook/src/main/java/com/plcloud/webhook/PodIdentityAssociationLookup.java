package com.plcloud.webhook;

import io.fabric8.kubernetes.api.model.ConfigMap;
import io.fabric8.kubernetes.client.KubernetesClient;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.util.Map;

/**
 * Looks up Pod Identity Associations from the ConfigMap.
 * Keys follow the format: "clusterName:namespace:serviceAccount" or "clusterName:namespace:*"
 */
@ApplicationScoped
public class PodIdentityAssociationLookup {

    private static final Logger LOG = Logger.getLogger(PodIdentityAssociationLookup.class);

    @Inject
    KubernetesClient kubernetesClient;

    @ConfigProperty(name = "eks.pod-identity.configmap.name", defaultValue = "pod-identity-associations")
    String configMapName;

    @ConfigProperty(name = "eks.pod-identity.configmap.namespace", defaultValue = "kube-system")
    String configMapNamespace;

    public boolean hasAssociation(String clusterName, String namespace, String serviceAccount) {
        try {
            ConfigMap configMap = kubernetesClient.configMaps()
                .inNamespace(configMapNamespace)
                .withName(configMapName)
                .get();

            if (configMap == null || configMap.getData() == null) {
                LOG.warnf("ConfigMap %s/%s not found, skipping injection", configMapNamespace, configMapName);
                return false;
            }

            Map<String, String> data = configMap.getData();
            String exactKey = clusterName + ":" + namespace + ":" + serviceAccount;
            String wildcardKey = clusterName + ":" + namespace + ":*";

            return data.containsKey(exactKey) || data.containsKey(wildcardKey);
        } catch (Exception e) {
            LOG.errorf("Error looking up Pod Identity association: %s", e.getMessage());
            return false;
        }
    }
}
