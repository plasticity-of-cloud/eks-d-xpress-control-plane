package ai.codriverlabs.karpenter.reconciler;

import ai.codriverlabs.karpenter.model.Ec2NodeClass;
import ai.codriverlabs.karpenter.service.ValidationConditionService;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.informers.ResourceEventHandler;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

/**
 * Watches EC2NodeClass resources via a Fabric8 SharedIndexInformer and patches
 * {@code status.conditions[ValidationSucceeded=True]} on CREATE/UPDATE.
 *
 * Replaces the fragile kubectl proxy + curl PATCH from configure-nodepools.sh.
 * Uses quarkus-kubernetes-client directly — no quarkus-operator-sdk dependency needed.
 */
@ApplicationScoped
public class Ec2NodeClassReconciler {

    private static final Logger LOG = Logger.getLogger(Ec2NodeClassReconciler.class);

    @Inject KubernetesClient client;
    @Inject ValidationConditionService validationConditionService;

    void onStart(@Observes StartupEvent ev) {
        client.resources(Ec2NodeClass.class)
            .inAnyNamespace()
            .inform(new ResourceEventHandler<>() {
                @Override
                public void onAdd(Ec2NodeClass nc) { reconcile(nc); }

                @Override
                public void onUpdate(Ec2NodeClass old, Ec2NodeClass nc) { reconcile(nc); }

                @Override
                public void onDelete(Ec2NodeClass nc, boolean deletedFinalStateUnknown) {}
            });
        LOG.info("EC2NodeClass informer started");
    }

    void reconcile(Ec2NodeClass nc) {
        if (validationConditionService.isValidationSucceeded(nc)) {
            LOG.debugf("EC2NodeClass/%s already ValidationSucceeded=True — no-op", nc.getMetadata().getName());
            return;
        }
        validationConditionService.setValidationSucceeded(nc);
        client.resources(Ec2NodeClass.class)
            .resource(nc)
            .patchStatus();
    }
}
