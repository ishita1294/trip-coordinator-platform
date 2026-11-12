package com.ishita.tripcoordinatorplatform.service;

import com.ishita.tripcoordinatorplatform.ai.document.*;
import com.ishita.tripcoordinatorplatform.intelligence.commitment.ConfirmedArrivalEvidence;
import com.ishita.tripcoordinatorplatform.model.Location;
import com.ishita.tripcoordinatorplatform.request.TourCorrectionRequest.Change;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;
import java.time.*;
import java.util.*;

/** One effective representation, shared by source assessment and authoritative confirmation. */
@Component
public class TourConfirmationPolicy {
    static final Set<String> FIELDS = Set.of("name", "provider", "bookingReference", "startDate", "startTime", "endDate", "endTime",
            "durationMinutes", "timezone", "meetingPoint", "endingPoint", "endingLocationChoice", "arrivalLeadMinutes",
            "requiredArrivalLocal", "lifecycle");
    private final JsonMapper mapper;
    private final boolean readingWorkflowEnabled;

    @org.springframework.beans.factory.annotation.Autowired
    public TourConfirmationPolicy(JsonMapper mapper, org.springframework.core.env.Environment environment) {
        this(mapper, environment.getProperty("app.document-intelligence.v3.workflow-enabled", Boolean.class, false));
    }

    // Direct construction stays disabled unless a test explicitly enables a semantic workflow.
    public TourConfirmationPolicy(JsonMapper mapper) { this(mapper, false); }

    // Shared initialization for configured V3 operation and explicit test wiring.
    TourConfirmationPolicy(JsonMapper mapper, boolean v3Enabled) {
        this.mapper = mapper;
        this.readingWorkflowEnabled = v3Enabled;
    }

    boolean readingWorkflowEnabled() { return readingWorkflowEnabled; }

    /** Refuses to continue if the stored reading was made from a different file than the one now on record (checked by file hash). */
    public void requireMatchingSource(String json, String actualSha256) {
        var decoded = new TourExtractionCodec(mapper).decode(json);
        if (decoded.reading() != null && !decoded.reading().provenance().sourceSha256().equals(actualSha256))
            throw TourCorrectionException.stale("Original source no longer matches the semantic extraction.");
    }

    /**
     * Refuses corrections and confirmation for read-only evaluation documents, and for readings whose confirmation workflow is not
     * switched on.
     */
    public void requireLegacyExtraction(String json) {
        if (com.ishita.tripcoordinatorplatform.ai.document.StoredSourceBooking.isNativeVersion(mapper.readTree(json)))
            throw TourCorrectionException.validation("READ_ONLY_EVALUATION",
                    "Native evaluation documents do not support correction or confirmation.");

        try {
            int version = new TourExtractionCodec(mapper).schemaVersion(json);
            if (version == 3 && !readingWorkflowEnabled)
                throw invalid("SEMANTIC_CONFIRMATION_NOT_ENABLED",
                        "Semantic extraction is available for read-only review; confirmation and corrections are not enabled.");
        } catch (InvalidDocumentExtractionException malformed) {
            throw invalid("SOURCE_PROPOSAL_INVALID", "The stored proposal has an invalid or unsupported format.");
        }
    }

    /**
     * Allows confirmation only when the reading has established active bookings; provisional or unresolved entries, or documents with only
     * historical bookings, are refused.
     */
    public void requireLifecycleConfirmation(String json) {
        TourReading v3;
        try {
            v3 = new TourExtractionCodec(mapper).decode(json).reading();
        } catch (InvalidDocumentExtractionException malformed) {
            throw invalid("SOURCE_PROPOSAL_INVALID", "The stored proposal has an invalid or unsupported format.");
        }
        if (v3 == null)
            return;
        var selection = BookingEligibility.assess(v3);
        if (!selection.confirmationAllowed())
            throw invalid(selection.blockCode(),
                    "Confirm only established active bookings. Provisional or unresolved entries keep the entire document in review; "
                            + "historical-only documents create no commitments.");
    }

    /** Turns a stored extraction into the editable set of booking facts that corrections and confirmation work on. */
    public ObjectNode facts(String json) {
        requireLegacyExtraction(json);
        var decoded = new TourExtractionCodec(mapper).decode(json);
        if (decoded.reading() != null) {
            var root = mapper.createObjectNode();
            var bookings = root.putArray("bookings");
            root.set("semanticOriginal", decoded.original());
            var v3 = decoded.reading();
            var checks = new TourReadingValidator().validate(v3, v3.provenance().sourceSha256());
            List<BookingProposal> views = v3.bookings().stream().map(b -> BookingProposal.fromReading(b, checks.calculations())).toList();
            for (int index = 0; index < views.size(); index++) {
                var view = views.get(index);
                ObjectNode booking = mapper.valueToTree(view.booking());
                booking.put("requiredArrivalLocal", view.requiredArrivalLocal() == null ? null : view.requiredArrivalLocal().toString());
                booking.put("endingLocationChoice", view.endingLocationChoice());
                if (decoded.reading() != null)
                    booking.set("lifecycle", mapper.valueToTree(decoded.reading().bookings().get(index).lifecycle()));
                bookings.add(booking);
            }
            return root;
        }
        final ObjectNode facts;
        try {
            facts = (ObjectNode) mapper.readTree(json).deepCopy();
        } catch (JacksonException invalid) {
            throw invalid("SOURCE_PROPOSAL_INVALID", "The stored proposal cannot be reviewed.");
        }
        facts.remove("schemaVersion"); // Metadata is excluded only from this effective view, never stored JSON.
        var reviews = ConfirmedArrivalEvidence.review(json);
        for (var review : reviews) {
            var booking = (ObjectNode) facts.path("bookings").get(review.bookingIndex());
            booking.put("requiredArrivalLocal", review.requiredArrivalLocal() == null ? null : review.requiredArrivalLocal().toString());
            booking.put("endingLocationChoice", initialEndpointChoice(booking));
        }
        return facts;
    }

