package ai.codriverlabs.eksdx.tenant.resource;

import ai.codriverlabs.eksdx.tenant.model.TenantProgress;
import ai.codriverlabs.eksdx.tenant.service.TenantProvisioningService;
import io.smallrye.mutiny.Multi;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import org.jboss.resteasy.reactive.RestStreamElementType;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * SSE progress stream — served via Lambda Function URL (RESPONSE_STREAM mode).
 *
 * Two-phase streaming:
 *   Phase 1 — EC2 boot: polls EC2 DescribeInstances until instance is running + has public IP.
 *             Emits events: "launching", "running", "public IP assigned", "provisioning starting".
 *   Phase 2 — DynamoDB: polls DynamoDB every 5s for boot script progress updates.
 *             Emits events until state == "ready" or "failed".
 *
 * Auth: AWS_IAM on the Function URL.
 */
@Path("/tenants")
@Produces(MediaType.APPLICATION_JSON)
public class TenantStreamResource {

    @Inject TenantProvisioningService provisioningService;

    @GET
    @Path("/{id}/stream")
    @Produces(MediaType.SERVER_SENT_EVENTS)
    @RestStreamElementType(MediaType.APPLICATION_JSON)
    public Multi<TenantProgress> streamProgress(@PathParam("id") String id) {
        AtomicBoolean emittedTerminal = new AtomicBoolean(false);

        // Phase 1: EC2 boot events (blocking poll, returns when instance is running)
        Multi<TenantProgress> ec2Phase = Multi.createFrom().items(
            provisioningService.pollEc2BootPhase(id).stream()
        );

        // Phase 2: DynamoDB polling every 5s, max 96 ticks (8 minutes)
        Multi<TenantProgress> dynamoPhase = Multi.createFrom().ticks().every(Duration.ofSeconds(5))
            .select().first(96)
            .map(tick -> provisioningService.getProgress(id))
            .select().first(p -> {
                if (emittedTerminal.get()) return false;
                boolean terminal = "ready".equals(p.state()) || "failed".equals(p.state());
                if (terminal) emittedTerminal.set(true);
                return true;
            });

        return Multi.createBy().concatenating().streams(ec2Phase, dynamoPhase);
    }
}
