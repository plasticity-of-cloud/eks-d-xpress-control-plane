package ai.codriverlabs.eksdx.cli.association;

import ai.codriverlabs.eksdx.cli.util.EksDxApiClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.inject.Inject;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

@Command(name = "pod-identity-associations", description = "List pod identity associations")
public class ListAssociationsCommand implements Runnable {

    @Inject EksDxApiClient apiClient;

    private final ObjectMapper mapper = new ObjectMapper();

    @Option(names = "--cluster-name", required = true) String clusterName;
    @Option(names = "--namespace") String namespace;
    @Option(names = "--service-account") String serviceAccount;

    @Override
    public void run() {
        try {
            StringBuilder path = new StringBuilder("/clusters/" + clusterName + "/pod-identity-associations");
            String sep = "?";
            if (namespace != null) { path.append(sep).append("namespace=").append(namespace); sep = "&"; }
            if (serviceAccount != null) { path.append(sep).append("serviceAccount=").append(serviceAccount); }

            String response = apiClient.get(path.toString());
            JsonNode root = mapper.readTree(response);
            JsonNode associations = root.get("associations");

            if (associations == null || associations.isEmpty()) {
                System.out.println("No associations found.");
                return;
            }

            System.out.printf("%-20s %-20s %-25s %-50s%n",
                "ASSOCIATION ID", "NAMESPACE", "SERVICE ACCOUNT", "ROLE ARN");
            for (JsonNode a : associations) {
                System.out.printf("%-20s %-20s %-25s %-50s%n",
                    field(a, "associationId"),
                    field(a, "namespace"),
                    field(a, "serviceAccount"),
                    field(a, "roleArn"));
            }
        } catch (Exception e) {
            System.err.printf("Failed to list associations: %s%n", e.getMessage());
            System.exit(1);
        }
    }

    private String field(JsonNode node, String name) {
        JsonNode f = node.get(name);
        return f != null ? f.asText() : "-";
    }
}
