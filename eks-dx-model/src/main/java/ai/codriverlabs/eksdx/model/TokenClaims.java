package ai.codriverlabs.eksdx.model;

public record TokenClaims(
    String namespace,
    String serviceAccount,
    String serviceAccountUid,
    String podName,
    String podUid,
    String subject
) {
}
