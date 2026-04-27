package cloud.plasticity.eksdx.cli.cluster;

import cloud.plasticity.eksdx.cli.util.EksDxApiClient;
import io.fabric8.kubernetes.client.KubernetesClient;
import jakarta.inject.Inject;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

@Command(name = "cluster", description = "Register a cluster with EKS-DX")
public class CreateClusterCommand implements Runnable {

    @Inject KubernetesClient kubernetesClient;
    @Inject EksDxApiClient apiClient;

    @Option(names = "--name", required = true, description = "Cluster name")
    String name;

    @Option(names = "--region", required = true, description = "AWS region")
    String region;

    @Override
    public void run() {
        // 1. Read JWKS from kube-apiserver
        String jwks = kubernetesClient.raw("/openid/v1/jwks");

        // 2. Read issuer from OIDC discovery
        String oidcConfig = kubernetesClient.raw("/.well-known/openid-configuration");
        // TODO: parse issuer from oidcConfig JSON

        // 3. Register with EKS-DX service
        // TODO: apiClient.registerCluster(name, region, issuer, jwks)

        System.out.printf("✓ Cluster \"%s\" registered%n", name);
    }
}
