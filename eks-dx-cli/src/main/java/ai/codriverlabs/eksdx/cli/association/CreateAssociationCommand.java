package ai.codriverlabs.eksdx.cli.association;

import ai.codriverlabs.eksdx.cli.util.EksDxApiClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.inject.Inject;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

@Command(name = "pod-identity-association", description = "Create a pod identity association")
public class CreateAssociationCommand implements Runnable {

    @Inject EksDxApiClient apiClient;

    private final ObjectMapper mapper = new ObjectMapper();

    @Option(names = "--cluster-name", required = true) String clusterName;
    @Option(names = "--namespace", required = true) String namespace;
    @Option(names = "--service-account", required = true) String serviceAccount;
    @Option(names = "--role-arn", required = true) String roleArn;

    @Override
    public void run() {
        try {
            ObjectNode body = mapper.createObjectNode();
            body.put("namespace", namespace);
            body.put("serviceAccount", serviceAccount);
            body.put("roleArn", roleArn);

            String response = apiClient.post(
                "/clusters/" + clusterName + "/pod-identity-associations",
                body.toString());

            JsonNode node = mapper.readTree(response);
            String associationId = node.has("associationId") ? node.get("associationId").asText() : "-";

            System.out.printf("✓ Association created: %s/%s → %s%n", namespace, serviceAccount, roleArn);
            System.out.printf("  Association ID: %s%n", associationId);
        } catch (Exception e) {
            System.err.printf("Failed to create association: %s%n", e.getMessage());
            System.exit(1);
        }
    }
}
