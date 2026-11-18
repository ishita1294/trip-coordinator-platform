package com.ishita.tripcoordinatorplatform.intelligence.explore;

import org.springframework.context.annotation.Profile;
import com.ishita.tripcoordinatorplatform.intelligence.planning.VisitFeasibility;

import com.ishita.tripcoordinatorplatform.intelligence.commitment.CommitmentResolver;
import com.ishita.tripcoordinatorplatform.intelligence.commitment.Commitment;
import com.ishita.tripcoordinatorplatform.intelligence.places.PlacesProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.*;
import java.util.*;
import static com.ishita.tripcoordinatorplatform.intelligence.planning.VisitFeasibility.*;

/**
 * The Explore feature: finds places a traveler can visit in a free-time window and checks each can be reached in time for the next
 * booking. It reads the participant's schedule, uses Google Places and routes within the paid-call budget, and saves nothing. Not loaded
 * in the document worker.
 */
@Profile("!document-worker")
@Service
public class ExploreService {
    private final CommitmentResolver commitments;
    private final com.ishita.tripcoordinatorplatform.intelligence.planning.FreeTimeOpportunities opportunities;
    private final PlacesProvider places;
    private final FeasibilityEngine feasibility;
    private final GroundedExploreRanker ranker;
    private final int radius, locationMaxAge;
    @Value("${app.explore.initial-evaluation-count:8}")
    private int initialEvaluationCount = 8;
    @Value("${app.explore.desired-feasible-count:4}")
    private int desiredFeasibleCount = 4;
    private final ExploreRequestGuard requestGuard;
    private final com.ishita.tripcoordinatorplatform.intelligence.google.GoogleRequestBudget googleBudget;

    public ExploreService(CommitmentResolver commitments, PlacesProvider places, FeasibilityEngine feasibility,
            GroundedExploreRanker ranker, @Value("${app.explore.radius-meters:2000}") int radius,
            @Value("${app.explore.location-max-age-seconds:300}") int locationMaxAge,
            ExploreRequestGuard requestGuard,
            com.ishita.tripcoordinatorplatform.intelligence.google.GoogleRequestBudget googleBudget,
            com.ishita.tripcoordinatorplatform.intelligence.planning.FreeTimeOpportunities opportunities) {
        this.opportunities = opportunities;
        this.requestGuard = requestGuard;
        this.googleBudget = googleBudget;
        this.commitments = commitments;
        this.places = places;
        this.feasibility = feasibility;
        this.ranker = ranker;
        if (radius < 100 || radius > 5000 || locationMaxAge < 1) {
            throw new IllegalArgumentException("Invalid EXPLORE cost/freshness safeguards");
        }
        this.radius = radius;
        this.locationMaxAge = locationMaxAge;
    }

    public CommitmentResolver.Resolution context(Long tripId, Long userId) { return context(tripId, userId, null); }

    public record Bootstrap(com.ishita.tripcoordinatorplatform.intelligence.planning.FreeTimeOpportunities.Schedule schedule,
            CommitmentResolver.Resolution context, int startTimeSchemaVersion, List<Commitment> calendarCommitments) {
        public Bootstrap(com.ishita.tripcoordinatorplatform.intelligence.planning.FreeTimeOpportunities.Schedule schedule,
                CommitmentResolver.Resolution context) {
            this(schedule, context, 2, List.of());
        }

        public Bootstrap(com.ishita.tripcoordinatorplatform.intelligence.planning.FreeTimeOpportunities.Schedule schedule,
                CommitmentResolver.Resolution context, int version) {
            this(schedule, context, version, List.of());
        }
    }

    /**
     * Starts the Explore screen for a traveler: the free-time windows from their schedule, using the given time-zone answers and
     * completion times.
     */
    public Bootstrap bootstrap(Long tripId, Long userId, Map<Long, String> clarifications, Map<Long, Instant> completedAt) {
        return bootstrap(tripId, userId, clarifications, completedAt, Map.of());
    }

    /**
     * Same as above, also taking the expected end times the traveler gave for earlier activities. Reads the database only and uses no paid
     * search.
     */
    public Bootstrap bootstrap(Long tripId, Long userId, Map<Long, String> clarifications, Map<Long, Instant> completedAt,
            Map<Long, Instant> expectedEnds) {
        if (userId == null || userId <= 0)
            throw new IllegalArgumentException("Traveler is required");
        Instant now = Instant.now();
        // Like the existing opportunity API, this authorized DB-only preview consumes no paid-search admission slot.
        googleBudget.begin("bootstrap");
        try {
            var snapshot = googleBudget.timed("scheduleRead",
                    () -> expectedEnds.isEmpty() ? opportunities.snapshot(tripId, userId, clarifications, completedAt, now)
                            : opportunities.snapshot(tripId, userId, clarifications, completedAt, expectedEnds, now));
            var inputs = new com.ishita.tripcoordinatorplatform.intelligence.commitment.CommitmentContext(userId, null, null,
                    clarifications);
            var context = googleBudget.timed("commitmentResolution",
                    () -> commitments.previewScheduled(snapshot.commitments(), inputs, now, now.plusSeconds(20)));
            // Calendar visibility must not depend on whether the personal hours produce a window.
            // Reuse the authorized snapshot and the same deterministic arrival/timezone policy;
            // previewScheduled explicitly forbids provider lookup.
            var calendarCommitments = snapshot.commitments().stream().filter(item -> item.hard())
                    .map(item -> context.next() != null && Objects.equals(context.next().itineraryItemId(), item.id())
                            ? context.next()
                            : commitments.previewScheduled(List.of(item), inputs, now, now.plusSeconds(20)).next())
                    .filter(Objects::nonNull).toList();
            return new Bootstrap(snapshot.schedule(), context, 2, calendarCommitments);
        } finally {
            googleBudget.end();
        }
    }

