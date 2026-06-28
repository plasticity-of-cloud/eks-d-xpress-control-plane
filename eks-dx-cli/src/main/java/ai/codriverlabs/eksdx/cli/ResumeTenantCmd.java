package ai.codriverlabs.eksdx.cli;

import ai.codriverlabs.eksdx.cli.tenant.ResumeTenantCommand;
import picocli.CommandLine.Command;

@Command(name = "resume-tenant", hidden = true, description = "Deprecated: use resume-cluster")
public class ResumeTenantCmd extends ResumeTenantCommand {}
