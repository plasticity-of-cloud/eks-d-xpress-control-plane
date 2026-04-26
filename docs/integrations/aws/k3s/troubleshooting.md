# Troubleshooting Guide for k3s EKS Pod Identity Integration

> **Note**: The deployment guides have moved. See [`deploy/README.md`](../../../deploy/README.md)
> for the current troubleshooting table. The diagnostic steps below are still valid.

## Common Issues and Solutions

### 1. EKS Pod Identity Agent Issues

#### Agent Not Starting
**Symptoms**: Agent DaemonSet pods not running or crashing

**Check Status**:
```bash
kubectl get pods -n kube-system | grep eks-pod-identity-agent
kubectl logs -n kube-system daemonset/eks-pod-identity-agent
kubectl describe pod -n kube-system -l app=eks-pod-identity-agent
```

**Common Causes & Solutions**:

1. **Missing Environment Variables**:
   ```bash
   # Check DaemonSet configuration
   kubectl get daemonset -n kube-system eks-pod-identity-agent -o yaml
   
   # Ensure these environment variables are set:
   # EKS_CLUSTER_NAME=k3s-cluster
   # EKS_POD_IDENTITY_ASSOCIATION_ENDPOINT=http://eks-auth-proxy.kube-system:8080
   ```

2. **Image Pull Issues**:
   ```bash
   # Check if image is available
   kubectl describe pod -n kube-system -l app=eks-pod-identity-agent
   
   # Verify image name
   kubectl get daemonset -n kube-system eks-pod-identity-agent -o jsonpath='{.spec.template.spec.containers[0].image}'
   ```

3. **Port Conflicts**:
   ```bash
   # Check if port 80 is in use on nodes
   kubectl get pods -n kube-system -o wide | grep eks-pod-identity-agent
   
   # Check node port usage
   kubectl exec -n kube-system daemonset/eks-pod-identity-agent -- netstat -tlnp | grep :80
   ```

### 2. Auth Service Proxy Issues

#### Proxy Not Responding
**Symptoms**: Agent can't connect to auth proxy endpoint

**Check Proxy Status**:
```bash
kubectl get pods -n kube-system | grep eks-auth-proxy
kubectl logs -n kube-system deployment/eks-auth-proxy
kubectl describe pod -n kube-system -l app=eks-auth-proxy
```

**Common Solutions**:

1. **Service Not Ready**:
   ```bash
   # Check service and endpoints
   kubectl get svc -n kube-system eks-auth-proxy
   kubectl get endpoints -n kube-system eks-auth-proxy
   
   # Restart if needed
   kubectl rollout restart deployment/eks-auth-proxy -n kube-system
   ```

2. **Configuration Issues**:
   ```bash
   # Check configuration
   kubectl get configmap -n kube-system eks-auth-proxy-config -o yaml
   
   # Verify JWT audience setting
   kubectl exec -n kube-system deployment/eks-auth-proxy -- env | grep JWT
   ```

3. **Network Connectivity**:
   ```bash
   # Test connectivity from agent to proxy
   curl -v http://eks-auth-proxy.kube-system:8080/health/live
   
   # Check k3s DNS
   kubectl run debug --image=busybox --rm -it -- nslookup eks-auth-proxy.kube-system
   ```

### 3. Pod Identity Association Issues

#### Association Not Found
**Symptoms**: Pods can't assume roles, "no association found" errors

**Check Associations**:
```bash
# List all associations
kubectl get podidentityassociations -A

# Check specific association
kubectl describe podidentityassociation -n default my-app-association

# Verify CRD is installed
kubectl get crd podidentityassociations.eks.amazonaws.com
```

**Solutions**:

1. **Create Missing Association**:
   ```bash
   # Using CLI tool
   ./eks-d-auth-cli/target/eks-d-auth-cli-*-runner create \
     --cluster k3s-cluster \
     --namespace default \
     --service-account my-app \
     --role-arn arn:aws:iam::ACCOUNT_ID:role/k3s-pod-identity-app-role
   ```

2. **Check Service Account Match**:
   ```bash
   # Verify service account exists
   kubectl get sa -n default my-app
   
   # Check pod is using correct service account
   kubectl get pod test-pod -o yaml | grep serviceAccountName
   ```

3. **Verify Role ARN Format**:
   ```bash
   # Check role exists in AWS
   aws iam get-role --role-name k3s-pod-identity-app-role
   
   # Verify ARN format in association
   kubectl get podidentityassociation -n default -o yaml
   ```

### 4. AWS Permissions Issues

#### STS AssumeRole Failures
**Symptoms**: "Access Denied" when assuming roles

**Check Instance Profile**:
```bash
# Verify instance has correct IAM role
curl http://169.254.169.254/latest/meta-data/iam/security-credentials/

# Test STS access from instance
aws sts get-caller-identity
```

**Check Role Trust Relationships**:
```bash
# Verify trust policy allows instance role to assume app role
aws iam get-role --role-name k3s-pod-identity-app-role --query 'Role.AssumeRolePolicyDocument'

# Should include instance role ARN in Principal
```

**Solutions**:

1. **Fix Trust Policy**:
   ```bash
   # Update trust policy to include instance role
   cat > trust-policy.json << EOF
   {
     "Version": "2012-10-17",
     "Statement": [
       {
         "Effect": "Allow",
         "Principal": {
           "AWS": "arn:aws:iam::ACCOUNT_ID:role/k3s-ec2-instance-role"
         },
         "Action": "sts:AssumeRole"
       }
     ]
   }
   EOF
   
   aws iam update-assume-role-policy \
     --role-name k3s-pod-identity-app-role \
     --policy-document file://trust-policy.json
   ```

