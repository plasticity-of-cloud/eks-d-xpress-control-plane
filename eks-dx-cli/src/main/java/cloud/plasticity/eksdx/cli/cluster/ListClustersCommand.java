package cloud.plasticity.eksdx.cli.cluster;

import cloud.plasticity.eksdx.cli.util.EksDxApiClient;
import jakarta.inject.Inject;
import picocli.CommandLine.Command;

@Command(name = "clusters", description = "List registered clusters")
public class ListClustersCommand implements Runnable {
    @Inject EksDxApiClient apiClient;
    @Override
    public void run() { /* TODO */ }
}