    /** Initial documentary decision only, never geographic verification or an existing-booking rewrite. */
    public static String initialEndpointChoice(JsonNode booking) {
        var meeting = booking.path("meetingPoint");
        var ending = booking.path("endingPoint");
        String name = description(ending, "name"), address = description(ending, "address");
        if (name == null && address == null)
            return "UNKNOWN";
        String meetingName = description(meeting, "name"), meetingAddress = description(meeting, "address");
        boolean sameName = name != null && name.equals(meetingName), sameAddress = address != null && address.equals(meetingAddress);
        // Exact complete source descriptions (including any named entrance), not fuzzy venue matching.
        // Coordinates, casing/alias guesses and provider identities do not establish documentary intent.
        if (sameName && sameAddress)
            return "SAME_AS_MEETING_POINT";
        if (sameName || sameAddress && (name == null || meetingName == null))
            return "UNKNOWN"; // Partial co-reference cannot establish the exact endpoint.
        return "LOCATION"; // Independently documented ending description; no geographic readiness implied.
    }

    /** Returns the text of a location field, or null when it is missing or blank. */
    private static String description(JsonNode location, String field) {
        var value = location.path(field);
        return value.isTextual() && !value.asText().isBlank() ? value.asText() : null;
    }

    /** Applies the user's corrections to the stored facts and returns the proposed result, without saving anything. */
    public ObjectNode proposed(String original, List<Change> changes) {
        return proposed(original, changes, false);
    }

    /** Same as proposed, but an empty list of corrections returns the facts unchanged and there is no 32-correction limit. */
    ObjectNode proposedCombined(String original, List<Change> changes) {
        return changes.isEmpty() ? facts(original) : proposed(original, changes, true);
    }

    /** Applies each correction to a copy of the facts; unless combined, the list must hold 1 to 32 corrections. */
    private ObjectNode proposed(String original, List<Change> changes, boolean combined) {
        if (changes == null || changes.isEmpty() || !combined && changes.size() > 32)
            throw TourCorrectionException.invalid("Supply 1–32 explicit material-field corrections.");
        var facts = facts(original);
        var seen = new HashSet<String>();
        var v3 = facts.has("semanticOriginal") && facts.path("semanticOriginal").path("schemaVersion").asInt() == 3
                ? new TourExtractionCodec(mapper).decode(facts.path("semanticOriginal").toString()).reading()
                : null;
        for (var change : changes) {
            if (change == null || change.bookingIndex() == null || change.bookingIndex() < 0
                    || change.bookingIndex() >= facts.path("bookings").size()
                    || !FIELDS.contains(change.field() == null ? "" : change.field()) || change.originalValue() == null
                    || change.proposedValue() == null
                    || !seen.add(change.bookingIndex() + ":" + change.field()))
                throw TourCorrectionException.invalid("Invalid or duplicate booking field correction.");
            if (change.field().equals("lifecycle") && (v3 == null || !TourReading.isCurrentContract(v3.provenance().promptVersion())))
                throw invalid("INVALID_LIFECYCLE_CLARIFICATION", "Lifecycle clarification requires the exact V3.4 contract.");
            JsonNode old = facts.path("bookings").get(change.bookingIndex()).path(change.field());
            if (old.isMissingNode())
                old = mapper.nullNode();
            if (!canonicalOriginal(change.field(), old).equals(canonicalOriginal(change.field(), change.originalValue())))
                throw TourCorrectionException.stale("Original field value changed; reload the exact extraction.");
            if (canonicalOriginal(change.field(), old).equals(canonicalOriginal(change.field(), change.proposedValue())))
                throw TourCorrectionException.invalid("Unchanged values are confirmation, not corrections.");
        }
        for (var c : changes)
            ((ObjectNode) facts.path("bookings").get(c.bookingIndex())).set(c.field(), c.proposedValue());
        if (v3 != null && TourReading.isCurrentContract(v3.provenance().promptVersion())) {
            var effectiveEligibility = lifecycleSelection(v3, clarifications(facts, correctedFields(changes)));
            for (var change : changes)
                if (!combined && !change.field().equals("lifecycle") && effectiveEligibility.bookings().get(change.bookingIndex())
                        .disposition() != BookingEligibility.Disposition.ACTIVE_ELIGIBLE)
                    throw invalid("BOOKING_NOT_ELIGIBLE",
                            "Historical or unresolved validity cannot be changed by an unrelated field correction.");
        }
        invalidateReadingDerived(facts, correctedFields(changes));
        return facts;
    }

    /** True when at least one correction answers the lifecycle question. */
    public static boolean hasLifecycleClarification(List<Change> changes) {
        return changes != null && changes.stream().anyMatch(c -> c != null && "lifecycle".equals(c.field()));
    }

