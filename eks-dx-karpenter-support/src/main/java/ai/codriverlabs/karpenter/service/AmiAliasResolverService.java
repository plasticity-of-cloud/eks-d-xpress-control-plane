package ai.codriverlabs.karpenter.service;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;
import software.amazon.awssdk.services.ssm.SsmClient;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Resolves Karpenter EC2NodeClass AMI aliases to concrete AMI IDs via AWS SSM.
 *
 * Supported alias format: {@code al2023@v1.35} or {@code al2023@latest}
 * SSM path: /aws/service/eks/optimized-ami/{version}/amazon-linux-2023/{arch}/standard/recommended/image_id
 *
 * Results are cached indefinitely per (alias, arch) — AMI IDs are immutable.
 */
@ApplicationScoped
public class AmiAliasResolverService {

    private static final Logger LOG = Logger.getLogger(AmiAliasResolverService.class);

    // alias+arch → ami-id
    private final Map<String, String> cache = new ConcurrentHashMap<>();

    @Inject SsmClient ssm;

    /**
     * Resolves an alias string (e.g. "al2023@v1.35") to an AMI ID for the given arch.
     *
     * @param alias e.g. "al2023@v1.35" or "al2023@latest"
     * @param arch  e.g. "arm64" or "x86_64"
     * @return AMI ID, or null if alias format is unrecognised or SSM lookup fails
     */
    public String resolve(String alias, String arch) {
        if (alias == null || !alias.contains("@")) return null;

        String[] parts = alias.split("@", 2);
        String family = parts[0].toLowerCase();
        String version = parts[1].startsWith("v") ? parts[1].substring(1) : parts[1];

        String ssmFamily = switch (family) {
            case "al2023" -> "amazon-linux-2023";
            case "bottlerocket" -> "bottlerocket";
            default -> null;
        };
        if (ssmFamily == null) {
            LOG.warnf("Unrecognised AMI alias family '%s' — skipping resolution", family);
            return null;
        }

        String ssmArch = "arm64".equals(arch) ? "arm64" : "x86_64";
        String cacheKey = alias + "|" + ssmArch;

        return cache.computeIfAbsent(cacheKey, k -> {
            String path = "/aws/service/eks/optimized-ami/%s/%s/%s/standard/recommended/image_id"
                .formatted(version, ssmFamily, ssmArch);
            try {
                String amiId = ssm.getParameter(r -> r.name(path)).parameter().value();
                LOG.infof("Resolved AMI alias '%s' (%s) → %s", alias, ssmArch, amiId);
                return amiId;
            } catch (Exception e) {
                LOG.errorf("Failed to resolve AMI alias '%s' via SSM path %s: %s", alias, path, e.getMessage());
                return null;
            }
        });
    }
}
