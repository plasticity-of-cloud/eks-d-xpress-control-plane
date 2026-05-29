package ai.codriverlabs.eksdx.cli.cluster;

import ai.codriverlabs.eksdx.cli.util.EksDxApiClient;
import ai.codriverlabs.eksdx.cli.util.KubeApiClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.inject.Inject;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

@Command(name = "cluster", description = "Register a cluster with EKS-DX")
public class CreateClusterCommand implements Runnable {

    @Inject EksDxApiClient apiClient;

    private final ObjectMapper mapper = new ObjectMapper();

    // package-private for testing
    KubeApiClient kubeApiClient;

    @Option(names = "--name", required = true, description = "Cluster name")
    String name;

    @Option(names = "--region", required = true, description = "AWS region")
    String region;

    @Option(names = "--kubeconfig", description = "Path to kubeconfig (default: ~/.kube/config)")
    String kubeconfig;

    @Override
    public void run() {
        try {
            KubeApiClient kube = kubeApiClient != null ? kubeApiClient : new KubeApiClient(kubeconfig);

            String jwks = kube.get("/openid/v1/jwks");
            String oidcConfig = kube.get("/.well-known/openid-configuration");
            String issuer = parseIssuer(oidcConfig);

            ObjectNode body = mapper.createObjectNode();
            body.put("name", name);
            body.put("issuer", issuer);
            body.put("jwks", jwks);

            apiClient.post("/clusters", body.toString());

            System.out.printf("✓ Cluster \"%s\" registered%n", name);
            System.out.printf("  Issuer: %s%n", issuer);
            System.out.printf("  JWKS: %d key(s)%n", countKeys(jwks));
        } catch (Exception e) {
            System.err.printf("Failed to register cluster: %s%n", e.getMessage());
            System.exit(1);
        }
    }

    String parseIssuer(String oidcConfigJson) {
        try {
            JsonNode node = mapper.readTree(oidcConfigJson);
            JsonNode issuerNode = node.get("issuer");
            if (issuerNode == null || issuerNode.asText().isBlank()) {
                throw new IllegalArgumentException("No 'issuer' field in OIDC configuration");
            }
            return issuerNode.asText();
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("Failed to parse OIDC configuration: " + e.getMessage(), e);
        }
    }

    int countKeys(String jwksJson) {
        try {
            JsonNode node = mapper.readTree(jwksJson);
            JsonNode keys = node.get("keys");
            return keys != null && keys.isArray() ? keys.size() : 0;
        } catch (Exception e) {
            return 0;
        }
    }
}