    /** Collects the lifecycle answers the user gave, keyed by booking number. */
    private Map<Integer, com.ishita.tripcoordinatorplatform.request.TourCorrectionRequest.LifecycleClarification> clarifications(
            ObjectNode facts, Map<Integer, Set<String>> corrected) {
        var result = new HashMap<Integer, com.ishita.tripcoordinatorplatform.request.TourCorrectionRequest.LifecycleClarification>();
        if (!facts.has("semanticOriginal") || facts.path("semanticOriginal").path("schemaVersion").asInt() != 3)
            return result;
        var v3 = new TourExtractionCodec(mapper).decode(facts.path("semanticOriginal").toString()).reading();
        for (var b : v3.bookings()) {
            var node = facts.path("bookings").get(b.bookingIndex()).path("lifecycle");
            if (!corrected.getOrDefault(b.bookingIndex(), Set.of()).contains("lifecycle")) {
                if (!node.equals(mapper.valueToTree(b.lifecycle())))
                    throw invalid("INVALID_LIFECYCLE_CLARIFICATION", "Lifecycle changes require an explicit reviewed declaration.");
                continue;
            }
            if (!TourReading.isCurrentContract(v3.provenance().promptVersion()) || !node.isObject() || node.size() != 3
                    || !node.path("state").isTextual() || !node.path("reason").isTextual()
                    || !node.path("acknowledgeSourceConflict").isBoolean())
                throw invalid("INVALID_LIFECYCLE_CLARIFICATION",
                        "Supply a lifecycle state, explanation and explicit conflict acknowledgement.");
            com.ishita.tripcoordinatorplatform.request.TourCorrectionRequest.LifecycleClarification clarification;
            try {
                clarification = mapper
                        .readerFor(com.ishita.tripcoordinatorplatform.request.TourCorrectionRequest.LifecycleClarification.class)
                        .with(tools.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).readValue(node.toString());
            } catch (JacksonException invalid) {
                throw invalid("INVALID_LIFECYCLE_CLARIFICATION", "Use a supported lifecycle state.");
            }
            if (clarification.state() == null || clarification.reason().isBlank() || clarification.reason().length() > 500)
                throw invalid("INVALID_LIFECYCLE_CLARIFICATION", "Explain the user-reported lifecycle in 1–500 characters.");
            boolean conflict = b.lifecycle().status() == BookingFact.Status.CONFLICTING
                    || b.lifecycle().status() == BookingFact.Status.CLEAR && b.lifecycle().value() != clarification.state();
            if (conflict && !clarification.acknowledgeSourceConflict())
                throw invalid("LIFECYCLE_CONFLICT_ACKNOWLEDGEMENT_REQUIRED",
                        "Explicitly acknowledge conflicting source information; a user report is not document verification.");
            result.put(b.bookingIndex(), clarification);
        }
        lifecycleSelection(v3, result);
        return Map.copyOf(result);
    }

    /** Works out which bookings are eligible to confirm, given the user's lifecycle answers; contradictory answers are refused. */
    private BookingEligibility.Selection lifecycleSelection(TourReading v3,
            Map<Integer, com.ishita.tripcoordinatorplatform.request.TourCorrectionRequest.LifecycleClarification> declarations) {
        var states = new HashMap<Integer, ReadingBookingLifecycle.State>();
        declarations.forEach((i, c) -> states.put(i, c.state()));
        try {
            return BookingEligibility.assess(v3, states);
        } catch (InvalidDocumentExtractionException contradictory) {
            throw invalid("INVALID_LIFECYCLE_CLARIFICATION", contradictory.getMessage());
        }
    }

    private static final Map<BookingFact.Field, List<String>> V3_FIELDS = Map.ofEntries(
            Map.entry(BookingFact.Field.NAME, List.of("name")), Map.entry(BookingFact.Field.PROVIDER, List.of("provider")),
            Map.entry(BookingFact.Field.BOOKING_REFERENCE, List.of("bookingReference")),
            Map.entry(BookingFact.Field.START, List.of("startDate", "startTime")),
            Map.entry(BookingFact.Field.END, List.of("endDate", "endTime")), Map.entry(BookingFact.Field.TIMEZONE, List.of("timezone")),
            Map.entry(BookingFact.Field.DURATION_MINUTES, List.of("durationMinutes")),
            Map.entry(BookingFact.Field.REQUIRED_ARRIVAL, List.of("requiredArrivalLocal")),
            Map.entry(BookingFact.Field.ARRIVAL_LEAD_MINUTES, List.of("arrivalLeadMinutes")),
            Map.entry(BookingFact.Field.MEETING_POINT, List.of("meetingPoint")),
            Map.entry(BookingFact.Field.ENDING_POINT, List.of("endingPoint", "endingLocationChoice")),
            Map.entry(BookingFact.Field.INSTRUCTIONS, List.of("instructions")));

