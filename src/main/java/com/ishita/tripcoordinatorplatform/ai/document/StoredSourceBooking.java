package com.ishita.tripcoordinatorplatform.ai.document;

import java.time.*;
import java.util.*;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Server-bound read-only envelope. Model facts retain their own contract, never converted to V3. */
public record StoredSourceBooking(int schemaVersion,String contract,String mode,Provenance provenance,SourceBookingExtraction facts) {
    public static final String MODE="READ_ONLY_EVALUATION";
    public record Provenance(String sourceSha256,UUID processingJobId,Instant uploadCompletedAt,Instant queuedAt,Instant readinessRecordedAt) {
        @com.fasterxml.jackson.annotation.JsonProperty(value="uploadToReadinessRecordedMs",access=com.fasterxml.jackson.annotation.JsonProperty.Access.READ_ONLY)
        public Long uploadToReadinessRecordedMs(){return uploadCompletedAt==null||readinessRecordedAt==null||readinessRecordedAt.isBefore(uploadCompletedAt)?null:Duration.between(uploadCompletedAt,readinessRecordedAt).toMillis();}
    }
    public static boolean isStored(JsonNode root){return root!=null&&root.path("schemaVersion").asInt()==4&&MODE.equals(root.path("mode").asText());}
    public static boolean isNativeVersion(JsonNode root){return root!=null&&root.path("schemaVersion").asInt()==4;}
    public static StoredSourceBooking create(String raw,JsonMapper mapper,DocumentAiInput original,UUID job,Instant uploaded,Instant queued,Instant recorded) {
        var read=new SourceBookingReader().read(raw,mapper,original);
        if(job==null||uploaded==null||queued==null||recorded==null)throw invalid();
        return new StoredSourceBooking(4,SourceBookingExtraction.CONTRACT,MODE,new Provenance(read.sourceSha256(),job,uploaded,queued,recorded),read.facts());
    }
    public static StoredSourceBooking read(String json,JsonMapper mapper,DocumentAiInput original) {
        try {
            var tree=mapper.reader().with(tools.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS,
                    tools.jackson.databind.DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY).readTree(json);
            var stored=mapper.readerFor(StoredSourceBooking.class).with(tools.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES,
                    tools.jackson.databind.DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES,tools.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS,
                    tools.jackson.databind.DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY).readValue(SourceBookingReader.withUnboundLegacySchedules(tree).toString());
            var value=(StoredSourceBooking)stored;
            if(value.schemaVersion()!=4||!MODE.equals(value.mode())||!SourceBookingExtraction.CONTRACT.equals(value.contract())||value.provenance()==null||value.facts()==null
                    ||value.provenance().processingJobId()==null||value.provenance().uploadCompletedAt()==null||value.provenance().queuedAt()==null||value.provenance().readinessRecordedAt()==null)throw invalid();
            var read=new SourceBookingReader().read(mapper.writeValueAsString(value.facts()),mapper,original);
            if(!Objects.equals(value.provenance().sourceSha256(),read.sourceSha256()))throw invalid();
            return value;
        }catch(tools.jackson.core.JacksonException|IllegalArgumentException malformed){throw invalid();}
    }
    public SourceBookingReview.Review review(JsonMapper mapper,DocumentAiInput original){return SourceBookingReview.read(mapper.writeValueAsString(facts),mapper,original);}
    private static InvalidDocumentExtractionException invalid(){return new InvalidDocumentExtractionException("Invalid source-bound native review envelope");}
}
