package cloud.plasticity.eksdx.lambda.service;

import cloud.plasticity.eksdx.lambda.model.TokenClaims;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;
import org.jose4j.jwk.JsonWebKeySet;
import org.jose4j.jwt.JwtClaims;
import org.jose4j.jwt.MalformedClaimException;
import org.jose4j.jwt.consumer.JwtConsumer;
import org.jose4j.jwt.consumer.JwtConsumerBuilder;
import org.jose4j.jwt.consumer.InvalidJwtException;
import org.jose4j.keys.resolvers.JwksVerificationKeyResolver;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Validates Kubernetes SA tokens using JWKS stored in DynamoDB.
 * JWKS is cached in memory with a 5-minute TTL.
 */
@ApplicationScoped
public class JwksTokenValidationService {

    private static final Logger LOG = Logger.getLogger(JwksTokenValidationService.class);
    private static final String EKS_POD_IDENTITY_AUDIENCE = "pods.eks.amazonaws.com";
    private static final String EKS_DX_AUDIENCE = "eks-dx.plasticity.cloud";
    private static final long JWKS_CACHE_TTL_SECONDS = 300; // 5 minutes

    @Inject
    DynamoDbClusterService clusterService;

    private final Map<String, CachedJwks> jwksCache = new ConcurrentHashMap<>();

    /**
     * Validate a pod SA token for credential exchange.
     * Audience: pods.eks.amazonaws.com
     */
    public TokenClaims validateToken(String token, String clusterName) {
        JwtClaims claims = validateJwt(token, clusterName, EKS_POD_IDENTITY_AUDIENCE);
        return extractTokenClaims(claims);
    }

    /**
     * Validate a webhook SA token for management API access.
     * Audience: eks-dx.plasticity.cloud
     * Subject must be the webhook service account.
     */
    public void validateWebhookToken(String token, String clusterName, String expectedSubject) {
        JwtClaims claims = validateJwt(token, clusterName, EKS_DX_AUDIENCE);
        try {
            String subject = claims.getSubject();
            if (!expectedSubject.equals(subject)) {
                throw new SecurityException("Unexpected subject: " + subject);
            }
        } catch (MalformedClaimException e) {
            throw new SecurityException("Missing subject claim", e);
        }
    }

    private JwtClaims validateJwt(String token, String clusterName, String expectedAudience) {
        if (token.startsWith("Bearer ")) {
            token = token.substring(7);
        }

        JsonWebKeySet jwks = getJwks(clusterName);
        String issuer = clusterService.getIssuer(clusterName);

        try {
            JwtConsumer consumer = new JwtConsumerBuilder()
                .setVerificationKeyResolver(new JwksVerificationKeyResolver(jwks.getJsonWebKeys()))
                .setExpectedAudience(expectedAudience)
                .setExpectedIssuer(issuer)
                .setRequireExpirationTime()
                .setRequireSubject()
                .build();

            return consumer.processToClaims(token);
        } catch (InvalidJwtException e) {
            LOG.warnf("JWT validation failed for cluster %s: %s", clusterName, e.getMessage());
            throw new IllegalArgumentException("Invalid token: " + e.getMessage(), e);
        }
    }

    private TokenClaims extractTokenClaims(JwtClaims claims) {
        try {
            String subject = claims.getSubject();
            // subject = "system:serviceaccount:<namespace>:<serviceAccount>"
            String[] parts = subject.split(":");
            if (parts.length != 4 || !"system".equals(parts[0]) || !"serviceaccount".equals(parts[1])) {
                throw new IllegalArgumentException("Unexpected subject format: " + subject);
            }

            String namespace = parts[2];
            String serviceAccount = parts[3];

            // Optional claims — may not be present depending on K8s version
            String podName = getStringClaim(claims, "kubernetes.io/pod/name");
            String podUid = getStringClaim(claims, "kubernetes.io/pod/uid");
            String saUid = getStringClaim(claims, "kubernetes.io/serviceaccount/uid");

            LOG.infof("Token validated: %s/%s (pod: %s)", namespace, serviceAccount, podName);

            return new TokenClaims(namespace, serviceAccount, saUid, podName, podUid, subject);
        } catch (MalformedClaimException e) {
            throw new IllegalArgumentException("Failed to extract claims: " + e.getMessage(), e);
        }
    }

    private String getStringClaim(JwtClaims claims, String name) {
        try {
            return claims.getStringClaimValue(name);
        } catch (MalformedClaimException e) {
            return null;
        }
    }

    private JsonWebKeySet getJwks(String clusterName) {
        CachedJwks cached = jwksCache.get(clusterName);
        if (cached != null && !cached.isExpired()) {
            return cached.jwks;
        }

        String jwksJson = clusterService.getJwks(clusterName);
        try {
            JsonWebKeySet jwks = new JsonWebKeySet(jwksJson);
            jwksCache.put(clusterName, new CachedJwks(jwks, Instant.now()));
            LOG.infof("JWKS loaded for cluster %s (%d keys)", clusterName, jwks.getJsonWebKeys().size());
            return jwks;
        } catch (Exception e) {
            throw new IllegalStateException("Failed to parse JWKS for cluster " + clusterName, e);
        }
    }

    private record CachedJwks(JsonWebKeySet jwks, Instant loadedAt) {
        boolean isExpired() {
            return Instant.now().isAfter(loadedAt.plusSeconds(JWKS_CACHE_TTL_SECONDS));
        }
    }
}
