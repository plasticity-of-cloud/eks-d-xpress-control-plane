package ai.codriverlabs.karpenter.reconciler;

import ai.codriverlabs.karpenter.model.Ec2NodeClass;
import ai.codriverlabs.karpenter.service.ValidationConditionService;
import io.fabric8.kubernetes.api.model.ObjectMeta;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.dsl.Resource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class Ec2NodeClassReconcilerTest {

    @Mock ValidationConditionService validationConditionService;
    @Mock KubernetesClient client;

    Ec2NodeClassReconciler reconciler;

    @BeforeEach
    void setUp() {
        reconciler = new Ec2NodeClassReconciler();
        reconciler.validationConditionService = validationConditionService;
        reconciler.client = client;
    }

    @Test
    void reconcile_noOp_whenAlreadySucceeded() {
        when(validationConditionService.isValidationSucceeded(any())).thenReturn(true);
        reconciler.reconcile(ec2NodeClass("default"));
        verify(validationConditionService, never()).setValidationSucceeded(any());
        verifyNoInteractions(client);
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void reconcile_patchesStatus_whenConditionMissing() {
        when(validationConditionService.isValidationSucceeded(any())).thenReturn(false);
        var op = mock(io.fabric8.kubernetes.client.dsl.MixedOperation.class);
        Resource resource = mock(Resource.class);
        when(client.resources(Ec2NodeClass.class)).thenReturn(op);
        when(op.resource(any())).thenReturn(resource);

        reconciler.reconcile(ec2NodeClass("default"));

        verify(validationConditionService).setValidationSucceeded(any());
        verify(resource).patchStatus();
    }

    private Ec2NodeClass ec2NodeClass(String name) {
        var nc = new Ec2NodeClass();
        var meta = new ObjectMeta();
        meta.setName(name);
        meta.setGeneration(1L);
        nc.setMetadata(meta);
        return nc;
    }
}
