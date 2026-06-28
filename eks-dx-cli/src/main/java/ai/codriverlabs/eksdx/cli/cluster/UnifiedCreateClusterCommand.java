package ai.codriverlabs.eksdx.cli.cluster;

import ai.codriverlabs.eksdx.cli.config.EksDxConfig;
import ai.codriverlabs.eksdx.cli.util.AwsSigV4Signer;
import ai.codriverlabs.eksdx.cli.util.EksDxApiClient;
import ai.codriverlabs.eksdx.cli.util.KubeApiClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.inject.Inject;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.LinkedHashMap;

/**
 * Unified create-cluster command. Behaviour depends on --oidc-mode:
 *
 * managed (default): Full tenant provisioning — generates PKI, launches EC2, pre-registers JWKS.
 * self-managed: Registers an externally-managed cluster with user-provided JWKS/issuer.
 */
@Command(name = "create-cluster", description = "Create a managed cluster or register a self-managed one")
public class UnifiedCreateClusterCommand implements Runnable {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Option(names = "--name", required = true, description = "Cluster name")
    String name;

    @Option(names = "--oidc-mode", defaultValue = "managed",
        description = "OIDC mode: 'managed' (default) or 'self-managed'")
    String oidcMode;

    // --- Managed mode options ---
    @Option(names = "--arch", defaultValue = "arm64", description = "CPU architecture: arm64 or x86_64 (managed only)")
    String arch;

    @Option(names = "--pricing", defaultValue = "spot", description = "EC2 pricing: spot or ondemand (managed only)")
    String ec2PricingModel;

    @Option(names = "--k8s-version", defaultValue = "1.35", description = "Kubernetes version (managed only)")
    String k8sVersion;

    @Option(names = "--disk-size", defaultValue = "20", description = "Root disk size in GB (managed only)")
    int diskSizeGb;

    @Option(names = "--eip", description = "Assign Elastic IP (managed only)")
    boolean assignElasticIp;

    @Option(names = "--ssh-cidr", description = "CIDR for SSH access (managed only)")
    String sshCidr;

    @Option(names = "--wait", description = "Stream progress and wait for completion (managed only)")
    boolean wait;

    // --- Self-managed mode options ---
    @Option(names = "--jwks-uri", description = "JWKS endpoint URL (self-managed only)")
    String jwksUri;

    @Option(names = "--jwks-file", description = "Path to JWKS JSON file (self-managed only)")
    String jwksFile;

    @Option(names = "--issuer", description = "SA token issuer URL (self-managed only)")
    String issuer;

    @Option(names = "--ca-cert", description = "Path to cluster CA cert for audit (self-managed, optional)")
    String caCert;

    @Option(names = "--kubeconfig", description = "Path to kubeconfig for JWKS/issuer discovery (self-managed)")
    String kubeconfig;

    // --- Common ---
    @Option(names = "--region", description = "AWS region")
    String region;

    @Option(names = "--output", defaultValue = "text", description = "Output format: text or json")
    String output;

    @Inject EksDxApiClient apiClient;

    @Override
    public void run() {
        if ("managed".equals(oidcMode)) {
            validateManagedMode();
            runManaged();
        } else if ("self-managed".equals(oidcMode)) {
            runSelfManaged();
        } else {
            System.err.println("Error: --oidc-mode must be 'managed' or 'self-managed'");
            System.exit(1);
        }
    }

    private void validateManagedMode() {
        if (jwksUri != null || jwksFile != null || issuer != null) {
            System.err.println("Error: --jwks-uri, --jwks-file, and --issuer cannot be specified in managed mode.");
            System.err.println("       The control plane generates and manages PKI material for managed clusters.");
            System.exit(1);
        }
    }

    private void runManaged() {
        try {
            var body = new LinkedHashMap<String, Object>();
            body.put("clusterName", name);
            body.put("oidcMode", "managed");
            body.put("arch", arch);
            body.put("ec2PricingModel", ec2PricingModel);
            body.put("k8sVersion", k8sVersion);
            body.put("diskSizeGb", diskSizeGb);
            body.put("assignElasticIp", assignElasticIp);
            if (sshCidr != null) body.put("sshCidr", sshCidr);

            EksDxConfig config = new EksDxConfig();
            String resolvedRegion = region != null ? region : config.getRegion();

            String provisioningUrl = config.getProvisioningUrl();
            if (provisioningUrl == null) {
                System.err.println("Error: provisioning URL not configured. Set EKS_DX_PROVISIONING_URL or run 'eks-dx configure'.");
                System.exit(1);
            }
            String url = provisioningUrl.replaceAll("/$", "") + "/clusters";
            String responseBody = apiClient.postFunctionUrl(url, MAPPER.writeValueAsString(body), resolvedRegion);

            JsonNode resp = MAPPER.readTree(responseBody);
            String tenantId = resp.path("tenantId").asText();

            if ("text".equals(output)) {
                System.out.printf("Created cluster \"%s\" (tenant: %s, managed)%n", name, tenantId);
            } else {
                System.out.println(responseBody);
            }

            if (!wait) return;

            String streamUrl = config.getStreamUrl();
            if (streamUrl == null) {
                System.err.println("Error: stream URL not configured.");
                System.exit(1);
            }
            String streamEndpoint = streamUrl.stripTrailing().replaceAll("/$", "")
                + "/tenants/" + tenantId + "/stream";
            streamProgress(streamEndpoint, resolvedRegion, tenantId, config);
        } catch (Exception e) {
            System.err.println("Error: " + e.getMessage());
            System.exit(1);
        }
    }

