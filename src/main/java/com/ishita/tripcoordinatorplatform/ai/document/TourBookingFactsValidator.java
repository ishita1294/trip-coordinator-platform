package com.ishita.tripcoordinatorplatform.ai.document;

import java.time.*;
import java.util.*;
import java.util.function.Consumer;
import static com.ishita.tripcoordinatorplatform.ai.document.BookingFact.*;
import static com.ishita.tripcoordinatorplatform.ai.document.TourBookingFacts.*;

/** Validates structured claims, never interprets quotations or free-text instructions. */
public final class TourBookingFactsValidator {
    /** Request-independent arithmetic result, not provider coordinates or verified route evidence. */
    public record Timing(Instant start, Instant end, Instant requiredArrival,
                         boolean endDerived, boolean arrivalDerived) {}

    public void validate(TourBookingFacts extraction, String expectedSourceSha256) {
        require(extraction != null && extraction.schemaVersion() == SCHEMA_VERSION, "Unsupported semantic extraction version");
        var p = extraction.provenance();
        require(p != null && text(p.sourceSha256()) && p.sourceSha256().matches("[a-f0-9]{64}")
                && p.sourceSha256().equals(expectedSourceSha256) && text(p.model())
                && PROMPT_VERSION.equals(p.promptVersion()) && text(p.providerResponseId()) && p.extractedAt() != null,
                "Semantic extraction source/provenance mismatch");
        require(extraction.bookings() != null && !extraction.bookings().isEmpty(), "Semantic bookings are required");
        for (int i = 0; i < extraction.bookings().size(); i++) {
            var b = extraction.bookings().get(i);
            require(b != null && b.bookingIndex() == i, "Booking order/identity mismatch");
            validateBooking(b, true);
        }
    }

    /** Shared domain shape checks; successor contracts validate their own dependency/calculation semantics. */
    void validateBooking(Booking b, boolean validateTiming) {
        var fields = fields(b);
        for (var fact : fields.values()) validateFact(fact, b.bookingIndex());
        for (var entry : fields.entrySet()) {
            var fact = entry.getValue();
            require(!fact.derivation().inputs().contains(entry.getKey()), "Self-referencing derivation");
            if (fact.derivation().basis() == Basis.CALCULATED) {
                for (var input : fact.derivation().inputs())
                    require(fields.containsKey(input) && fields.get(input).status() == Status.CLEAR,
                            "Calculated fact requires clear structured inputs");
            }
            validateDependencies(entry.getKey(), fields, EnumSet.noneOf(Field.class));
        }
        for (var f : List.of(b.name(), b.provider(), b.bookingReference(), b.timezone(), b.instructions()))
            values(f, v -> require(text(v), "Clear text must not be blank"));
        values(b.timezone(), v -> {
            try { ZoneId.of(v); } catch (DateTimeException e) { fail("Invalid semantic timezone"); }
        });
        values(b.durationMinutes(), v -> require(v > 0, "Duration must be positive"));
        values(b.arrivalLeadMinutes(), v -> require(v >= 0, "Arrival lead must not be negative"));
        for (var f : List.of(b.start(), b.end(), b.requiredArrival())) {
            values(f, v -> require(v.date() != null || v.time() != null, "Empty temporal value"));
            if (f.status() == Status.CLEAR) require(f.value().date() != null && f.value().time() != null,
                    "Partial temporal evidence must remain unresolved");
        }
        values(b.meetingPoint(), this::location);
        values(b.endingPoint(), v -> {
            require(v.meaning() != null, "Ending meaning is required");
            if (v.meaning() == EndingMeaning.DIFFERENT_LOCATION ? v.location() == null : v.location() != null)
                fail("booking[" + b.bookingIndex() + "].endingPoint: Endpoint meaning/location mismatch (meaning="
                        + v.meaning() + ", location=" + (v.location() == null ? "absent" : "present") + ")");
            if (v.location() != null) location(v.location());
            if (v.meaning() == EndingMeaning.SAME_AS_MEETING_POINT)
                require(b.meetingPoint().status() == Status.CLEAR, "Same endpoint needs an established meeting point");
        });
        require(b.conditions() != null, "Conditions must be represented explicitly");
        for (var condition : b.conditions()) {
            validateFact(condition, b.bookingIndex());
            require(condition.derivation().basis() != Basis.CALCULATED, "Conditions require source interpretation");
            values(condition, v -> {
                require(v.kind() != null && v.obligation() != null && text(v.description()), "Invalid booking condition");
                require(v.minimumAgeYears() == null || (v.minimumAgeYears() >= 0 && v.kind() == ConditionKind.PARTICIPATION), "Invalid age condition");
                require(v.noticeMinutes() == null || (v.noticeMinutes() >= 0 && v.kind() == ConditionKind.CANCELLATION), "Invalid notice condition");
            });
        }
        if (validateTiming) timing(b);
    }

