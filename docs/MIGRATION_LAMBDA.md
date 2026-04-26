# Migration: eks-auth-proxy → Lambda (Quarkus)

## Scope

Migrate the `eks-auth-proxy` module from a Kubernetes Deployment to an AWS Lambda function behind API Gateway, using Quarkus Amazon Lambda REST extension. The Lambda replaces the in-cluster proxy as the central auth service.

## What Changes

| Aspect | Current (in-cluster) | Target (Lambda) |
|--------|---------------------|-----------------|
| Runtime | Kubernetes Deployment (JVM) | Lambda (SnapStart for fast cold start) |
| HTTP layer | Quarkus REST (Vert.x) | API Gateway → `quarkus-amazon-lambda-rest` |
| Token validation | Kubernetes TokenReview API | Direct JWT/JWKS validation (JWKS from DynamoDB) |
| Association lookup | CRD via Fabric8 client | DynamoDB GetItem |
| STS call | Same | Same (STS AssumeRole + TagSession) |
| Cluster metadata | Not needed | DynamoDB `eks-dx-clusters` table |
| Endpoint | `http://eks-auth-proxy.kube-system:8080` | `https://<api-gw-id>.execute-api.<region>.amazonaws.com` |

## What Stays the Same

- `POST /clusters/{clusterName}/assets` endpoint (wire-compatible with EKS Pod Identity Agent)
- `AwsCredentialService` (STS AssumeRole with session tags)
- Response format (camelCase JSON matching AWS Smithy model)
- `pods.eks.amazonaws.com` audience requirement

## Quarkus Lambda Setup

### Dependencies

Replace Kubernetes-specific dependencies with Lambda + DynamoDB:

```xml
<!-- Remove -->
<dependency>quarkus-kubernetes-client</dependency>
<dependency>quarkus-kubernetes</dependency>
<dependency>quarkus-helm</dependency>
<dependency>quarkus-container-image-jib</dependency>
<!-- eks-pod-identity-crd module dependency -->

<!-- Add -->
<dependency>
  <groupId>io.quarkus</groupId>
  <artifactId>quarkus-amazon-lambda-rest</artifactId>
</dependency>
<dependency>
  <groupId>io.quarkus</groupId>
  <artifactId>quarkus-amazon-dynamodb</artifactId>
</dependency>
<dependency>
  <groupId>io.quarkus</groupId>
  <artifactId>quarkus-smallrye-jwt</artifactId>
</dependency>
```

`quarkus-amazon-lambda-rest` automatically wraps JAX-RS endpoints as Lambda handlers — no code changes to `EksAuthAgentResource`.

### Build Output

```bash
# JVM Lambda (with SnapStart)
mvn -pl eks-auth-proxy package

# Produces:
#   target/function.zip              → Lambda deployment package
#   target/sam.jvm.yaml              → SAM template
#   target/manage.sh                 → Helper script for create/update/delete

# Native Lambda (fastest cold start, ~200ms)
mvn -pl eks-auth-proxy package -Pnative -Dquarkus.native.container-build=true

# Produces:
#   target/function.zip              → Native Lambda package
#   target/sam.native.yaml           → SAM template
```

### application.properties Changes

```properties
# Remove
quarkus.kubernetes.*
quarkus.helm.*
quarkus.container-image.*
quarkus.http.ssl.*
eks.pod-identity.configmap.*

# Add
quarkus.lambda.handler=rest-handler

# DynamoDB tables
eks-dx.clusters-table=eks-dx-clusters
eks-dx.associations-table=eks-dx-associations

# JWT validation (replaces TokenReview)
# JWKS is loaded from DynamoDB at runtime, not from a static URL
mp.jwt.verify.audiences=pods.eks.amazonaws.com

# SnapStart (primes the Lambda during snapshot)
quarkus.snapstart.enable=true

# STS (unchanged)
aws.sts.session-duration=PT1H
```

## Service Layer Changes

### TokenValidationService → JwksTokenValidationService

Replace Kubernetes TokenReview with direct JWT validation:

```java
@ApplicationScoped
public class JwksTokenValidationService {

    @Inject
    DynamoDbClient dynamoDb;

    @ConfigProperty(name = "eks-dx.clusters-table")
    String clustersTable;

    // Cache JWKS per cluster, refresh every 5 min
    private final Map<String, CachedJwks> jwksCache = new ConcurrentHashMap<>();

    public TokenClaims validateToken(String token, String clusterName) {
        JsonWebKeySet jwks = getJwks(clusterName);

        // Verify signature, audience, expiry, issuer
        JwtConsumer consumer = new JwtConsumerBuilder()
            .setVerificationKeyResolver(new JwksVerificationKeyResolver(jwks.getJsonWebKeys()))
            .setExpectedAudience("pods.eks.amazonaws.com")
            .setRequireExpirationTime()
            .build();

        JwtClaims claims = consumer.processToClaims(token);

        String username = claims.getSubject(); // system:serviceaccount:<ns>:<sa>
        String[] parts = username.split(":");
        // ... extract namespace, serviceAccount, podName, podUid
        return new TokenClaims(...);
    }

    private JsonWebKeySet getJwks(String clusterName) {
        // Check cache (5 min TTL)
        // On miss: read from DynamoDB eks-dx-clusters table
    }
}
```

### PodIdentityAssociationService → DynamoDbAssociationService

Replace CRD lookup with DynamoDB:

