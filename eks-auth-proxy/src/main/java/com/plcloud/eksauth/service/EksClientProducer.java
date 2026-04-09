package com.plcloud.eksauth.service;

import software.amazon.awssdk.services.eks.EksClient;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Singleton;

public class EksClientProducer {

    @Produces
    @Singleton
    public EksClient createEksClient() {
        return EksClient.builder().build();
    }
}
