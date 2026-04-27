package cloud.plasticity.eksdx.cli.association;

import cloud.plasticity.eksdx.cli.util.EksDxApiClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class DeleteAssociationCommandTest {

    @Mock EksDxApiClient apiClient;

    DeleteAssociationCommand cmd;

    @BeforeEach
    void setUp() {
        cmd = new DeleteAssociationCommand();
        cmd.apiClient = apiClient;
        cmd.clusterName = "test-cluster";
        cmd.associationId = "assoc-abc";
    }

    @Test
    void run_callsCorrectEndpoint() {
        when(apiClient.delete("/clusters/test-cluster/pod-identity-associations/assoc-abc"))
            .thenReturn("");

        cmd.run();

        verify(apiClient).delete("/clusters/test-cluster/pod-identity-associations/assoc-abc");
    }
}
