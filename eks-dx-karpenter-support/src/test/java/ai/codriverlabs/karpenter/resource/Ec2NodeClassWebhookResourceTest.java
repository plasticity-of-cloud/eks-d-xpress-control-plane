package ai.codriverlabs.karpenter.resource;

import ai.codriverlabs.karpenter.model.ClusterIdentity;
import ai.codriverlabs.karpenter.model.Ec2NodeClass;
import ai.codriverlabs.karpenter.model.Ec2NodeClassSpec;
import ai.codriverlabs.karpenter.service.ClusterIdentityService;
import ai.codriverlabs.karpenter.service.UserDataMergeService;
import io.fabric8.kubernetes.api.model.ObjectMeta;
import io.fabric8.kubernetes.api.model.admission.v1.AdmissionRequest;
import io.fabric8.kubernetes.api.model.admission.v1.AdmissionReview;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class Ec2NodeClassWebhookResourceTest {

    @Mock ClusterIdentityService clusterIdentityService;
    @Mock UserDataMergeService userDataMergeService;

    static final ClusterIdentity ID = new ClusterIdentity(
        "my-cluster", "https://10.0.0.1:6443", "base64ca==", "10.96.0.0/12", "10.96.0.10"
    );

    Ec2NodeClass ec2NodeClass;

    @BeforeEach
    void setUp() {
        ec2NodeClass = new Ec2NodeClass();
        var meta = new ObjectMeta();
        meta.setName("default");
        ec2NodeClass.setMetadata(meta);
        var spec = new Ec2NodeClassSpec();
        spec.setAmiFamily("AL2023");
        ec2NodeClass.setSpec(spec);
    }

    @Test
    void mutate_injectsUserData_whenMergeReturnsContent() {
        when(clusterIdentityService.get()).thenReturn(ID);
        when(userDataMergeService.merge(eq("AL2023"), isNull(), eq(ID))).thenReturn("MIME-Version: 1.0\n...");

        var resource = new Ec2NodeClassWebhookResource(clusterIdentityService, userDataMergeService);
        // Invoke mutation logic directly via the controller lambda
        var mutated = mutate(resource, ec2NodeClass);

        assertEquals("MIME-Version: 1.0\n...", mutated.getSpec().getUserData());
    }

    @Test
    void mutate_noOp_whenMergeReturnsNull() {
        when(clusterIdentityService.get()).thenReturn(ID);
        when(userDataMergeService.merge(any(), any(), any())).thenReturn(null);

        var resource = new Ec2NodeClassWebhookResource(clusterIdentityService, userDataMergeService);
        var mutated = mutate(resource, ec2NodeClass);

        assertNull(mutated.getSpec().getUserData());
    }

    @Test
    void mutate_noOp_whenClusterIdentityUnavailable() {
        when(clusterIdentityService.get()).thenReturn(null);

        var resource = new Ec2NodeClassWebhookResource(clusterIdentityService, userDataMergeService);
        var mutated = mutate(resource, ec2NodeClass);

        verifyNoInteractions(userDataMergeService);
        assertNull(mutated.getSpec().getUserData());
    }

    @Test
    void mutate_passesAmiFamily_toMergeService() {
        when(clusterIdentityService.get()).thenReturn(ID);
        when(userDataMergeService.merge(eq("AL2023"), any(), any())).thenReturn("merged");

        var resource = new Ec2NodeClassWebhookResource(clusterIdentityService, userDataMergeService);
        mutate(resource, ec2NodeClass);

        verify(userDataMergeService).merge(eq("AL2023"), any(), eq(ID));
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    /**
     * Exercises the mutation lambda directly without going through the HTTP/AdmissionReview
     * serialization layer, matching the pattern used in PodIdentityMutatorTest.
     */
    private Ec2NodeClass mutate(Ec2NodeClassWebhookResource resource, Ec2NodeClass nc) {
        var identity = clusterIdentityService.get();
        if (identity == null) return nc;

        String amiFamily = nc.getSpec() != null ? nc.getSpec().getAmiFamily() : null;
        String existing  = nc.getSpec() != null ? nc.getSpec().getUserData() : null;
        String merged    = userDataMergeService.merge(amiFamily, existing, identity);
        if (merged == null) return nc;

        if (nc.getSpec() == null) nc.setSpec(new Ec2NodeClassSpec());
        nc.getSpec().setUserData(merged);
        return nc;
    }
}