    public Timing timing(Booking b) {
        var zoneName = clear(b.timezone());
        ZoneId zone = zoneName == null ? null : ZoneId.of(zoneName);
        var startLocal = clearMoment(b.start()); var endLocal = clearMoment(b.end()); var arrivalLocal = clearMoment(b.requiredArrival());
        var duration = clear(b.durationMinutes()); var lead = clear(b.arrivalLeadMinutes());
        // Local ordering is still meaningful without a known zone; elapsed arithmetic is not.
        if (startLocal != null && endLocal != null) require(endLocal.isAfter(startLocal), "End must follow start");
        if (startLocal != null && arrivalLocal != null) require(!arrivalLocal.isAfter(startLocal), "Arrival must not follow start");
        Instant start = resolve(startLocal, zone), end = resolve(endLocal, zone), arrival = resolve(arrivalLocal, zone);
        boolean endDerived = false, arrivalDerived = false;
        if (start != null && duration != null) {
            Instant calculated = start.plusSeconds(Math.multiplyExact(duration.longValue(), 60));
            if (end != null) require(end.equals(calculated), "End/duration conflict");
            else if (b.end().status() == Status.MISSING) { end = calculated; endDerived = true; }
        }
        if (start != null && lead != null) {
            Instant calculated = start.minusSeconds(Math.multiplyExact(lead.longValue(), 60));
            if (arrival != null) require(arrival.equals(calculated), "Arrival/lead conflict");
            else if (b.requiredArrival().status() == Status.MISSING) { arrival = calculated; arrivalDerived = true; }
        }
        return new Timing(start, end, arrival, endDerived, arrivalDerived);
    }

    private Instant resolve(LocalDateTime local, ZoneId zone) {
        if (local == null || zone == null) return null;
        var offsets = zone.getRules().getValidOffsets(local);
        require(offsets.size() == 1, "Ambiguous or nonexistent local timestamp must remain unresolved");
        return local.toInstant(offsets.getFirst());
    }
    private void location(DocumentLocation v) {
        require(text(v.name()) || text(v.address()), "Location needs a documented name/address");
        require((v.latitude() == null) == (v.longitude() == null), "Incomplete printed coordinates");
        if (v.latitude() != null) require(Double.isFinite(v.latitude()) && Double.isFinite(v.longitude())
                && Math.abs(v.latitude()) <= 90 && Math.abs(v.longitude()) <= 180, "Invalid printed coordinates");
    }
    private void validateDependencies(Field field, Map<Field, BookingFact<?>> fields, Set<Field> path) {
        require(path.add(field), "Circular semantic derivation");
        for (var input : fields.get(field).derivation().inputs()) {
            require(fields.containsKey(input), "Unknown derivation input");
            validateDependencies(input, fields, path);
        }
        path.remove(field);
    }
    private void validateFact(BookingFact<?> fact, int index) {
        require(fact != null && fact.status() != null && fact.alternatives() != null && fact.evidence() != null
                && fact.derivation() != null && fact.derivation().basis() != null && fact.derivation().inputs() != null,
                "Incomplete semantic fact");
        switch (fact.status()) {
            case CLEAR -> require(fact.value() != null && fact.alternatives().isEmpty()
                    && !fact.evidence().isEmpty() && fact.derivation().basis() != Basis.NOT_ESTABLISHED, "Clear fact needs supported value");
            case MISSING -> require(fact.value() == null && fact.alternatives().isEmpty() && fact.evidence().isEmpty()
                    && fact.derivation().basis() == Basis.NOT_ESTABLISHED, "Missing fact cannot contain an invented value");
            case AMBIGUOUS, CONFLICTING -> {
                require(fact.value() == null && text(fact.uncertainty()) && !fact.evidence().isEmpty(), "Uncertain fact needs evidence/uncertainty, not a selected value");
                if (fact.status() == Status.CONFLICTING)
                    require(new HashSet<>(fact.alternatives()).size() >= 2, "Conflicting fact needs distinct alternatives");
            }
        }
        require(fact.derivation().basis() == Basis.CALCULATED ? !fact.derivation().inputs().isEmpty()
                : fact.derivation().inputs().isEmpty(), "Derivation inputs/basis mismatch");
        for (var evidence : fact.evidence()) require(evidence != null && evidence.bookingIndex() == index
                && text(evidence.bookingAssociation()) && text(evidence.quotation())
                && (evidence.reportedPage() == null || evidence.reportedPage() > 0), "Evidence booking association is invalid");
    }
    private Map<Field, BookingFact<?>> fields(Booking b) {
        var result = new EnumMap<Field, BookingFact<?>>(Field.class);
        result.put(Field.NAME, b.name()); result.put(Field.PROVIDER, b.provider()); result.put(Field.BOOKING_REFERENCE, b.bookingReference());
        result.put(Field.START, b.start()); result.put(Field.END, b.end()); result.put(Field.TIMEZONE, b.timezone());
        result.put(Field.DURATION_MINUTES, b.durationMinutes()); result.put(Field.REQUIRED_ARRIVAL, b.requiredArrival());
        result.put(Field.ARRIVAL_LEAD_MINUTES, b.arrivalLeadMinutes()); result.put(Field.MEETING_POINT, b.meetingPoint());
        result.put(Field.ENDING_POINT, b.endingPoint()); result.put(Field.INSTRUCTIONS, b.instructions()); return result;
    }
    private static <T> T clear(BookingFact<T> f) { return f.status() == Status.CLEAR ? f.value() : null; }
    private static LocalDateTime clearMoment(BookingFact<LocalMoment> f) {
        var moment = clear(f); return moment == null ? null : moment.toLocalDateTime();
    }
    private static <T> void values(BookingFact<T> f, Consumer<T> check) {
        if (f.value() != null) check.accept(f.value()); f.alternatives().forEach(check);
    }
    private static boolean text(String v) { return v != null && !v.isBlank(); }
    private static void require(boolean test, String message) { if (!test) fail(message); }
    private static void fail(String message) { throw new InvalidDocumentExtractionException(message); }
}
