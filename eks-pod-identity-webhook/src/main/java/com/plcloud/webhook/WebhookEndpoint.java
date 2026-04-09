package com.plcloud.webhook;

import io.fabric8.kubernetes.api.model.admission.v1.AdmissionReview;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

@Path("/mutate")
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
public class WebhookEndpoint {

    @Inject
    PodIdentityMutator mutator;

    @POST
    public AdmissionReview mutate(AdmissionReview admissionReview) {
        return mutator.handle(admissionReview);
    }
}
