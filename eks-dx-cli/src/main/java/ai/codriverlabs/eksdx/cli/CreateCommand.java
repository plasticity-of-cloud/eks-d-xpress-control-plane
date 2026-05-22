package ai.codriverlabs.eksdx.cli;

import ai.codriverlabs.eksdx.cli.cluster.CreateClusterCommand;
import ai.codriverlabs.eksdx.cli.association.CreateAssociationCommand;
import ai.codriverlabs.eksdx.cli.tenant.CreateTenantCommand;
import picocli.CommandLine.Command;

@Command(name = "create", subcommands = {
    CreateClusterCommand.class,
    CreateAssociationCommand.class,
    CreateTenantCommand.class
})
public class CreateCommand {}
