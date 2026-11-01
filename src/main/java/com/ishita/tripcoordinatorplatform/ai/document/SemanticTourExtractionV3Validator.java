package com.ishita.tripcoordinatorplatform.ai.document;

import java.time.*;
import java.util.*;
import static com.ishita.tripcoordinatorplatform.ai.document.SemanticFact.*;
import static com.ishita.tripcoordinatorplatform.ai.document.SemanticFactV3.*;
import static com.ishita.tripcoordinatorplatform.ai.document.SemanticTourExtraction.LocalMoment;

/** Structured validation only. No interpretation of source expressions, evidence or natural language. */
public final class SemanticTourExtractionV3Validator {
    public enum Verification { LOCAL_ARITHMETIC_VERIFIED, ELAPSED_ARITHMETIC_VERIFIED }
    public record Validation(Map<Reference, Verification> calculations) {
        public Validation { calculations = Map.copyOf(calculations); }
    }

    public Validation validate(SemanticTourExtractionV3 extraction, String expectedSourceSha256) {
        require(extraction != null && extraction.schemaVersion() == SemanticTourExtractionV3.SCHEMA_VERSION,
                "Unsupported semantic extraction version");
        var p = extraction.provenance();
        require(p != null && p.sourceSha256() != null && p.sourceSha256().matches("[a-f0-9]{64}")
                && p.sourceSha256().equals(expectedSourceSha256) && text(p.model())
                && SemanticTourExtractionV3.READABLE_PROMPT_VERSIONS.contains(p.promptVersion())
                && text(p.providerResponseId()) && p.extractedAt() != null, "Semantic source/provenance mismatch");
        require(extraction.bookings() != null && !extraction.bookings().isEmpty(), "Semantic bookings are required");
        var verified = new LinkedHashMap<Reference, Verification>();
        var allFacts = new LinkedHashMap<Reference, SemanticFactV3<?>>();
        var assertions = new SemanticAssertionsV3Validator();
        for (int i = 0; i < extraction.bookings().size(); i++) {
            var b = extraction.bookings().get(i);
            require(b != null && b.bookingIndex() == i && b.conditions() != null, "Booking order/identity mismatch");
            var facts = fields(b);
            allFacts.putAll(facts);
            for (var entry : facts.entrySet()) {
                try {
                    validateFact(entry.getValue(), facts);
                    for (var e : entry.getValue().evidence()) require(e.bookingIndex() != null && e.bookingIndex() == i,
                            "Evidence booking association is invalid");
                }
                catch (InvalidDocumentExtractionException e) { throw at(entry.getKey(), e); }
            }
            for (var ref : facts.keySet()) visit(ref, facts, new HashSet<>(), new HashSet<>());
            assertions.validateBookingConditions(b);
            // Reuse only unchanged typed/status/evidence/location/condition rules, not v2 derivation or timing semantics.
            new SemanticTourExtractionValidator().validateBooking(b.shape(), false);
            if (b.timezone().value() != null) canonicalZone(b.timezone().value());
            b.timezone().alternatives().forEach(this::canonicalZone);
            for (var entry : facts.entrySet()) {
                if (entry.getValue().interpretation().calculation() != null) {
                    try { verified.put(entry.getKey(), calculate(entry.getKey(), entry.getValue(), facts, b)); }
                    catch (InvalidDocumentExtractionException e) { throw at(entry.getKey(), e); }
                }
            }
            SemanticTemporalConstraintV3.validate(b, this, facts);
            if (SemanticTourExtractionV3.LEGACY_PROMPT_VERSION.equals(p.promptVersion()))
                require(b.temporalConstraints().isEmpty(), "Temporal constraints require explicit contract 3.2 identity");
            if (!SemanticTourExtractionV3.LEGACY_PROMPT_VERSION.equals(p.promptVersion()) && (b.requiredArrival().status() == Status.CLEAR || b.arrivalLeadMinutes().status() == Status.CLEAR))
                require(b.temporalConstraints().stream().anyMatch(c -> c.target() == SemanticTemporalConstraintV3.Target.REQUIRED_ARRIVAL),
                        "Arrival semantics must be explicit in contract 3.2");
            consistency(b);
        }
        SemanticBookingLifecycleV3.validate(extraction,this);
        assertions.validateSupplemental(extraction, allFacts, this);
        return new Validation(verified);
    }

