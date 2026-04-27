package cloud.plasticity.eksdx.cli;

import cloud.plasticity.eksdx.cli.cluster.ListClustersCommand;
import cloud.plasticity.eksdx.cli.association.ListAssociationsCommand;
import picocli.CommandLine.Command;

@Command(name = "list", subcommands = {
    ListClustersCommand.class,
    ListAssociationsCommand.class
})
public class ListCommand {}
