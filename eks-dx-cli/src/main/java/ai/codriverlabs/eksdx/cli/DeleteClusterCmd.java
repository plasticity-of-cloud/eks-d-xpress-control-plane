package ai.codriverlabs.eksdx.cli;

import ai.codriverlabs.eksdx.cli.cluster.UnifiedDeleteClusterCommand;
import picocli.CommandLine.Command;

@Command(name = "delete-cluster", description = "Delete a cluster (managed: full teardown; self-managed: deregister)")
public class DeleteClusterCmd extends UnifiedDeleteClusterCommand {}
