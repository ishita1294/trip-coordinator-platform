package com.ishita.tripcoordinatorplatform.ai.document;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;
import tools.jackson.databind.DeserializationFeature;
import java.util.*;

/** Model-only representation. Expansion never selects facts, changes arithmetic, or repairs evidence. */
final class CompactSemanticTourV3 {
    static final String PROMPT_VERSION = SemanticTourExtractionV3.COMPACT_PROMPT_VERSION;
    static final String OUTPUT_NAME = "tour_semantic_v3_4_compact_1";
    static final String INSTRUCTIONS = SemanticTourSchemaV3.INSTRUCTIONS.replace(
            "Return schemaVersion 3, bookings, supplementalFacts and supplementalCoverageWarning\nonly; the server assigns trusted provenance (contract tour-semantic-3.4).",
            "The backend constructs schemaVersion 3 and trusted provenance.") + """
            
            Return only the compact wire schema, wireVersion 1. The preceding instructions describe
            the full semantic meaning; the following mapping specifies its response representation.
            Keep all bookings, typed values, calculated results, participants, policies, instructions,
            alternatives, uncertainty, lifecycle and replacement decisions. Do not omit material facts.
            sources is a table of exact quotation/reportedPage pairs. evidence is a table of source
            indices, bookingIndex and concise bookingAssociation. Each fact's evidence array contains
            zero-based indices into evidence. Share a row only when every component is identical;
            identical text on different pages or for different bookings is not the same evidence.
            Every source/evidence row must be referenced. No guessed indices, dangling references,
            concatenated quotations, unused material information or invented booking associations.
            A CLEAR fact uses kind EXPLICIT, CONTEXT_INTERPRETED or CALCULATED and retains its typed
            value. An uncertain fact uses kind AMBIGUOUS_EXPLICIT, AMBIGUOUS_CONTEXT_INTERPRETED,
            AMBIGUOUS_NOT_ESTABLISHED, CONFLICTING_EXPLICIT, CONFLICTING_CONTEXT_INTERPRETED or
            CONFLICTING_NOT_ESTABLISHED and puts the typed alternatives array in value, never a
            selected value.
            All facts have the same six fields: kind, value, uncertainty, sourceExpressions,
            evidence and interpretation. Both retain uncertainty, sourceExpressions, evidence indices and interpretation. Here
            interpretation contains dependencies and calculation, without basis (encoded in kind).
            Null interpretation means empty dependencies and no calculation; never omit a real
            dependency or calculation. CALCULATED still supplies its result and complete calculation;
            Java verifies them. MISSING has null value/interpretation and empty evidence/sourceExpressions;
            it asserts no value, alternatives, evidence, expressions, dependencies or calculation. Do not use MISSING to
            hide partial or contradictory information. Nothing defaults to CLEAR or ACTIVE.
            Supplemental detail uses type, value and unit. Value retains its original domain type;
            unit is source-established for QUANTITY and null otherwise. Java fills only inactive
            value slots with null. Do not change condition kinds to populate restricted numeric fields.
            Return bookings and supplementalFacts with their existing associations and ordering, and
            supplementalCoverageWarning when material coverage is incomplete. The backend expands
            structural defaults and references only, then applies unchanged semantic and quotation checks.
            Existing supplemental and storage limits apply to the expanded full representation, not
            only the compact wire bytes; leave headroom rather than filling a compact list to its limit.
            """;
    private static final Set<String> FACT = Set.of("status", "value", "alternatives", "evidence",
            "sourceExpressions", "uncertainty", "interpretation");
    private static final Set<String> CLEAR = Set.of("EXPLICIT", "CONTEXT_INTERPRETED", "CALCULATED");
    private static final Set<String> UNRESOLVED = Set.of("AMBIGUOUS_EXPLICIT", "AMBIGUOUS_CONTEXT_INTERPRETED",
            "AMBIGUOUS_NOT_ESTABLISHED", "CONFLICTING_EXPLICIT", "CONFLICTING_CONTEXT_INTERPRETED", "CONFLICTING_NOT_ESTABLISHED");
    private CompactSemanticTourV3() {}

