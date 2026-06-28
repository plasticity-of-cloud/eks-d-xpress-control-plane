package ai.codriverlabs.eksdx.cli;

import ai.codriverlabs.eksdx.cli.cluster.DeleteClusterCommand;
import picocli.CommandLine.Command;

@Command(name = "deregister-cluster", hidden = true, description = "Deprecated: use delete-cluster")
public class DeregisterClusterCmd extends DeleteClusterCommand {}