    /** Invalidate transitive arithmetic-derived values in the effective view only, never the original extraction. */
    Map<Integer, Set<String>> invalidateReadingDerived(ObjectNode facts, Map<Integer, Set<String>> corrected) {
        if (!facts.has("semanticOriginal") || facts.get("semanticOriginal").path("schemaVersion").asInt() != 3)
            return Map.of();
        var result = new HashMap<Integer, Set<String>>();
        var original = new TourExtractionCodec(mapper).decode(facts.get("semanticOriginal").toString()).reading();
        for (var b : original.bookings()) {
            var explicit = corrected.getOrDefault(b.bookingIndex(), Set.of());
            var changed = EnumSet.noneOf(BookingFact.Field.class);
            V3_FIELDS.forEach((field, keys) -> {
                if (keys.stream().anyMatch(explicit::contains))
                    changed.add(field);
            });
            var tree = mapper.valueToTree(b);
            var invalidated = new HashSet<String>();
            boolean again;
            do {
                again = false;
                for (var entry : V3_FIELDS.entrySet()) {
                    var field = entry.getKey();
                    if (changed.contains(field))
                        continue;
                    String key = entry.getValue().getFirst();
                    String sourceKey = switch (field) {
                    case START -> "start";
                    case END -> "end";
                    case REQUIRED_ARRIVAL -> "requiredArrival";
                    default -> key;
                    };
                    var d = tree.path(sourceKey).path("interpretation");
                    String basis = d.path("basis").asString();
                    if (!Set.of("CALCULATED", "CONTEXT_INTERPRETED").contains(basis))
                        continue;
                    var refs = new ArrayList<JsonNode>();
                    d.path("dependencies").forEach(refs::add);
                    d.path("calculation").path("operands").forEach(refs::add);
                    if (refs.stream().noneMatch(r -> changed.contains(BookingFact.Field.valueOf(r.path("field").asString()))))
                        continue;
                    // Retain contextual source claims as AI interpretations; propagate their changed
                    // support so downstream model calculations are never reused as current results.
                    if (basis.equals("CONTEXT_INTERPRETED")) {
                        changed.add(field);
                        again = true;
                        continue;
                    }
                    var booking = (ObjectNode) facts.path("bookings").get(b.bookingIndex());
                    for (String affected : entry.getValue()) {
                        if (affected.equals("endingLocationChoice"))
                            booking.put(affected, "UNKNOWN");
                        else
                            booking.putNull(affected);
                        invalidated.add(affected);
                    }
                    changed.add(field);
                    again = true;
                }
            } while (again);
            result.put(b.bookingIndex(), Set.copyOf(invalidated));
        }
        return result;
    }

    /** Builds the preview the user sees before confirming: the bookings with the corrections applied, without saving anything. */
    public com.ishita.tripcoordinatorplatform.response.TourBookingPreview preview(String json, List<Change> changes, Long extractionId) {
        if (!hasLifecycleClarification(changes))
            return validatedPreview(json, changes, extractionId);
        var proposed = proposed(json, changes);
        var declarations = clarifications(proposed, correctedFields(changes));
        var v3 = new TourExtractionCodec(mapper).decode(json).reading();
        var selection = lifecycleSelection(v3, declarations);
        try {
            return validatedPreview(json, changes, extractionId);
        } catch (TourCorrectionException blocked) {
            var rows = new ArrayList<com.ishita.tripcoordinatorplatform.response.TourBookingPreview.Booking>();
            for (var b : v3.bookings())
                rows.add(new com.ishita.tripcoordinatorplatform.response.TourBookingPreview.Booking(b.bookingIndex(),
                        previewBooking(proposed, b.bookingIndex()), null, "UNKNOWN", Set.of(),
                        selection.activeIndexes().contains(b.bookingIndex()),
                        selection.bookings().get(b.bookingIndex()), declarations.get(b.bookingIndex())));
            return new com.ishita.tripcoordinatorplatform.response.TourBookingPreview(3, v3.provenance().promptVersion(), extractionId,
                    rows, false, blocked.code(), false);
        }
    }

    /** Applies the corrections, validates the result and builds the preview bookings. */
    private com.ishita.tripcoordinatorplatform.response.TourBookingPreview validatedPreview(String json, List<Change> changes,
            Long extractionId) {
        var proposed = proposed(json, changes);
        var edits = correctedFields(changes);
        var invalidated = invalidateReadingDerived(proposed, edits);
        var effective = validate(proposed, edits);
        var bookings = new ArrayList<com.ishita.tripcoordinatorplatform.response.TourBookingPreview.Booking>();
        var v3 = new TourExtractionCodec(mapper).decode(json).reading();
        var selection = v3 == null ? null : lifecycleSelection(v3, effective.lifecycleClarifications());
        for (int i = 0; i < effective.proposal().bookings().size(); i++) {
            var b = effective.proposal().bookings().get(i);
            if (!effective.timings().containsKey(i)) {
                bookings.add(new com.ishita.tripcoordinatorplatform.response.TourBookingPreview.Booking(i, b, null, "UNKNOWN", Set.of(),
                        false, selection == null ? null : selection.bookings().get(i), effective.lifecycleClarifications().get(i)));
                continue;
            }
            var timing = effective.timings().get(i);
            var endpoint = effective.endpoints().get(i);
            var resolved = new AttractionTourBookingExtraction(b.name(), b.bookingReference(), b.provider(), timing.start().toLocalDate(),
                    timing.start().toLocalTime(),
                    timing.end() == null ? null : timing.end().toLocalDate(), timing.end() == null ? null : timing.end().toLocalTime(),
                    b.durationMinutes(), b.meetingPoint(), b.timezone(), b.instructions(), b.arrivalLeadMinutes(), endpoint.location());
            var arrival = effective.arrivals().get(i);
            bookings.add(new com.ishita.tripcoordinatorplatform.response.TourBookingPreview.Booking(i, resolved,
                    arrival == null ? null : LocalDateTime.ofInstant(arrival, ZoneId.of(b.timezone())), endpoint.choice(),
                    invalidated.getOrDefault(i, Set.of()), effective.confirmationBookingIndexes().contains(i),
                    selection == null ? null : selection.bookings().get(i), effective.lifecycleClarifications().get(i)));
        }
        return new com.ishita.tripcoordinatorplatform.response.TourBookingPreview(3,
                mapper.readTree(json).path("provenance").path("promptVersion").asText(), extractionId, List.copyOf(bookings));
    }

