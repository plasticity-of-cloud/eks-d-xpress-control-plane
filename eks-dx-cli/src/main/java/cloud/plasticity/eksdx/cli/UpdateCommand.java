package cloud.plasticity.eksdx.cli;

import cloud.plasticity.eksdx.cli.cluster.UpdateClusterCommand;
import picocli.CommandLine.Command;

@Command(name = "update", subcommands = { UpdateClusterCommand.class })
public class UpdateCommand {}