    void validateFact(SemanticFactV3<?> f, Map<Reference, SemanticFactV3<?>> facts) {
        require(f != null && f.status() != null && f.interpretation() != null
                && f.interpretation().basis() != null && f.interpretation().dependencies() != null
                && f.sourceExpressions() != null && f.alternatives() != null && f.evidence() != null, "Incomplete semantic fact");
        switch (f.status()) {
            case CLEAR -> require(f.value() != null && f.alternatives().isEmpty() && !f.evidence().isEmpty()
                    && f.interpretation().basis() != Basis.NOT_ESTABLISHED, "Clear fact needs supported value");
            case MISSING -> require(f.value() == null && f.alternatives().isEmpty() && f.evidence().isEmpty()
                    && f.interpretation().basis() == Basis.NOT_ESTABLISHED, "Missing fact cannot contain an invented value");
            case AMBIGUOUS, CONFLICTING -> {
                require(f.value() == null && text(f.uncertainty()) && !f.evidence().isEmpty(), "Uncertain fact needs evidence/uncertainty");
                if (f.status() == Status.CONFLICTING) require(new HashSet<>(f.alternatives()).size() >= 2,
                        "Conflicting fact needs distinct alternatives");
            }
        }
        for (var e : f.evidence()) require(e != null && (e.bookingIndex() == null || e.bookingIndex() >= 0)
                && text(e.bookingAssociation()) && text(e.quotation()) && (e.reportedPage() == null || e.reportedPage() > 0),
                "Invalid source evidence");
        require(f.sourceExpressions().size() <= 16 && f.sourceExpressions().stream().allMatch(v -> text(v) && v.length() <= 500),
                "Source expressions must be short literal expressions");
        var d = f.interpretation();
        require(d.dependencies().size() <= facts.size() && new HashSet<>(d.dependencies()).size() == d.dependencies().size(),
                "Invalid or duplicate factual dependencies");
        for (var ref : d.dependencies()) {
            require(facts.containsKey(ref) && facts.get(ref) != null && facts.get(ref).status() != Status.MISSING,
                    "Invalid, missing or cross-booking factual dependency");
        }
        if (d.basis() == Basis.EXPLICIT || d.basis() == Basis.NOT_ESTABLISHED)
            require(d.dependencies().isEmpty(), "This interpretation basis cannot claim factual dependencies");
        require((d.basis() == Basis.CALCULATED) == (d.calculation() != null), "Calculation/basis mismatch");
        if (f.status() == Status.MISSING) require(f.sourceExpressions().isEmpty(), "Missing fact cannot have source expressions");
        if (d.calculation() != null) {
            var c = d.calculation();
            require(f.status() == Status.CLEAR && c.operation() != null && c.unit() == Unit.MINUTES
                    && c.temporalBasis() != null && c.operands() != null && c.operands().size() == 2,
                    "Calculation requires a clear value, supported operation, unit, temporal basis and two operands");
            for (var operand : c.operands()) require(facts.containsKey(operand) && facts.get(operand) != null
                    && facts.get(operand).status() == Status.CLEAR, "Calculation requires clear same-booking operands");
        }
    }

    private void visit(Reference ref, Map<Reference, SemanticFactV3<?>> facts, Set<Reference> path, Set<Reference> done) {
        if (done.contains(ref)) return;
        require(path.add(ref), "Cyclic semantic reference at " + ref);
        var d = facts.get(ref).interpretation();
        for (var dependency : d.dependencies()) visit(dependency, facts, path, done);
        if (d.calculation() != null) for (var operand : d.calculation().operands()) visit(operand, facts, path, done);
        path.remove(ref); done.add(ref);
    }

