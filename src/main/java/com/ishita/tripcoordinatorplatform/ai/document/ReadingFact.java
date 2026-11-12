package com.ishita.tripcoordinatorplatform.ai.document;

import java.util.List;
import static com.ishita.tripcoordinatorplatform.ai.document.BookingFact.*;

/** Untrusted AI claims. Verification of a calculation is returned by Java, never asserted by the model. */
public record ReadingFact<T>(Status status, T value, List<T> alternatives, List<SourceEvidence> evidence,
        String uncertainty, List<String> sourceExpressions, Interpretation interpretation) {
    public ReadingFact {
        alternatives = alternatives == null ? null : List.copyOf(alternatives);
        evidence = evidence == null ? null : List.copyOf(evidence);
        sourceExpressions = sourceExpressions == null ? null : List.copyOf(sourceExpressions);
    }
    /** References are local to a booking; CONDITIONS requires a specific conditionIndex. */
    public record Reference(int bookingIndex, Field field, Integer conditionIndex) {}
    /** Null bookingIndex means a document/shared quotation; applicability is carried separately, not inferred here. */
    public record SourceEvidence(Integer bookingIndex, String bookingAssociation, String quotation, Integer reportedPage) {
        Evidence bookingEvidence() { return new Evidence(bookingIndex, bookingAssociation, quotation, reportedPage); }
    }
    public record Interpretation(Basis basis, List<Reference> dependencies, Calculation calculation) {
        public Interpretation { dependencies = dependencies == null ? null : List.copyOf(dependencies); }
    }
    public enum Operation { MINUTES_BETWEEN, ADD_MINUTES, SUBTRACT_MINUTES }
    public enum Unit { MINUTES }
    public enum TemporalBasis { WALL_CLOCK, ELAPSED }
    /** Ordered operands: earlier/later for a difference, moment/minutes for addition/subtraction. */
    public record Calculation(Operation operation, List<Reference> operands, Unit unit, TemporalBasis temporalBasis) {
        public Calculation { operands = operands == null ? null : List.copyOf(operands); }
    }

    /** Internal validation view only, never persisted or presented as a historical v2 extraction. */
    BookingFact<T> shape() {
        return shape(java.util.function.Function.identity());
    }
    <R> BookingFact<R> shape(java.util.function.Function<T,R> mapping) {
        return new BookingFact<>(status, value == null ? null : mapping.apply(value),
                alternatives == null ? null : alternatives.stream().map(mapping).toList(),
                evidence == null ? null : evidence.stream().map(SourceEvidence::bookingEvidence).toList(), uncertainty,
                interpretation == null ? null : new Derivation(
                        interpretation.basis() == Basis.CALCULATED ? Basis.CONTEXT_INTERPRETED : interpretation.basis(),
                        List.of()));
    }
}
