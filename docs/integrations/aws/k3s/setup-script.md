# Quick Setup Script for k3s with EKS Pod Identity

This script automates the setup of k3s on EC2 with EKS Pod Identity integration.

## Prerequisites

- AWS CLI configured with appropriate permissions
- SSH key pair created in AWS
- Replace variables in the script with your values

## Usage

```bash
# Make script executable
chmod +x setup-k3s-eks-pod-identity.sh

# Run the setup
./setup-k3s-eks-pod-identity.sh
```

## Script

```bash
#!/bin/bash
set -e

# Configuration - MODIFY THESE VALUES
REGION="us-east-1"
KEY_PAIR_NAME="your-key-pair"
INSTANCE_TYPE="t3.medium"
CLUSTER_NAME="k3s-cluster"

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

echo -e "${GREEN}Setting up k3s cluster with EKS Pod Identity integration${NC}"

# Get account ID
ACCOUNT_ID=$(aws sts get-caller-identity --query Account --output text)
echo "Account ID: $ACCOUNT_ID"

# Step 1: Create IAM roles and policies
echo -e "${YELLOW}Creating IAM roles and policies...${NC}"

# Create trust policy for EC2
cat > ec2-trust-policy.json << 'EOF'
{
  "Version": "2012-10-17",
  "Statement": [
    {
      "Effect": "Allow",
      "Principal": {
        "Service": "ec2.amazonaws.com"
      },
      "Action": "sts:AssumeRole"
    }
  ]
}
EOF

# Create EC2 instance role
aws iam create-role \
  --role-name k3s-ec2-instance-role \
  --assume-role-policy-document file://ec2-trust-policy.json \
  --output text || echo "Role already exists"

# Create comprehensive EC2 policy
cat > ec2-instance-policy.json << 'EOF'
{
  "Version": "2012-10-17",
  "Statement": [
    {
      "Effect": "Allow",
      "Action": [
        "autoscaling:*",
        "ec2:*",
        "elasticloadbalancing:*",
        "iam:CreateServiceLinkedRole",
        "ecr:*",
        "sts:AssumeRole"
      ],
      "Resource": "*"
    }
  ]
}
EOF

# Create and attach policy
aws iam create-policy \
  --policy-name k3s-ec2-instance-policy \
  --policy-document file://ec2-instance-policy.json \
  --output text || echo "Policy already exists"

aws iam attach-role-policy \
  --role-name k3s-ec2-instance-role \
  --policy-arn arn:aws:iam::${ACCOUNT_ID}:policy/k3s-ec2-instance-policy

# Create instance profile
aws iam create-instance-profile \
  --instance-profile-name k3s-ec2-instance-profile \
  --output text || echo "Instance profile already exists"

aws iam add-role-to-instance-profile \
  --instance-profile-name k3s-ec2-instance-profile \
  --role-name k3s-ec2-instance-role || echo "Role already added"

# Create application role for pod identity
cat > pod-identity-trust-policy.json << EOF
{
  "Version": "2012-10-17",
  "Statement": [
    {
      "Effect": "Allow",
      "Principal": {
        "AWS": "arn:aws:iam::${ACCOUNT_ID}:role/k3s-ec2-instance-role"
      },
      "Action": "sts:AssumeRole"
    }
  ]
}
EOF

aws iam create-role \
  --role-name k3s-pod-identity-app-role \
  --assume-role-policy-document file://pod-identity-trust-policy.json \
  --output text || echo "App role already exists"

aws iam attach-role-policy \
  --role-name k3s-pod-identity-app-role \
  --policy-arn arn:aws:iam::aws:policy/AmazonS3ReadOnlyAccess

echo -e "${GREEN}IAM roles created successfully${NC}"

# Step 2: Create security group
echo -e "${YELLOW}Creating security group...${NC}"

SG_ID=$(aws ec2 create-security-group \
  --group-name k3s-cluster-sg \
  --description "Security group for k3s cluster" \
  --query 'GroupId' \
  --output text 2>/dev/null || \
  aws ec2 describe-security-groups \
    --group-names k3s-cluster-sg \
    --query 'SecurityGroups[0].GroupId' \
    --output text)

echo "Security Group ID: $SG_ID"

# Add security group rules
aws ec2 authorize-security-group-ingress \
  --group-id $SG_ID \
  --protocol tcp \
  --port 6443 \
  --cidr 0.0.0.0/0 2>/dev/null || echo "Rule already exists"

aws ec2 authorize-security-group-ingress \
  --group-id $SG_ID \
  --protocol tcp \
  --port 80 \
  --cidr 0.0.0.0/0 2>/dev/null || echo "Rule already exists"

aws ec2 authorize-security-group-ingress \
  --group-id $SG_ID \
  --protocol tcp \
  --port 443 \
  --cidr 0.0.0.0/0 2>/dev/null || echo "Rule already exists"

aws ec2 authorize-security-group-ingress \
  --group-id $SG_ID \
  --protocol tcp \
  --port 22 \
  --cidr 0.0.0.0/0 2>/dev/null || echo "Rule already exists"

# Step 3: Create user data script
echo -e "${YELLOW}Creating user data script...${NC}"

cat > user-data.sh << 'EOF'
#!/bin/bash
set -e

# Update system
apt-get update
apt-get install -y curl wget unzip git

# Install Docker
curl -fsSL https://get.docker.com -o get-docker.sh
sh get-docker.sh
usermod -aG docker ubuntu

# Install k3s
curl -sfL https://get.k3s.io | INSTALL_K3S_EXEC="--disable traefik --disable servicelb" sh -

# Wait for k3s to be ready
sleep 30

# Set up kubeconfig for ubuntu user
mkdir -p /home/ubuntu/.kube
cp /etc/rancher/k3s/k3s.yaml /home/ubuntu/.kube/config
chown ubuntu:ubuntu /home/ubuntu/.kube/config
chmod 600 /home/ubuntu/.kube/config

# Install kubectl
curl -LO "https://dl.k8s.io/release/$(curl -L -s https://dl.k8s.io/release/stable.txt)/bin/linux/amd64/kubectl"
install -o root -g root -m 0755 kubectl /usr/local/bin/kubectl

# Install helm
curl https://raw.githubusercontent.com/helm/helm/main/scripts/get-helm-3 | bash

echo "k3s installation completed"
EOF

# Step 4: Launch EC2 instance
echo -e "${YELLOW}Launching EC2 instance...${NC}"

# Get latest Ubuntu 22.04 AMI
AMI_ID=$(aws ec2 describe-images \
  --owners 099720109477 \
  --filters "Name=name,Values=ubuntu/images/hvm-ssd/ubuntu-jammy-22.04-amd64-server-*" \
  --query 'Images | sort_by(@, &CreationDate) | [-1].ImageId' \
  --output text)

echo "Using AMI: $AMI_ID"

INSTANCE_ID=$(aws ec2 run-instances \
  --image-id $AMI_ID \
  --instance-type $INSTANCE_TYPE \
  --key-name $KEY_PAIR_NAME \
  --security-group-ids $SG_ID \
  --iam-instance-profile Name=k3s-ec2-instance-profile \
  --user-data file://user-data.sh \
  --tag-specifications "ResourceType=instance,Tags=[{Key=Name,Value=$CLUSTER_NAME}]" \
  --query 'Instances[0].InstanceId' \
  --output text)

echo "Instance ID: $INSTANCE_ID"

# Wait for instance to be running
echo -e "${YELLOW}Waiting for instance to be running...${NC}"
aws ec2 wait instance-running --instance-ids $INSTANCE_ID

# Get instance public IP
PUBLIC_IP=$(aws ec2 describe-instances \
  --instance-ids $INSTANCE_ID \
  --query 'Reservations[0].Instances[0].PublicIpAddress' \
  --output text)

echo -e "${GREEN}Instance is running at: $PUBLIC_IP${NC}"

# Step 5: Wait for instance initialization
echo -e "${YELLOW}Waiting for instance initialization (this may take 5-10 minutes)...${NC}"
sleep 300

# Step 6: Create deployment script
cat > deploy-auth-proxy.sh << EOF
#!/bin/bash
set -e

echo "Deploying AWS EKS Auth Service Proxy..."

# Clone the repository
git clone https://github.com/plasticity-of-cloud/aws-eks-auth-service-proxy.git
cd aws-eks-auth-service-proxy

# Deploy the auth proxy and EKS Pod Identity Agent
./deploy.sh --cluster $CLUSTER_NAME --region $REGION

# Deploy EKS Pod Identity Agent DaemonSet
kubectl apply -f - << 'EOF'
apiVersion: apps/v1
kind: DaemonSet
metadata:
  name: eks-pod-identity-agent
  namespace: kube-system
spec:
  selector:
    matchLabels:
      app: eks-pod-identity-agent
  template:
    metadata:
      labels:
        app: eks-pod-identity-agent
    spec:
      hostNetwork: true
      serviceAccountName: eks-pod-identity-agent
      containers:
      - name: eks-pod-identity-agent
        image: amazon/amazon-eks-pod-identity-agent:latest
        ports:
        - containerPort: 80
          hostPort: 80
        env:
        - name: EKS_CLUSTER_NAME
          value: "$CLUSTER_NAME"
        - name: EKS_POD_IDENTITY_ASSOCIATION_ENDPOINT
          value: "http://eks-auth-proxy.kube-system:8080"
        - name: AWS_REGION
          value: "$REGION"
        securityContext:
          privileged: true
      tolerations:
      - operator: Exists
---
apiVersion: v1
kind: ServiceAccount
metadata:
  name: eks-pod-identity-agent
  namespace: kube-system
EOF

# Create a sample pod identity association
./eks-d-auth-cli/target/eks-d-auth-cli-*-runner create \\
  --cluster $CLUSTER_NAME \\
  --namespace default \\
  --service-account my-app \\
  --role-arn arn:aws:iam::$ACCOUNT_ID:role/k3s-pod-identity-app-role

echo "Deployment completed!"
EOF

chmod +x deploy-auth-proxy.sh

# Clean up temporary files
rm -f ec2-trust-policy.json ec2-instance-policy.json pod-identity-trust-policy.json user-data.sh

echo -e "${GREEN}Setup completed!${NC}"
echo ""
echo "Next steps:"
echo "1. SSH into the instance: ssh -i your-key.pem ubuntu@$PUBLIC_IP"
echo "2. Wait for k3s to be fully ready: sudo systemctl status k3s"
echo "3. Run the deployment script: ./deploy-auth-proxy.sh"
echo "4. Test the setup with: kubectl run test-pod --image=amazon/aws-cli:latest --rm -it -- aws sts get-caller-identity"
echo ""
echo "Instance details:"
echo "  Instance ID: $INSTANCE_ID"
echo "  Public IP: $PUBLIC_IP"
echo "  Security Group: $SG_ID"
EOF
```

