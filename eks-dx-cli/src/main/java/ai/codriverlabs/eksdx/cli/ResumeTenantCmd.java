package ai.codriverlabs.eksdx.cli;

import ai.codriverlabs.eksdx.cli.tenant.ResumeTenantCommand;
import picocli.CommandLine.Command;

@Command(name = "resume-tenant", description = "Resume a stopped tenant cluster")
public class ResumeTenantCmd extends ResumeTenantCommand {}