    /** Reads one booking from the facts as a booking record, leaving out the lifecycle, arrival and ending-choice fields. */
    private AttractionTourBookingExtraction previewBooking(ObjectNode facts, int index) {
        var row = (ObjectNode) facts.path("bookings").get(index).deepCopy();
        row.remove("lifecycle");
        row.remove("requiredArrivalLocal");
        row.remove("endingLocationChoice");
        return mapper.treeToValue(row, AttractionTourBookingExtraction.class);
    }

    /** Writes the required-arrival time in its standard text form so equal times compare equal; other values are returned as they are. */
    private JsonNode canonicalOriginal(String field, JsonNode value) {
        if (!"requiredArrivalLocal".equals(field) || !value.isTextual())
            return value;
        if (value.asText().isEmpty())
            return mapper.nullNode();
        try {
            return mapper.valueToTree(LocalDateTime.parse(value.asText()).toString());
        } catch (DateTimeException invalid) {
            return value;
        } // A genuine differing value still fails stale-source comparison.
    }

    /** Lists, for each booking number, which fields the user corrected. */
    public static Map<Integer, Set<String>> correctedFields(List<Change> changes) {
        var result = new HashMap<Integer, Set<String>>();
        for (var c : changes)
            result.computeIfAbsent(c.bookingIndex(), ignored -> new HashSet<>()).add(c.field());
        return result;
    }

    public record Timing(LocalDateTime start, LocalDateTime end, Instant startsAt, Instant endsAt) {}

    public record Endpoint(String choice, Location location) {}

    public record Effective(AttractionTourExtractionResult proposal, Map<Integer, Instant> arrivals,
            Map<Integer, Timing> timings, Map<Integer, Endpoint> endpoints, Set<Integer> endpointCorrections,
            Set<Integer> confirmationBookingIndexes,
            Map<Integer, com.ishita.tripcoordinatorplatform.request.TourCorrectionRequest.LifecycleClarification>
                    lifecycleClarifications) {}

    // Compatibility for existing pure-policy callers; production tracks every explicitly corrected field.
    public Effective validate(ObjectNode facts, Set<Integer> explicitArrivals) {
        var corrected = new HashMap<Integer, Set<String>>();
        for (int i : explicitArrivals)
            corrected.put(i, Set.of("requiredArrivalLocal"));
        return validate(facts, corrected);
    }

    /**
     * Checks the whole set of facts and works out the arrival, timing and ending of each booking; bad times or values are turned into
     * correction errors.
     */
    public Effective validate(ObjectNode facts, Map<Integer, Set<String>> corrected) {
        try {
            return resolve(facts, corrected, null);
        } catch (DateTimeException invalid) {
            throw invalid("INVALID_BOOKING_TIME", "Use valid local timestamps and an explicit, unambiguous activity timezone.");
        } catch (JacksonException invalid) {
            throw invalid("INVALID_CORRECTION_VALUE", "A proposed booking field has an invalid type or format.");
        } catch (ArithmeticException invalid) {
            throw invalid("INVALID_BOOKING_TIME", "The proposed duration or arrival cannot be represented.");
        }
    }

    /** Works out which bookings of the original reading can be confirmed, using the lifecycle answers in the facts. */
    public BookingEligibility.Selection eligibility(ObjectNode facts, Map<Integer, Set<String>> corrected) {
        var source = new TourExtractionCodec(mapper).decode(facts.path("semanticOriginal").toString()).reading();
        return lifecycleSelection(source, clarifications(facts, corrected));
    }

    /** Same as validate, but only for the bookings the user selected; at least one must be selected. */
    public Effective validateSelected(ObjectNode facts, Map<Integer, Set<String>> corrected, Set<Integer> selected) {
        if (selected == null || selected.isEmpty())
            throw invalid("BOOKING_SELECTION_REQUIRED", "Explicitly select at least one original booking index.");
        try {
            return resolve(facts, corrected, selected);
        } catch (DateTimeException | ArithmeticException invalid) {
            throw invalid("INVALID_BOOKING_TIME", "Use valid unambiguous booking timestamps.");
        } catch (JacksonException invalid) {
            throw invalid("INVALID_CORRECTION_VALUE", "A proposed booking field has an invalid type or format.");
        }
    }

