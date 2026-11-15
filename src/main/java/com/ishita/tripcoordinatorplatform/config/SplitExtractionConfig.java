package com.ishita.tripcoordinatorplatform.config;

import com.ishita.tripcoordinatorplatform.ai.document.AttractionTourDocumentExtractor;
import com.ishita.tripcoordinatorplatform.ai.document.SplitTourExtractionService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Per-booking parallel extraction. Without the flag no bean exists and processing is exactly the single call. */
@Configuration
@ConditionalOnProperty(name = "app.document-processing.split-extraction.enabled", havingValue = "true")
public class SplitExtractionConfig {

    @Bean
    public SplitTourExtractionService splitTourExtractionService(
            AttractionTourDocumentExtractor extractor,
            @Value("${app.ai.openai.extraction-model}") String model,
            @Value("${app.document-processing.split-extraction.max-parallel:4}") int maxParallel,
            @Value("${app.document-processing.split-extraction.max-bookings:6}") int maxBookings,
            @Value("${app.document-processing.split-extraction.min-usable-seconds:30}") int minUsableSeconds
    ) {
        return new SplitTourExtractionService(extractor, model, maxParallel, maxBookings, minUsableSeconds);
    }
}
