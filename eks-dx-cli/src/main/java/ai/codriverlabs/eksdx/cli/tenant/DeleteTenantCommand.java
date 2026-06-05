package ai.codriverlabs.eksdx.cli.tenant;

import ai.codriverlabs.eksdx.cli.config.EksDxConfig;
import ai.codriverlabs.eksdx.cli.util.EksDxApiClient;
import jakarta.inject.Inject;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

@Command(name = "tenant", description = "Deprovision a tenant cluster")
public class DeleteTenantCommand implements Runnable {

    @Inject EksDxApiClient apiClient;

    @Parameters(index = "0", description = "Tenant ID") String tenantId;

    @Option(names = "--wait", description = "Wait until deprovisioning completes", defaultValue = "true")
    boolean wait;

    @Override
    public void run() {
        try {
            EksDxConfig config = new EksDxConfig();
            String tenantApiUrl = config.getTenantApiUrl();
            if (tenantApiUrl == null) {
                System.err.println("Error: tenant API URL not configured. Set EKS_DX_TENANT_API_URL or run 'eks-dx configure'.");
                System.exit(1);
            }
            // DELETE is synchronous in the Lambda but API Gateway times out at 29s and returns 503.
            // The Lambda keeps running and completes the deletion. Treat 503/timeout as "accepted".
            int deleteStatus = apiClient.deleteStatusOnUrl(tenantApiUrl, "/tenants/" + tenantId);
            if (deleteStatus != 204 && deleteStatus != 202 && deleteStatus != 503 && deleteStatus != 404) {
                System.err.printf("Unexpected status %d deleting tenant%n", deleteStatus);
                System.exit(1);
            }
            if (deleteStatus == 404) {
                System.out.printf("✓ Tenant \"%s\" not found (already deleted)%n", tenantId);
                return;
            }
            System.out.printf("Deprovisioning tenant \"%s\"...%n", tenantId);

            if (!wait) return;

            // Poll GET /tenants/{id} until 404 (gone) or timeout (5 min)
            long deadline = System.currentTimeMillis() + 300_000;
            while (System.currentTimeMillis() < deadline) {
                Thread.sleep(5_000);
                int status = apiClient.getStatusOnUrl(tenantApiUrl, "/tenants/" + tenantId);
                if (status == 404) {
                    System.out.printf("✓ Tenant \"%s\" deprovisioned%n", tenantId);
                    return;
                }
            }
            System.err.println("Timeout waiting for deprovisioning to complete.");
            System.exit(1);
        } catch (Exception e) {
            System.err.printf("Failed to deprovision tenant: %s%n", e.getMessage());
            System.exit(1);
        }
    }
}
