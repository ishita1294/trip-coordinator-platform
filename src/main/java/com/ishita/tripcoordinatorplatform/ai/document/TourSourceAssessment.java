package com.ishita.tripcoordinatorplatform.ai.document;

import java.util.List;

/** Source assessment plus server-bound review-state fencing; neither is independent documentary verification. */
public record TourSourceAssessment(String model, List<Finding> findings, String acceptedStateToken) {
    public TourSourceAssessment(String model,List<Finding> findings){this(model,findings,null);}
    public enum Outcome { SOURCE_SUPPORTED_BY_AI_REVIEW, SOURCE_CONTRADICTED, UNRESOLVED, USER_REPORTED_LIFECYCLE_CLARIFICATION, USER_REPORTED_SCHEDULE_CLARIFICATION }
    public record Finding(int bookingIndex, String field, Outcome outcome, String sourceExcerpt,
                          String bookingAssociation, String uncertainty) {}
}
