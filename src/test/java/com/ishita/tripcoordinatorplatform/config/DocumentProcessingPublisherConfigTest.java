package com.ishita.tripcoordinatorplatform.config;

import com.ishita.tripcoordinatorplatform.service.DocumentProcessingOutboxPublisher;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.BeanCreationException;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;
import software.amazon.awssdk.services.sqs.SqsClient;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class DocumentProcessingPublisherConfigTest {

    @Test
    void disabledByDefaultRequiresNeitherSqsConfigurationNorAwsCredentials() {
        try (var context = new AnnotationConfigApplicationContext()) {
            context.register(DocumentProcessingPublisherConfig.class, DocumentProcessingOutboxPublisher.class);

            context.refresh();

            assertTrue(context.getBeansOfType(SqsClient.class).isEmpty());
            assertTrue(context.getBeansOfType(DocumentProcessingOutboxPublisher.class).isEmpty());
            assertFalse(context.containsBean("org.springframework.context.annotation.internalScheduledAnnotationProcessor"));
        }
    }

    @Test
    void enabledPublisherRequiresQueueUrlBeforeBuildingAwsClient() {
        try (var context = new AnnotationConfigApplicationContext()) {
            context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("test",
                    Map.of("app.document-processing.publisher.enabled", "true")));
            context.register(DocumentProcessingPublisherConfig.class);

            var error = assertThrows(BeanCreationException.class, context::refresh);

            assertTrue(error.getMostSpecificCause().getMessage().contains(
                    "app.document-processing.sqs.queue-url is required when publisher is enabled"));
        }
    }
}
