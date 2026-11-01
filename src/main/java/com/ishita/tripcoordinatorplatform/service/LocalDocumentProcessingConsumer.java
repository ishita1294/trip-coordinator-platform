package com.ishita.tripcoordinatorplatform.service;

import com.ishita.tripcoordinatorplatform.repository.TravelDocumentRepository;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.*;
import java.util.UUID;
import java.util.concurrent.*;

/** Delivers durable database jobs to the same worker used by Lambda. */
@Component
@EnableScheduling
@ConditionalOnProperty(name="app.document-processing.execution-mode", havingValue="LOCAL")
public class LocalDocumentProcessingConsumer {
    private static final Logger log = LoggerFactory.getLogger(LocalDocumentProcessingConsumer.class);
    private final TravelDocumentRepository documents;
    private final DocumentProcessingWorkerClaimService claims;
    private final DocumentProcessingWorkerExecutionService worker;
    private final int maxAttempts;
    private final Duration lease;
    private final Clock clock;
    private final ScheduledExecutorService leaseRenewals;

    @org.springframework.beans.factory.annotation.Autowired
    public LocalDocumentProcessingConsumer(TravelDocumentRepository documents,
            DocumentProcessingWorkerClaimService claims, DocumentProcessingWorkerExecutionService worker,
            @Value("${app.document-processing.worker.max-deliveries:5}") int maxAttempts,
            @Value("${app.document-processing.worker.lease-seconds:180}") long leaseSeconds) {
        this(documents, claims, worker, maxAttempts, leaseSeconds, Clock.systemUTC(),
                Executors.newSingleThreadScheduledExecutor(r -> {
                    var thread = new Thread(r, "document-processing-lease");thread.setDaemon(true);return thread;
                }));
    }

    LocalDocumentProcessingConsumer(TravelDocumentRepository documents, DocumentProcessingWorkerClaimService claims,
            DocumentProcessingWorkerExecutionService worker, int maxAttempts, long leaseSeconds, Clock clock,
            ScheduledExecutorService leaseRenewals) {
        if (maxAttempts < 1 || leaseSeconds < 120) throw new IllegalArgumentException("Unsafe local processing lease or attempt limit");
        this.documents=documents;this.claims=claims;this.worker=worker;this.maxAttempts=maxAttempts;
        this.lease=Duration.ofSeconds(leaseSeconds);this.clock=clock;this.leaseRenewals=leaseRenewals;
    }

    // Keep provider waits off the application's default scheduler (including cleanup tasks).
    @org.springframework.context.annotation.Bean(defaultCandidate=false)
    public org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler localDocumentProcessingScheduler() {
        var scheduler = new org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix("local-document-processing-");
        return scheduler;
    }

    @Scheduled(fixedDelayString="${app.document-processing.local.poll-delay-ms:2000}", scheduler="localDocumentProcessingScheduler")
    public void poll() {
        var candidates = documents.findLocalProcessingCandidates(clock.instant().minus(lease), maxAttempts, PageRequest.of(0,20));
        for (var document : candidates) {
            try {
                var claim = claims.claimLocal(document.getId(), document.getProcessingJobId());
                if (claim.status() != DocumentProcessingWorkerClaimResult.Status.CLAIMED) continue;
                var current = documents.findById(document.getId()).orElseThrow();
                var renewal = leaseRenewals.scheduleAtFixedRate(
                        () -> renew(document.getId(), document.getProcessingJobId(), claim.attemptId()),
                        lease.toMillis()/3, lease.toMillis()/3, TimeUnit.MILLISECONDS);
                try {
                    // No transaction surrounds provider work. The worker commits its own result or failure.
                    worker.execute(document.getId(), claim.attemptId(), current.getProcessingDeliveryCount(), maxAttempts);
                } finally {
                    renewal.cancel(false);
                }
            } catch (RuntimeException failure) {
                // Worker failures are already persisted. QUEUED jobs are eligible on a later poll;
                // abandoned PROCESSING jobs become eligible only after their lease expires.
                log.warn("Local document delivery ended documentId={} causeClass={}",
                        document.getId(), failure.getClass().getSimpleName());
            }
        }
    }

    private void renew(Long documentId, UUID jobId, UUID attemptId) {
        try {
            if (!claims.renewLease(documentId, jobId, attemptId))
                log.warn("Local document attempt no longer owns documentId={}", documentId);
        } catch (RuntimeException failure) {
            log.warn("Local document lease renewal failed documentId={} causeClass={}",documentId,failure.getClass().getSimpleName());
        }
    }

    @PreDestroy
    public void close() { leaseRenewals.shutdownNow(); }
}
