package com.ishita.tripcoordinatorplatform.config;


import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Profile;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.bedrockruntime.BedrockRuntimeClient;
import org.springframework.context.annotation.Configuration;

/**
 * Creates the AWS Bedrock Runtime client used by the application
 * to communicate with models available through Amazon Bedrock.
 */
@Configuration
@Profile("!document-worker")
public class BedrockConfig {
    @Bean
    public BedrockRuntimeClient bedrockRuntimeClient() {
        return BedrockRuntimeClient.builder()
                .region(Region.US_EAST_1)
                .credentialsProvider(DefaultCredentialsProvider.create())
                .build();
    }
}