    public CommitmentResolver.Resolution context(Long tripId, Long userId, Long itemId) { return context(tripId, userId, itemId, userId); }

    /** Works out the commitment (the next booking the traveler must reach) for one itinerary item. */
    public CommitmentResolver.Resolution context(Long tripId, Long userId, Long itemId, Long actorId) {
        return context(tripId, userId, itemId, actorId, null, Map.of());
    }

    /** Same as above, but with the traveler's own choice of commitment and time-zone answers; too many answers are refused. */
    public CommitmentResolver.Resolution context(Long tripId, Long userId, Long itemId, Long actorId,
            com.ishita.tripcoordinatorplatform.intelligence.commitment.CommitmentContext.Selection selection,
            Map<Long, String> clarifications) {
        if (userId == null || userId <= 0)
            throw new IllegalArgumentException("Traveler is required");
        if (clarifications.size() > 100
                || clarifications.values().stream().anyMatch(z -> z == null || !java.time.ZoneId.getAvailableZoneIds().contains(z)))
            throw new IllegalArgumentException("Choose a valid IANA timezone.");
        if (selection != null && selection.timeZone() != null && !selection.timeZone().isBlank()
                && !java.time.ZoneId.getAvailableZoneIds().contains(selection.timeZone()))
            throw new IllegalArgumentException("Choose a valid IANA timezone.");
        if (selection != null && (selection.itineraryItemId() == null || !selection.itineraryItemId().equals(itemId)
                || (selection.arrivalLeadMinutes() != null
                        && (selection.arrivalLeadMinutes() < 0 || selection.arrivalLeadMinutes() > 1440))))
            throw new IllegalArgumentException("Invalid commitment confirmation.");
        Instant now = Instant.now();
        try (var permit = requestGuard.beginContext(tripId, actorId, userId, itemId, now)) {
            googleBudget.begin("context", actorId);
            try {
                var inputs = new com.ishita.tripcoordinatorplatform.intelligence.commitment.CommitmentContext(userId, null,
                        selection == null && itemId != null
                                ? new com.ishita.tripcoordinatorplatform.intelligence.commitment.CommitmentContext.Selection(itemId, null,
                                        null, null, null)
                                : selection,
                        clarifications);
                var context = (itemId == null ? commitments.resolve(tripId, inputs, now, now.plusSeconds(20))
                        : commitments.resolveItem(tripId, inputs, itemId, now, now.plusSeconds(20)));
                if (!context.status().equals("DESTINATION_LOOKUP_UNAVAILABLE"))
                    permit.succeeded();
                return context;
            } finally {
                googleBudget.end();
            }
        }
    }

    /** Searches for the meeting point of a commitment by name (2 to 160 characters), as one guarded and budgeted Google lookup. */
    public CommitmentResolver.Resolution meetingPoints(Long tripId, Long travelerId, Long itemId, String query, Long actorId) {
        if (travelerId == null || travelerId <= 0 || itemId == null || query == null || query.trim().length() < 2 || query.length() > 160)
            throw new IllegalArgumentException("Enter a meeting-point name (2–160 characters).");
        try (var permit = requestGuard.beginContext(tripId, actorId, travelerId, itemId, Instant.now())) {
            googleBudget.begin("context", actorId);
            try {
                var result = commitments.meetingPoints(tripId, travelerId, itemId, query, Instant.now());
                permit.succeeded();
                return result;
            } finally {
                googleBudget.end();
            }
        }
    }

    public java.util.List<com.ishita.tripcoordinatorplatform.intelligence.places.PlaceDestination> origins(Long tripId, Long travelerId,
            String query, Long actorId) {
        return origins(tripId, travelerId, query, actorId, null);
    }

    public java.util.List<com.ishita.tripcoordinatorplatform.intelligence.places.PlaceDestination> origins(Long tripId, Long travelerId,
            String query, Long actorId,
            com.ishita.tripcoordinatorplatform.intelligence.location.Coordinates bias) {
        return origins(tripId, travelerId, query, actorId, bias, null, null);
    }

    public java.util.List<com.ishita.tripcoordinatorplatform.intelligence.places.PlaceDestination> origins(Long tripId, Long travelerId,
            String query, Long actorId,
            com.ishita.tripcoordinatorplatform.intelligence.location.Coordinates bias, Long originItemId, Long nextItemId) {
        if (query == null || query.trim().length() < 2 || query.length() > 160)
            throw new IllegalArgumentException("Enter a place name or address (2–160 characters).");
        String text = query.trim();
        // A text search requests candidates, not a verified departure location.
        // Optional coordinates guide the provider; selection and routing checks remain separate.
        try (var permit = requestGuard.beginContext(tripId, actorId, travelerId, null, Instant.now())) {
            googleBudget.begin("origin", actorId);
            try {
                var result = places.findDestinations(new PlacesProvider.DestinationQuery(text, null, null, bias));
                permit.succeeded();
                return result;
            } finally {
                googleBudget.end();
            }
        }
    }

}
