package com.ishita.tripcoordinatorplatform.ai.document;

import java.util.*;
import java.util.function.Consumer;
import tools.jackson.databind.json.JsonMapper;
import static com.ishita.tripcoordinatorplatform.ai.document.BookingFact.*;
import static com.ishita.tripcoordinatorplatform.ai.document.ReadingFact.*;
import static com.ishita.tripcoordinatorplatform.ai.document.ReadingAssertion.*;
import static com.ishita.tripcoordinatorplatform.ai.document.SupplementalReadingFact.*;

/** Structural semantics only: cannot establish whether a model correctly classified the source. */
final class ReadingAssertionValidator {
    private static final JsonMapper MAPPER = new JsonMapper();
    void validateBookingConditions(TourReading.Booking b) {
        for (var f : b.conditions()) {
            require(f.interpretation().basis() != Basis.CALCULATED, "Policies require semantic interpretation, not calculation");
            values(f, c -> {
                require(c.kind() != null && c.semantics() != null, "Incomplete booking condition");
                assertion(c.semantics(), f.status());
                require(actionable(c.semantics().role()), "Notices and metadata belong in supplementalFacts, not booking conditions");
            });
        }
    }

    /** Size of the supplemental list the way the limit is checked, so other code can measure it identically. */
    static int serializedCharacters(List<SupplementalReadingFact> supplemental) {
        return MAPPER.writeValueAsString(supplemental).length();
    }

    void validateSupplemental(TourReading extraction, Map<Reference, ReadingFact<?>> allFacts,
            TourReadingValidator factValidator) {
        var supplemental = extraction.supplementalFacts();
        require(supplemental != null && supplemental.size() <= MAX_FACTS, "Supplemental fact count exceeds limit or is missing");
        optional(extraction.supplementalCoverageWarning(),500,"Supplemental coverage warning");
        require(serializedCharacters(supplemental) <= MAX_SERIALIZED_CHARACTERS,
                "Supplemental serialized character budget exceeded");
        var duplicates = new HashSet<Object>();
        for (var item : supplemental) {
            require(item != null && item.applicability() != null && item.fact() != null, "Incomplete supplemental fact");
            var a = item.applicability(); var f = item.fact();
            applicability(a, extraction.bookings().size());
            var allowed = applicableIndexes(a, extraction.bookings().size());
            var references = new LinkedHashMap<Reference, ReadingFact<?>>();
            allFacts.forEach((r,v) -> { if (allowed.contains(r.bookingIndex())) references.put(r,v); });
            factValidator.validateFact(f, references);
            require(f.status() != Status.MISSING, "Do not emit absent supplemental facts");
            require(f.alternatives().size() <= 4, "Too many supplemental fact alternatives");
            require(f.interpretation().basis() != Basis.CALCULATED, "Supplemental assertions are not arithmetic results");
            require(f.evidence().size() <= 4, "Supplemental evidence limit exceeded");
            optional(f.uncertainty(),500,"Supplemental uncertainty");
            for (var e : f.evidence()) {
                require(e.bookingIndex() == null || allowed.contains(e.bookingIndex()), "Evidence is outside supplemental applicability");
                bounded(e.quotation(),600,"Supplemental quotation"); bounded(e.bookingAssociation(),200,"Evidence association");
            }
            values(f, value -> {
                assertion(value, f.status());
                if (a.status() == Status.CLEAR) {
                    require(value.role() != Role.METADATA || a.value().kind() == ScopeKind.DOCUMENT,
                            "Document metadata cannot be scoped as a booking policy");
                    require(!actionable(value.role()) || a.value().kind() != ScopeKind.DOCUMENT,
                            "Booking requirements and policies need booking applicability, not document metadata scope");
                }
            });
            if (f.status() == Status.CLEAR && a.status() == Status.CLEAR) {
                require(duplicates.add(List.of(scopeIdentity(a.value()),f.value())), "Duplicate supplemental assertion");
                // Exact typed duplicates can be rejected; paraphrase recognition remains the AI's responsibility.
                for (var index : allowed) for (var condition : extraction.bookings().get(index).conditions())
                    require(condition.status() != Status.CLEAR || !condition.value().semantics().equals(f.value()),
                            "Supplemental assertion duplicates a booking condition");
            }
        }
    }