    private Verification calculate(Reference target, SemanticFactV3<?> fact,
            Map<Reference, SemanticFactV3<?>> facts, SemanticTourExtractionV3.Booking b) {
        var c = fact.interpretation().calculation();
        var a = c.operands().get(0); var z = c.operands().get(1);
        require(a.conditionIndex() == null && z.conditionIndex() == null && target.conditionIndex() == null,
                "Conditions are not arithmetic operands or results");
        require(validOperation(target.field(), c.operation(), a.field(), z.field()), "Operation/field operand mismatch");
        if (c.temporalBasis() == TemporalBasis.ELAPSED) {
            require(fact.interpretation().dependencies().contains(new Reference(b.bookingIndex(), Field.TIMEZONE, null))
                    && b.timezone().status() == Status.CLEAR, "Elapsed arithmetic requires an established timezone dependency");
            for (var operand : c.operands()) require(!hasLocalCalculation(operand, facts),
                    "Provisional local arithmetic cannot establish an elapsed operand");
        }
        Object expected;
        Object first = facts.get(a).value(), second = facts.get(z).value();
        try {
            if (c.operation() == Operation.MINUTES_BETWEEN) {
                var from = ((LocalMoment) first).toLocalDateTime();
                var to = ((LocalMoment) second).toLocalDateTime();
                var duration = c.temporalBasis() == TemporalBasis.WALL_CLOCK ? Duration.between(from, to)
                        : Duration.between(requiredInstant(from, b), requiredInstant(to, b));
                require(duration.getNano() == 0 && duration.getSeconds() % 60 == 0, "Calculation is not a whole minute value");
                expected = Math.toIntExact(duration.toMinutes());
            } else {
                var local = ((LocalMoment) first).toLocalDateTime();
                long minutes = (Integer) second;
                if (c.operation() == Operation.SUBTRACT_MINUTES) minutes = -minutes;
                if (c.temporalBasis() == TemporalBasis.WALL_CLOCK) expected = LocalMoment.of(local.plusMinutes(minutes));
                else {
                    var instant = requiredInstant(local, b).plusSeconds(Math.multiplyExact(minutes, 60));
                    var result = instant.atZone(ZoneId.of(b.timezone().value())).toLocalDateTime();
                    // LocalMoment has no offset: an overlapping result cannot identify the computed instant.
                    require(requiredInstant(result, b).equals(instant), "Elapsed result cannot be represented without an offset");
                    expected = LocalMoment.of(result);
                }
            }
        } catch (DateTimeException | ArithmeticException | ClassCastException e) {
            throw new InvalidDocumentExtractionException("Invalid typed calculation operands or result");
        }
        require(expected.equals(fact.value()), "Calculated value disagrees with structured operands");
        return c.temporalBasis() == TemporalBasis.ELAPSED ? Verification.ELAPSED_ARITHMETIC_VERIFIED
                : Verification.LOCAL_ARITHMETIC_VERIFIED;
    }

    private boolean validOperation(Field out, Operation operation, Field a, Field b) {
        return switch (operation) {
            case MINUTES_BETWEEN -> out == Field.DURATION_MINUTES && a == Field.START && b == Field.END
                    || out == Field.ARRIVAL_LEAD_MINUTES && a == Field.REQUIRED_ARRIVAL && b == Field.START;
            case ADD_MINUTES -> out == Field.END && a == Field.START && b == Field.DURATION_MINUTES
                    || out == Field.START && a == Field.REQUIRED_ARRIVAL && b == Field.ARRIVAL_LEAD_MINUTES;
            case SUBTRACT_MINUTES -> out == Field.REQUIRED_ARRIVAL && a == Field.START && b == Field.ARRIVAL_LEAD_MINUTES
                    || out == Field.START && a == Field.END && b == Field.DURATION_MINUTES;
        };
    }

