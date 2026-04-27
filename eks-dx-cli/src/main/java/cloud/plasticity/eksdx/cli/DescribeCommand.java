package cloud.plasticity.eksdx.cli;

import cloud.plasticity.eksdx.cli.cluster.DescribeClusterCommand;
import cloud.plasticity.eksdx.cli.association.DescribeAssociationCommand;
import picocli.CommandLine.Command;

@Command(name = "describe", subcommands = {
    DescribeClusterCommand.class,
    DescribeAssociationCommand.class
})
public class DescribeCommand {}
