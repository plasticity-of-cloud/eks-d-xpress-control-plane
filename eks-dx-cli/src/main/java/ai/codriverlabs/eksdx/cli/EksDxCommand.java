package ai.codriverlabs.eksdx.cli;

import ai.codriverlabs.eksdx.cli.config.ConfigureCommand;

import io.quarkus.picocli.runtime.annotations.TopCommand;
import picocli.CommandLine.Command;

/**
 * Top-level CLI command. Core subcommands are listed statically below.
 *
 * <p>Additional commands can be contributed by implementing
 * {@link ai.codriverlabs.eksdx.cli.spi.CliExtensionProvider} — they are
 * discovered via CDI and registered dynamically at startup by
 * {@link ai.codriverlabs.eksdx.cli.spi.CliExtensionRegistrar}.
 */
@TopCommand
@Command(name = "eks-dx", mixinStandardHelpOptions = true,
    description = "EKS-DX — Pod Identity for k3s, microk8s, and EKS-D clusters",
    subcommands = {
        ConfigureCommand.class,
        CreateClusterCmd.class,
        DeleteClusterCmd.class,
        StopClusterCmd.class,
        ResumeClusterCmd.class,
        DescribeClusterCmd.class,
        ListClustersCmd.class,
        UpdateClusterCmd.class,
        CreateAssociationCmd.class,
        DeleteAssociationCmd.class,
        DescribeAssociationCmd.class,
        ListAssociationsCmd.class,
        GetClusterAccessCmd.class,
    })
public class EksDxCommand {}
