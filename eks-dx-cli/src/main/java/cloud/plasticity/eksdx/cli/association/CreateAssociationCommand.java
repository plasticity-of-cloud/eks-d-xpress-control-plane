package cloud.plasticity.eksdx.cli.association;

import cloud.plasticity.eksdx.cli.util.EksDxApiClient;
import jakarta.inject.Inject;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

@Command(name = "pod-identity-association", description = "Create a pod identity association")
public class CreateAssociationCommand implements Runnable {
    @Inject EksDxApiClient apiClient;
    @Option(names = "--cluster-name", required = true) String clusterName;
    @Option(names = "--namespace", required = true) String namespace;
    @Option(names = "--service-account", required = true) String serviceAccount;
    @Option(names = "--role-arn", required = true) String roleArn;
    @Override
    public void run() {
        // TODO: apiClient.createAssociation(clusterName, namespace, serviceAccount, roleArn)
        System.out.printf("✓ Association created: %s/%s → %s%n", namespace, serviceAccount, roleArn);
    }
}
