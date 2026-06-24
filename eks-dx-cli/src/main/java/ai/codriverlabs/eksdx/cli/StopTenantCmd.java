package ai.codriverlabs.eksdx.cli;

import ai.codriverlabs.eksdx.cli.tenant.StopTenantCommand;
import picocli.CommandLine.Command;

@Command(name = "stop-tenant", description = "Stop a tenant cluster (EC2 stopped, EBS preserved)")
public class StopTenantCmd extends StopTenantCommand {}
