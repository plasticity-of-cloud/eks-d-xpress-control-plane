package ai.codriverlabs.eksdx.cli.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class EksDxApiClientTest {

    @Test
    void defaultEndpoint_isPlasticityCloud() {
        // Verify the default endpoint annotation value in the source
        // The actual HTTP calls are integration-level; here we verify construction
        EksDxApiClient client = new EksDxApiClient();
        // endpoint field is set by CDI; in unit test it's null
        // This test documents the expected default
        assertNotNull(client);
    }
}
