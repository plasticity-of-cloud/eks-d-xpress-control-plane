package ai.codriverlabs.eksdx.cli;

import ai.codriverlabs.eksdx.cli.tenant.CreateTenantCommand;
import picocli.CommandLine.Command;

@Command(name = "create-tenant", hidden = true, description = "Deprecated: use create-cluster")
public class CreateTenantCmd extends CreateTenantCommand {}
