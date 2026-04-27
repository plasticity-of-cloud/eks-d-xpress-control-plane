package cloud.plasticity.eksdx.lambda.resource;

import cloud.plasticity.eksdx.lambda.service.DynamoDbAssociationService;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

/**
 * Pod Identity Association CRUD — EKS API-compatible surface.
 */
@Path("/clusters/{clusterName}/pod-identity-associations")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class AssociationResource {

    @Inject DynamoDbAssociationService associationService;

    public static class CreateAssociationRequest {
        @JsonProperty("namespace") public String namespace;
        @JsonProperty("serviceAccount") public String serviceAccount;
        @JsonProperty("roleArn") public String roleArn;
    }

    @POST
    public Response createAssociation(
            @PathParam("clusterName") String clusterName,
            CreateAssociationRequest request) {
        // TODO: write to DynamoDB
        return Response.ok().build();
    }

    @GET
    public Response listAssociations(
            @PathParam("clusterName") String clusterName,
            @QueryParam("namespace") String namespace,
            @QueryParam("serviceAccount") String serviceAccount) {
        // TODO: query DynamoDB
        return Response.ok().build();
    }

    @GET
    @Path("/{associationId}")
    public Response describeAssociation(
            @PathParam("clusterName") String clusterName,
            @PathParam("associationId") String associationId) {
        // TODO: read from DynamoDB
        return Response.ok().build();
    }

    @DELETE
    @Path("/{associationId}")
    public Response deleteAssociation(
            @PathParam("clusterName") String clusterName,
            @PathParam("associationId") String associationId) {
        // TODO: delete from DynamoDB
        return Response.noContent().build();
    }
}
