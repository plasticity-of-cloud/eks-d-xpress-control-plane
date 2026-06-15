package ai.codriverlabs.karpenter.service;

import ai.codriverlabs.karpenter.model.ClusterIdentity;
import io.fabric8.kubernetes.client.KubernetesClient;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.net.InetAddress;
import java.time.Instant;
import java.util.Base64;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Resolves and caches Karpenter cluster bootstrap fields from in-cluster sources.
 *
 * <p>Sources per roadmap spec (NODEPOOL_CONTROLLER_MIGRATION.md):
 * <ul>
 *   <li>apiServerEndpoint  — endpoints/kubernetes in default namespace</li>
 *   <li>certificateAuthority — configmap/kube-root-ca.crt in kube-system (base64-encoded)</li>
 *   <li>serviceCidr — configmap/kubeadm-config → ClusterConfiguration.serviceSubnet;
 *       fallback: configmap/eks-dx-config serviceSubnet key</li>
 *   <li>clusterDnsIp — 10th host of serviceCidr</li>
 *   <li>clusterName — configmap/eks-dx-config cluster-name key</li>
 * </ul>
 *
 * <p>TTL: 5 minutes. Call {@link #refresh()} to force an immediate re-resolve.
 */
@ApplicationScoped
public class ClusterIdentityService {

    private static final Logger LOG = Logger.getLogger(ClusterIdentityService.class);
    private static final long TTL_SECONDS = 300;

    @Inject
    KubernetesClient client;

    private final AtomicReference<ClusterIdentity> cache = new AtomicReference<>();
    private volatile Instant refreshAt = Instant.EPOCH;

    public ClusterIdentity get() {
        if (Instant.now().isAfter(refreshAt)) {
            refresh();
        }
        return cache.get();
    }

    public synchronized void refresh() {
        try {
            var identity = resolve();
            cache.set(identity);
            refreshAt = Instant.now().plusSeconds(TTL_SECONDS);
            LOG.infof("Resolved cluster identity: cluster=%s endpoint=%s serviceCidr=%s dnsIp=%s",
                identity.clusterName(), identity.apiServerEndpoint(),
                identity.serviceCidr(), identity.clusterDnsIp());
        } catch (Exception e) {
            LOG.errorf("Failed to resolve cluster identity: %s", e.getMessage());
            if (cache.get() == null) throw new IllegalStateException("Cluster identity unavailable", e);
            // Keep stale cache rather than returning null
        }
    }

    private ClusterIdentity resolve() throws Exception {
        String serviceCidr = resolveServiceCidr();
        return new ClusterIdentity(
            resolveClusterName(),
            resolveTenantId(),
            resolveApiServerEndpoint(),
            resolveCertificateAuthority(),
            serviceCidr,
            computeClusterDnsIp(serviceCidr)
        );
    }

    private String resolveApiServerEndpoint() {
        var ep = client.endpoints().inNamespace("default").withName("kubernetes").get();
        if (ep == null || ep.getSubsets() == null || ep.getSubsets().isEmpty())
            throw new IllegalStateException("endpoints/kubernetes not found in default namespace");
        var subset = ep.getSubsets().get(0);
        String ip = subset.getAddresses().get(0).getIp();
        int port = subset.getPorts().get(0).getPort();
        return "https://" + ip + ":" + port;
    }

    private String resolveCertificateAuthority() {
        var cm = client.configMaps().inNamespace("kube-system").withName("kube-root-ca.crt").get();
        if (cm == null) throw new IllegalStateException("configmap/kube-root-ca.crt not found in kube-system");
        return Base64.getEncoder().encodeToString(cm.getData().get("ca.crt").getBytes());
    }

    private String resolveServiceCidr() {
        // Primary: kubeadm-config ClusterConfiguration.serviceSubnet
        var kubeadm = client.configMaps().inNamespace("kube-system").withName("kubeadm-config").get();
        if (kubeadm != null) {
            for (String line : kubeadm.getData().getOrDefault("ClusterConfiguration", "").split("\n")) {
                String t = line.trim();
                if (t.startsWith("serviceSubnet:")) return t.substring("serviceSubnet:".length()).trim();
            }
        }
        // Fallback: eks-dx-config (written by the eks-dx CLI at cluster registration)
        var eksDxConfig = client.configMaps().inNamespace("kube-system").withName("eks-dx-config").get();
        if (eksDxConfig != null && eksDxConfig.getData().containsKey("serviceSubnet"))
            return eksDxConfig.getData().get("serviceSubnet");
        throw new IllegalStateException("serviceCidr not found in kubeadm-config or eks-dx-config");
    }

    private String resolveClusterName() {
        var cm = client.configMaps().inNamespace("kube-system").withName("eks-dx-config").get();
        if (cm != null && cm.getData().containsKey("cluster-name")) return cm.getData().get("cluster-name");
        LOG.warn("eks-dx-config missing cluster-name key — using 'default'");
        return "default";
    }

    private String resolveTenantId() {
        var cm = client.configMaps().inNamespace("kube-system").withName("eks-dx-config").get();
        if (cm != null && cm.getData().containsKey("tenant-id")) return cm.getData().get("tenant-id");
        LOG.warn("eks-dx-config missing tenant-id key");
        return "";
    }

    /**
     * Returns the 10th host address in the given CIDR.
     * e.g. 10.96.0.0/12 → 10.96.0.10, 172.20.0.0/16 → 172.20.0.10
     */
    static String computeClusterDnsIp(String cidr) throws Exception {
        String[] parts = cidr.split("/");
        byte[] addr = InetAddress.getByName(parts[0]).getAddress();
        int prefix = Integer.parseInt(parts[1]);
        // Zero out host bits to get the network address
        for (int i = prefix; i < 32; i++) {
            addr[i / 8] &= (byte) ~(1 << (7 - (i % 8)));
        }
        // Add 10
        int carry = 10;
        for (int i = 3; i >= 0 && carry > 0; i--) {
            int sum = (addr[i] & 0xFF) + carry;
            addr[i] = (byte) (sum & 0xFF);
            carry = sum >> 8;
        }
        return InetAddress.getByAddress(addr).getHostAddress();
    }
}
