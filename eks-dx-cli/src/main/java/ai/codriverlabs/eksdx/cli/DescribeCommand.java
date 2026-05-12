package ai.codriverlabs.eksdx.cli;

import ai.codriverlabs.eksdx.cli.cluster.DescribeClusterCommand;
import ai.codriverlabs.eksdx.cli.association.DescribeAssociationCommand;
import picocli.CommandLine.Command;

@Command(name = "describe", subcommands = {
    DescribeClusterCommand.class,
    DescribeAssociationCommand.class
})
public class DescribeCommand {}
