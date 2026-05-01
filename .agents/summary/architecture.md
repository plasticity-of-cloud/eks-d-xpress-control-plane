# System Architecture

## Overview
EKS-DX Control Plane implements a distributed authentication system that replicates AWS EKS Pod Identity for non-EKS Kubernetes environments. The architecture follows a microservices pattern with serverless backend components.

## High-Level Architecture

```mermaid
graph TB
    subgraph "Kubernetes Cluster"
        Pod[Application Pod]
        Webhook[Pod Identity Webhook]
        Proxy[EKS Auth Proxy]
        SA[Service Account Token]
    end
    
    subgraph "AWS Cloud"
        APIGW[API Gateway]
        Lambda[EKS-DX Lambda]
        DDB[(DynamoDB)]
        STS[AWS STS]
    end
    
    subgraph "Management"
        CLI[EKS-DX CLI]
        CDK[CDK Infrastructure]
    end
    
    Pod -->|Projected Token| SA
    Webhook -->|Mutate Pod| Pod
    Pod -->|HTTP Request| Proxy
    Proxy -->|TokenReview| K8sAPI[Kubernetes API]
    Proxy -->|Forward Token| APIGW
    APIGW --> Lambda
    Lambda -->|Query| DDB
    Lambda -->|AssumeRole| STS
    CLI -->|Manage| APIGW
    CDK -->|Deploy| AWS[AWS Resources]
```

## Component Architecture

### Authentication Flow Components

```mermaid
graph LR
    subgraph "Request Path"
        A[Pod Request] --> B[EKS Auth Proxy]
        B --> C[TokenReview Validation]
        B --> D[Lambda Forwarding]
        D --> E[JWKS Validation]
        E --> F[Association Lookup]
        F --> G[STS AssumeRole]
        G --> H[AWS Credentials]
    end
```

### Data Flow Architecture

```mermaid
graph TD
    subgraph "Control Plane"
        CLI[CLI Commands] --> API[Management API]
        API --> Clusters[(Clusters Table)]
        API --> Associations[(Associations Table)]
    end
    
    subgraph "Data Plane"
        Token[Service Account Token] --> Validation[Token Validation]
        Validation --> Lookup[Association Lookup]
        Lookup --> Associations
        Lookup --> Role[IAM Role ARN]
        Role --> STS[STS AssumeRole]
    end
```

## Design Patterns

### Microservices Pattern
- **Service Separation**: Each component has a single responsibility
- **Independent Deployment**: Components can be deployed separately
- **Technology Diversity**: Different components use optimal technologies

### Event-Driven Architecture
- **Stateless Components**: No local state, all data in DynamoDB
- **Async Processing**: Non-blocking HTTP clients
- **Reactive Patterns**: Quarkus reactive extensions

### Security-First Design
- **Defense in Depth**: Multiple validation layers
- **Principle of Least Privilege**: Minimal IAM permissions
- **Token Validation**: Multi-stage JWT verification

## Infrastructure Patterns

### Serverless-First
```mermaid
graph TB
    subgraph "Serverless Components"
        Lambda[AWS Lambda]
        APIGW[API Gateway]
        DDB[(DynamoDB)]
    end
    
    subgraph "Container Components"
        Proxy[EKS Auth Proxy]
        Webhook[Pod Identity Webhook]
    end
    
    subgraph "Native Components"
        CLI[Native CLI Binary]
    end
```

### Infrastructure as Code
- **CDK Primary**: Complete infrastructure definition
- **SAM Alternative**: Simplified serverless deployment
- **Immutable Infrastructure**: No manual AWS console changes

## Scalability Architecture

### Horizontal Scaling
- **Lambda Auto-scaling**: Automatic concurrency management
- **DynamoDB On-Demand**: Pay-per-request scaling
- **Stateless Design**: Easy horizontal scaling

### Performance Optimization
- **JWKS Caching**: Reduced external API calls
- **Connection Pooling**: Efficient HTTP client usage
- **Native Compilation**: Fast startup times (CLI)

## Security Architecture

### Authentication Layers
```mermaid
sequenceDiagram
    participant Client
    participant Proxy
    participant Lambda
    participant K8s as Kubernetes API
    participant DDB as DynamoDB
    participant STS as AWS STS
    
    Client->>Proxy: Service Account Token
    Proxy->>K8s: TokenReview Request
    K8s-->>Proxy: Token Valid/Invalid
    Proxy->>Lambda: Forward Valid Token
    Lambda->>Lambda: JWKS Signature Validation
    Lambda->>DDB: Association Lookup
    Lambda->>STS: AssumeRole with Session Tags
    STS-->>Lambda: Temporary Credentials
    Lambda-->>Client: AWS Credentials
```

### Security Controls
- **JWT Signature Verification**: JWKS-based validation
- **Audience Validation**: Strict audience checking
- **Session Tagging**: Kubernetes metadata in AWS sessions
- **IAM Role Validation**: Trust policy verification

## Deployment Architecture

### Multi-Environment Support
- **Development**: Local DynamoDB, mock services
- **Staging**: Shared AWS resources, isolated data
- **Production**: Dedicated AWS account, monitoring

### Container Strategy
- **Quarkus Native**: Fast startup, low memory
- **Multi-stage Builds**: Optimized container images
- **Distroless Base**: Minimal attack surface

## Monitoring and Observability

### CloudWatch Integration
- **Lambda Metrics**: Duration, errors, throttles
- **DynamoDB Metrics**: Read/write capacity, throttles
- **Custom Metrics**: Authentication success/failure rates

### Logging Strategy
- **Structured Logging**: JSON format for parsing
- **Correlation IDs**: Request tracing across components
- **Security Events**: Authentication attempts and failures
