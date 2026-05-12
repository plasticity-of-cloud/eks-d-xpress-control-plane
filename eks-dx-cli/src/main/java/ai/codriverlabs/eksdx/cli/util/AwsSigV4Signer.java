package ai.codriverlabs.eksdx.cli.util;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URI;
import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.HexFormat;

/**
 * Lightweight AWS SigV4 signer using JDK crypto. No AWS SDK dependency.
 * Signs requests for API Gateway IAM authorization.
 */
public class AwsSigV4Signer {

    private static final DateTimeFormatter ISO_DATE = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC);
    private static final DateTimeFormatter DATE_ONLY = DateTimeFormatter.ofPattern("yyyyMMdd").withZone(ZoneOffset.UTC);
    private static final String ALGORITHM = "AWS4-HMAC-SHA256";

    private final String accessKey;
    private final String secretKey;
    private final String sessionToken;
    private final String region;

    AwsSigV4Signer(String accessKey, String secretKey, String sessionToken, String region) {
        this.accessKey = accessKey;
        this.secretKey = secretKey;
        this.sessionToken = sessionToken;
        this.region = region;
    }

    /**
     * Create a signer from the standard AWS credential chain (env vars → ~/.aws/credentials).
     * Returns null if no credentials are found (requests will be unsigned).
     */
    public static AwsSigV4Signer create(String region) {
        String ak = System.getenv("AWS_ACCESS_KEY_ID");
        String sk = System.getenv("AWS_SECRET_ACCESS_KEY");
        String st = System.getenv("AWS_SESSION_TOKEN");

        if (ak == null || sk == null) {
            // Try ~/.aws/credentials default profile
            try {
                var creds = java.nio.file.Files.readString(
                    java.nio.file.Path.of(System.getProperty("user.home"), ".aws", "credentials"));
                for (String line : creds.split("\n")) {
                    line = line.trim();
                    if (line.startsWith("aws_access_key_id")) ak = line.split("=", 2)[1].trim();
                    if (line.startsWith("aws_secret_access_key")) sk = line.split("=", 2)[1].trim();
                    if (line.startsWith("aws_session_token")) st = line.split("=", 2)[1].trim();
                }
            } catch (Exception e) {
                // No credentials file
            }
        }

        if (ak == null || sk == null) return null;
        return new AwsSigV4Signer(ak, sk, st, region);
    }

    public void sign(HttpRequest.Builder builder, String method, URI uri,
                     String body, String service) {
        Instant now = Instant.now();
        String amzDate = ISO_DATE.format(now);
        String dateStamp = DATE_ONLY.format(now);
        String host = uri.getHost();
        String path = uri.getRawPath().isEmpty() ? "/" : uri.getRawPath();
        String query = uri.getRawQuery() != null ? uri.getRawQuery() : "";
        String payloadHash = sha256Hex(body != null ? body : "");

        // Canonical request
        String signedHeaders = sessionToken != null
            ? "content-type;host;x-amz-date;x-amz-security-token"
            : "content-type;host;x-amz-date";

        StringBuilder canonical = new StringBuilder();
        canonical.append(method).append('\n');
        canonical.append(path).append('\n');
        canonical.append(query).append('\n');
        canonical.append("content-type:application/json\n");
        canonical.append("host:").append(host).append('\n');
        canonical.append("x-amz-date:").append(amzDate).append('\n');
        if (sessionToken != null) {
            canonical.append("x-amz-security-token:").append(sessionToken).append('\n');
        }
        canonical.append('\n');
        canonical.append(signedHeaders).append('\n');
        canonical.append(payloadHash);

        // String to sign
        String credentialScope = dateStamp + "/" + region + "/" + service + "/aws4_request";
        String stringToSign = ALGORITHM + "\n" + amzDate + "\n" + credentialScope + "\n"
            + sha256Hex(canonical.toString());

        // Signing key
        byte[] signingKey = getSignatureKey(secretKey, dateStamp, region, service);
        String signature = hmacSha256Hex(signingKey, stringToSign);

        // Authorization header
        String authorization = ALGORITHM + " Credential=" + accessKey + "/" + credentialScope
            + ", SignedHeaders=" + signedHeaders + ", Signature=" + signature;

        builder.header("x-amz-date", amzDate);
        builder.header("x-amz-content-sha256", payloadHash);
        builder.header("Authorization", authorization);
        if (sessionToken != null) {
            builder.header("x-amz-security-token", sessionToken);
        }
    }

    private static byte[] getSignatureKey(String key, String dateStamp, String region, String service) {
        byte[] kDate = hmacSha256(("AWS4" + key).getBytes(StandardCharsets.UTF_8), dateStamp);
        byte[] kRegion = hmacSha256(kDate, region);
        byte[] kService = hmacSha256(kRegion, service);
        return hmacSha256(kService, "aws4_request");
    }

    private static byte[] hmacSha256(byte[] key, String data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new RuntimeException("HMAC-SHA256 failed", e);
        }
    }

    private static String hmacSha256Hex(byte[] key, String data) {
        return HexFormat.of().formatHex(hmacSha256(key, data));
    }

    private static String sha256Hex(String data) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(data.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new RuntimeException("SHA-256 failed", e);
        }
    }
}
