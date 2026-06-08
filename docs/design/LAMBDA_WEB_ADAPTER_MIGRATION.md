# Tenant Service: Lambda Web Adapter Migration for SSE Streaming

## Problem

The tenant-service SSE endpoint (`GET /tenants/{id}/stream`) does not stream events to clients.
The client receives nothing until the Lambda times out or the full response is buffered.

## Root Cause

Quarkus's `quarkus-amazon-lambda-http` extension (v3.36) implements:

```java
RequestHandler<AwsProxyRequest, AwsProxyResponse>
```

It buffers the entire HTTP response into a `ByteArrayOutputStream`, serializes it as a JSON
`AwsProxyResponse` object, and returns it to the Lambda runtime as a single payload. This is
fundamentally incompatible with Lambda response streaming, regardless of Function URL invoke mode.

The `QuarkusStreamHandler` name refers to the Lambda *invocation* stream protocol (reading raw
bytes from InputStream), NOT to HTTP response streaming.

**Evidence:** `javap` of `quarkus-amazon-lambda-http-3.36.0.jar` confirms:
- `LambdaHttpHandler implements RequestHandler<APIGatewayV2HTTPEvent, APIGatewayV2HTTPResponse>`
- Uses `createByteStream()` → `ByteArrayOutputStream` for response body

## Solution: AWS Lambda Web Adapter