    @SuppressWarnings("unchecked")
    static Map<String, Object> schema() {
        var root = new LinkedHashMap<>(SemanticTourSchemaV3.schema());
        var defs = new LinkedHashMap<>((Map<String, Object>) root.get("$defs"));
        var id = Map.<String, Object>of("type", "integer", "minimum", 0);
        for (var entry : new ArrayList<>(defs.entrySet())) {
            var definition = (Map<String, Object>) entry.getValue();
            var props = (Map<String, Object>) definition.get("properties");
            if (props == null || !props.keySet().equals(FACT)) continue;
            var reference = (String) ((Map<String, Object>) props.get("interpretation")).get("$ref");
            var original = (Map<String, Object>) ((Map<String, Object>) defs.get(reference.substring(8))).get("properties");
            var meta = nullable(object(Map.of("dependencies", original.get("dependencies"), "calculation", original.get("calculation"))));
            var common = new LinkedHashMap<String, Object>();
            common.put("uncertainty", props.get("uncertainty"));
            var ids = new LinkedHashMap<>((Map<String, Object>) props.get("evidence")); ids.put("items", id);
            common.put("evidence", ids); common.put("sourceExpressions", props.get("sourceExpressions")); common.put("interpretation", meta);
            boolean arithmetic = !"null".equals(((Map<String, Object>) original.get("calculation")).get("type"));
            var kinds = new ArrayList<>(UNRESOLVED.stream().sorted().toList());
            kinds.addAll(arithmetic ? List.of("EXPLICIT", "CONTEXT_INTERPRETED", "CALCULATED", "MISSING")
                    : List.of("EXPLICIT", "CONTEXT_INTERPRETED", "MISSING"));
            if (entry.getKey().equals("assertionFact")) kinds.remove("MISSING"); // Absent supplemental facts are not supported.
            common.put("kind", enumeration(kinds));
            common.put("value", Map.of("anyOf", List.of(props.get("value"), props.get("alternatives"))));
            defs.put(entry.getKey(), object(common));
        }
        defs.put("detail", object(Map.of("type", enumeration(List.of("TEXT", "BOOLEAN", "QUANTITY", "DATE", "LOCAL_DATE_TIME")),
                "value", Map.of("anyOf", List.of(Map.of("type", "string"), Map.of("type", "boolean"), Map.of("type", "number"),
                        object(Map.of("date", Map.of("type", "string"), "time", Map.of("type", "string"))))),
                "unit", nullable(Map.of("type", "string")))));
        var properties = new LinkedHashMap<>((Map<String, Object>) root.get("properties")); properties.remove("schemaVersion");
        properties.put("wireVersion", Map.of("type", "integer", "enum", List.of(1)));
        properties.put("sources", array(object(Map.of("quotation", Map.of("type", "string"), "reportedPage", nullable(Map.of("type", "integer", "minimum", 1))))));
        properties.put("evidence", array(object(Map.of("source", id, "bookingIndex", nullable(id), "bookingAssociation", Map.of("type", "string")))));
        root.put("properties", properties); root.put("required", List.copyOf(properties.keySet())); root.put("$defs", defs);
        // Discard only unreachable definitions, not model facts. This avoids the old prototype's schema growth.
        var reachable = new HashSet<String>(); collectReferences(properties, defs, reachable);
        defs.keySet().retainAll(reachable);
        return root;
    }
    private static void collectReferences(Object value, Map<String, Object> defs, Set<String> seen) {
        if (value instanceof Map<?, ?> map) {
            if (map.get("$ref") instanceof String ref && seen.add(ref.substring(8))) collectReferences(defs.get(ref.substring(8)), defs, seen);
            map.values().forEach(v -> collectReferences(v, defs, seen));
        } else if (value instanceof List<?> list) list.forEach(v -> collectReferences(v, defs, seen));
    }
    private static Map<String, Object> object(Map<String, ?> props) { return Map.of("type", "object", "properties", props, "required", List.copyOf(props.keySet()), "additionalProperties", false); }
    private static Map<String, Object> array(Object item) { return Map.of("type", "array", "items", item); }
    private static Map<String, Object> nullable(Object item) { return Map.of("anyOf", List.of(item, Map.of("type", "null"))); }
    private static Map<String, Object> enumeration(List<String> items) { return Map.of("type", "string", "enum", items); }

