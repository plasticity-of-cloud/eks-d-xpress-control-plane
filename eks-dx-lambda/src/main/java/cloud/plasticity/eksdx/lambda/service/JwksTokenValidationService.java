package cloud.plasticity.eksdx.lambda.service;

import cloud.plasticity.eksdx.lambda.model.TokenClaims;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;

/**
 * Validates Kubernetes SA tokens using JWKS stored in DynamoDB.
 * JWKS is cached in memory with a 5-minute TTL.
 */
@ApplicationScoped
public class JwksTokenValidationService {

    private static final Logger LOG = Logger.getLogger(JwksTokenValidationService.class);

    @Inject DynamoDbClient dynamoDb;
    @Inject DynamoDbClusterService clusterService;

    public TokenClaims validateToken(String token, String clusterName) {
        // TODO:
        // 1. Get JWKS for cluster from DynamoDB (cached)
        // 2. Verify JWT signature (RS256)
        // 3. Validate audience (pods.eks.amazonaws.com), expiry, issuer
        // 4. Extract claims: subject → namespace + serviceAccount
        throw new UnsupportedOperationException("Not yet implemented");
    }
}
