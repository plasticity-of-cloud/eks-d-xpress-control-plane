package ai.codriverlabs.eksdx.cli;

import ai.codriverlabs.eksdx.cli.tenant.CreateTenantCommand;
import picocli.CommandLine.Command;

@Command(name = "create-tenant", description = "Provision a new EKS-D-Xpress tenant cluster")
public class CreateTenantCmd extends CreateTenantCommand {}