The Lambda Web Adapter (https://github.com/awslabs/aws-lambda-web-adapter) is an AWS-maintained
Rust extension that bridges between Lambda's streaming Runtime API and a standard HTTP server
running inside the Lambda execution environment.

### How it works

```
Client → Function URL (RESPONSE_STREAM) → Lambda Runtime
    → Web Adapter (implements streaming Runtime API protocol)
        → Quarkus HTTP server on localhost:8080
            → Multi<TenantProgress> SSE (chunked transfer encoding)
        ← Web Adapter reads chunked response, streams via Runtime API
    ← Lambda streams chunks to client
← Client receives SSE events incrementally
```

### Why this works

- Quarkus runs as a **real HTTP server** (Vert.x/Netty), not in Lambda handler mode
- SSE with `Multi<>` produces `Transfer-Encoding: chunked` responses naturally
- Web Adapter reads the chunked HTTP response and forwards it through Lambda's streaming protocol
- The streaming protocol uses `Lambda-Runtime-Function-Response-Mode: streaming` + chunked encoding
  to the Runtime API response endpoint

## Changes Required

### 1. pom.xml (`eks-dx-tenant-service`)

```xml
<!-- REMOVE -->
<artifactId>quarkus-amazon-lambda-http</artifactId>

<!-- ADD -->
<artifactId>quarkus-rest</artifactId>
```

This switches from Lambda handler mode to standalone HTTP server mode.

### 2. application.properties

```properties
# Web Adapter connects on this port
quarkus.http.port=${PORT:8080}

# Remove Lambda-specific config
# quarkus.lambda.handler=rest-handler  (delete this line)
```

### 3. CDK Stack (`EksDXpressControlPlaneStack.java`)

```java
// Add Web Adapter layer
import software.amazon.awscdk.services.lambda.LayerVersion;

LayerVersion webAdapterLayer = LayerVersion.fromLayerVersionArn(this, "LambdaWebAdapter",
    "arn:aws:lambda:us-east-1:753240598075:layer:LambdaAdapterLayerArm64:25");

// For x86:
// "arn:aws:lambda:us-east-1:753240598075:layer:LambdaAdapterLayerX86:25"

Function tenantFn = Function.Builder.create(this, "EksDxTenantFunction")
    // ... existing config ...
    .layers(List.of(webAdapterLayer))
    .environment(Map.of(
        // ... existing env vars ...
        "AWS_LAMBDA_EXEC_WRAPPER", "/opt/bootstrap",
        "AWS_LWA_INVOKE_MODE", "response_stream",
        "PORT", "8080",
        "READINESS_CHECK_PATH", "/q/health/ready"
    ))
    .build();
```

### 4. Handler change

```java
// CDK: change handler from QuarkusStreamHandler to the Web Adapter bootstrap
.handler("bootstrap")  // Web Adapter is the bootstrap for custom runtime
// OR for JVM mode:
.handler("io.quarkus.amazon.lambda.runtime.QuarkusStreamHandler::handleRequest")
// with AWS_LAMBDA_EXEC_WRAPPER=/opt/bootstrap wrapping the JVM startup
```

For **JVM mode** (java25 runtime): Web Adapter wraps the existing process via `AWS_LAMBDA_EXEC_WRAPPER`.
For **native mode** (provided.al2023): The native binary IS the HTTP server; Web Adapter launches it.

### 5. Packaging

- **JVM mode:** `function.zip` contains the uber-jar. Lambda starts JVM, Web Adapter wraps it.
- **Native mode:** `function.zip` contains the native binary. The `bootstrap` file is the Web Adapter,
  which starts the native binary as a subprocess.

For native, the Dockerfile/build needs to produce a binary named something other than `bootstrap`
(e.g., `application`) and the Web Adapter `bootstrap` starts it.

## No Code Changes to SSE Endpoint

`TenantStreamResource.java` remains exactly as-is:

```java
@GET
@Path("/{id}/stream")
@Produces(MediaType.SERVER_SENT_EVENTS)
@RestStreamElementType(MediaType.APPLICATION_JSON)
@Blocking
public Multi<TenantProgress> streamProgress(@PathParam("id") String id) { ... }
```

## References

1. **AWS Blog: Using response streaming with Lambda Web Adapter**
   https://aws.amazon.com/blogs/compute/using-response-streaming-with-aws-lambda-web-adapter-to-optimize-performance/

2. **Lambda Web Adapter GitHub (awslabs)**
   https://github.com/awslabs/aws-lambda-web-adapter

3. **Lambda Response Streaming documentation**
   https://docs.aws.amazon.com/lambda/latest/dg/configuration-response-streaming.html

4. **API Gateway Response Streaming (REST API)**
   https://docs.aws.amazon.com/apigateway/latest/developerguide/response-transfer-mode.html

5. **API Gateway streaming Lambda proxy integration setup**
   https://docs.aws.amazon.com/apigateway/latest/developerguide/response-streaming-lambda-configure.html

6. **InvokeWithResponseStream API**
   https://docs.aws.amazon.com/lambda/latest/api/API_InvokeWithResponseStream.html

7. **Lambda Web Adapter FastAPI streaming example**
   https://github.com/awslabs/aws-lambda-web-adapter/blob/main/examples/fastapi-response-streaming/README.md

8. **Quarkus Lambda HTTP guide (confirms buffered architecture)**
   https://quarkus.io/guides/aws-lambda-http

## Key Environment Variables

| Variable | Value | Purpose |
|----------|-------|---------|
| `AWS_LAMBDA_EXEC_WRAPPER` | `/opt/bootstrap` | Activates Web Adapter (zip deploy, JVM mode) |
| `AWS_LWA_INVOKE_MODE` | `response_stream` | Tells adapter to use streaming Runtime API |
| `PORT` | `8080` | Port where Quarkus HTTP server listens |
| `READINESS_CHECK_PATH` | `/q/health/ready` | Web Adapter waits for this before accepting traffic |

## Layer ARNs (us-east-1)

| Architecture | ARN |
|-------------|-----|
| arm64 | `arn:aws:lambda:us-east-1:753240598075:layer:LambdaAdapterLayerArm64:25` |
| x86_64 | `arn:aws:lambda:us-east-1:753240598075:layer:LambdaAdapterLayerX86:25` |

Check latest versions at: https://github.com/awslabs/aws-lambda-web-adapter/releases

## Testing

1. Deploy with dry-run mode: `./deploy-local.sh --context jvmTenant=true --context dryRun=true`
2. Invoke stream: `curl` against Function URL with SigV4 auth
3. Verify SSE events arrive incrementally (every 2s in dry-run mode)

## Rollback

Tag `lambda-web-adapter-pre-migration` marks the state before this change.
