package ai.codriverlabs.karpenter.model;

/**
 * Cluster bootstrap fields required by Karpenter EC2NodeClass userData.
 * Resolved from in-cluster Kubernetes API sources and cached with a 5-minute TTL.
 */
public record ClusterIdentity(
    String clusterName,
    String apiServerEndpoint,
    String certificateAuthority,  // base64-encoded PEM
    String serviceCidr,
    String clusterDnsIp
) {}
