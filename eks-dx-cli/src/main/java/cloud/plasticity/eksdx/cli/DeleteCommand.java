package cloud.plasticity.eksdx.cli;

import cloud.plasticity.eksdx.cli.cluster.DeleteClusterCommand;
import cloud.plasticity.eksdx.cli.association.DeleteAssociationCommand;
import picocli.CommandLine.Command;

@Command(name = "delete", subcommands = {
    DeleteClusterCommand.class,
    DeleteAssociationCommand.class
})
public class DeleteCommand {}
