package com.ishita.tripcoordinatorplatform.ai.document;

import java.util.List;

/** Shared/document facts are represented once, without implying authoritative domain enforcement. */
public record SemanticSupplementalFactV3(Applicability applicability, SemanticFactV3<SemanticAssertionV3> fact) {
    public static final int MAX_FACTS = 24;
    /** Serialized supplemental list only; leaves output headroom for essential booking facts, not a token guarantee. */
    public static final int MAX_SERIALIZED_CHARACTERS = 16_000;
    public enum ScopeKind { DOCUMENT, BOOKINGS, ALL_BOOKINGS }
    public record Scope(ScopeKind kind, List<Integer> bookingIndexes) {
        public Scope { bookingIndexes = bookingIndexes == null ? null : List.copyOf(bookingIndexes); }
    }
    /** Fact evidence supports both meaning and applicability; uncertain scopes are retained, never silently chosen. */
    public record Applicability(SemanticFact.Status status, Scope value, List<Scope> alternatives, String uncertainty) {
        public Applicability { alternatives = alternatives == null ? null : List.copyOf(alternatives); }
    }
}
