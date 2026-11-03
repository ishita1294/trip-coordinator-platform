package com.ishita.tripcoordinatorplatform.intelligence.google.accounting;

import com.ishita.tripcoordinatorplatform.intelligence.explore.ProviderUnavailableException;
import java.time.Instant;

public final class GoogleAccountingException extends ProviderUnavailableException {
    private final String code;
    private final Instant nextWindowAt;
    public GoogleAccountingException(String code, String detail) { this(code,detail,null); }
    public GoogleAccountingException(String code, String detail, Instant nextWindowAt) { super(detail); this.code=code; this.nextWindowAt=nextWindowAt; }
    public String code() { return code; }
    @Override public Instant nextEligibleAt() {return nextWindowAt;}
    public Instant nextWindowAt() { return nextWindowAt; }
}
