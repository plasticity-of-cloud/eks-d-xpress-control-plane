package ai.codriverlabs.eksdx.cli.cluster;

import ai.codriverlabs.eksdx.cli.util.EksDxApiClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.fabric8.kubernetes.client.KubernetesClient;
import jakarta.inject.Inject;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

@Command(name = "cluster", description = "Update a cluster")
public class UpdateClusterCommand implements Runnable {

    @Inject KubernetesClient kubernetesClient;
    @Inject EksDxApiClient apiClient;

    private final ObjectMapper mapper = new ObjectMapper();

    @Option(names = "--name", required = true) String name;
    @Option(names = "--refresh-jwks", description = "Re-read and push JWKS from cluster") boolean refreshJwks;

    @Override
    public void run() {
        if (!refreshJwks) {
            System.err.println("No update action specified. Use --refresh-jwks.");
            System.exit(1);
        }

        try {
            String jwks = kubernetesClient.raw("/openid/v1/jwks");

            ObjectNode body = mapper.createObjectNode();
            body.put("jwks", jwks);

            apiClient.put("/clusters/" + name + "/jwks", body.toString());
            System.out.printf("✓ JWKS refreshed for \"%s\"%n", name);
        } catch (Exception e) {
            System.err.printf("Failed to update cluster: %s%n", e.getMessage());
            System.exit(1);
        }
    }
}