    static SemanticTourExtractionV3.ModelOutput readModel(String text, JsonMapper mapper) {
        try {
            need(text != null && text.length() <= 100_000);
            JsonNode wire = mapper.reader().with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS,
                    DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY).readTree(text);
            keys(wire, Set.of("wireVersion", "sources", "evidence", "bookings", "supplementalFacts", "supplementalCoverageWarning"));
            need(wire.path("wireVersion").isIntegralNumber() && wire.path("wireVersion").canConvertToInt()
                    && wire.path("wireVersion").asInt() == 1);
            var expansion = new Expansion(wire, mapper);
            var result = mapper.createObjectNode().put("schemaVersion", 3);
            result.set("bookings", expansion.expand(wire.get("bookings")));
            result.set("supplementalFacts", expansion.expand(wire.get("supplementalFacts")));
            result.set("supplementalCoverageWarning", wire.get("supplementalCoverageWarning"));
            need(expansion.usedEvidence.cardinality() == wire.get("evidence").size() && expansion.usedSources.cardinality() == wire.get("sources").size());
            String full = mapper.writeValueAsString(result); need(full.length() <= 100_000);
            return SemanticTourV3Codec.readModel(full, mapper);
        } catch (tools.jackson.core.JacksonException | NullPointerException | ClassCastException malformed) {
            throw new InvalidDocumentExtractionException(InvalidDocumentExtractionException.Code.MALFORMED_JSON, "Invalid compact semantic response");
        }
    }
    private static final class Expansion {
        final JsonNode sources, evidence;
        final BitSet usedSources = new BitSet(), usedEvidence = new BitSet();
        final JsonMapper mapper;
        long expandedEvidenceCharacters;
        Expansion(JsonNode wire, JsonMapper mapper) {
            this.mapper = mapper; sources = wire.get("sources"); evidence = wire.get("evidence"); need(sources.isArray() && evidence.isArray());
            for (var source : sources) { keys(source, Set.of("quotation", "reportedPage")); need(source.get("quotation").isString()); }
            for (var row : evidence) keys(row, Set.of("source", "bookingIndex", "bookingAssociation"));
        }
        JsonNode expand(JsonNode node) {
            if (node.isObject() && node.has("kind") && node.has("uncertainty")) return fact(node);
            if (node.isObject() && node.has("type") && node.has("value")) return detail(node);
            need(!(node.isObject() && node.has("status") && node.has("interpretation")));
            if (node.isObject()) { var out = mapper.createObjectNode(); node.properties().forEach(p -> out.set(p.getKey(), expand(p.getValue()))); return out; }
            if (node.isArray()) { var out = mapper.createArrayNode(); node.forEach(v -> out.add(expand(v))); return out; }
            return node.deepCopy();
        }
        JsonNode fact(JsonNode node) {
            need(node.get("kind").isString()); String kind = node.get("kind").asString();
            boolean missing = kind.equals("MISSING"), clear = CLEAR.contains(kind); need(missing || clear || UNRESOLVED.contains(kind));
            keys(node, Set.of("kind", "uncertainty", "value", "evidence", "sourceExpressions", "interpretation"));
            if (missing) need(node.get("value").isNull() && node.get("evidence").isArray() && node.get("evidence").isEmpty()
                    && node.get("sourceExpressions").isArray() && node.get("sourceExpressions").isEmpty() && node.get("interpretation").isNull());
            if (!missing && !clear) need(node.get("value").isArray());
            String status = missing ? "MISSING" : clear ? "CLEAR" : kind.substring(0, kind.indexOf('_'));
            String basis = missing ? "NOT_ESTABLISHED" : clear ? kind : kind.substring(kind.indexOf('_') + 1);
            var out = mapper.createObjectNode().put("status", status); out.set("uncertainty", node.get("uncertainty"));
            if (clear) out.set("value", expand(node.get("value"))); else out.putNull("value");
            out.set("alternatives", !missing && !clear ? expand(node.get("value")) : mapper.createArrayNode());
            var refs = out.putArray("evidence");
            if (!missing) {
                need(node.get("evidence").isArray());
                for (var id : node.get("evidence")) {
                    int index = index(id, evidence.size()); usedEvidence.set(index); var row = evidence.get(index);
                    int source = index(row.get("source"), sources.size()); usedSources.set(source);
                    var expanded = mapper.createObjectNode(); expanded.set("bookingIndex", row.get("bookingIndex")); expanded.set("bookingAssociation", row.get("bookingAssociation"));
                    expanded.set("quotation", sources.get(source).get("quotation")); expanded.set("reportedPage", sources.get(source).get("reportedPage"));
                    // Bound reference expansion before serializing the full result, including repeated IDs.
                    expandedEvidenceCharacters += mapper.writeValueAsString(expanded).length();
                    need(expandedEvidenceCharacters <= 100_000); refs.add(expanded);
                }
            }
            out.set("sourceExpressions", missing ? mapper.createArrayNode() : node.get("sourceExpressions"));
            var interpretation = out.putObject("interpretation").put("basis", basis);
            var supplied = missing ? null : node.get("interpretation");
            if (supplied == null || supplied.isNull()) { interpretation.putArray("dependencies"); interpretation.putNull("calculation"); }
            else { keys(supplied, Set.of("dependencies", "calculation")); interpretation.set("dependencies", supplied.get("dependencies")); interpretation.set("calculation", supplied.get("calculation")); }
            return out;
        }
        JsonNode detail(JsonNode node) {
            keys(node, Set.of("type", "value", "unit")); need(node.get("type").isString());
            String type = node.get("type").asString(); String slot = switch (type) { case "TEXT" -> "text"; case "BOOLEAN" -> "booleanValue"; case "QUANTITY" -> "quantity"; case "DATE" -> "date"; case "LOCAL_DATE_TIME" -> "localDateTime"; default -> throw invalid(); };
            need(type.equals("QUANTITY") || node.get("unit").isNull());
            var out = mapper.createObjectNode().put("type", type);
            for (var name : List.of("text", "booleanValue", "quantity", "date", "localDateTime")) out.putNull(name);
            out.set(slot, node.get("value")); out.set("unit", node.get("unit")); return out;
        }
    }
    private static int index(JsonNode node, int size) { need(node != null && node.isIntegralNumber() && node.canConvertToInt()); int i = node.asInt(); need(i >= 0 && i < size); return i; }
    private static void keys(JsonNode node, Set<String> expected) { need(node != null && node.isObject()); var actual = new HashSet<String>(); node.properties().forEach(p -> actual.add(p.getKey())); need(actual.equals(expected)); }
    private static void need(boolean condition) { if (!condition) throw invalid(); }
    private static InvalidDocumentExtractionException invalid() { return new InvalidDocumentExtractionException(InvalidDocumentExtractionException.Code.MALFORMED_JSON, "Invalid compact semantic response"); }
}
