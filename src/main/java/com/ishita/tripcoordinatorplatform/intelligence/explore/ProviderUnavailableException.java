package com.ishita.tripcoordinatorplatform.intelligence.explore;

// Never contains provider bodies, URLs, credentials, or personal location evidence.
public class ProviderUnavailableException extends RuntimeException {
    private final String code;
    private final java.time.Instant retryAt;
    public ProviderUnavailableException(String message) {this(message,"PROVIDER_UNAVAILABLE",null);}
    public ProviderUnavailableException(String message,String code,java.time.Instant retryAt) {super(message);this.code=code;this.retryAt=retryAt;}
    public String code() {return code;}
    public java.time.Instant nextEligibleAt() {return retryAt;}
}
