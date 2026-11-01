package com.ishita.tripcoordinatorplatform.ai.document;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.MapperFeature;
import tools.jackson.databind.json.JsonMapper;

/** Strict v3 reader for explicit review dispatch; production extraction/confirmation remain disabled. */
final class SemanticTourV3Codec {
    private SemanticTourV3Codec() {}
    static SemanticTourExtractionV3.ModelOutput readModel(String json, JsonMapper mapper) {
        return read(json, mapper, SemanticTourExtractionV3.ModelOutput.class);
    }
    static SemanticTourExtractionV3 readStored(String json, JsonMapper mapper, String expectedSourceSha256) {
        var result = read(json, mapper, SemanticTourExtractionV3.class);
        new SemanticTourExtractionV3Validator().validate(result, expectedSourceSha256);
        return result;
    }
    private static <T> T read(String json, JsonMapper mapper, Class<T> type) {
        try {
            // Duplicate-tree-key detection applies to tree parsing, not direct record binding.
            var tree = mapper.reader().with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS,
                    DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY).readTree(json);
            if (type == SemanticTourExtractionV3.class && !SemanticTourExtractionV3.READABLE_PROMPT_VERSIONS.contains(tree.path("provenance").path("promptVersion").asText("")))
                throw new InvalidDocumentExtractionException("Semantic source/provenance mismatch");
            // Historical 3.1 lacked this field. Adapt only the in-memory reader tree, never stored JSON.
            if (type == SemanticTourExtractionV3.class && tree.path("provenance").path("promptVersion").asText("")
                    .equals(SemanticTourExtractionV3.LEGACY_PROMPT_VERSION)) {
                for (var b : tree.path("bookings")) if (!b.has("temporalConstraints"))
                    ((tools.jackson.databind.node.ObjectNode)b).putArray("temporalConstraints");
            }
            if (type == SemanticTourExtractionV3.class && !SemanticTourExtractionV3.isV34Contract(tree.path("provenance").path("promptVersion").asText(""))) {
                for (var node : tree.path("bookings")) {
                    var b=(tools.jackson.databind.node.ObjectNode)node;
                    if(!b.has("lifecycle"))b.set("lifecycle",mapper.valueToTree(SemanticBookingLifecycleV3.unestablished()));
                    if(!b.has("replaces"))b.putArray("replaces");
                }
            }
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
