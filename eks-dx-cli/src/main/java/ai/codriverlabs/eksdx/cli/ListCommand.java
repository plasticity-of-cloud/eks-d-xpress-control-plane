package ai.codriverlabs.eksdx.cli;

import ai.codriverlabs.eksdx.cli.cluster.ListClustersCommand;
import ai.codriverlabs.eksdx.cli.association.ListAssociationsCommand;
import picocli.CommandLine.Command;

@Command(name = "list", subcommands = {
    ListClustersCommand.class,
    ListAssociationsCommand.class
})
public class ListCommand {}
