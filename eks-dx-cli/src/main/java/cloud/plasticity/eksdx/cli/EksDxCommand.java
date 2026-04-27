package cloud.plasticity.eksdx.cli;

import cloud.plasticity.eksdx.cli.cluster.*;
import cloud.plasticity.eksdx.cli.association.*;
import cloud.plasticity.eksdx.cli.config.ConfigureCommand;
import io.quarkus.picocli.runtime.annotations.TopCommand;
import picocli.CommandLine.Command;

@TopCommand
@Command(name = "eks-dx", mixinStandardHelpOptions = true,
    description = "EKS-DX — Pod Identity for k3s, microk8s, and EKS-D clusters",
    subcommands = {
        ConfigureCommand.class,
        CreateCommand.class,
        DescribeCommand.class,
        ListCommand.class,
        UpdateCommand.class,
        DeleteCommand.class
    })
public class EksDxCommand {}
