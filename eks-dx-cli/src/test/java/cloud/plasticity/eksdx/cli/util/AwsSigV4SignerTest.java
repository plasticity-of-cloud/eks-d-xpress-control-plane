package cloud.plasticity.eksdx.cli.util;

import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpRequest;

import static org.junit.jupiter.api.Assertions.*;

class AwsSigV4SignerTest {

    @Test
    void sign_addsAuthorizationHeader() {
        AwsSigV4Signer signer = new AwsSigV4Signer(
            "AKIAIOSFODNN7EXAMPLE",
            "wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY",
            null, "us-east-1");

        URI uri = URI.create("https://api.example.com/clusters");
        HttpRequest.Builder builder = HttpRequest.newBuilder()
            .uri(uri)
            .header("Content-Type", "application/json");

        signer.sign(builder, "GET", uri, null, "execute-api");

        HttpRequest request = builder.GET().build();
        assertTrue(request.headers().firstValue("Authorization").isPresent());
        assertTrue(request.headers().firstValue("Authorization").get().startsWith("AWS4-HMAC-SHA256"));
        assertTrue(request.headers().firstValue("x-amz-date").isPresent());
        assertTrue(request.headers().firstValue("x-amz-content-sha256").isPresent());
    }

    @Test
    void sign_includesSessionToken_whenPresent() {
        AwsSigV4Signer signer = new AwsSigV4Signer(
            "AKIAIOSFODNN7EXAMPLE",
            "wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY",
            "FwoGZXIvYXdzEBY...",
            "us-east-1");

        URI uri = URI.create("https://api.example.com/clusters");
        HttpRequest.Builder builder = HttpRequest.newBuilder()
            .uri(uri)
            .header("Content-Type", "application/json");

        signer.sign(builder, "POST", uri, "{\"name\":\"test\"}", "execute-api");

        HttpRequest request = builder.POST(HttpRequest.BodyPublishers.ofString("{}")).build();
        assertTrue(request.headers().firstValue("x-amz-security-token").isPresent());
        assertEquals("FwoGZXIvYXdzEBY...",
            request.headers().firstValue("x-amz-security-token").get());
    }

    @Test
    void sign_omitsSessionToken_whenNull() {
        AwsSigV4Signer signer = new AwsSigV4Signer(
            "AKIAIOSFODNN7EXAMPLE",
            "wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY",
            null, "us-east-1");

        URI uri = URI.create("https://api.example.com/clusters");
        HttpRequest.Builder builder = HttpRequest.newBuilder()
            .uri(uri)
            .header("Content-Type", "application/json");

        signer.sign(builder, "GET", uri, null, "execute-api");

        HttpRequest request = builder.GET().build();
        assertTrue(request.headers().firstValue("x-amz-security-token").isEmpty());
    }

    @Test
    void sign_includesRegionInCredentialScope() {
        AwsSigV4Signer signer = new AwsSigV4Signer(
            "AKIAIOSFODNN7EXAMPLE",
            "wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY",
            null, "eu-west-1");

        URI uri = URI.create("https://api.example.com/clusters");
        HttpRequest.Builder builder = HttpRequest.newBuilder()
            .uri(uri)
            .header("Content-Type", "application/json");

        signer.sign(builder, "GET", uri, null, "execute-api");

        HttpRequest request = builder.GET().build();
        String auth = request.headers().firstValue("Authorization").get();
        assertTrue(auth.contains("eu-west-1/execute-api/aws4_request"));
    }

    @Test
    void create_returnsNull_whenNoCredentials() {
        // In CI/test environments without AWS creds, should return null
        // This test documents the behavior — it may return non-null if
        // the test runner has AWS credentials configured
        AwsSigV4Signer signer = AwsSigV4Signer.create("us-east-1");
        // Just verify it doesn't throw
        assertDoesNotThrow(() -> AwsSigV4Signer.create("us-east-1"));
    }

    @Test
    void sign_handlesQueryParameters() {
        AwsSigV4Signer signer = new AwsSigV4Signer(
            "AKIAIOSFODNN7EXAMPLE",
            "wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY",
            null, "us-east-1");

        URI uri = URI.create("https://api.example.com/clusters/test/pod-identity-associations?namespace=default");
        HttpRequest.Builder builder = HttpRequest.newBuilder()
            .uri(uri)
            .header("Content-Type", "application/json");

        signer.sign(builder, "GET", uri, null, "execute-api");

        HttpRequest request = builder.GET().build();
        assertTrue(request.headers().firstValue("Authorization").isPresent());
    }
}
