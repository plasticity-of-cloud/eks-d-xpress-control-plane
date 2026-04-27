package cloud.plasticity.eksdx.cli;

import cloud.plasticity.eksdx.cli.cluster.*;
import cloud.plasticity.eksdx.cli.association.*;
import picocli.CommandLine.Command;

@Command(name = "eks-dx", mixinStandardHelpOptions = true,
    description = "EKS-DX — Pod Identity for k3s, microk8s, and EKS-D clusters",
    subcommands = {
        CreateCommand.class,
        DescribeCommand.class,
        ListCommand.class,
        UpdateCommand.class,
        DeleteCommand.class
    })
public class EksDxCommand {}
