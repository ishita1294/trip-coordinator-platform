package com.ishita.tripcoordinatorplatform.ai.document;

/** Meaning is separate from modality and conditionality. No application rules are executed from this record. */
public record ReadingAssertion(Role role, Modality modality, Conditionality conditionality,
        String trigger, String description, String qualification, Detail detail) {
    public enum Role { REQUIREMENT, RESTRICTION, OPTION, POLICY, NOTICE, METADATA, OTHER }
    public enum Modality { REQUIRED, PROHIBITED, PERMITTED, NOT_APPLICABLE }
    public enum Conditionality { UNCONDITIONAL, CONDITIONAL, UNRESOLVED }
    public enum ValueType { TEXT, BOOLEAN, QUANTITY, DATE, LOCAL_DATE_TIME }
    /** Exactly one value slot is populated. Quantity requires a source-established unit. No arbitrary JSON. */
    public record Detail(ValueType type, String text, Boolean booleanValue, Double quantity, String unit,
            java.time.LocalDate date, TourBookingFacts.LocalMoment localDateTime) {}
}
