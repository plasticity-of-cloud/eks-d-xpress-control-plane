package cloud.plasticity.eksdx.cli;

import cloud.plasticity.eksdx.cli.cluster.CreateClusterCommand;
import cloud.plasticity.eksdx.cli.association.CreateAssociationCommand;
import picocli.CommandLine.Command;

@Command(name = "create", subcommands = {
    CreateClusterCommand.class,
    CreateAssociationCommand.class
})
public class CreateCommand {}
