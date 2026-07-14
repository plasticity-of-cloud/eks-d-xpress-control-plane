package ai.codriverlabs.eksdx.mgmt.config;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import software.amazon.awssdk.services.eks.EksClient;

/**
 * CDI producer for the EKS client used by EksManagedProvider.
 */
@ApplicationScoped
public class EksClientProducer {

    @Produces
    @ApplicationScoped
    public EksClient eksClient() {
        return EksClient.create();
    }
}
