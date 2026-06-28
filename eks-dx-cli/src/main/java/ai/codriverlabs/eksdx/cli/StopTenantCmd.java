package ai.codriverlabs.eksdx.cli;

import ai.codriverlabs.eksdx.cli.tenant.StopTenantCommand;
import picocli.CommandLine.Command;

@Command(name = "stop-tenant", hidden = true, description = "Deprecated: use stop-cluster")
public class StopTenantCmd extends StopTenantCommand {}
