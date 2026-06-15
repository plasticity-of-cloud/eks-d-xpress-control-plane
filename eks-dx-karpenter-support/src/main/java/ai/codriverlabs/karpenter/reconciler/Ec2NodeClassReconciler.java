package ai.codriverlabs.karpenter.reconciler;

import ai.codriverlabs.karpenter.model.Ec2NodeClass;
import ai.codriverlabs.karpenter.service.ValidationConditionService;
import io.javaoperatorsdk.operator.api.reconciler.Context;
import io.javaoperatorsdk.operator.api.reconciler.ControllerConfiguration;
import io.javaoperatorsdk.operator.api.reconciler.Reconciler;
import io.javaoperatorsdk.operator.api.reconciler.UpdateControl;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

/**
 * JOSDK reconciler for Karpenter EC2NodeClass resources.
 *
 * <p><b>Phase 1:</b> patches {@code status.conditions[ValidationSucceeded=True]}, replacing
 * the fragile kubectl-proxy hack from configure-nodepools.sh. Idempotent — no-ops if the
 * condition already matches the current generation.
 *
 * <p><b>Phase 2 (future):</b> AMI auto-resolution via annotation
 * {@code eks-dx.codriverlabs.ai/node-variant} → SSM lookup → patch spec.amiSelectorTerms.
 */
@ControllerConfiguration
public class Ec2NodeClassReconciler implements Reconciler<Ec2NodeClass> {

    private static final Logger LOG = Logger.getLogger(Ec2NodeClassReconciler.class);

    @Inject
    ValidationConditionService validationConditionService;

    @Override
    public UpdateControl<Ec2NodeClass> reconcile(Ec2NodeClass resource, Context<Ec2NodeClass> context) {
        String name = resource.getMetadata().getName();
        LOG.debugf("Reconciling EC2NodeClass/%s", name);

        if (validationConditionService.isValidationSucceeded(resource)) {
            LOG.debugf("EC2NodeClass/%s already ValidationSucceeded=True — no-op", name);
            return UpdateControl.noUpdate();
        }

        validationConditionService.setValidationSucceeded(resource);
        return UpdateControl.patchStatus(resource);
    }
}
