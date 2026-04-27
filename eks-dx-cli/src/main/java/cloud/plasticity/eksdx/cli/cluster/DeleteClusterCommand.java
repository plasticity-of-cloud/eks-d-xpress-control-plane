package cloud.plasticity.eksdx.cli.cluster;

import cloud.plasticity.eksdx.cli.util.EksDxApiClient;
import jakarta.inject.Inject;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

@Command(name = "cluster", description = "Delete a cluster")
public class DeleteClusterCommand implements Runnable {
    @Inject EksDxApiClient apiClient;
    @Option(names = "--name", required = true) String name;
    @Override
    public void run() { /* TODO */ }
}
