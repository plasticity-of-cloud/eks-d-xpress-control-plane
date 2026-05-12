package ai.codriverlabs.eksdx.cli.cluster;

import ai.codriverlabs.eksdx.cli.util.EksDxApiClient;
import jakarta.inject.Inject;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

@Command(name = "cluster", description = "Delete a cluster")
public class DeleteClusterCommand implements Runnable {

    @Inject EksDxApiClient apiClient;

    @Option(names = "--name", required = true) String name;

    @Override
    public void run() {
        try {
            apiClient.delete("/clusters/" + name);
            System.out.printf("✓ Cluster \"%s\" deregistered%n", name);
        } catch (Exception e) {
            System.err.printf("Failed to delete cluster: %s%n", e.getMessage());
            System.exit(1);
        }
    }
}
