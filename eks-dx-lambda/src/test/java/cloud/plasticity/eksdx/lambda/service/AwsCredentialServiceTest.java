package cloud.plasticity.eksdx.lambda.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import software.amazon.awssdk.services.sts.StsClient;
import software.amazon.awssdk.services.sts.model.*;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AwsCredentialServiceTest {

    @Mock
    StsClient stsClient;

    AwsCredentialService service;

    @BeforeEach
    void setUp() {
        service = new AwsCredentialService();
        service.stsClient = stsClient;
        service.sessionDuration = Duration.ofHours(1);
    }

    @Test
    void assumeRole_returnsCredentials() {
        Instant expiration = Instant.now().plusSeconds(3600);
        when(stsClient.assumeRole(any(AssumeRoleRequest.class)))
            .thenReturn(AssumeRoleResponse.builder()
                .credentials(Credentials.builder()
                    .accessKeyId("AKIAIOSFODNN7EXAMPLE")
                    .secretAccessKey("wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY")
                    .sessionToken("FwoGZXIvYXdzEBY...")
                    .expiration(expiration)
                    .build())
                .build());

        Credentials creds = service.assumeRole(
            "arn:aws:iam::123456789012:role/test-role",
            "default-my-sa",
            "test-cluster",
            Map.of("kubernetes-namespace", "default", "kubernetes-service-account", "my-sa"));

        assertEquals("AKIAIOSFODNN7EXAMPLE", creds.accessKeyId());
        assertEquals("wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY", creds.secretAccessKey());
        assertEquals("FwoGZXIvYXdzEBY...", creds.sessionToken());
        assertEquals(expiration, creds.expiration());
    }

    @Test
    void assumeRole_sendsCorrectRoleArn() {
        mockStsResponse();

        service.assumeRole("arn:aws:iam::123456789012:role/my-role",
            "session", "cluster", Map.of());

        ArgumentCaptor<AssumeRoleRequest> captor = ArgumentCaptor.forClass(AssumeRoleRequest.class);
        verify(stsClient).assumeRole(captor.capture());
        assertEquals("arn:aws:iam::123456789012:role/my-role", captor.getValue().roleArn());
    }

    @Test
    void assumeRole_sendsCorrectSessionName() {
        mockStsResponse();

        service.assumeRole("arn:aws:iam::123456789012:role/r",
            "default-my-sa", "cluster", Map.of());

        ArgumentCaptor<AssumeRoleRequest> captor = ArgumentCaptor.forClass(AssumeRoleRequest.class);
        verify(stsClient).assumeRole(captor.capture());
        assertEquals("default-my-sa", captor.getValue().roleSessionName());
    }

    @Test
    void assumeRole_sendsCorrectDuration() {
        mockStsResponse();

        service.assumeRole("arn:aws:iam::123456789012:role/r",
            "session", "cluster", Map.of());

        ArgumentCaptor<AssumeRoleRequest> captor = ArgumentCaptor.forClass(AssumeRoleRequest.class);
        verify(stsClient).assumeRole(captor.capture());
        assertEquals(3600, captor.getValue().durationSeconds());
    }

    @Test
    void assumeRole_includesClusterNameTag() {
        mockStsResponse();

        service.assumeRole("arn:aws:iam::123456789012:role/r",
            "session", "my-cluster", Map.of());

        ArgumentCaptor<AssumeRoleRequest> captor = ArgumentCaptor.forClass(AssumeRoleRequest.class);
        verify(stsClient).assumeRole(captor.capture());
        assertTrue(captor.getValue().tags().stream()
            .anyMatch(t -> "eks-cluster-name".equals(t.key()) && "my-cluster".equals(t.value())));
    }

    @Test
    void assumeRole_includesSessionTags() {
        mockStsResponse();

        service.assumeRole("arn:aws:iam::123456789012:role/r",
            "session", "cluster",
            Map.of("kubernetes-namespace", "default", "kubernetes-service-account", "my-sa"));

        ArgumentCaptor<AssumeRoleRequest> captor = ArgumentCaptor.forClass(AssumeRoleRequest.class);
        verify(stsClient).assumeRole(captor.capture());
        var tags = captor.getValue().tags();
        assertTrue(tags.stream().anyMatch(t -> "kubernetes-namespace".equals(t.key()) && "default".equals(t.value())));
        assertTrue(tags.stream().anyMatch(t -> "kubernetes-service-account".equals(t.key()) && "my-sa".equals(t.value())));
    }

    @Test
    void assumeRole_skipsEmptyTagValues() {
        mockStsResponse();

        service.assumeRole("arn:aws:iam::123456789012:role/r",
            "session", "cluster",
            Map.of("kubernetes-pod-name", "", "kubernetes-namespace", "default"));

        ArgumentCaptor<AssumeRoleRequest> captor = ArgumentCaptor.forClass(AssumeRoleRequest.class);
        verify(stsClient).assumeRole(captor.capture());
        var tags = captor.getValue().tags();
        assertFalse(tags.stream().anyMatch(t -> "kubernetes-pod-name".equals(t.key())));
        assertTrue(tags.stream().anyMatch(t -> "kubernetes-namespace".equals(t.key())));
    }

    @Test
    void assumeRole_propagatesStsException() {
        when(stsClient.assumeRole(any(AssumeRoleRequest.class)))
            .thenThrow(StsException.builder().message("Access denied").build());

        assertThrows(StsException.class,
            () -> service.assumeRole("arn:aws:iam::123456789012:role/r",
                "session", "cluster", Map.of()));
    }

    @Test
    void assumeRole_respectsCustomDuration() {
        service.sessionDuration = Duration.ofMinutes(30);
        mockStsResponse();

        service.assumeRole("arn:aws:iam::123456789012:role/r",
            "session", "cluster", Map.of());

        ArgumentCaptor<AssumeRoleRequest> captor = ArgumentCaptor.forClass(AssumeRoleRequest.class);
        verify(stsClient).assumeRole(captor.capture());
        assertEquals(1800, captor.getValue().durationSeconds());
    }

    private void mockStsResponse() {
        when(stsClient.assumeRole(any(AssumeRoleRequest.class)))
            .thenReturn(AssumeRoleResponse.builder()
                .credentials(Credentials.builder()
                    .accessKeyId("AKIA")
                    .secretAccessKey("secret")
                    .sessionToken("token")
                    .expiration(Instant.now().plusSeconds(3600))
                    .build())
                .build());
    }
}
