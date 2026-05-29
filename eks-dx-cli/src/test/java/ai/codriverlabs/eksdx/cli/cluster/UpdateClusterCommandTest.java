package ai.codriverlabs.eksdx.cli.cluster;

import ai.codriverlabs.eksdx.cli.util.EksDxApiClient;
import ai.codriverlabs.eksdx.cli.util.KubeApiClient;
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

    @Mock KubeApiClient kubeApiClient;
    @Mock EksDxApiClient apiClient;

    UpdateClusterCommand cmd() {
        UpdateClusterCommand cmd = new UpdateClusterCommand();
        cmd.kubeApiClient = kubeApiClient;
        cmd.apiClient = apiClient;
        cmd.name = "test-cluster";
        cmd.refreshJwks = true;
        return cmd;
    }

    @Test
    void run_refreshesJwks_whenFlagSet() {
        when(kubeApiClient.get("/openid/v1/jwks")).thenReturn("{\"keys\":[{\"kty\":\"RSA\"}]}");
        when(apiClient.put(any(), any())).thenReturn("{}");

        cmd().run();

        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        verify(apiClient).put(eq("/clusters/test-cluster/jwks"), body.capture());
        assertTrue(body.getValue().contains("keys"));
    }
}
