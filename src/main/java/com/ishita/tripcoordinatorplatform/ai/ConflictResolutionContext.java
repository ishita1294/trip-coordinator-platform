package com.ishita.tripcoordinatorplatform.ai;

import com.ishita.tripcoordinatorplatform.model.ItineraryConflictType;

/**
 * Contains the verified application data provided to the AI
 * when asking it to suggest ways to resolve an itinerary conflict.
 */
public class ConflictResolutionContext {

    private Long conflictId;
    private ItineraryConflictType conflictType;
    private String memberName;
    private ConflictResolutionItemContext firstItem;
    private ConflictResolutionItemContext secondItem;

    public ConflictResolutionContext() {
    }

    public Long getConflictId() {
        return conflictId;
    }

    public void setConflictId(Long conflictId) {
        this.conflictId = conflictId;
    }

    public ItineraryConflictType getConflictType() {
        return conflictType;
    }

    public void setConflictType(ItineraryConflictType conflictType) {
        this.conflictType = conflictType;
    }

    public String getMemberName() {
        return memberName;
    }

    public void setMemberName(String memberName) {
        this.memberName = memberName;
    }

    public ConflictResolutionItemContext getFirstItem() {
        return firstItem;
    }

    public void setFirstItem(ConflictResolutionItemContext firstItem) {
        this.firstItem = firstItem;
    }

    public ConflictResolutionItemContext getSecondItem() {
        return secondItem;
    }

    public void setSecondItem(ConflictResolutionItemContext secondItem) {
        this.secondItem = secondItem;
    }
}