    /** Does the checking for validate and validateSelected, booking by booking: start, end, arrival and ending location. */
    private Effective resolve(ObjectNode facts, Map<Integer, Set<String>> corrected, Set<Integer> selected) {
        var arrivals = new HashMap<Integer, Instant>();
        var timings = new HashMap<Integer, Timing>();
        var endpoints = new HashMap<Integer, Endpoint>();
        var endpointCorrections = new HashSet<Integer>();
        var declarations = clarifications(facts, corrected);
        var clean = facts.deepCopy();
        boolean semantic = facts.has("semanticOriginal");
        boolean reading = semantic && facts.get("semanticOriginal").path("schemaVersion").asInt() == 3;
        List<TourReading.Booking> temporalBookings = List.of();
        Set<Integer> confirmationIndexes = new HashSet<>();
        for (int index = 0; index < facts.path("bookings").size(); index++)
            confirmationIndexes.add(index);
        boolean lifecycleScoped = false;
        clean.remove("semanticOriginal");
        if (semantic) {
            String json = facts.get("semanticOriginal").toString();
            requireLegacyExtraction(json);
            var original = new TourExtractionCodec(mapper).decode(json);
            var v3 = original.reading();
            temporalBookings = v3.bookings();
            var selection = lifecycleSelection(v3, declarations);
            confirmationIndexes = new HashSet<>(selection.activeIndexes());
            lifecycleScoped = TourReading.isCurrentContract(v3.provenance().promptVersion());
            if (selected != null) {
                if (!lifecycleScoped || !confirmationIndexes.containsAll(selected))
                    throw invalid("BOOKING_NOT_ELIGIBLE", "Only explicitly selected eligible ACTIVE bookings may be confirmed.");
                confirmationIndexes = new HashSet<>(selected);
            }
            if (selected == null && lifecycleScoped && !selection.confirmationAllowed())
                throw invalid(selection.blockCode(),
                        "The entire document remains in review while lifecycle is unresolved/provisional or no active bookings remain.");
            for (var b : v3.bookings())
                if ((!lifecycleScoped || confirmationIndexes.contains(b.bookingIndex())) && (!ReadingTimeRule.representable(b)
                        || !ReadingTimeRule.correctionCompatible(b, corrected.getOrDefault(b.bookingIndex(), Set.of()))))
                    throw invalid("TEMPORAL_CONSTRAINT_NOT_REPRESENTABLE",
                            "Temporal requirements need compatible scheduling/correction handling; no boundary semantics may be "
                                    + "discarded.");
            var checks = new TourReadingValidator().validate(v3, v3.provenance().sourceSha256());
            List<BookingProposal> views = v3.bookings().stream().map(b -> BookingProposal.fromReading(b, checks.calculations())).toList();
            for (int index = 0; index < views.size(); index++)
                for (String unresolved : views.get(index).unresolvedFields()) {
                    if (lifecycleScoped && !confirmationIndexes.contains(index))
                        continue;
                    var edits = corrected.getOrDefault(index, Set.of());
                    boolean resolved = switch (unresolved) {
                    case "requiredArrivalLocal", "arrivalLeadMinutes" ->
                        edits.contains("requiredArrivalLocal") || edits.contains("arrivalLeadMinutes");
                    case "endingPoint/endingLocationChoice" -> edits.contains("endingPoint") || edits.contains("endingLocationChoice");
                    case "startDate/startTime" -> edits.containsAll(Set.of("startDate", "startTime"));
                    case "endDate/endTime" -> edits.containsAll(Set.of("endDate", "endTime"));
                    default -> edits.contains(unresolved);
                    };
                    if (!resolved)
                        throw invalid("SEMANTIC_FACT_UNRESOLVED",
                                "Resolve booking " + (index + 1) + " field " + unresolved + " through source-supported correction review.");
                }
        }
        if (!clean.path("bookings").isArray() || clean.path("bookings").isEmpty())
            throw invalid("MISSING_BOOKING_FIELDS", "Bookings are required.");
        for (var n : clean.path("bookings")) {
            ((ObjectNode) n).remove("requiredArrivalLocal");
            ((ObjectNode) n).remove("endingLocationChoice");
            ((ObjectNode) n).remove("lifecycle");
        }
        var result = mapper.readValue(clean.toString(), AttractionTourExtractionResult.class);
        for (int i = 0; i < result.bookings().size(); i++) {
            if (lifecycleScoped && !confirmationIndexes.contains(i))
                continue;
            var b = result.bookings().get(i);
            var explicit = corrected.getOrDefault(i, Set.of());
            if (b == null || b.name() == null || b.name().isBlank() || b.startDate() == null || b.startTime() == null)
                throw invalid("MISSING_BOOKING_FIELDS", "Activity name and start date/time are required.");
            if (reading && (b.timezone() == null || !ZoneId.getAvailableZoneIds().contains(b.timezone())))
                throw invalid("BOOKING_TIMEZONE_REQUIRED",
                        "V3 confirmation requires a supported IANA timezone, including after corrections.");
            for (var location : java.util.stream.Stream.of(b.meetingPoint(), b.endingPoint()).filter(Objects::nonNull).toList()) {
                if (!location.isCoordinatePairValid() || location.getLatitude() != null
                        && (location.getLatitude().abs().doubleValue() > 90 || location.getLongitude().abs().doubleValue() > 180))
                    throw invalid("INVALID_BOOKING_LOCATION", "Invalid document location coordinates.");
                if (location.getName() != null && location.getName().length() > 255
                        || location.getAddress() != null && location.getAddress().length() > 1000)
                    throw invalid("INVALID_BOOKING_LOCATION", "Location text is too long.");
            }
            if (b.name().length() > 255 || b.provider() != null && b.provider().length() > 255
                    || b.bookingReference() != null && b.bookingReference().length() > 255)
                throw invalid("INVALID_BOOKING_FIELDS", "Booking text is too long.");
            var timing = timing(b);
            timings.put(i, timing);
            boolean arrivalChanged = explicit.contains("requiredArrivalLocal"), leadChanged = explicit.contains("arrivalLeadMinutes");
            Instant arrival = null;
            Integer lead = null;
            if (arrivalChanged || semantic && !leadChanged && !facts.path("bookings").get(i).path("requiredArrivalLocal").isNull()) {
                String required = facts.path("bookings").get(i).path("requiredArrivalLocal").asText(null);
                if (required == null)
                    throw invalid("ARRIVAL_REQUIREMENT_UNRESOLVED",
                            "Replace an existing requirement explicitly; missing evidence is not a zero-minute requirement.");
                requireZone(timing);
                var local = LocalDateTime.parse(required);
                arrival = strict(local, b.timezone());
                long seconds = Duration.between(arrival, timing.startsAt()).getSeconds();
                if (seconds < 0 || seconds % 60 != 0 || seconds / 60 > Integer.MAX_VALUE || local.getSecond() != 0 || local.getNano() != 0)
                    throw invalid("INVALID_REQUIRED_ARRIVAL", "Required arrival must precede the start, in whole minutes.");
                lead = (int) (seconds / 60);
                if ((leadChanged || semantic && !arrivalChanged && b.arrivalLeadMinutes() != null)
                        && !Objects.equals(lead, b.arrivalLeadMinutes()))
                    throw invalid("CONFLICTING_ARRIVAL_REQUIREMENTS",
                            "Corrected arrival time and arrival lead describe different deadlines.");
            } else if (leadChanged || semantic && b.arrivalLeadMinutes() != null) {
                lead = b.arrivalLeadMinutes();
                if (lead == null || lead < 0)
                    throw invalid("ARRIVAL_REQUIREMENT_UNRESOLVED",
                            "Supply an explicit supported arrival lead, including zero when documented.");
                requireZone(timing);
                arrival = timing.startsAt().minusSeconds(lead * 60L);
            } else if (!semantic) {
                var evidence = ConfirmedArrivalEvidence.inspect(clean.toString(), i, timing.start(), b.timezone());
                if (evidence.issue() != null)
                    throw invalid("ARRIVAL_REQUIREMENT_UNRESOLVED", evidence.issue());
                lead = evidence.leadMinutes();
                if (lead != null) {
                    requireZone(timing);
                    arrival = timing.startsAt().minusSeconds(lead * 60L);
                }
            }
            if (reading && !ReadingTimeRule.matchesEffective(temporalBookings.get(i), timing.start(), timing.end(),
                    arrival == null ? null : LocalDateTime.ofInstant(arrival, ZoneId.of(b.timezone()))))
                throw invalid("TEMPORAL_CONSTRAINT_CONFLICT", "Effective timing does not preserve the documented temporal boundary.");
            if (arrival != null)
                arrivals.put(i, arrival);
            // Supersede stale inferred values only in the effective proposal; the extraction is immutable.
            var effectiveBooking = (ObjectNode) clean.path("bookings").get(i);
            if (lead == null)
                effectiveBooking.putNull("arrivalLeadMinutes");
            else
                effectiveBooking.put("arrivalLeadMinutes", lead);
            var endpoint = endpoint(facts.path("bookings").get(i), b, explicit, semantic);
            endpoints.put(i, endpoint);
            effectiveBooking
                    .set("endingPoint",
                            mapper.valueToTree("UNKNOWN".equals(endpoint.choice()) && !explicit.contains("endingPoint")
                                    && !explicit.contains("endingLocationChoice") ? documentaryLocation(b.endingPoint())
                                            : endpoint.location()));
            effectiveBooking.set("meetingPoint", mapper.valueToTree(documentaryLocation(b.meetingPoint())));
            if (explicit.contains("endingPoint") || explicit.contains("endingLocationChoice"))
                endpointCorrections.add(i);
        }
        return new Effective(mapper.readValue(clean.toString(), AttractionTourExtractionResult.class), Map.copyOf(arrivals),
                Map.copyOf(timings), Map.copyOf(endpoints), Set.copyOf(endpointCorrections), Set.copyOf(confirmationIndexes),
                Map.copyOf(declarations));
    }