    /** Preserve explicit local facts, but never choose an arbitrary offset for an unresolved instant. */
    private Instant instant(LocalDateTime local, SemanticTourExtractionV3.Booking b) {
        if (local == null || b.timezone().status() != Status.CLEAR) return null;
        var offsets = ZoneId.of(b.timezone().value()).getRules().getValidOffsets(local);
        return offsets.size() == 1 ? local.toInstant(offsets.getFirst()) : null;
    }
    private Instant requiredInstant(LocalDateTime local, SemanticTourExtractionV3.Booking b) {
        var instant = instant(local, b);
        require(instant != null, "Elapsed arithmetic lacks a uniquely resolvable instant");
        return instant;
    }
    private LocalDateTime local(SemanticFactV3<LocalMoment> f) {
        return f.status() == Status.CLEAR ? f.value().toLocalDateTime() : null;
    }
    private boolean provisional(SemanticFactV3<?> f) {
        var c = f.interpretation().calculation();
        return c != null && c.temporalBasis() == TemporalBasis.WALL_CLOCK;
    }
    private boolean hasLocalCalculation(Reference ref, Map<Reference, SemanticFactV3<?>> facts) {
        var f = facts.get(ref);
        if (provisional(f)) return true;
        var c = f.interpretation().calculation();
        return f.interpretation().dependencies().stream().anyMatch(r -> hasLocalCalculation(r, facts))
                || c != null && c.operands().stream().anyMatch(r -> hasLocalCalculation(r, facts));
    }
    private void canonicalZone(String value) {
        require(ZoneId.getAvailableZoneIds().contains(value), "Timezone must be a supported IANA identifier, not a raw expression or offset");
    }
    private void consistency(SemanticTourExtractionV3.Booking b) {
        var start = local(b.start()); var end = local(b.end()); var arrival = local(b.requiredArrival());
        if (start != null && end != null) require(end.isAfter(start), "End must follow start");
        if (start != null && arrival != null) require(!arrival.isAfter(start), "Arrival must not follow start");
        var si = instant(start, b); var ei = instant(end, b); var ai = instant(arrival, b);
        if (si != null && ei != null) {
            require(ei.isAfter(si), "End instant must follow start");
            if (b.durationMinutes().status() == Status.CLEAR && !provisional(b.durationMinutes())
                    && !provisional(b.start()) && !provisional(b.end()))
                require(si.plusSeconds(b.durationMinutes().value().longValue() * 60).equals(ei), "End/duration conflict");
        }
        if (si != null && ai != null) {
            require(!ai.isAfter(si), "Arrival instant must not follow start");
            if (b.arrivalLeadMinutes().status() == Status.CLEAR && !provisional(b.arrivalLeadMinutes())
                    && !provisional(b.start()) && !provisional(b.requiredArrival()))
                require(si.minusSeconds(b.arrivalLeadMinutes().value().longValue() * 60).equals(ai), "Arrival/lead conflict");
        }
    }
    Map<Reference, SemanticFactV3<?>> fields(SemanticTourExtractionV3.Booking b) {
        var map = new LinkedHashMap<Reference, SemanticFactV3<?>>();
        put(map,b,Field.NAME,b.name()); put(map,b,Field.PROVIDER,b.provider()); put(map,b,Field.BOOKING_REFERENCE,b.bookingReference());
        put(map,b,Field.START,b.start()); put(map,b,Field.END,b.end()); put(map,b,Field.TIMEZONE,b.timezone());
        put(map,b,Field.DURATION_MINUTES,b.durationMinutes()); put(map,b,Field.REQUIRED_ARRIVAL,b.requiredArrival());
        put(map,b,Field.ARRIVAL_LEAD_MINUTES,b.arrivalLeadMinutes()); put(map,b,Field.MEETING_POINT,b.meetingPoint());
        put(map,b,Field.ENDING_POINT,b.endingPoint()); put(map,b,Field.INSTRUCTIONS,b.instructions());
        for (int i=0; i<b.conditions().size(); i++) map.put(new Reference(b.bookingIndex(),Field.CONDITIONS,i),b.conditions().get(i));
        return map;
    }
    private void put(Map<Reference, SemanticFactV3<?>> map, SemanticTourExtractionV3.Booking b, Field f, SemanticFactV3<?> v) {
        map.put(new Reference(b.bookingIndex(),f,null),v);
    }
    private static InvalidDocumentExtractionException at(Reference r, InvalidDocumentExtractionException e) {
        return new InvalidDocumentExtractionException("booking[" + r.bookingIndex() + "]." + r.field()
                + (r.conditionIndex() == null ? "" : "[" + r.conditionIndex() + "]") + ": " + e.getMessage());
    }
    private static boolean text(String s) { return s != null && !s.isBlank(); }
    private static void require(boolean valid, String message) { if (!valid) throw new InvalidDocumentExtractionException(message); }
}
