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
class UpdateClusterCommandTest {

    @Mock KubernetesClient kubernetesClient;
    @Mock EksDxApiClient apiClient;

    UpdateClusterCommand cmd;

    @BeforeEach
    void setUp() {
        cmd = new UpdateClusterCommand();
        cmd.kubernetesClient = kubernetesClient;
        cmd.apiClient = apiClient;
        cmd.name = "test-cluster";
    }

    @Test
    void run_refreshesJwks_whenFlagSet() {
        cmd.refreshJwks = true;
        when(kubernetesClient.raw("/openid/v1/jwks"))
            .thenReturn("{\"keys\":[{\"kty\":\"RSA\"}]}");
        when(apiClient.put(any(), any())).thenReturn("{}");

        cmd.run();

        verify(kubernetesClient).raw("/openid/v1/jwks");
        ArgumentCaptor<String> bodyCaptor = ArgumentCaptor.forClass(String.class);
        verify(apiClient).put(eq("/clusters/test-cluster/jwks"), bodyCaptor.capture());
        assertTrue(bodyCaptor.getValue().contains("keys"));
    }

    @Test
    void run_callsCorrectEndpoint() {
        cmd.refreshJwks = true;
        when(kubernetesClient.raw("/openid/v1/jwks")).thenReturn("{\"keys\":[]}");
        when(apiClient.put(any(), any())).thenReturn("{}");

        cmd.run();

        verify(apiClient).put(eq("/clusters/test-cluster/jwks"), any());
    }
}