    private void applicability(Applicability a, int count) {
        require(a.status() != null && a.alternatives() != null, "Incomplete applicability");
        optional(a.uncertainty(),500,"Applicability uncertainty");
        require(a.alternatives().size() <= 4, "Too many applicability alternatives");
        switch (a.status()) {
            case CLEAR -> require(a.value() != null && a.alternatives().isEmpty(), "Clear applicability needs one scope");
            case MISSING -> require(a.value() == null && a.alternatives().isEmpty() && text(a.uncertainty()), "Missing applicability needs uncertainty");
            case AMBIGUOUS, CONFLICTING -> {
                require(a.value() == null && text(a.uncertainty()), "Unresolved applicability cannot select a scope");
            }
        }
        if (a.value() != null) scope(a.value(),count);
        for (var s : a.alternatives()) scope(s,count);
        if (a.status() == Status.CONFLICTING) require(a.alternatives().stream().map(this::scopeIdentity).distinct().count() >= 2,
                "Conflicting applicability needs distinct alternatives");
    }
    private Object scopeIdentity(Scope scope) { return List.of(scope.kind(),new TreeSet<>(scope.bookingIndexes())); }
    private void scope(Scope s, int count) {
        require(s != null && s.kind() != null && s.bookingIndexes() != null, "Incomplete applicability scope");
        if (s.kind() == ScopeKind.BOOKINGS) {
            require(!s.bookingIndexes().isEmpty() && s.bookingIndexes().size() <= count
                    && new HashSet<>(s.bookingIndexes()).size() == s.bookingIndexes().size(), "Invalid booking applicability indexes");
            for (var i : s.bookingIndexes()) require(i >= 0 && i < count, "Invalid booking applicability index");
        } else require(s.bookingIndexes().isEmpty(), "Document/all-booking scopes must not enumerate indexes");
    }
    private Set<Integer> applicableIndexes(Applicability a, int count) {
        var indexes = new HashSet<Integer>();
        Consumer<Scope> add = s -> {
            if(s.kind() == ScopeKind.ALL_BOOKINGS) for(int i=0;i<count;i++) indexes.add(i);
            else if(s.kind() == ScopeKind.BOOKINGS) indexes.addAll(s.bookingIndexes());
        };
        if(a.value() != null) add.accept(a.value()); a.alternatives().forEach(add);
        return indexes;
    }
    private void assertion(ReadingAssertion a, Status status) {
        require(a != null && a.role() != null && a.modality() != null && a.conditionality() != null, "Incomplete semantic assertion");
        bounded(a.description(),500,"Assertion description"); optional(a.qualification(),400,"Assertion qualification");
        boolean correctModality = switch(a.role()) {
            case REQUIREMENT -> a.modality() == Modality.REQUIRED;
            case RESTRICTION -> a.modality() == Modality.PROHIBITED;
            case OPTION -> a.modality() == Modality.PERMITTED;
            case POLICY -> true;
            case NOTICE, METADATA, OTHER -> a.modality() == Modality.NOT_APPLICABLE;
        };
        require(correctModality, "Role/modality mismatch");
        switch(a.conditionality()) {
            case UNCONDITIONAL -> require(a.trigger() == null, "Unconditional assertion cannot have a trigger");
            case CONDITIONAL -> bounded(a.trigger(),400,"Conditional trigger");
            case UNRESOLVED -> require(a.trigger() == null && status != Status.CLEAR, "Unresolved condition cannot be clear or invent a trigger");
        }
        if(a.detail() != null) detail(a.detail());
    }
    private void detail(Detail d) {
        require(d.type() != null, "Supplemental detail type is required");
        int populated=(d.text()!=null?1:0)+(d.booleanValue()!=null?1:0)+(d.quantity()!=null?1:0)
                +(d.date()!=null?1:0)+(d.localDateTime()!=null?1:0);
        require(populated == 1, "Typed detail requires exactly one value");
        boolean correct = switch(d.type()) {
            case TEXT -> d.text() != null;
            case BOOLEAN -> d.booleanValue() != null;
            case QUANTITY -> d.quantity() != null;
            case DATE -> d.date() != null;
            case LOCAL_DATE_TIME -> d.localDateTime() != null && d.localDateTime().date() != null && d.localDateTime().time() != null;
        };
        require(correct, "Typed detail/value mismatch");
        if(d.type() == ValueType.QUANTITY) require(Double.isFinite(d.quantity()) && text(d.unit()) && d.unit().length() <= 40,
                "Quantity requires a finite value and a bounded source-established unit");
        else require(d.unit() == null, "Only quantities have units");
        if(d.text() != null) bounded(d.text(),500,"Supplemental text detail");
    }
    private boolean actionable(Role role) { return role == Role.REQUIREMENT || role == Role.RESTRICTION || role == Role.OPTION || role == Role.POLICY; }
    private static <T> void values(ReadingFact<T> fact, Consumer<T> check) {
        if(fact.value()!=null) check.accept(fact.value()); fact.alternatives().forEach(check);
    }
    private static boolean text(String s) { return s!=null && !s.isBlank(); }
    private static void bounded(String s,int max,String name) { require(text(s) && s.length() <= max,name+" is missing or too long"); }
    private static void optional(String s,int max,String name) { if(s!=null) bounded(s,max,name); }
    private static void require(boolean valid,String message) { if(!valid) throw new InvalidDocumentExtractionException(message); }
}
