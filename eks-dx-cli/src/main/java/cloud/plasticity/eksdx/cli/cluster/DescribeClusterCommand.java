package cloud.plasticity.eksdx.cli.cluster;

import cloud.plasticity.eksdx.cli.util.EksDxApiClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.inject.Inject;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

@Command(name = "cluster", description = "Describe a cluster")
public class DescribeClusterCommand implements Runnable {

    @Inject EksDxApiClient apiClient;

    private final ObjectMapper mapper = new ObjectMapper();

    @Option(names = "--name", required = true) String name;

    @Override
    public void run() {
        try {
            String response = apiClient.get("/clusters/" + name);
            JsonNode node = mapper.readTree(response);

            System.out.printf("Name:       %s%n", field(node, "clusterName"));
            System.out.printf("Issuer:     %s%n", field(node, "issuer"));
            System.out.printf("Created:    %s%n", field(node, "createdAt"));
            System.out.printf("Updated:    %s%n", field(node, "updatedAt"));
        } catch (Exception e) {
            System.err.printf("Failed to describe cluster: %s%n", e.getMessage());
            System.exit(1);
        }
    }

    private String field(JsonNode node, String name) {
        JsonNode f = node.get(name);
        return f != null ? f.asText() : "-";
    }
}
