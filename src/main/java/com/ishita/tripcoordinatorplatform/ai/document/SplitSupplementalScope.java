package com.ishita.tripcoordinatorplatform.ai.document;

import java.util.ArrayList;
import java.util.List;
import java.util.OptionalInt;

/** Partitions supplemental facts between BOOKING calls (one booking only) and the SHARED call (everything else). */
final class SplitSupplementalScope {
    private SplitSupplementalScope() {}

    /** The only booking a fact concerns, when every scope it mentions is BOOKINGS with that single index. */
    static OptionalInt singleBooking(SupplementalReadingFact fact) {
        var applicability = fact.applicability();
        if (applicability == null) return OptionalInt.empty();
        var scopes = new ArrayList<SupplementalReadingFact.Scope>();
        if (applicability.value() != null) scopes.add(applicability.value());
        if (applicability.alternatives() != null) scopes.addAll(applicability.alternatives());
        Integer only = null;
        for (var scope : scopes) {
            if (scope == null || scope.kind() != SupplementalReadingFact.ScopeKind.BOOKINGS
                    || scope.bookingIndexes() == null || scope.bookingIndexes().size() != 1) return OptionalInt.empty();
            int index = scope.bookingIndexes().getFirst();
            if (only != null && only != index) return OptionalInt.empty();
            only = index;
        }
        return only == null ? OptionalInt.empty() : OptionalInt.of(only);
    }

    static List<SupplementalReadingFact> keep(List<SupplementalReadingFact> facts, SplitCallTarget target) {
        return facts.stream().filter(fact -> {
            var single = singleBooking(fact);
            return target.kind() == SplitCallTarget.Kind.SHARED ? single.isEmpty()
                    : single.isPresent() && single.getAsInt() == target.ordinal();
        }).toList();
    }
}
