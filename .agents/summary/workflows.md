# Workflows

## 1. Cluster Registration

```mermaid
sequenceDiagram
    participant Operator
    participant CLI as eks-dx CLI
    participant K8s as K8s API Server
    participant GW as API Gateway (IAM)
    participant Lambda
    participant DDB as DynamoDB (clusters)

    Operator->>CLI: eks-dx cluster create --name my-cluster
    CLI->>K8s: GET /.well-known/openid-configuration
    K8s-->>CLI: { issuer: "https://..." }
    CLI->>K8s: GET /openid/v1/jwks
    K8s-->>CLI: { keys: [...] }
    CLI->>GW: POST /clusters {name, issuer, jwks} (SigV4)
    GW->>Lambda: invoke
    Lambda->>DDB: GetItem(clusterName) → not found
    Lambda->>DDB: PutItem(clusterName, issuer, jwks, createdAt)
    Lambda-->>GW: 201 {clusterName, issuer, createdAt}
    GW-->>CLI: 201
    CLI-->>Operator: Cluster registered
```

## 2. Pod Identity Association Creation

```mermaid
sequenceDiagram
    participant Operator
    participant CLI as eks-dx CLI
    participant GW as API Gateway (IAM)
    participant Lambda
    participant IAM as AWS IAM
    participant DDB as DynamoDB (associations)

    Operator->>CLI: eks-dx association create --cluster X --namespace ns --service-account sa --role-arn arn:...
    CLI->>GW: POST /clusters/X/pod-identity-associations (SigV4)
    GW->>Lambda: invoke
    Lambda->>IAM: GetRole(roleName)
    IAM-->>Lambda: role + trust policy
    Lambda->>Lambda: validate trust policy has sts:AssumeRole
    Lambda->>DDB: GetItem(PK=CLUSTER#X, SK=ns#sa) → not found
    Lambda->>DDB: PutItem(PK, SK, associationId, roleArn, ...)
    Lambda-->>GW: 201 {associationId, ...}
    GW-->>CLI: 201
```

## 3. Pod Startup (Full Flow)

```mermaid
sequenceDiagram
    participant K8s as K8s API Server
    participant Webhook as eks-dx-pod-identity-webhook
    participant GW as API Gateway
    participant Pod
    participant Agent as Pod Identity Agent (DaemonSet)
    participant Proxy as eks-dx-auth-proxy
    participant Lambda as eks-dx-lambda
    participant STS

    K8s->>Webhook: AdmissionReview (Pod CREATE)
    Webhook->>GW: GET /clusters/{name}/pod-identity-associations?namespace=ns&sa=sa\n(Bearer SA token)
    GW-->>Webhook: [{associationId, roleArn}]
    Webhook-->>K8s: JSONPatch (inject env vars + projected token volume)
    K8s->>Pod: schedule with injected env vars

    Pod->>Agent: GET http://169.254.170.23/v1/credentials\n(AWS_CONTAINER_CREDENTIALS_FULL_URI)
    Agent->>Proxy: POST /clusters/{name}/assets {token: <projected SA token>}
    Proxy->>K8s: TokenReview(token, audience=pods.eks.amazonaws.com)
    K8s-->>Proxy: authenticated=true, namespace/sa/pod
    Proxy->>GW: POST /clusters/{name}/assets {token}
    GW->>Lambda: invoke
    Lambda->>Lambda: JWKS validate (DynamoDB cache, 5min TTL)
    Lambda->>Lambda: getRoleArn(cluster, ns, sa)
    Lambda->>STS: AssumeRole(roleArn, sessionTags)
    STS-->>Lambda: credentials
    Lambda-->>Proxy: 200 credentials
    Proxy-->>Agent: 200 credentials
    Agent-->>Pod: credentials
```

## 4. JWKS Refresh

When cluster keys rotate:
```
eks-dx cluster update my-cluster --refresh-jwks
```
CLI fetches fresh JWKS from kube-apiserver and calls `PUT /clusters/{name}/jwks`. Lambda updates the DynamoDB item. The in-memory cache in Lambda expires within 5 minutes.

## 5. CI/CD Integration

Pods can use the proxy directly as a credential provider:
```bash
export AWS_CONTAINER_CREDENTIALS_FULL_URI=http://eks-dx-auth-proxy:8080/clusters/{name}/assets
export AWS_CONTAINER_AUTHORIZATION_TOKEN="Bearer $(cat /var/run/secrets/kubernetes.io/serviceaccount/token)"
# AWS SDK now resolves credentials via the proxy
aws s3 ls
```