2. **Add Missing Permissions**:
   ```bash
   # Ensure instance role can assume other roles
   aws iam attach-role-policy \
     --role-name k3s-ec2-instance-role \
     --policy-arn arn:aws:iam::aws:policy/PowerUserAccess
   ```

### 5. Token Validation Issues

#### JWT Token Problems
**Symptoms**: "Invalid token" or "Token validation failed" errors

**Check Token Generation**:
```bash
# Verify service account token is being created
kubectl get pod test-pod -o yaml | grep -A 10 volumes

# Check token file exists in pod
kubectl exec test-pod -- ls -la /var/run/secrets/kubernetes.io/serviceaccount/
kubectl exec test-pod -- cat /var/run/secrets/kubernetes.io/serviceaccount/token
```

**Check Token Audience**:
```bash
# Verify token has correct audience
kubectl exec test-pod -- cat /var/run/secrets/pods.eks.amazonaws.com/serviceaccount/token | base64 -d

# Check auth proxy audience configuration
kubectl logs -n kube-system deployment/eks-auth-proxy | grep audience
```

**Solutions**:

1. **Fix Token Volume Mount**:
   ```bash
   # Ensure pod has correct volume mount
   kubectl patch deployment test-app -p '
   {
     "spec": {
       "template": {
         "spec": {
           "volumes": [
             {
               "name": "aws-iam-token",
               "projected": {
                 "sources": [
                   {
                     "serviceAccountToken": {
                       "audience": "pods.eks.amazonaws.com",
                       "expirationSeconds": 86400,
                       "path": "token"
                     }
                   }
                 ]
               }
             }
           ]
         }
       }
     }
   }'
   ```

2. **Update Audience Configuration**:
   ```bash
   # For k3s, may need to adjust audience
   kubectl patch configmap -n kube-system eks-auth-proxy-config --patch '
   {
     "data": {
       "mp.jwt.verify.audiences": "https://kubernetes.default.svc.cluster.local"
     }
   }'
   ```

### 6. k3s Specific Issues

#### k3s Service Problems
**Symptoms**: k3s not starting or pods not scheduling

**Check k3s Status**:
```bash
sudo systemctl status k3s
sudo journalctl -u k3s -f
```

**Common Solutions**:

1. **Restart k3s**:
   ```bash
   sudo systemctl restart k3s
   sudo systemctl status k3s
   ```

2. **Check Node Resources**:
   ```bash
   kubectl describe node
   kubectl top node
   ```

3. **Verify kubeconfig**:
   ```bash
   # Fix kubeconfig permissions
   sudo cp /etc/rancher/k3s/k3s.yaml ~/.kube/config
   sudo chown $USER:$USER ~/.kube/config
   chmod 600 ~/.kube/config
   ```

### 7. Network Connectivity Issues

#### DNS Resolution Problems
**Symptoms**: Services can't resolve each other

**Test DNS**:
```bash
# Test from pod
kubectl run debug --image=busybox --rm -it -- nslookup kubernetes.default

# Check CoreDNS
kubectl get pods -n kube-system | grep coredns
kubectl logs -n kube-system -l k8s-app=kube-dns
```

#### Service Discovery Issues
**Symptoms**: Agent can't reach auth proxy

**Check Services**:
```bash
# Verify service exists and has endpoints
kubectl get svc -n kube-system eks-auth-proxy
kubectl get endpoints -n kube-system eks-auth-proxy

# Test connectivity
kubectl run debug --image=curlimages/curl --rm -it -- curl -v http://eks-auth-proxy.kube-system:8080/health/live
```

## Diagnostic Commands

### Complete Health Check Script
```bash
#!/bin/bash

echo "=== k3s EKS Pod Identity Health Check ==="

echo "1. Checking k3s status..."
sudo systemctl is-active k3s

echo "2. Checking EKS Pod Identity Agent..."
kubectl get pods -n kube-system | grep eks-pod-identity-agent

echo "3. Checking auth proxy deployment..."
kubectl get pods -n kube-system | grep eks-auth-proxy

echo "4. Checking pod identity associations..."
kubectl get podidentityassociations -A

echo "5. Checking AWS connectivity..."
aws sts get-caller-identity

echo "6. Testing pod identity from test pod..."
kubectl run test-aws --image=amazon/aws-cli:latest --rm -it --serviceaccount=my-app -- aws sts get-caller-identity

echo "Health check completed!"
```

### Log Collection Script
```bash
#!/bin/bash

echo "Collecting logs for troubleshooting..."

# System logs
sudo journalctl -u k3s --since "1 hour ago" > k3s.log
kubectl logs -n kube-system daemonset/eks-pod-identity-agent > agent.log

# Kubernetes logs
kubectl logs -n kube-system deployment/eks-auth-proxy > auth-proxy.log
kubectl get events --sort-by='.lastTimestamp' > events.log

# Configuration
kubectl get podidentityassociations -A -o yaml > associations.yaml
kubectl get pods -A -o wide > pods.log

echo "Logs collected in current directory"
```

## Prevention Best Practices

1. **Monitor Services**: Set up monitoring for all components
2. **Regular Updates**: Keep k3s and components updated
3. **Backup Configurations**: Backup CRD resources and configurations
4. **Test Changes**: Test in non-production environment first
5. **Documentation**: Keep deployment documentation updated

## Getting Help

If issues persist:

1. **Check GitHub Issues**: Search existing issues in the project repository
2. **Enable Debug Logging**: Increase log verbosity for detailed troubleshooting
3. **Collect Logs**: Use the diagnostic scripts above
4. **Community Support**: Reach out to k3s and EKS communities
