package ai.codriverlabs.eksdx.cli;

import ai.codriverlabs.eksdx.cli.cluster.CreateClusterCommand;
import picocli.CommandLine.Command;

@Command(name = "register-cluster", hidden = true, description = "Deprecated: use create-cluster --oidc-mode self-managed")
public class RegisterClusterCmd extends CreateClusterCommand {}
