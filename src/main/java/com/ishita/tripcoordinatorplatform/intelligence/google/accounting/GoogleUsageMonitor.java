package com.ishita.tripcoordinatorplatform.intelligence.google.accounting;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;

/** Read-only observability. Warning thresholds do not authorize, stop, or renew paid work. */
@Component
@ConditionalOnProperty(name="app.explore.google.accounting.mode",havingValue="durable")
public final class GoogleUsageMonitor {
    private static final org.slf4j.Logger log=org.slf4j.LoggerFactory.getLogger(GoogleUsageMonitor.class);
    private final GoogleUsageLedger ledger;
    private final Map<String,AtomicLong> charges=new HashMap<>(),reserves=new HashMap<>();
    private final Map<String,Long> thresholds;
    private final Set<String> warned=new HashSet<>();
    private final AtomicLong enabled=new AtomicLong(),healthy=new AtomicLong();
    public GoogleUsageMonitor(GoogleUsageLedger ledger,MeterRegistry registry,
            @Value("${app.explore.google.accounting.alert-hourly-micros:0}") long hour,
            @Value("${app.explore.google.accounting.alert-daily-micros:0}") long day,
            @Value("${app.explore.google.accounting.alert-monthly-micros:0}") long month) {
        if(hour<0||day<0||month<0)throw new IllegalArgumentException("Warning thresholds cannot be negative");
        this.ledger=ledger;thresholds=Map.of("GOOGLE_UTC_HOUR",hour,"GOOGLE_UTC_DAY",day,"GOOGLE_UTC_MONTH",month);
        for(String key:thresholds.keySet()) {
            charges.put(key,new AtomicLong());reserves.put(key,new AtomicLong());
            registry.gauge("explore.google.estimated.charged.micros",List.of(io.micrometer.core.instrument.Tag.of("window",key)),charges.get(key));
            registry.gauge("explore.google.estimated.reserved.micros",List.of(io.micrometer.core.instrument.Tag.of("window",key)),reserves.get(key));
        }
        registry.gauge("explore.google.admissions.enabled",enabled);registry.gauge("explore.google.accounting.report.healthy",healthy);
    }
    @Scheduled(fixedDelay=60000,initialDelay=60000) public void report() {
        try {
            var snapshot=ledger.usageSnapshot();enabled.set(snapshot.admissionsEnabled()?1:0);healthy.set(1);
            for(String key:thresholds.keySet()) {
                long amount=snapshot.chargedMicros().getOrDefault(key,0L);charges.get(key).set(amount);reserves.get(key).set(snapshot.reservedMicros().getOrDefault(key,0L));
                long threshold=thresholds.get(key);
                String warningKey=key+":"+snapshot.windowIds().getOrDefault(key,0L);
                warned.removeIf(old->old.startsWith(key+":") && !old.equals(warningKey));
                if(threshold>0 && amount>=threshold && warned.add(warningKey))log.warn("Google estimated usage warning: window={} chargedMicros={} warningMicros={}; not actual billing and not a dispatch cap",key,amount,threshold);
                if(amount<threshold)warned.remove(warningKey);
            }
        } catch(RuntimeException failure) {healthy.set(0);log.warn("Google accounting usage report unavailable; no accounting bypass or automatic retry");}
    }
}
