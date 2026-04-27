package cloud.plasticity.eksdx.lambda.resource;

import cloud.plasticity.eksdx.lambda.service.DynamoDbClusterService;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.List;
import java.util.Map;

/**
 * Cluster registration and management API.
 */
@Path("/clusters")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class ClusterResource {

    @Inject DynamoDbClusterService clusterService;

    public static class RegisterClusterRequest {
        @JsonProperty("name") public String name;
        @JsonProperty("issuer") public String issuer;
        @JsonProperty("jwks") public String jwks;
    }

    @POST
    public Response registerCluster(RegisterClusterRequest request) {
        // TODO: validate, store in DynamoDB
        return Response.ok().build();
    }

    @GET
    @Path("/{name}")
    public Response describeCluster(@PathParam("name") String name) {
        // TODO: read from DynamoDB
        return Response.ok().build();
    }

    @GET
    public Response listClusters() {
        // TODO: scan DynamoDB
        return Response.ok().build();
    }

    @PUT
    @Path("/{name}/jwks")
    public Response refreshJwks(@PathParam("name") String name, Map<String, Object> jwks) {
        // TODO: update JWKS in DynamoDB
        return Response.ok().build();
    }

    @DELETE
    @Path("/{name}")
    public Response deregisterCluster(@PathParam("name") String name) {
        // TODO: delete from DynamoDB
        return Response.noContent().build();
    }
}
