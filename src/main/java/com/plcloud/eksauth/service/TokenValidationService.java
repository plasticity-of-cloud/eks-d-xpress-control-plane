package com.plcloud.eksauth.service;

import io.smallrye.jwt.auth.principal.JWTParser;
import io.smallrye.jwt.auth.principal.ParseException;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.jwt.JsonWebToken;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;

@ApplicationScoped
public class TokenValidationService {

    private static final Logger LOG = Logger.getLogger(TokenValidationService.class);
    
    public static final String EKS_POD_IDENTITY_AUDIENCE = "pods.eks.amazonaws.com";

    public static class TokenClaims {
        private final String namespace;
        private final String serviceAccount;
        private final String serviceAccountUid;
        private final String podName;
        private final String podUid;
        private final String subject;
        private final Instant expiration;

        public TokenClaims(String namespace, String serviceAccount, String serviceAccountUid,
                          String podName, String podUid, String subject, Instant expiration) {
            this.namespace = namespace;
            this.serviceAccount = serviceAccount;
            this.serviceAccountUid = serviceAccountUid;
            this.podName = podName;
            this.podUid = podUid;
            this.subject = subject;
            this.expiration = expiration;
        }

        public String getNamespace() { return namespace; }
        public String getServiceAccount() { return serviceAccount; }
        public String getServiceAccountUid() { return serviceAccountUid; }
        public String getPodName() { return podName; }
        public String getPodUid() { return podUid; }
        public String getSubject() { return subject; }
        public Instant getExpiration() { return expiration; }
        
        public Map<String, String> getSessionTags() {
            return Map.of(
                "kubernetes-namespace", namespace,
                "kubernetes-service-account", serviceAccount,
                "kubernetes-pod-name", podName != null ? podName : "",
                "kubernetes-pod-uid", podUid != null ? podUid : ""
            );
        }
    }

    @Inject
    JWTParser jwtParser;

    public TokenClaims validateToken(String token, String clusterName) {
        return validateToken(token, clusterName, false);
    }

    public TokenClaims validateToken(String token, String clusterName, boolean skipAudienceCheck) {
        if (token.startsWith("Bearer ")) {
            token = token.substring(7);
        }

        try {
            JsonWebToken jwt = jwtParser.parse(token);

            // Validate required claims
            String namespace = jwt.getClaim("kubernetes.io/serviceaccount/namespace");
            String serviceAccount = jwt.getClaim("kubernetes.io/serviceaccount/service-account.name");

            if (namespace == null || serviceAccount == null) {
                throw new IllegalArgumentException("Missing namespace or service account claims");
            }

            // Validate audience (skip for CI/CD compatibility if requested)
            if (!skipAudienceCheck) {
                validateAudience(jwt);
            }

            // Validate expiration
            Instant expiration = validateExpiration(jwt);

            // Extract additional claims
            String serviceAccountUid = jwt.getClaim("kubernetes.io/serviceaccount/service-account.uid");
            String podName = jwt.getClaim("kubernetes.io/pod/name");
            String podUid = jwt.getClaim("kubernetes.io/pod/uid");

            LOG.infof("Validated token for %s/%s in cluster %s (pod: %s)", 
                namespace, serviceAccount, clusterName, podName);

            return new TokenClaims(namespace, serviceAccount, serviceAccountUid, 
                podName, podUid, jwt.getSubject(), expiration);

        } catch (ParseException e) {
            LOG.errorf("Token validation failed: %s", e.getMessage());
            throw new IllegalArgumentException("Invalid service account token", e);
        }
    }

    private void validateAudience(JsonWebToken jwt) {
        Object audClaim = jwt.getClaim("aud");
        String audience = null;
        
        if (audClaim instanceof String) {
            audience = (String) audClaim;
        } else if (audClaim instanceof String[]) {
            String[] audiences = (String[]) audClaim;
            if (audiences.length > 0) {
                audience = audiences[0];
            }
        }
        
        if (audience == null || !EKS_POD_IDENTITY_AUDIENCE.equals(audience)) {
            LOG.warnf("Invalid audience: %s, expected: %s", audience, EKS_POD_IDENTITY_AUDIENCE);
            throw new IllegalArgumentException(
                "Invalid token audience. Expected: " + EKS_POD_IDENTITY_AUDIENCE);
        }
    }

    private Instant validateExpiration(JsonWebToken jwt) {
        Long exp = jwt.getClaim("exp");
        if (exp == null) {
            throw new IllegalArgumentException("Token missing expiration claim");
        }
        
        Instant expiration = Instant.ofEpochSecond(exp);
        if (expiration.isBefore(Instant.now())) {
            throw new IllegalArgumentException("Token has expired at " + expiration);
        }
        
        return expiration;
    }
}
