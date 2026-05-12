package ai.codriverlabs.eksdx.cli;

import ai.codriverlabs.eksdx.cli.cluster.CreateClusterCommand;
import ai.codriverlabs.eksdx.cli.association.CreateAssociationCommand;
import picocli.CommandLine.Command;

@Command(name = "create", subcommands = {
    CreateClusterCommand.class,
    CreateAssociationCommand.class
})
public class CreateCommand {}
