package cloud.plasticity.eksdx.cli.cluster;

import cloud.plasticity.eksdx.cli.util.EksDxApiClient;
import io.fabric8.kubernetes.client.KubernetesClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CreateClusterCommandTest {

    @Mock KubernetesClient kubernetesClient;
    @Mock EksDxApiClient apiClient;

    CreateClusterCommand cmd;

    @BeforeEach
    void setUp() {
        cmd = new CreateClusterCommand();
        cmd.kubernetesClient = kubernetesClient;
        cmd.apiClient = apiClient;
        cmd.name = "test-cluster";
        cmd.region = "us-east-1";
    }

    @Test
    void run_callsApiWithCorrectBody() {
        when(kubernetesClient.raw("/openid/v1/jwks"))
            .thenReturn("{\"keys\":[{\"kty\":\"RSA\"}]}");
        when(kubernetesClient.raw("/.well-known/openid-configuration"))
            .thenReturn("{\"issuer\":\"https://oidc.example.com\"}");
        when(apiClient.post(eq("/clusters"), any())).thenReturn("{}");

        cmd.run();

        ArgumentCaptor<String> bodyCaptor = ArgumentCaptor.forClass(String.class);
        verify(apiClient).post(eq("/clusters"), bodyCaptor.capture());
        String body = bodyCaptor.getValue();
        assertTrue(body.contains("\"name\":\"test-cluster\""));
        assertTrue(body.contains("\"issuer\":\"https://oidc.example.com\""));
        assertTrue(body.contains("\"jwks\":\"{\\\"keys\\\":[{\\\"kty\\\":\\\"RSA\\\"}]}\""));
    }

    @Test
    void run_readsJwksFromKubeApiserver() {
        when(kubernetesClient.raw("/openid/v1/jwks")).thenReturn("{\"keys\":[]}");
        when(kubernetesClient.raw("/.well-known/openid-configuration"))
            .thenReturn("{\"issuer\":\"https://oidc.example.com\"}");
        when(apiClient.post(any(), any())).thenReturn("{}");

        cmd.run();

        verify(kubernetesClient).raw("/openid/v1/jwks");
        verify(kubernetesClient).raw("/.well-known/openid-configuration");
    }

    // --- parseIssuer ---

    @Test
    void parseIssuer_extractsIssuer() {
        String result = cmd.parseIssuer("{\"issuer\":\"https://oidc.example.com\",\"jwks_uri\":\"...\"}");
        assertEquals("https://oidc.example.com", result);
    }

    @Test
    void parseIssuer_throws_whenNoIssuerField() {
        assertThrows(IllegalArgumentException.class,
            () -> cmd.parseIssuer("{\"jwks_uri\":\"...\"}"));
    }

    @Test
    void parseIssuer_throws_whenIssuerBlank() {
        assertThrows(IllegalArgumentException.class,
            () -> cmd.parseIssuer("{\"issuer\":\"\"}"));
    }

    @Test
    void parseIssuer_throws_whenInvalidJson() {
        assertThrows(IllegalArgumentException.class,
            () -> cmd.parseIssuer("not-json"));
    }

    // --- countKeys ---

    @Test
    void countKeys_returnsCorrectCount() {
        assertEquals(2, cmd.countKeys("{\"keys\":[{\"kty\":\"RSA\"},{\"kty\":\"EC\"}]}"));
    }

    @Test
    void countKeys_returnsZero_whenNoKeys() {
        assertEquals(0, cmd.countKeys("{\"keys\":[]}"));
    }

    @Test
    void countKeys_returnsZero_whenInvalidJson() {
        assertEquals(0, cmd.countKeys("not-json"));
    }

    @Test
    void countKeys_returnsZero_whenNoKeysField() {
        assertEquals(0, cmd.countKeys("{\"other\":\"value\"}"));
    }
}
