package ai.codriverlabs.eksdx.cli.tenant;

import ai.codriverlabs.eksdx.cli.util.EksDxApiClient;
import jakarta.inject.Inject;
import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;

@Command(name = "tenant", description = "Deprovision a tenant cluster")
public class DeleteTenantCommand implements Runnable {

    @Inject EksDxApiClient apiClient;

    @Parameters(index = "0", description = "Tenant ID") String tenantId;

    @Override
    public void run() {
        try {
            apiClient.delete("/tenants/" + tenantId);
            System.out.printf("✓ Tenant \"%s\" deprovisioning started%n", tenantId);
        } catch (Exception e) {
            System.err.printf("Failed to deprovision tenant: %s%n", e.getMessage());
            System.exit(1);
        }
    }
}
