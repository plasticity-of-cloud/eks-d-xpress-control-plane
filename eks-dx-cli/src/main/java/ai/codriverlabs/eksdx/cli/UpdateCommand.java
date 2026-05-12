package ai.codriverlabs.eksdx.cli;

import ai.codriverlabs.eksdx.cli.cluster.UpdateClusterCommand;
import picocli.CommandLine.Command;

@Command(name = "update", subcommands = { UpdateClusterCommand.class })
public class UpdateCommand {}
