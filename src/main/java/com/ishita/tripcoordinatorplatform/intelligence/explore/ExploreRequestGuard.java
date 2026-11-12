package com.ishita.tripcoordinatorplatform.intelligence.explore;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import java.time.Instant;
import java.util.*;

/**
 * Decides whether an Explore search may start right now. It limits how many searches run at once overall and per traveler,
 * how many a traveler may start per minute, and refuses an identical search that is already running or just finished.
 * Everything is held in memory only; no responses or provider content are kept.
 */
@Component
@Profile("!document-worker")
public class ExploreRequestGuard {
    private record Search(Long tripId, List<Object> inputs) {}
    private static final class Traveler {
        final Deque<Instant> starts = new ArrayDeque<>();
        final Set<Search> active = new HashSet<>();
        final Map<Search, Instant> completed = new HashMap<>();
    }
    private final Map<Long, Traveler> travelers = new HashMap<>();
    private final int concurrentLimit, travelerConcurrentLimit, requestsPerMinute, duplicateSeconds;
    private int active;

    @org.springframework.beans.factory.annotation.Autowired
    public ExploreRequestGuard(@Value("${app.explore.max-concurrent-requests:8}") int concurrentLimit,
            @Value("${app.explore.max-concurrent-per-traveler:2}") int travelerConcurrentLimit,
            @Value("${app.explore.max-requests-per-minute:6}") int requestsPerMinute,
            @Value("${app.explore.duplicate-window-seconds:5}") int duplicateSeconds) {
        if (concurrentLimit < 1 || travelerConcurrentLimit < 1 || requestsPerMinute < 1 || duplicateSeconds < 0)
            throw new IllegalArgumentException("Invalid EXPLORE request safeguards");
        this.concurrentLimit = concurrentLimit; this.travelerConcurrentLimit = travelerConcurrentLimit;
        this.requestsPerMinute = requestsPerMinute; this.duplicateSeconds = duplicateSeconds;
    }

    public Permit begin(Long tripId, ExploreRequest request, boolean fresh, Instant now) {
        return begin(tripId, request.userId(), request, fresh, now);
    }
    public Permit begin(Long tripId, Long actorId, ExploreRequest request, boolean fresh, Instant now) {
        // Observation timestamps do not make an otherwise identical fresh search new.
        // Freshness/source/accuracy changes do matter, as do all scheduling clarifications.
        var clarifications = new TreeMap<Long, String>();
        if (request.timeZoneClarifications() != null)
            request.timeZoneClarifications().forEach(c -> clarifications.put(c.itineraryItemId(), c.timeZone()));
        var search = new Search(tripId, Arrays.asList(request.userId(), request.originPlaceId(), request.location()==null?null:request.location().coordinates(), request.location()==null?null:request.location().source(),
                request.location()==null?null:request.location().accuracyMeters(), fresh, request.journeyModes(),
                request.preference() == null ? ExplorePreference.ANYTHING : request.preference(),
                request.intent(), request.workflow(), request.opportunity(), request.departureAt(), request.minimumVisitMinutes(), request.scheduleTimeZone(), request.commitmentOverride(), Map.copyOf(clarifications), request.evaluatedPlaceIds() == null ? Set.of() : Set.copyOf(request.evaluatedPlaceIds()), request.continuation()));
        return admit(actorId, search, now);
    }
    public Permit beginContext(Long tripId, Long userId, Instant now) { return beginContext(tripId, userId, null, now); }
    public Permit beginContext(Long tripId, Long userId, Long itemId, Instant now) {
        return beginContext(tripId, userId, userId, itemId, now);
    }
    public Permit beginContext(Long tripId, Long actorId, Long travelerId, Long itemId, Instant now) {
        return admit(actorId, new Search(tripId, Arrays.asList("COMMITMENT_CONTEXT", travelerId, itemId)), now);
    }
    public Permit beginComparison(Long tripId, Long userId, Object inputs, Instant now) {
        return admit(userId, new Search(tripId, List.of("SCENARIOS", inputs)), now);
    }
    /** Applies all the limits above and, if the search is allowed, returns a permit that must be closed when it ends. */
    private synchronized Permit admit(Long userId, Search search, Instant now) {
        travelers.values().forEach(t -> {
            t.starts.removeIf(at -> !at.isAfter(now.minusSeconds(60)));
            t.completed.values().removeIf(at -> !at.isAfter(now.minusSeconds(duplicateSeconds)));
        });
        travelers.values().removeIf(t -> t.active.isEmpty() && t.starts.isEmpty() && t.completed.isEmpty());
        if (!travelers.containsKey(userId) && travelers.size() >= 2000)
            throw limit("EXPLORE request capacity reached; retry shortly.");
        var traveler = travelers.computeIfAbsent(userId, ignored -> new Traveler());
        if (traveler.active.contains(search))
            throw new ResponseStatusException(HttpStatus.CONFLICT, "This same EXPLORE search is already running. Wait for its result.");
        if (traveler.completed.containsKey(search))
            throw limit("This same search just completed. Wait " + duplicateSeconds + " seconds to refresh, or change your search inputs.");
        if (traveler.starts.size() >= requestsPerMinute)
            throw limit("Too many EXPLORE searches for this account. Retry after the rolling one-minute window.");
        if (traveler.active.size() >= travelerConcurrentLimit)
            throw limit("This account already has " + travelerConcurrentLimit + " searches finishing. Retry shortly.");
        if (active >= concurrentLimit) throw limit("EXPLORE capacity is temporarily full. Retry shortly.");
        traveler.starts.addLast(now); traveler.active.add(search); active++;
        return new Permit(traveler, search);
    }
    private ResponseStatusException limit(String message) { return new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, message); }

    public final class Permit implements AutoCloseable {
        private final Traveler traveler;
        private final Search search;
        private boolean successful, closed;
        private Permit(Traveler traveler, Search search) { this.traveler = traveler; this.search = search; }
        public void succeeded() { successful = true; }
        @Override public void close() {
            synchronized (ExploreRequestGuard.this) {
                if (closed) return;
                closed = true; traveler.active.remove(search); active--;
                if (successful) traveler.completed.put(search, Instant.now());
            }
        }
    }
}
