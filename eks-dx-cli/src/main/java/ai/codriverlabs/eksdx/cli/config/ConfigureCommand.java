package ai.codriverlabs.eksdx.cli.config;

import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

@Command(name = "configure", description = "Configure EKS-DX CLI endpoint and region")
public class ConfigureCommand implements Runnable {

    @Option(names = "--endpoint", description = "EKS-DX API endpoint URL")
    String endpoint;

    @Option(names = "--region", description = "AWS region")
    String region;

    @Override
    public void run() {
        if (endpoint == null && region == null) {
            // Show current config
            EksDxConfig config = new EksDxConfig();
            System.out.printf("Endpoint: %s%n", config.getEndpoint());
            System.out.printf("Region:   %s%n", config.getRegion());
            System.out.printf("Config:   %s%n", EksDxConfig.configFile());
            return;
        }

        try {
            new EksDxConfig().save(endpoint, region);
            System.out.println("✓ Configuration saved to " + EksDxConfig.configFile());
            if (endpoint != null) System.out.printf("  Endpoint: %s%n", endpoint);
            if (region != null) System.out.printf("  Region:   %s%n", region);
        } catch (Exception e) {
            System.err.printf("Failed to save configuration: %s%n", e.getMessage());
            System.exit(1);
        }
    }
}
