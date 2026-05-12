package ai.codriverlabs.eksdx.cli;

import ai.codriverlabs.eksdx.cli.cluster.DeleteClusterCommand;
import ai.codriverlabs.eksdx.cli.association.DeleteAssociationCommand;
import picocli.CommandLine.Command;

@Command(name = "delete", subcommands = {
    DeleteClusterCommand.class,
    DeleteAssociationCommand.class
})
public class DeleteCommand {}
