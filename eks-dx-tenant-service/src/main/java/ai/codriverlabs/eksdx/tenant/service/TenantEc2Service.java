package ai.codriverlabs.eksdx.tenant.service;

import jakarta.enterprise.context.ApplicationScoped;
import org.jboss.logging.Logger;
import software.amazon.awssdk.services.ec2.Ec2Client;
import software.amazon.awssdk.services.ec2.model.BlockDeviceMapping;
import software.amazon.awssdk.services.ec2.model.EbsBlockDevice;
import software.amazon.awssdk.services.ec2.model.AllocateAddressRequest;
import software.amazon.awssdk.services.ec2.model.AssociateAddressRequest;
import software.amazon.awssdk.services.ec2.model.IamInstanceProfileSpecification;
import software.amazon.awssdk.services.ec2.model.LaunchTemplateSpecification;
import software.amazon.awssdk.services.ec2.model.ResourceType;
import software.amazon.awssdk.services.ec2.model.RunInstancesRequest;
import software.amazon.awssdk.services.ec2.model.RunInstancesResponse;
import software.amazon.awssdk.services.ec2.model.Tag;
import software.amazon.awssdk.services.ec2.model.TagSpecification;
import software.amazon.awssdk.services.ssm.SsmClient;
import software.amazon.awssdk.services.ssm.model.GetParameterRequest;

import java.util.Base64;

/**
 * Launches the tenant EC2 instance with user data, tags, and optional Elastic IP.
 */
@ApplicationScoped
public class TenantEc2Service {

    private static final Logger LOG = Logger.getLogger(TenantEc2Service.class);

    private final Ec2Client ec2 = Ec2Client.create();
    private final SsmClient ssm = SsmClient.create();

    public record Ec2Result(String instanceId, String elasticIp) {}

    public Ec2Result launchInstance(String tenantId, String clusterName, String launchTemplateId,
                                   String subnetId, String securityGroupId, String instanceProfileName,
                                   String keyName, String region, String k8sVersion,
                                   boolean assignElasticIp, int diskSizeGb, String arch) {

        String amiId = ssm.getParameter(GetParameterRequest.builder()
            .name("/eks-d-xpress/infra/ami/" + arch + "/" + k8sVersion)
            .build()).parameter().value();

        String userData = Base64.getEncoder().encodeToString(userDataScript(
            tenantId, clusterName, region, k8sVersion).getBytes());

        RunInstancesResponse runResp = ec2.runInstances(RunInstancesRequest.builder()
            .imageId(amiId)
            .launchTemplate(LaunchTemplateSpecification.builder()
                .launchTemplateId(launchTemplateId).build())
            .subnetId(subnetId)
            .securityGroupIds(securityGroupId)
            .keyName(keyName)
            .iamInstanceProfile(IamInstanceProfileSpecification.builder()
                .name(instanceProfileName).build())
            .userData(userData)
            .blockDeviceMappings(BlockDeviceMapping.builder()
                .deviceName("/dev/xvda")
                .ebs(EbsBlockDevice.builder().volumeSize(diskSizeGb).build())
                .build())
            .minCount(1).maxCount(1)
            .tagSpecifications(TagSpecification.builder()
                .resourceType(ResourceType.INSTANCE)
                .tags(
                    Tag.builder().key("Name").value(clusterName).build(),
                    Tag.builder().key("eks-d-xpress-tenant").value(tenantId).build(),
                    Tag.builder().key("kubernetes.io/cluster/" + clusterName).value("owned").build(),
                    Tag.builder().key("ebs.csi.aws.com/cluster-name").value(clusterName).build(),
                    Tag.builder().key("Platform").value("eks-d-xpress").build())
                .build())
            .build());

        String instanceId = runResp.instances().getFirst().instanceId();
        LOG.infof("Launched EC2 instance %s for tenant %s", instanceId, tenantId);

        String elasticIp = null;
        if (assignElasticIp) {
            var allocResp = ec2.allocateAddress(AllocateAddressRequest.builder()
                .domain("vpc")
                .tagSpecifications(TagSpecification.builder()
                    .resourceType(ResourceType.ELASTIC_IP)
                    .tags(Tag.builder().key("Name").value(clusterName).build(),
                          Tag.builder().key("eks-d-xpress-tenant").value(tenantId).build())
                    .build())
                .build());
            ec2.associateAddress(AssociateAddressRequest.builder()
                .instanceId(instanceId)
                .allocationId(allocResp.allocationId())
                .build());
            elasticIp = allocResp.publicIp();
            LOG.infof("Assigned Elastic IP %s to tenant %s", elasticIp, tenantId);
        }

        return new Ec2Result(instanceId, elasticIp);
    }

    private String userDataScript(String tenantId, String clusterName,
                                  String region, String k8sVersion) {
        return """
            #!/bin/bash
            mkdir -p /opt/eks-d
            EKS_DX_ENDPOINT=$(aws ssm get-parameter \
              --name /eks-d-xpress/control-plane/api/endpoint \
              --region %s \
              --query Parameter.Value \
              --output text)
            cat > /opt/eks-d/cluster.env <<CONF
            TENANT_ID="%s"
            CLUSTER_NAME="%s"
            EKS_DX_ENDPOINT="${EKS_DX_ENDPOINT}"
            EKS_DX_API_URL="${EKS_DX_ENDPOINT}/clusters/%s/assets"
            REGION="%s"
            K8S_VERSION="%s"
            CONF
            """.formatted(region, tenantId, clusterName, clusterName, region, k8sVersion);
    }
}
