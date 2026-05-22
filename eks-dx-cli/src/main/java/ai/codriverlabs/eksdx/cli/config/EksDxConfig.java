package ai.codriverlabs.eksdx.cli.config;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/**
 * Reads/writes ~/.eks-dx/config.
 * Resolution order: CLI flag → env var → config file → default.
 */
public class EksDxConfig {

    private static final Path CONFIG_DIR = Path.of(System.getProperty("user.home"), ".eks-dx");
    private static final Path CONFIG_FILE = CONFIG_DIR.resolve("config");

    /** ~/.eks-dx/tenants/{region}/{tenantId}.pem */
    public Path tenantSshKeyPath(String region, String tenantId) {
        return CONFIG_DIR.resolve("tenants").resolve(region).resolve(tenantId + ".pem");
    }
    private final Properties props = new Properties();

    public EksDxConfig() {
        load();
    }

    public String getEndpoint() {
        String env = System.getenv("EKS_DX_ENDPOINT");
        if (env != null && !env.isBlank()) return env;
        return props.getProperty("endpoint", "https://eks-dx.codriverlabs.ai");
    }

    public String getRegion() {
        String env = System.getenv("AWS_REGION");
        if (env != null && !env.isBlank()) return env;
        return props.getProperty("region", "us-east-1");
    }

    public String getStreamUrl() {
        String env = System.getenv("EKS_DX_STREAM_URL");
        if (env != null && !env.isBlank()) return env;
        return props.getProperty("stream-url", "");
    }

    public void save(String endpoint, String region) throws IOException {
        Files.createDirectories(CONFIG_DIR);
        Properties p = new Properties();
        if (endpoint != null) p.setProperty("endpoint", endpoint);
        if (region != null) p.setProperty("region", region);
        try (var out = Files.newOutputStream(CONFIG_FILE)) {
            p.store(out, "eks-dx CLI configuration");
        }
    }

    private void load() {
        if (Files.exists(CONFIG_FILE)) {
            try (var in = Files.newInputStream(CONFIG_FILE)) {
                props.load(in);
            } catch (IOException e) {
                // Ignore — use defaults
            }
        }
    }

    public static Path configFile() {
        return CONFIG_FILE;
    }
}