## Manual Steps After Instance Launch

After the script completes, SSH into the instance and run:

```bash
# SSH into the instance
ssh -i your-key.pem ubuntu@<PUBLIC_IP>

# Check k3s status
sudo systemctl status k3s

# Check EKS Pod Identity Agent status
sudo systemctl status eks-pod-identity-agent

# Deploy the auth proxy (if not done automatically)
git clone https://github.com/plasticity-of-cloud/aws-eks-auth-service-proxy.git
cd aws-eks-auth-service-proxy
./deploy.sh --cluster k3s-cluster --region us-east-1

# Create pod identity association
./eks-d-auth-cli/target/eks-d-auth-cli-*-runner create \
  --cluster k3s-cluster \
  --namespace default \
  --service-account my-app \
  --role-arn arn:aws:iam::$(aws sts get-caller-identity --query Account --output text):role/k3s-pod-identity-app-role

# Test the setup
kubectl apply -f - << 'EOF'
apiVersion: v1
kind: ServiceAccount
metadata:
  name: my-app
  namespace: default
---
apiVersion: v1
kind: Pod
metadata:
  name: test-pod
  namespace: default
spec:
  serviceAccountName: my-app
  containers:
  - name: aws-cli
    image: amazon/aws-cli:latest
    command: ["sleep", "3600"]
EOF

# Verify AWS access
kubectl exec test-pod -- aws sts get-caller-identity
```

This should return the assumed role identity, confirming that EKS Pod Identity is working correctly.
