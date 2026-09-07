package com.ishita.tripcoordinatorplatform.response;

import com.ishita.tripcoordinatorplatform.model.ItineraryConflictStatus;
import com.ishita.tripcoordinatorplatform.model.ItineraryConflictType;

public class ItineraryConflictResponse {

    private Long id;
    private ItineraryConflictType type;
    private ItineraryConflictStatus status;

    private Long tripMemberId;
    private String memberName;

    private Long firstItemId;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public ItineraryConflictType getType() {
        return type;
    }

    public void setType(ItineraryConflictType type) {
        this.type = type;
    }

    public ItineraryConflictStatus getStatus() {
        return status;
    }

    public void setStatus(ItineraryConflictStatus status) {
        this.status = status;
    }

    public Long getTripMemberId() {
        return tripMemberId;
    }

    public void setTripMemberId(Long tripMemberId) {
        this.tripMemberId = tripMemberId;
    }

    public String getMemberName() {
        return memberName;
    }

    public void setMemberName(String memberName) {
        this.memberName = memberName;
    }

    public Long getFirstItemId() {
        return firstItemId;
    }

    public void setFirstItemId(Long firstItemId) {
        this.firstItemId = firstItemId;
    }

    public String getFirstItemTitle() {
        return firstItemTitle;
    }

    public void setFirstItemTitle(String firstItemTitle) {
        this.firstItemTitle = firstItemTitle;
    }

    public Long getSecondItemId() {
        return secondItemId;
    }

    public void setSecondItemId(Long secondItemId) {
        this.secondItemId = secondItemId;
    }

    public String getSecondItemTitle() {
        return secondItemTitle;
    }

    public void setSecondItemTitle(String secondItemTitle) {
        this.secondItemTitle = secondItemTitle;
    }

    private String firstItemTitle;

    private Long secondItemId;
    private String secondItemTitle;

    public ItineraryConflictResponse() {
    }
}