    /**
     * Builds a booking's start and end as local times plus exact instants (when a time zone is known); a half-given end or a non-positive
     * duration is refused.
     */
    private Timing timing(AttractionTourBookingExtraction b) {
        var start = b.startDate().atTime(b.startTime());
        if ((b.endDate() == null) != (b.endTime() == null))
            throw invalid("INVALID_BOOKING_END", "Supply the complete activity end or leave it unknown.");
        var end = b.endDate() == null ? null : b.endDate().atTime(b.endTime());
        if (b.durationMinutes() != null && b.durationMinutes() <= 0)
            throw invalid("INVALID_BOOKING_DURATION", "Elapsed duration must be positive.");
        Instant starts = b.timezone() == null ? null : strict(start, b.timezone());
        Instant ends = end == null || b.timezone() == null ? null : strict(end, b.timezone());
        if (b.durationMinutes() != null) {
            if (starts == null)
                throw invalid("BOOKING_TIMEZONE_REQUIRED", "Elapsed duration needs the activity timezone.");
            Instant derived = starts.plusSeconds(b.durationMinutes() * 60L);
            if (end != null && !derived.equals(ends))
                throw invalid("CONFLICTING_BOOKING_END", "Documented end and elapsed duration disagree.");
            if (end == null) {
                end = LocalDateTime.ofInstant(derived, ZoneId.of(b.timezone()));
                // Reject an overlap which the existing local-time persistence contract cannot distinguish.
                ends = strict(end, b.timezone());
                if (!derived.equals(ends))
                    throw invalid("INVALID_BOOKING_END", "The derived end cannot be represented unambiguously.");
            }
        }
        if (end != null && (starts != null ? ends.isBefore(starts) : end.isBefore(start)))
            throw invalid("INVALID_BOOKING_END", "Activity end precedes its start.");
        return new Timing(start, end, starts, ends);
    }

