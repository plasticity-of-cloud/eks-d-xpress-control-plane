package ai.codriverlabs.karpenter.reconciler;

import ai.codriverlabs.karpenter.model.Ec2NodeClass;
import ai.codriverlabs.karpenter.service.ValidationConditionService;
import io.fabric8.kubernetes.api.model.ObjectMeta;
import io.javaoperatorsdk.operator.api.reconciler.Context;
import io.javaoperatorsdk.operator.api.reconciler.UpdateControl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class Ec2NodeClassReconcilerTest {

    @Mock ValidationConditionService validationConditionService;
    @Mock Context<Ec2NodeClass> context;

    Ec2NodeClassReconciler reconciler;

    @BeforeEach
    void setUp() {
        reconciler = new Ec2NodeClassReconciler();
        reconciler.validationConditionService = validationConditionService;
    }

    @Test
    void reconcile_noUpdate_whenAlreadySucceeded() {
        when(validationConditionService.isValidationSucceeded(any())).thenReturn(true);
        UpdateControl<Ec2NodeClass> result = reconciler.reconcile(ec2NodeClass("default"), context);
        assertTrue(result.isNoUpdate());
        verify(validationConditionService, never()).setValidationSucceeded(any());
    }

    @Test
    void reconcile_patchesStatus_whenConditionMissing() {
        when(validationConditionService.isValidationSucceeded(any())).thenReturn(false);
        UpdateControl<Ec2NodeClass> result = reconciler.reconcile(ec2NodeClass("default"), context);
        assertFalse(result.isNoUpdate());
        assertTrue(result.isPatchStatus());
        verify(validationConditionService).setValidationSucceeded(any());
    }

    @Test
    void reconcile_callsSetBeforePatch() {
        when(validationConditionService.isValidationSucceeded(any())).thenReturn(false);
        var nc = ec2NodeClass("my-nodeclass");
        reconciler.reconcile(nc, context);
        verify(validationConditionService).setValidationSucceeded(nc);
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private Ec2NodeClass ec2NodeClass(String name) {
        var nc = new Ec2NodeClass();
        var meta = new ObjectMeta();
        meta.setName(name);
        meta.setGeneration(1L);
        nc.setMetadata(meta);
        return nc;
    }
}
