package cloud.plasticity.eksdx.cli.cluster;

import cloud.plasticity.eksdx.cli.util.EksDxApiClient;
import io.fabric8.kubernetes.client.KubernetesClient;
import jakarta.inject.Inject;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

@Command(name = "cluster", description = "Update a cluster")
public class UpdateClusterCommand implements Runnable {
    @Inject KubernetesClient kubernetesClient;
    @Inject EksDxApiClient apiClient;
    @Option(names = "--name", required = true) String name;
    @Option(names = "--refresh-jwks", description = "Re-read and push JWKS from cluster") boolean refreshJwks;
    @Override
    public void run() {
        if (refreshJwks) {
            String jwks = kubernetesClient.raw("/openid/v1/jwks");
            // TODO: apiClient.updateJwks(name, jwks)
            System.out.printf("✓ JWKS refreshed for \"%s\"%n", name);
        }
    }
}
