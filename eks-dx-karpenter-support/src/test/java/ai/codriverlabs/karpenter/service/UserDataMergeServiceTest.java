package ai.codriverlabs.karpenter.service;

import ai.codriverlabs.karpenter.model.ClusterIdentity;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
class UserDataMergeServiceTest {

    @Inject
    UserDataMergeService service;

    static final ClusterIdentity ID = new ClusterIdentity(
        "my-cluster", "https://10.0.0.1:6443", "base64ca==", "10.96.0.0/12", "10.96.0.10"
    );

    // ── BottleRocket ──────────────────────────────────────────────────────────

    @Test
    void br_empty_injectsBlock() {
        String r = service.merge("Bottlerocket", null, ID);
        assertNotNull(r);
        assertTrue(r.contains("[settings.kubernetes]"));
        assertTrue(r.contains("api-server = \"https://10.0.0.1:6443\""));
        assertTrue(r.contains("cluster-dns-ip = \"10.96.0.10\""));
        assertTrue(r.contains("cluster-name = \"my-cluster\""));
    }

    @Test
    void br_idempotent_returnsNull() {
        String first = service.merge("Bottlerocket", null, ID);
        assertNull(service.merge("Bottlerocket", first, ID), "Re-merge of managed userData must return null");
    }

    @Test
    void br_existingContentPreserved() {
        String existing = "[settings.network]\nhostname = \"node1\"";
        String r = service.merge("Bottlerocket", existing, ID);
        assertNotNull(r);
        assertTrue(r.contains("[settings.network]"));
        assertTrue(r.contains("[settings.kubernetes]"));
    }

    @Test
    void br_existingSectionMerged() {
        String existing = "[settings.kubernetes]\nsome-other-key = \"val\"";
        String r = service.merge("Bottlerocket", existing, ID);
        assertNotNull(r);
        assertTrue(r.contains("api-server"));
        assertTrue(r.contains("some-other-key"));
    }

    // ── AL2 / AL2023 ─────────────────────────────────────────────────────────

    @Test
    void al2_empty_createsMime() {
        String r = service.merge("AL2023", null, ID);
        assertNotNull(r);
        assertTrue(r.startsWith("MIME-Version:"));
        assertTrue(r.contains("application/node.eks.aws"));
        assertTrue(r.contains("apiServerEndpoint: https://10.0.0.1:6443"));
        assertTrue(r.contains("cidr: 10.96.0.0/12"));
    }

    @Test
    void al2_idempotent_returnsNull() {
        String first = service.merge("AL2023", null, ID);
        assertNull(service.merge("AL2023", first, ID), "Re-merge of managed userData must return null");
    }

    @Test
    void al2_existingShellScriptPreserved() {
        String r = service.merge("AL2", "#!/bin/bash\necho hello", ID);
        assertNotNull(r);
        assertTrue(r.contains("application/node.eks.aws"));
        assertTrue(r.contains("#!/bin/bash"));
    }

    @Test
    void al2_existingMimePrepended() {
        String existing = "MIME-Version: 1.0\nContent-Type: multipart/mixed; boundary=\"//\"\n\n--//\nContent-Type: text/x-shellscript\n\n#!/bin/bash\n\n--//--\n";
        String r = service.merge("AL2023", existing, ID);
        assertNotNull(r);
        assertTrue(r.contains("application/node.eks.aws"));
        assertTrue(r.contains("#!/bin/bash"));
    }

    @Test
    void custom_treatedAsAl2() {
        String r = service.merge("Custom", null, ID);
        assertNotNull(r);
        assertTrue(r.contains("application/node.eks.aws"));
    }

    // ── ClusterIdentityService.computeClusterDnsIp ───────────────────────────

    @Test
    void dnsIp_standardCidr() throws Exception {
        assertEquals("10.96.0.10", ClusterIdentityService.computeClusterDnsIp("10.96.0.0/12"));
    }

    @Test
    void dnsIp_customCidr() throws Exception {
        assertEquals("172.20.0.10", ClusterIdentityService.computeClusterDnsIp("172.20.0.0/16"));
    }

    @Test
    void dnsIp_slash24() throws Exception {
        assertEquals("192.168.1.10", ClusterIdentityService.computeClusterDnsIp("192.168.1.0/24"));
    }
}
