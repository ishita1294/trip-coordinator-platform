package com.ishita.tripcoordinatorplatform.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.sqs.SqsClient;

@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "app.document-processing.publisher.enabled", havingValue = "true")
public class DocumentProcessingPublisherConfig {

    @Bean(destroyMethod = "close")
    public SqsClient sqsClient(
            @Value("${app.document-processing.sqs.region:us-east-1}") String region,
            @Value("${app.document-processing.sqs.queue-url:}") String queueUrl
    ) {
        if (queueUrl == null || queueUrl.isBlank()) {
            throw new IllegalStateException("app.document-processing.sqs.queue-url is required when publisher is enabled");
        }
        if (region == null || region.isBlank()) {
            throw new IllegalStateException("app.document-processing.sqs.region is required when publisher is enabled");
        }
        return SqsClient.builder()
                .region(Region.of(region))
                .credentialsProvider(DefaultCredentialsProvider.builder().build())
                .build();
    }
}
