package cloud.plasticity.eksdx.cli.association;

import cloud.plasticity.eksdx.cli.util.EksDxApiClient;
import jakarta.inject.Inject;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

@Command(name = "pod-identity-associations", description = "List pod identity associations")
public class ListAssociationsCommand implements Runnable {
    @Inject EksDxApiClient apiClient;
    @Option(names = "--cluster-name", required = true) String clusterName;
    @Override
    public void run() { /* TODO */ }
}