    /**
     * Decides where a booking ends: unknown, the same place as the meeting point, or a different location, taking the user's corrections
     * into account.
     */
    private Endpoint endpoint(JsonNode fact, AttractionTourBookingExtraction b, Set<String> explicit, boolean semantic) {
        boolean pointChanged = explicit.contains("endingPoint"), choiceChanged = explicit.contains("endingLocationChoice");
        String choice = fact.path("endingLocationChoice").asText(b.endingPoint() == null ? "UNKNOWN" : "LOCATION");
        if (pointChanged && !choiceChanged)
            choice = b.endingPoint() == null ? "UNKNOWN" : "LOCATION";
        else if (!semantic && !choiceChanged && explicit.contains("meetingPoint"))
            choice = initialEndpointChoice(fact);
        if (!Set.of("UNKNOWN", "LOCATION", "SAME_AS_MEETING_POINT").contains(choice))
            throw invalid("INVALID_ENDPOINT_CHOICE", "Choose Unknown, Same as meeting point, or Different location.");
        Location point = b.endingPoint();
        if ("SAME_AS_MEETING_POINT".equals(choice)) {
            if (!hasText(b.meetingPoint()))
                throw invalid("INVALID_BOOKING_LOCATION", "A meeting point is required for a Same endpoint.");
            if (pointChanged && !sameDocumentaryLocation(point, b.meetingPoint()))
                throw invalid("CONFLICTING_ENDPOINT_DECISIONS", "Same endpoint contradicts the corrected ending place.");
            point = b.meetingPoint();
        } else if ("UNKNOWN".equals(choice)) {
            if (pointChanged && point != null)
                throw invalid("CONFLICTING_ENDPOINT_DECISIONS", "Unknown endpoint contradicts the corrected ending place.");
            point = null;
        } else if (!hasText(point))
            throw invalid("INVALID_BOOKING_LOCATION", "A Different endpoint needs a documented name or address.");
        return new Endpoint(choice, documentaryLocation(point));
    }

    /**
     * Returns the ending location to use when confirming; an older-style ending instruction that disagrees with an accepted correction is
     * refused.
     */
    public Endpoint endpointForConfirmation(Effective effective, int index,
            com.ishita.tripcoordinatorplatform.request.ActivityEndingLocationRequest legacy) {
        var saved = effective.endpoints().get(index);
        if (legacy == null)
            return saved;
        if (effective.endpointCorrections().contains(index)) {
            if (!saved.choice().equals(legacy.choice())
                    || "LOCATION".equals(saved.choice()) && !sameDocumentaryLocation(saved.location(), legacy.location()))
                throw invalid("CONFLICTING_ENDPOINT_DECISIONS", "Legacy endpoint instructions conflict with the accepted correction.");
            return saved;
        }
        if ("UNKNOWN".equals(legacy.choice()))
            return new Endpoint("UNKNOWN", null);
        Location point = "SAME_AS_MEETING_POINT".equals(legacy.choice()) ? effective.proposal().bookings().get(index).meetingPoint()
                : legacy.location();
        var documented = saved.location() != null ? saved.location() : effective.proposal().bookings().get(index).endingPoint();
        if (documented == null || !sameDocumentaryLocation(documented, point))
            throw invalid("ENDPOINT_CORRECTION_REQUIRED",
                    "An undocumented or changed endpoint needs an explicitly accepted source correction.");
        return new Endpoint(legacy.choice(), documentaryLocation(point));
    }

    /** True when two locations have the same name and address. */
    public static boolean sameDocumentaryLocation(Location a, Location b) {
        return Objects.equals(a == null ? null : a.getName(), b == null ? null : b.getName())
                && Objects.equals(a == null ? null : a.getAddress(), b == null ? null : b.getAddress());
    }

    /** Documentary assertions never manufacture provider-verified coordinates. Evidence stays in source/history. */
    public static Location documentaryLocation(Location source) {
        if (source == null)
            return null;
        var copy = new Location();
        copy.setName(source.getName());
        copy.setAddress(source.getAddress());
        return copy;
    }

    /** True when the location has a non-blank name or address. */
    private static boolean hasText(Location location) {
        return location != null && (location.getName() != null && !location.getName().isBlank()
                || location.getAddress() != null && !location.getAddress().isBlank());
    }

    /** Refuses an arrival calculation when the activity has no time zone. */
    private static void requireZone(Timing timing) {
        if (timing.startsAt() == null)
            throw invalid("BOOKING_TIMEZONE_REQUIRED", "Arrival requires the activity timezone.");
    }

    /** Converts a local time to an exact instant; refuses times that are ambiguous or do not exist in that time zone. */
    private static Instant strict(LocalDateTime local, String zone) {
        var offsets = ZoneId.of(zone).getRules().getValidOffsets(local);
        if (offsets.size() != 1)
            throw invalid("AMBIGUOUS_BOOKING_TIME", "The activity timestamp is ambiguous or nonexistent; clarify its local time.");
        return local.toInstant(offsets.getFirst());
    }

    private static TourCorrectionException invalid(String code, String message) {
        return TourCorrectionException.validation(code, message);
    }
}
