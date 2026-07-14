package ai.codriverlabs.eksdx.model;

/**
 * Identifies how a cluster's pod identity associations are managed.
 * Used by the Unified Identity SPI to route association operations
 * to the correct backend provider.
 */
public enum ClusterType {

    /**
     * EKS-DX managed (non-EKS clusters: EKS-D, kubeadm, k3s, Rancher).
     * Associations stored in DynamoDB. Credential exchange via EKS-DX Lambda.
     */
    EKS_DX,

    /**
     * Native EKS managed cluster.
     * Associations proxied to aws eks create-pod-identity-association API.
     */
    EKS_MANAGED,

    /**
     * ECS cluster with k3s identity overlay.
     * Associations stored in DynamoDB + synced to k3s CRDs.
     */
    ECS_OVERLAY;

    /**
     * Parse from string, defaulting to EKS_DX for backward compatibility.
     */
    public static ClusterType fromString(String value) {
        if (value == null || value.isBlank()) {
            return EKS_DX;
        }
        try {
            return valueOf(value.toUpperCase().replace("-", "_"));
        } catch (IllegalArgumentException e) {
            return EKS_DX;
        }
    }
}
