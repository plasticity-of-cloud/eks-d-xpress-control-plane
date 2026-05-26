package ai.codriverlabs.eksdx.cli.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class EksDxConfigTest {

    @Test
    void getEndpoint_returnsDefault_whenNoConfig() {
        EksDxConfig config = new EksDxConfig();
        // Without env var or config file, returns default
        assertNotNull(config.getEndpoint());
    }

    @Test
    void getRegion_returnsDefault_whenNoConfig() {
        EksDxConfig config = new EksDxConfig();
        assertNotNull(config.getRegion());
    }

    @Test
    void configFile_returnsPathInHomeDir() {
        Path path = EksDxConfig.configFile();
        assertTrue(path.toString().contains(".eks-d-xpress"));
        assertTrue(path.toString().endsWith("config"));
    }
}
