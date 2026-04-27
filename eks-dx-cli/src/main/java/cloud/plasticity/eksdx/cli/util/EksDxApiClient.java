package cloud.plasticity.eksdx.cli.util;

import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

/**
 * JDK HttpClient wrapper for EKS-DX Lambda API calls.
 * No AWS SDK — all interactions go through the Lambda HTTP API.
 */
@ApplicationScoped
public class EksDxApiClient {

    private final HttpClient httpClient = HttpClient.newHttpClient();

    @ConfigProperty(name = "eks-dx.endpoint", defaultValue = "https://eks-dx.plasticity.cloud")
    String endpoint;

    public String post(String path, String body) {
        return send("POST", path, body);
    }

    public String get(String path) {
        return send("GET", path, null);
    }

    public String put(String path, String body) {
        return send("PUT", path, body);
    }

    public String delete(String path) {
        return send("DELETE", path, null);
    }

    private String send(String method, String path, String body) {
        try {
            var builder = HttpRequest.newBuilder()
                .uri(URI.create(endpoint + path))
                .header("Content-Type", "application/json");

            if (body != null) {
                builder.method(method, HttpRequest.BodyPublishers.ofString(body));
            } else {
                builder.method(method, HttpRequest.BodyPublishers.noBody());
            }

            var response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() >= 400) {
                System.err.printf("Error (%d): %s%n", response.statusCode(), response.body());
                System.exit(1);
            }

            return response.body();
        } catch (Exception e) {
            System.err.printf("Failed to reach EKS-DX service at %s: %s%n", endpoint, e.getMessage());
            System.exit(1);
            return null;
        }
    }
}
