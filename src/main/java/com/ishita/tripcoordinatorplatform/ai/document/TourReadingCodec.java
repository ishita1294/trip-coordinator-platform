package com.ishita.tripcoordinatorplatform.ai.document;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.MapperFeature;
import tools.jackson.databind.json.JsonMapper;

/** Strict v3 reader for explicit review dispatch; production extraction/confirmation remain disabled. */
final class TourReadingCodec {
    private TourReadingCodec() {}
    static TourReading.ModelOutput readModel(String json, JsonMapper mapper) {
        return read(json, mapper, TourReading.ModelOutput.class);
    }
    static TourReading readStored(String json, JsonMapper mapper, String expectedSourceSha256) {
        var result = read(json, mapper, TourReading.class);
        new TourReadingValidator().validate(result, expectedSourceSha256);
        return result;
    }
    private static <T> T read(String json, JsonMapper mapper, Class<T> type) {
        try {
            // Duplicate-tree-key detection applies to tree parsing, not direct record binding.
            var tree = mapper.reader().with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS,
                    DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY).readTree(json);
            if (type == TourReading.class && !TourReading.READABLE_PROMPT_VERSIONS.contains(tree.path("provenance").path("promptVersion").asText("")))
                throw new InvalidDocumentExtractionException("Semantic source/provenance mismatch");
            var result = mapper.rebuild().disable(MapperFeature.ALLOW_COERCION_OF_SCALARS).build().readerFor(type)
                    .with(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES,
                            DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES,
                            DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES,
                            DeserializationFeature.FAIL_ON_TRAILING_TOKENS,
                            DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY)
                    .without(DeserializationFeature.ACCEPT_FLOAT_AS_INT).<T>readValue(tree.toString());
            if (result == null) throw new InvalidDocumentExtractionException("Semantic extraction is required");
            return result;
        } catch (JacksonException | NullPointerException malformed) {
            throw new InvalidDocumentExtractionException(InvalidDocumentExtractionException.Code.MALFORMED_JSON,
                    "Invalid v3 semantic extraction response");
        }
    }
}
