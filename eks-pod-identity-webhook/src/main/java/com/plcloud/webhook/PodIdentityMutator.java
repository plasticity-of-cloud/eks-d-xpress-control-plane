package com.plcloud.webhook;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.fabric8.kubernetes.api.model.*;
import io.fabric8.kubernetes.api.model.admission.v1.AdmissionRequest;
import io.fabric8.kubernetes.api.model.admission.v1.AdmissionResponse;
import io.fabric8.kubernetes.api.model.admission.v1.AdmissionReview;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

/**
 * Mutates pods to inject EKS Pod Identity Agent env vars and projected token volume,
 * mirroring what the real EKS Pod Identity Agent does on managed EKS nodes.
 */
@ApplicationScoped
public class PodIdentityMutator {

    private static final Logger LOG = Logger.getLogger(PodIdentityMutator.class);

    static final String CREDENTIALS_URI = "http://169.254.170.23/v1/credentials";
    static final String TOKEN_FILE_PATH = "/var/run/secrets/pods.eks.amazonaws.com/serviceaccount/eks-pod-identity-token";
    static final String TOKEN_VOLUME_NAME = "eks-pod-identity-token";
    static final String TOKEN_MOUNT_PATH = "/var/run/secrets/pods.eks.amazonaws.com/serviceaccount";
    static final String TOKEN_AUDIENCE = "pods.eks.amazonaws.com";

    @Inject
    PodIdentityAssociationLookup associationLookup;

    @Inject
    ObjectMapper objectMapper;

    @ConfigProperty(name = "eks.cluster-name")
    String clusterName;

    public AdmissionReview handle(AdmissionReview review) {
        AdmissionRequest request = review.getRequest();
        AdmissionResponse response = new AdmissionResponse();
        response.setUid(request.getUid());
        response.setAllowed(true);

        try {
            Pod pod = objectMapper.convertValue(request.getObject(), Pod.class);
            String namespace = pod.getMetadata().getNamespace();
            if (namespace == null) namespace = request.getNamespace();
            String serviceAccount = pod.getSpec().getServiceAccountName();
            if (serviceAccount == null || serviceAccount.isBlank()) serviceAccount = "default";

            if (associationLookup.hasAssociation(clusterName, namespace, serviceAccount)) {
                LOG.infof("Injecting Pod Identity env vars for %s/%s", namespace, serviceAccount);
                List<JsonPatch> patches = buildPatches(pod);
                if (!patches.isEmpty()) {
                    response.setPatchType("JSONPatch");
                    response.setPatch(Base64.getEncoder().encodeToString(
                        objectMapper.writeValueAsBytes(patches)));
                }
            }
        } catch (Exception e) {
            LOG.errorf("Error processing admission request: %s", e.getMessage());
            response.setAllowed(false);
            response.setStatus(new StatusBuilder()
                .withCode(500)
                .withMessage("Internal webhook error: " + e.getMessage())
                .build());
        }

        AdmissionReview result = new AdmissionReview();
        result.setResponse(response);
        return result;
    }

    private List<JsonPatch> buildPatches(Pod pod) {
        List<JsonPatch> patches = new ArrayList<>();
        List<Container> containers = pod.getSpec().getContainers();

        for (int i = 0; i < containers.size(); i++) {
            Container c = containers.get(i);
            List<EnvVar> env = c.getEnv();

            boolean hasCredUri = env != null && env.stream()
                .anyMatch(e -> "AWS_CONTAINER_CREDENTIALS_FULL_URI".equals(e.getName()));
            boolean hasTokenFile = env != null && env.stream()
                .anyMatch(e -> "AWS_CONTAINER_AUTHORIZATION_TOKEN_FILE".equals(e.getName()));
            boolean hasMount = c.getVolumeMounts() != null && c.getVolumeMounts().stream()
                .anyMatch(m -> TOKEN_VOLUME_NAME.equals(m.getName()));

            String envPath = "/spec/containers/" + i + "/env";
            if (env == null || env.isEmpty()) {
                if (!hasCredUri) patches.add(new JsonPatch("add", envPath,
                    List.of(envVar("AWS_CONTAINER_CREDENTIALS_FULL_URI", CREDENTIALS_URI))));
                if (!hasTokenFile) patches.add(new JsonPatch("add", envPath + "/-",
                    envVar("AWS_CONTAINER_AUTHORIZATION_TOKEN_FILE", TOKEN_FILE_PATH)));
            } else {
                if (!hasCredUri) patches.add(new JsonPatch("add", envPath + "/-",
                    envVar("AWS_CONTAINER_CREDENTIALS_FULL_URI", CREDENTIALS_URI)));
                if (!hasTokenFile) patches.add(new JsonPatch("add", envPath + "/-",
                    envVar("AWS_CONTAINER_AUTHORIZATION_TOKEN_FILE", TOKEN_FILE_PATH)));
            }

            if (!hasMount) {
                String mountsPath = "/spec/containers/" + i + "/volumeMounts";
                if (c.getVolumeMounts() == null || c.getVolumeMounts().isEmpty()) {
                    patches.add(new JsonPatch("add", mountsPath,
                        List.of(volumeMount())));
                } else {
                    patches.add(new JsonPatch("add", mountsPath + "/-", volumeMount()));
                }
            }
        }

        // Add projected token volume if not present
        boolean hasVolume = pod.getSpec().getVolumes() != null && pod.getSpec().getVolumes().stream()
            .anyMatch(v -> TOKEN_VOLUME_NAME.equals(v.getName()));
        if (!hasVolume) {
            String volumesPath = "/spec/volumes";
            if (pod.getSpec().getVolumes() == null || pod.getSpec().getVolumes().isEmpty()) {
                patches.add(new JsonPatch("add", volumesPath, List.of(tokenVolume())));
            } else {
                patches.add(new JsonPatch("add", volumesPath + "/-", tokenVolume()));
            }
        }

        return patches;
    }

    private java.util.Map<String, String> envVar(String name, String value) {
        return java.util.Map.of("name", name, "value", value);
    }

    private java.util.Map<String, Object> volumeMount() {
        return java.util.Map.of(
            "name", TOKEN_VOLUME_NAME,
            "mountPath", TOKEN_MOUNT_PATH,
            "readOnly", true
        );
    }

    private java.util.Map<String, Object> tokenVolume() {
        return java.util.Map.of(
            "name", TOKEN_VOLUME_NAME,
            "projected", java.util.Map.of(
                "sources", List.of(java.util.Map.of(
                    "serviceAccountToken", java.util.Map.of(
                        "audience", TOKEN_AUDIENCE,
                        "expirationSeconds", 86400,
                        "path", "eks-pod-identity-token"
                    )
                ))
            )
        );
    }

    record JsonPatch(String op, String path, Object value) {}
}
