package ai.codriverlabs.eksdx.lambda.resource;

import ai.codriverlabs.eksdx.lambda.model.TokenClaims;
import ai.codriverlabs.eksdx.lambda.service.AwsCredentialService;
import ai.codriverlabs.eksdx.lambda.service.DynamoDbAssociationService;
import ai.codriverlabs.eksdx.lambda.service.JwksTokenValidationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import jakarta.ws.rs.core.Response;
import software.amazon.awssdk.services.sts.model.Credentials;

import java.time.Instant;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class EksAuthResourceTest {

    @Mock JwksTokenValidationService tokenValidationService;
    @Mock DynamoDbAssociationService associationService;
    @Mock AwsCredentialService credentialService;

    EksAuthResource resource;

    private static final TokenClaims VALID_CLAIMS = new TokenClaims(
        "default", "my-sa", "sa-uid", "my-pod", "pod-uid",
        "system:serviceaccount:default:my-sa");

    @BeforeEach
    void setUp() {
        resource = new EksAuthResource();
        resource.tokenValidationService = tokenValidationService;
        resource.associationService = associationService;
        resource.credentialService = credentialService;
    }

    @Test
    void assumeRole_returns200_withCredentials() {
        when(tokenValidationService.validateToken("valid-token", "cluster"))
            .thenReturn(VALID_CLAIMS);
        when(associationService.getRoleArn("cluster", "default", "my-sa"))
            .thenReturn("arn:aws:iam::123456789012:role/test-role");
        when(associationService.getAssociationId("cluster", "default", "my-sa"))
            .thenReturn("assoc-abc");
        when(credentialService.assumeRole(eq("arn:aws:iam::123456789012:role/test-role"),
            eq("default-my-sa"), eq("cluster"), any()))
            .thenReturn(Credentials.builder()
                .accessKeyId("AKIA")
                .secretAccessKey("secret")
                .sessionToken("token")
                .expiration(Instant.parse("2025-01-01T01:00:00Z"))
                .build());

        var req = new EksAuthResource.AgentRequest();
        req.token = "valid-token";

        try (Response resp = resource.assumeRoleForPodIdentity("cluster", req)) {
            assertEquals(200, resp.getStatus());
            var body = (EksAuthResource.AgentResponse) resp.getEntity();
            assertEquals("AKIA", body.credentials.accessKeyId);
            assertEquals("secret", body.credentials.secretAccessKey);
            assertEquals("token", body.credentials.sessionToken);
            assertEquals("default", body.subject.namespace);
            assertEquals("my-sa", body.subject.serviceAccount);
            assertEquals("pods.eks.amazonaws.com", body.audience);
            assertEquals("assoc-abc", body.podIdentityAssociation.associationId);
        }
    }

    @Test
    void assumeRole_returns400_whenTokenNull() {
        var req = new EksAuthResource.AgentRequest();
        req.token = null;

        try (Response resp = resource.assumeRoleForPodIdentity("cluster", req)) {
            assertEquals(400, resp.getStatus());
        }
    }

    @Test
    void assumeRole_returns400_whenTokenEmpty() {
        var req = new EksAuthResource.AgentRequest();
        req.token = "";

        try (Response resp = resource.assumeRoleForPodIdentity("cluster", req)) {
            assertEquals(400, resp.getStatus());
        }
    }

    @Test
    void assumeRole_returns400_whenRequestNull() {
        try (Response resp = resource.assumeRoleForPodIdentity("cluster", null)) {
            assertEquals(400, resp.getStatus());
        }
    }

    @Test
    void assumeRole_returns400_whenTokenInvalid() {
        when(tokenValidationService.validateToken("bad-token", "cluster"))
            .thenThrow(new IllegalArgumentException("Invalid token"));

        var req = new EksAuthResource.AgentRequest();
        req.token = "bad-token";

        try (Response resp = resource.assumeRoleForPodIdentity("cluster", req)) {
            assertEquals(400, resp.getStatus());
        }
    }

    @Test
    void assumeRole_returns403_whenSecurityException() {
        when(tokenValidationService.validateToken("forbidden-token", "cluster"))
            .thenThrow(new SecurityException("Access denied"));

        var req = new EksAuthResource.AgentRequest();
        req.token = "forbidden-token";

        try (Response resp = resource.assumeRoleForPodIdentity("cluster", req)) {
            assertEquals(403, resp.getStatus());
        }
    }

    @Test
    void assumeRole_returns404_whenNoAssociation() {
        when(tokenValidationService.validateToken("valid-token", "cluster"))
            .thenReturn(VALID_CLAIMS);
        when(associationService.getRoleArn("cluster", "default", "my-sa"))
            .thenReturn(null);

        var req = new EksAuthResource.AgentRequest();
        req.token = "valid-token";

        try (Response resp = resource.assumeRoleForPodIdentity("cluster", req)) {
            assertEquals(404, resp.getStatus());
        }
    }

    @Test
    void assumeRole_returns500_onUnexpectedError() {
        when(tokenValidationService.validateToken("valid-token", "cluster"))
            .thenReturn(VALID_CLAIMS);
        when(associationService.getRoleArn("cluster", "default", "my-sa"))
            .thenReturn("arn:aws:iam::123456789012:role/test-role");
        when(credentialService.assumeRole(any(), any(), any(), any()))
            .thenThrow(new RuntimeException("STS unavailable"));

        var req = new EksAuthResource.AgentRequest();
        req.token = "valid-token";

        try (Response resp = resource.assumeRoleForPodIdentity("cluster", req)) {
            assertEquals(500, resp.getStatus());
        }
    }

    @Test
    void assumeRole_passesSessionTagsFromClaims() {
        when(tokenValidationService.validateToken("valid-token", "cluster"))
            .thenReturn(VALID_CLAIMS);
        when(associationService.getRoleArn("cluster", "default", "my-sa"))
            .thenReturn("arn:aws:iam::123456789012:role/test-role");
        when(associationService.getAssociationId("cluster", "default", "my-sa"))
            .thenReturn("assoc-abc");
        when(credentialService.assumeRole(any(), any(), any(), any()))
            .thenReturn(Credentials.builder()
                .accessKeyId("AKIA").secretAccessKey("s").sessionToken("t")
                .expiration(Instant.now()).build());

        var req = new EksAuthResource.AgentRequest();
        req.token = "valid-token";
        resource.assumeRoleForPodIdentity("cluster", req);

        verify(credentialService).assumeRole(
            eq("arn:aws:iam::123456789012:role/test-role"),
            eq("default-my-sa"),
            eq("cluster"),
            eq(VALID_CLAIMS.sessionTags()));
    }
}
