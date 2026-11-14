package com.ishita.tripcoordinatorplatform.request;

import com.fasterxml.jackson.annotation.JsonAnySetter;

/** Optional initial trip and booking hints. Both are checked against the signed-in user. */
public record CreateAIConversationRequest(Long initialReservationId,Long initialTripId) {
    public CreateAIConversationRequest(Long initialReservationId){this(initialReservationId,null);}
    @JsonAnySetter public void rejectUnknownField(String name,Object value){
        throw new IllegalArgumentException("Unsupported conversation field: "+name);
    }
}
