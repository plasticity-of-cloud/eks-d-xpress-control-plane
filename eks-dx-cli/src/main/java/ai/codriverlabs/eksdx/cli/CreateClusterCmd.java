package ai.codriverlabs.eksdx.cli;

import ai.codriverlabs.eksdx.cli.cluster.UnifiedCreateClusterCommand;
import picocli.CommandLine.Command;

@Command(name = "create-cluster", description = "Create a managed cluster or register a self-managed one")
public class CreateClusterCmd extends UnifiedCreateClusterCommand {}
