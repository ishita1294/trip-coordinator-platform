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
import java.util.concurrent.atomic.AtomicReference;

/** Delivers durable database jobs to the same worker used by Lambda. */
@Component
@EnableScheduling
@ConditionalOnProperty(name="app.document-processing.execution-mode", havingValue="LOCAL")
public class LocalDocumentProcessingConsumer {
    private static final Logger log = LoggerFactory.getLogger(LocalDocumentProcessingConsumer.class);
    static final long DEFAULT_MAX_JOB_SECONDS = 300;
    static final long MIN_MAX_JOB_SECONDS = 60;
    private final TravelDocumentRepository documents;
    private final DocumentProcessingWorkerClaimService claims;
    private final DocumentProcessingWorkerExecutionService worker;
    private final int maxAttempts;
    private final Duration lease;
    private final Duration maxJobAge;
    private final Clock clock;
    private final ScheduledExecutorService leaseRenewals;

    @org.springframework.beans.factory.annotation.Autowired
    public LocalDocumentProcessingConsumer(TravelDocumentRepository documents,
            DocumentProcessingWorkerClaimService claims, DocumentProcessingWorkerExecutionService worker,
            @Value("${app.document-processing.worker.max-deliveries:5}") int maxAttempts,
            @Value("${app.document-processing.worker.lease-seconds:180}") long leaseSeconds,
            @Value("${app.document-processing.local.max-job-seconds:300}") long maxJobSeconds) {
        this(documents, claims, worker, maxAttempts, leaseSeconds, maxJobSeconds, Clock.systemUTC(),
                Executors.newSingleThreadScheduledExecutor(r -> {
                    var thread = new Thread(r, "document-processing-lease");thread.setDaemon(true);return thread;
                }));
    }

    LocalDocumentProcessingConsumer(TravelDocumentRepository documents, DocumentProcessingWorkerClaimService claims,
            DocumentProcessingWorkerExecutionService worker, int maxAttempts, long leaseSeconds, Clock clock,
            ScheduledExecutorService leaseRenewals) {
        this(documents, claims, worker, maxAttempts, leaseSeconds, DEFAULT_MAX_JOB_SECONDS, clock, leaseRenewals);
    }

    LocalDocumentProcessingConsumer(TravelDocumentRepository documents, DocumentProcessingWorkerClaimService claims,
            DocumentProcessingWorkerExecutionService worker, int maxAttempts, long leaseSeconds, long maxJobSeconds, Clock clock,
            ScheduledExecutorService leaseRenewals) {
        if (maxAttempts < 1 || leaseSeconds < 120) throw new IllegalArgumentException("Unsafe local processing lease or attempt limit");
        if (maxJobSeconds < MIN_MAX_JOB_SECONDS) throw new IllegalArgumentException("Unsafe local processing maximum job age");
        this.maxJobAge = Duration.ofSeconds(maxJobSeconds);
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
                var started = clock.instant();
                var renewalHandle = new AtomicReference<ScheduledFuture<?>>();
                var renewal = leaseRenewals.scheduleAtFixedRate(
                        () -> renew(document.getId(), document.getProcessingJobId(), claim.attemptId(), started, renewalHandle),
                        lease.toMillis()/3, lease.toMillis()/3, TimeUnit.MILLISECONDS);
                renewalHandle.set(renewal);
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

    /**
     * Renewal never outlives {@code maxJobAge}: a worker thread stuck outside a bounded HTTP call would otherwise keep the
     * lease for ever and block this poller. After the limit the lease expires on its own and the job is claimable again;
     * attempt fencing rejects any later write from the stuck attempt.
     */
    private void renew(Long documentId, UUID jobId, UUID attemptId, Instant started,
            AtomicReference<ScheduledFuture<?>> handle) {
        if (!clock.instant().isBefore(started.plus(maxJobAge))) {
            log.warn("Local document attempt exceeded the maximum job age and its lease is no longer renewed documentId={} "
                    + "maxJobSeconds={}", documentId, maxJobAge.toSeconds());
            var self = handle.get();
            if (self != null) self.cancel(false);
            return;
        }
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
