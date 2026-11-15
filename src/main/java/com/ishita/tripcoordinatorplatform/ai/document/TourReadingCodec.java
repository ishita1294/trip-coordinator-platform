package com.ishita.tripcoordinatorplatform.ai.document;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.DatabindException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.MapperFeature;
import tools.jackson.databind.exc.InvalidFormatException;
import tools.jackson.databind.exc.InvalidNullException;
import tools.jackson.databind.exc.MismatchedInputException;
import tools.jackson.databind.exc.UnrecognizedPropertyException;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;

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
    private static final String REJECTED = "Invalid v3 semantic extraction response";

    private static <T> T read(String json, JsonMapper mapper, Class<T> type) {
        boolean binding = false;
        try {
            // Duplicate-tree-key detection applies to tree parsing, not direct record binding.
            var tree = mapper.reader().with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS,
                    DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY).readTree(json);
            if (type == TourReading.class && !TourReading.READABLE_PROMPT_VERSIONS.contains(tree.path("provenance").path("promptVersion").asText("")))
                throw new InvalidDocumentExtractionException("Semantic source/provenance mismatch");
            binding = true;
            var result = mapper.rebuild().disable(MapperFeature.ALLOW_COERCION_OF_SCALARS).build().readerFor(type)
                    .with(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES,
                            DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES,
                            DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES,
                            DeserializationFeature.FAIL_ON_TRAILING_TOKENS,
                            DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY)
                    .without(DeserializationFeature.ACCEPT_FLOAT_AS_INT).<T>readValue(tree.toString());
            if (result == null) throw new InvalidDocumentExtractionException("Semantic extraction is required");
            return result;
        } catch (DatabindException failure) {
            throw malformed(binding ? describe(failure) : "reply has duplicate keys or trailing content");
        } catch (JacksonException failure) {
            throw malformed("reply is not valid JSON (" + failure.getClass().getSimpleName() + ")");
        } catch (NullPointerException missing) {
            throw malformed("a required field is absent");
        }
    }

    /**
     * Messages carry fixed text, schema field names, array positions and counts only. Parser and binder messages are
     * never used because they quote the reply; a model-chosen field name is shown as "?".
     */
    private static String describe(DatabindException failure) {
        boolean unknown = failure instanceof UnrecognizedPropertyException;
        String where = path(failure.getPath(), unknown);
        if (unknown) return "unknown field at " + where;
        if (failure instanceof InvalidFormatException) return "value has an invalid format at " + where;
        if (failure instanceof InvalidNullException) return "null where a value is required at " + where;
        if (failure instanceof MismatchedInputException) return "value is missing or has the wrong type at " + where;
        return "value could not be constructed at " + where;
    }

    private static String path(List<JacksonException.Reference> references, boolean maskLast) {
        var path = new StringBuilder();
        for (int i = 0; i < references.size(); i++) {
            var reference = references.get(i);
            if (reference.getPropertyName() != null) {
                boolean masked = maskLast && i == references.size() - 1;
                String name = reference.getPropertyName();
                path.append(path.isEmpty() ? "" : ".").append(!masked && name.matches("[A-Za-z][A-Za-z0-9_]{0,39}") ? name : "?");
            } else if (reference.getIndex() >= 0) {
                path.append('[').append(reference.getIndex()).append(']');
            }
        }
        return path.isEmpty() ? "top level" : path.toString();
    }

    private static InvalidDocumentExtractionException malformed(String check) {
        return new InvalidDocumentExtractionException(InvalidDocumentExtractionException.Code.MALFORMED_JSON, REJECTED + ": " + check);
    }
}
