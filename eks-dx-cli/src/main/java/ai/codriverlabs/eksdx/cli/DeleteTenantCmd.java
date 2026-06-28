package ai.codriverlabs.eksdx.cli;

import ai.codriverlabs.eksdx.cli.tenant.DeleteTenantCommand;
import picocli.CommandLine.Command;

@Command(name = "delete-tenant", hidden = true, description = "Deprecated: use delete-cluster")
public class DeleteTenantCmd extends DeleteTenantCommand {}
