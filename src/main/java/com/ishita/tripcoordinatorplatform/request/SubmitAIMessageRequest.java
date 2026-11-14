package com.ishita.tripcoordinatorplatform.request;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import java.util.UUID;

/** Reuse requestId only when retrying the exact same submission. */
public record SubmitAIMessageRequest(UUID requestId,String content,Long selectedTripId,String timeZone) {
    public SubmitAIMessageRequest(UUID requestId,String content,Long selectedTripId){this(requestId,content,selectedTripId,null);}
    public SubmitAIMessageRequest(UUID requestId,String content){this(requestId,content,null);}
    @JsonAnySetter public void rejectUnknownField(String name,Object value){throw new IllegalArgumentException("Unsupported message field: "+name);}
}