    private void runSelfManaged() {
        try {
            String resolvedJwks;
            String resolvedIssuer;

            if ((jwksFile != null || jwksUri != null) && issuer != null) {
                resolvedJwks = jwksFile != null
                    ? Files.readString(Path.of(jwksFile))
                    : fetchUrl(jwksUri);
                resolvedIssuer = issuer;
            } else if (jwksFile == null && jwksUri == null && issuer == null) {
                // Try kubeconfig discovery
                KubeApiClient kube = new KubeApiClient(kubeconfig);
                resolvedJwks = kube.get("/openid/v1/jwks");
                resolvedIssuer = parseIssuer(kube.get("/.well-known/openid-configuration"));
            } else {
                System.err.println("Error: Self-managed mode requires the following parameters:");
                System.err.println("         --jwks-uri <url>  or  --jwks-file <path>    (cluster's JWKS)");
                System.err.println("         --issuer <url>                               (SA token issuer URL)");
                System.err.println("       Or: provide neither and connect via --kubeconfig for auto-discovery.");
                System.exit(1);
                return;
            }

            EksDxConfig config = new EksDxConfig();
            String resolvedRegion = region != null ? region : config.getRegion();

            String provisioningUrl = config.getProvisioningUrl();
            if (provisioningUrl == null) {
                System.err.println("Error: provisioning URL not configured. Set EKS_DX_PROVISIONING_URL or run 'eks-dx configure'.");
                System.exit(1);
            }

            var body = new LinkedHashMap<String, Object>();
            body.put("clusterName", name);
            body.put("oidcMode", "self-managed");
            body.put("jwks", resolvedJwks);
            body.put("issuer", resolvedIssuer);

            String url = provisioningUrl.replaceAll("/$", "") + "/clusters";
            String responseBody = apiClient.postFunctionUrl(url, MAPPER.writeValueAsString(body), resolvedRegion);

            if ("text".equals(output)) {
                System.out.printf("✓ Cluster \"%s\" registered (self-managed)%n", name);
                System.out.printf("  Issuer: %s%n", resolvedIssuer);
            } else {
                System.out.println(responseBody);
            }
        } catch (Exception e) {
            System.err.println("Error: " + e.getMessage());
            System.exit(1);
        }
    }

    private void streamProgress(String url, String resolvedRegion, String tenantId, EksDxConfig config) {
        try {
            URI uri = URI.create(url);
            HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(uri).header("Accept", "text/event-stream").GET();

            AwsSigV4Signer signer = AwsSigV4Signer.create(resolvedRegion);
            if (signer != null) signer.sign(builder, "GET", uri, null, "lambda");

            HttpResponse<java.io.InputStream> response = HttpClient.newHttpClient()
                .send(builder.build(), HttpResponse.BodyHandlers.ofInputStream());

            if (response.statusCode() >= 400) {
                System.err.println("Stream error: HTTP " + response.statusCode());
                System.exit(1);
            }

            long startTime = System.currentTimeMillis();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(response.body()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (!line.startsWith("data:")) continue;
                    String json = line.substring(5).trim();
                    JsonNode event = MAPPER.readTree(json);
                    String state = event.path("state").asText();
                    String phase = event.path("phase").asText();
                    int progress = event.path("progress").asInt();

                    if ("text".equals(output)) {
                        long elapsed = (System.currentTimeMillis() - startTime) / 1000;
                        System.out.printf("  [%3d%%] %s  (+%ds)%n", progress, phase, elapsed);
                    }

                    if ("ready".equals(state)) {
                        String publicIp = event.path("publicIp").asText(null);
                        String sshKey = event.path("sshPrivateKey").asText(null);
                        if ("text".equals(output)) {
                            System.out.println("\n✓ Cluster ready.");
                            if (publicIp != null) System.out.println("  Public IP: " + publicIp);
                        } else {
                            System.out.println(json);
                        }
                        if (sshKey != null) {
                            Path keyPath = config.tenantSshKeyPath(resolvedRegion, tenantId);
                            Files.createDirectories(keyPath.getParent());
                            Files.writeString(keyPath, sshKey);
                            try { Files.setPosixFilePermissions(keyPath, PosixFilePermissions.fromString("rw-------")); }
                            catch (UnsupportedOperationException ignored) {}
                            if ("text".equals(output)) System.out.println("  SSH key: " + keyPath);
                        }
                        return;
                    }
                    if ("failed".equals(state)) {
                        System.err.println("Provisioning failed: " + event.path("error").asText());
                        System.exit(1);
                    }
                }
            }
        } catch (Exception e) {
            System.err.println("Stream error: " + e.getMessage());
            System.exit(1);
        }
    }

    private String parseIssuer(String oidcConfigJson) {
        try {
            JsonNode node = MAPPER.readTree(oidcConfigJson);
            return node.get("issuer").asText();
        } catch (Exception e) {
            throw new IllegalArgumentException("Failed to parse OIDC issuer: " + e.getMessage());
        }
    }

    private String fetchUrl(String url) {
        try {
            HttpResponse<String> resp = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder().uri(URI.create(url)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() >= 400) throw new RuntimeException("HTTP " + resp.statusCode());
            return resp.body();
        } catch (Exception e) {
            throw new RuntimeException("Failed to fetch " + url + ": " + e.getMessage());
        }
    }
}
