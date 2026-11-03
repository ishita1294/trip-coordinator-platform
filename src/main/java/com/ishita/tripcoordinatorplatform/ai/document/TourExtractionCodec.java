package com.ishita.tripcoordinatorplatform.ai.document;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Version boundary for immutable storage, not an adapter into legacy confirmation facts. */
public final class TourExtractionCodec {
    public enum Version {
        LEGACY, SEMANTIC_V3_4, AUTO_V3_4, FLIGHT_V1, UNSUPPORTED, SOURCE_BOOKING_V1_REVIEW;

        /** Jobs created before contract selection was added retain legacy behavior. */
        public static Version fromJobContract(String contract) {
            return contract == null ? LEGACY : valueOf(contract);
        }
    }
    public record Decoded(int schemaVersion, JsonNode original,
                          AttractionTourExtractionResult legacy, SemanticTourExtractionV3 semanticV3) {}
    private final JsonMapper mapper;
    public TourExtractionCodec(JsonMapper mapper) { this.mapper = mapper; }

    public static int schemaVersion(JsonNode root) {
        if (root == null || !root.isObject()) throw invalid();
        var version = root.get("schemaVersion");
        if (version == null) {
            // A damaged v2 envelope must never be treated as unversioned legacy data.
            if (root.has("provenance")) throw invalid();
            return 1;
        }
        if (!version.isIntegralNumber() || !version.canConvertToInt()
                || version.intValue() < 1 || version.intValue() == 2
                || version.intValue() > SemanticTourExtractionV3.SCHEMA_VERSION) throw invalid();
        return version.intValue();
    }

    public int schemaVersion(String json) { return schemaVersion(tree(json)); }

    public Decoded decode(String json) {
        var root = tree(json);
        int version = schemaVersion(root);
        try {
            if (version == 1) {
                var legacyView = root.deepCopy();
                ((tools.jackson.databind.node.ObjectNode) legacyView).remove("schemaVersion");
                AttractionTourExtractionResult legacy = mapper.readerFor(AttractionTourExtractionResult.class)
                        .with(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).readValue(legacyView.toString());
                return new Decoded(1, root, legacy, null);
            }
            if (version == 3) {
                var semantic = SemanticTourV3Codec.readStored(json, mapper,
                        root.path("provenance").path("sourceSha256").asString());
                return new Decoded(3, root, null, semantic);
            }
            throw invalid();
        } catch (JacksonException malformed) { throw invalid(); }
    }

    public static String sourceSha256(byte[] source) {
        try { return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(source)); }
        catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    private JsonNode tree(String json) {
        try {
            return mapper.reader().with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS,
                    DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY).readTree(json);
        } catch (JacksonException malformed) { throw invalid(); }
    }
    private static InvalidDocumentExtractionException invalid() {
        return new InvalidDocumentExtractionException("Invalid or unsupported tour extraction format");
    }
}
