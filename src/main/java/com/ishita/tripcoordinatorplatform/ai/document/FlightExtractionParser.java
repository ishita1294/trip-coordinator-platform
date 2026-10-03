package com.ishita.tripcoordinatorplatform.ai.document;


import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;
import org.springframework.stereotype.Component;

@Component
public class FlightExtractionParser {
    private final JsonMapper jsonMapper;

    public FlightExtractionParser(JsonMapper jsonMapper) {
        this.jsonMapper = jsonMapper;
    }

    public FlightExtractionResult parse(String rawExtraction) {

        if (rawExtraction == null || rawExtraction.isBlank()) {
            throw new IllegalArgumentException(
                    "Flight extraction response is empty"
            );
        }

        try {
            // Convert untrusted AI JSON into our defined Java structure
            // before any extracted values are validated or persisted.
            return jsonMapper.readValue(
                    rawExtraction,
                    FlightExtractionResult.class
            );

        } catch (JacksonException exception) {
            throw new IllegalArgumentException(
                    "Invalid flight extraction response",
                    exception
            );
        }
    }
}
