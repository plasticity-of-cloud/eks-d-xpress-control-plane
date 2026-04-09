package com.plcloud.eksauth;

import com.plcloud.eksauth.model.AssumeRoleForPodIdentityRequest;
import com.plcloud.eksauth.model.AssumeRoleForPodIdentityResponse;
import io.fabric8.kubernetes.api.model.ConfigMap;
import io.fabric8.kubernetes.api.model.ConfigMapBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.server.mock.EnableKubernetesMockClient;
import io.fabric8.kubernetes.client.server.mock.KubernetesMockServer;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import software.amazon.awssdk.services.sts.StsClient;
import software.amazon.awssdk.services.sts.model.AssumeRoleRequest;
import software.amazon.awssdk.services.sts.model.AssumeRoleResponse;
import software.amazon.awssdk.services.sts.model.Credentials;

import java.time.Instant;
import java.util.Base64;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@QuarkusTest
@EnableKubernetesMockClient(crud = true)
class EksAuthIntegrationTest {

    KubernetesMockServer mockServer;
    
    @org.junit.jupiter.api.io.TempDir
    static java.nio.file.Path tempDir;
    
    @ConfigProperty(name = "eks.pod-identity.configmap.name")
    String configMapName;
    
    @ConfigProperty(name = "eks.pod-identity.configmap.namespace")
    String configMapNamespace;

    @BeforeEach
    void setUp() {
        // Setup ConfigMap with pod identity associations
        ConfigMap configMap = new ConfigMapBuilder()
            .withNewMetadata()
                .withName(configMapName)
                .withNamespace(configMapNamespace)
            .endMetadata()
            .withData(Map.of(
                "test-cluster:default:my-sa", "arn:aws:iam::123456789012:role/test-role",
                "test-cluster:production:*", "arn:aws:iam::123456789012:role/production-role"
            ))
            .build();
        
        mockServer.expect()
            .get()
            .withPath("/api/v1/namespaces/" + configMapNamespace + "/configmaps/" + configMapName)
            .andReturn(200, configMap)
            .always();
    }

    @Test
    @DisplayName("Integration test - invalid audience rejected")
    void testInvalidAudienceRejected() {
        // Arrange - token with wrong audience
        String token = createTestToken("default", "my-sa", "sts.amazonaws.com");

        AssumeRoleForPodIdentityRequest request = new AssumeRoleForPodIdentityRequest();
        request.setClusterName("test-cluster");
        request.setToken(token);

        // Act & Assert
        given()
            .contentType(ContentType.JSON)
            .body(request)
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("error", equalTo("InvalidRequestException"))
            .body("message", containsString("Invalid token audience"));
    }

    @Test
    @DisplayName("Integration test - missing required claims")
    void testMissingRequiredClaims() {
        // Arrange - token without namespace claim
        String token = createTestToken(null, "my-sa", "pods.eks.amazonaws.com");

        AssumeRoleForPodIdentityRequest request = new AssumeRoleForPodIdentityRequest();
        request.setClusterName("test-cluster");
        request.setToken(token);

        // Act & Assert
        given()
            .contentType(ContentType.JSON)
            .body(request)
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("error", equalTo("InvalidRequestException"));
    }

    @Test
    @DisplayName("Integration test - health check endpoints")
    void testHealthCheckEndpoints() {
        given()
        .when()
            .get("/health/live")
        .then()
            .statusCode(200);

        given()
        .when()
            .get("/health/ready")
        .then()
            .statusCode(200);
    }

    /**
     * Creates a simplified test JWT token.
     * Note: This is a simplified token for testing purposes only.
     * In production, tokens are signed by Kubernetes API server.
     */
    private String createTestToken(String namespace, String serviceAccount, String audience) {
        // Create a minimal JWT for testing
        // Header
        String header = Base64.getUrlEncoder().withoutPadding()
            .encodeToString("{\"alg\":\"none\",\"typ\":\"JWT\"}".getBytes());
        
        // Payload with required claims
        StringBuilder payload = new StringBuilder("{");
        payload.append("\"exp\":").append(Instant.now().plusSeconds(3600).getEpochSecond()).append(",");
        payload.append("\"aud\":\"").append(audience).append("\",");
        if (namespace != null) {
            payload.append("\"kubernetes.io/serviceaccount/namespace\":\"").append(namespace).append("\",");
        }
        if (serviceAccount != null) {
            payload.append("\"kubernetes.io/serviceaccount/service-account.name\":\"").append(serviceAccount).append("\",");
        }
        payload.append("\"sub\":\"system:serviceaccount:").append(namespace != null ? namespace : "").append(":").append(serviceAccount != null ? serviceAccount : "").append("\"");
        payload.append("}");
        
        String payloadEncoded = Base64.getUrlEncoder().withoutPadding()
            .encodeToString(payload.toString().getBytes());
        
        // Signature (empty for testing)
        String signature = "";
        
        return header + "." + payloadEncoded + "." + signature;
    }
}
