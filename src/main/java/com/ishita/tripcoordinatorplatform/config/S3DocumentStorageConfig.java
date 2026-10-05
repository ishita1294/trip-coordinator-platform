package com.ishita.tripcoordinatorplatform.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;

@Configuration
@ConditionalOnProperty(name = "app.document-storage.provider", havingValue = "s3")
public class S3DocumentStorageConfig {

    @Bean(destroyMethod = "close")
    public S3Client s3Client(
            @Value("${app.document-storage.s3.region:us-east-1}") String region
    ) {
        if (region == null || region.isBlank()) {
            throw new IllegalStateException("app.document-storage.s3.region is required when using S3 storage");
        }
        return S3Client.builder()
                .region(Region.of(region))
                .credentialsProvider(DefaultCredentialsProvider.builder().build())
                .build();
    }
}