```java
@ApplicationScoped
public class DynamoDbAssociationService {

    @Inject
    DynamoDbClient dynamoDb;

    @ConfigProperty(name = "eks-dx.associations-table")
    String tableName;

    public String getRoleArnForServiceAccount(String clusterName, String namespace, String serviceAccount) {
        GetItemResponse response = dynamoDb.getItem(GetItemRequest.builder()
            .tableName(tableName)
            .key(Map.of(
                "PK", AttributeValue.fromS("CLUSTER#" + clusterName),
                "SK", AttributeValue.fromS(namespace + "#" + serviceAccount)))
            .build());

        if (response.hasItem()) {
            return response.item().get("roleArn").s();
        }

        // Generated default fallback
        String accountId = Optional.ofNullable(System.getenv("AWS_ACCOUNT_ID")).orElse("123456789012");
        return String.format("arn:aws:iam::%s:role/eks-dx-pod-%s-%s", accountId, namespace, serviceAccount);
    }
}
```

### EksAuthAgentResource — No Changes

The JAX-RS endpoint stays identical. `quarkus-amazon-lambda-rest` wraps it automatically:

```java
@Path("/clusters")
public class EksAuthAgentResource {
    @POST
    @Path("/{clusterName}/assets")
    public Response assumeRoleForPodIdentity(
            @PathParam("clusterName") String clusterName,
            AgentRequest request) {
        // Same flow, different service implementations injected
    }
}
```

### AwsCredentialService — No Changes

STS AssumeRole logic is identical.

## Infrastructure (CDK or SAM)

### SAM Template (minimal)

```yaml
AWSTemplateFormatVersion: '2010-09-09'
Transform: AWS::Serverless-2016-10-31

Globals:
  Function:
    Timeout: 30
    MemorySize: 512
    SnapStart:
      ApplyOn: PublishedVersions

Resources:
  EksDxAuthFunction:
    Type: AWS::Serverless::Function
    Properties:
      Handler: io.quarkus.amazon.lambda.runtime.QuarkusStreamHandler::handleRequest
      Runtime: java21
      CodeUri: target/function.zip
      AutoPublishAlias: live
      Policies:
        - DynamoDBReadPolicy:
            TableName: !Ref ClustersTable
        - DynamoDBReadPolicy:
            TableName: !Ref AssociationsTable
        - Statement:
            Effect: Allow
            Action:
              - sts:AssumeRole
              - sts:TagSession
            Resource: "arn:aws:iam::*:role/eks-dx-pod-*"
      Events:
        Api:
          Type: HttpApi
          Properties:
            Path: /clusters/{clusterName}/assets
            Method: POST

  ClustersTable:
    Type: AWS::DynamoDB::Table
    Properties:
      TableName: eks-dx-clusters
      BillingMode: PAY_PER_REQUEST
      AttributeDefinitions:
        - AttributeName: clusterName
          AttributeType: S
      KeySchema:
        - AttributeName: clusterName
          KeyType: HASH

  AssociationsTable:
    Type: AWS::DynamoDB::Table
    Properties:
      TableName: eks-dx-associations
      BillingMode: PAY_PER_REQUEST
      AttributeDefinitions:
        - AttributeName: PK
          AttributeType: S
        - AttributeName: SK
          AttributeType: S
      KeySchema:
        - AttributeName: PK
          KeyType: HASH
        - AttributeName: SK
          KeyType: RANGE
```

## Testing

### Local Testing

Quarkus provides a Lambda test harness:

```java
@QuarkusTest
class LambdaAuthTest {
    @Test
    void testTokenExchange() {
        // quarkus-amazon-lambda-rest provides a local HTTP server
        // Tests hit the same JAX-RS endpoints as in production
        given()
            .contentType(ContentType.JSON)
            .body(new AgentRequest("valid-token"))
        .when()
            .post("/clusters/my-k3s/assets")
        .then()
            .statusCode(200);
    }
}
```

DynamoDB local via `quarkus-amazon-dynamodb` test containers or LocalStack.

### SAM Local

```bash
sam local start-api --template target/sam.jvm.yaml
curl -X POST http://localhost:3000/clusters/my-k3s/assets -d '{"token":"..."}'
```

## Migration Steps

1. **Create new module** `eks-dx-lambda` (or refactor `eks-auth-proxy` in place)
2. **Swap dependencies**: Kubernetes client → DynamoDB + JWT
3. **Rewrite TokenValidationService** → JWKS-based JWT validation
4. **Rewrite PodIdentityAssociationService** → DynamoDB GetItem
5. **Keep** `EksAuthAgentResource` and `AwsCredentialService` unchanged
6. **Add SAM/CDK template** for Lambda + API Gateway + DynamoDB
7. **Test** with SAM local + DynamoDB local
8. **Deploy** and point agent `--endpoint` to API Gateway URL

## Module Structure (Post-Migration)

```
eks-dx-lambda/                    # Lambda auth service (NEW)
  src/main/java/cloud/plasticity/eksdx/
    resource/EksAuthAgentResource.java    # Unchanged JAX-RS endpoint
    service/JwksTokenValidationService.java  # JWT/JWKS validation
    service/DynamoDbAssociationService.java  # DynamoDB lookup
    service/AwsCredentialService.java        # STS AssumeRole (unchanged)
eks-d-auth-cli/                   # CLI (DynamoDB CRUD instead of CRD)
eks-pod-identity-webhook/         # Webhook (DynamoDB lookup, stays in-cluster)
eks-pod-identity-crd/             # Deprecated (kept for offline mode)
eks-auth-proxy/                   # Deprecated (kept for offline mode)
```
