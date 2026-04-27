package cloud.plasticity.eksdx.cli.association;

import cloud.plasticity.eksdx.cli.util.EksDxApiClient;
import jakarta.inject.Inject;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

@Command(name = "pod-identity-association", description = "Delete a pod identity association")
public class DeleteAssociationCommand implements Runnable {

    @Inject EksDxApiClient apiClient;

    @Option(names = "--cluster-name", required = true) String clusterName;
    @Option(names = "--association-id", required = true) String associationId;

    @Override
    public void run() {
        try {
            apiClient.delete(
                "/clusters/" + clusterName + "/pod-identity-associations/" + associationId);
            System.out.printf("✓ Association \"%s\" deleted%n", associationId);
        } catch (Exception e) {
            System.err.printf("Failed to delete association: %s%n", e.getMessage());
            System.exit(1);
        }
    }
}
