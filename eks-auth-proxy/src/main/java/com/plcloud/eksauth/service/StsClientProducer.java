package com.plcloud.eksauth.service;

import software.amazon.awssdk.services.sts.StsClient;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Singleton;

public class StsClientProducer {
    
    @Produces
    @Singleton
    public StsClient createStsClient() {
        return StsClient.builder().build();
    }
}
