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

    /** Loads the traveler's schedule, using the expected end times from the selected window when there are any. */
    private com.ishita.tripcoordinatorplatform.intelligence.planning.FreeTimeOpportunities.Snapshot scheduleSnapshot(Long tripId,
            ExploreRequest request, Map<Long, String> clarifications, Instant now) {
        var selected = request.opportunity();
        return selected == null || selected.expectedEnds().isEmpty()
                ? opportunities.snapshot(tripId, request.userId(), clarifications, selected == null ? Map.of() : selected.completedAt(),
                        now)
                : opportunities.snapshot(tripId, request.userId(), clarifications, selected.completedAt(), selected.expectedEnds(), now);
    }

    /** Reads the schedule again, in the same way, to check the selected window is still valid. */
    private com.ishita.tripcoordinatorplatform.intelligence.planning.FreeTimeOpportunities.Schedule revalidateSchedule(Long tripId,
            ExploreRequest request, Map<Long, String> clarifications, Instant now) {
        var selected = request.opportunity();
        return selected == null || selected.expectedEnds().isEmpty()
                ? opportunities.read(tripId, request.userId(), clarifications, selected == null ? Map.of() : selected.completedAt(), now)
                : opportunities.read(tripId, request.userId(), clarifications, selected.completedAt(), selected.expectedEnds(), now);
    }

    public record WindowPreview(String revision, Long directTravelSeconds, Long discretionaryMinutes, String status,
            java.util.List<String> warnings, Instant assessedAt, Instant expiresAt, ExplorationStartPlanner.Plan explorationStart,
            ExploreRequest.JourneyModes journeyModes) {
        public WindowPreview(String revision, Long seconds, Long minutes, String status, List<String> warnings, Instant assessed,
                Instant expires, ExplorationStartPlanner.Plan start) {
            this(revision, seconds, minutes, status, warnings, assessed, expires, start, null);
        }

        /** Returns a copy of the preview with the given journey modes. */
        public WindowPreview withModes(ExploreRequest.JourneyModes modes) {
            return new WindowPreview(revision, directTravelSeconds, discretionaryMinutes, status, warnings, assessedAt, expiresAt,
                    explorationStart, modes);
        }

        public WindowPreview(String revision, Long directTravelSeconds, Long discretionaryMinutes, String status, List<String> warnings,
                Instant assessedAt, Instant expiresAt) {
            this(revision, directTravelSeconds, discretionaryMinutes, status, warnings, assessedAt, expiresAt, null);
        }

        public WindowPreview(String revision, Long directTravelSeconds, Long discretionaryMinutes, String status, List<String> warnings) {
            this(revision, directTravelSeconds, discretionaryMinutes, status, warnings, Instant.now(), Instant.now().plusSeconds(300),
                    null);
        }

        /** Returns a copy of the preview with the given exploration start plan. */
        public WindowPreview withStart(ExplorationStartPlanner.Plan plan) {
            return new WindowPreview(revision, directTravelSeconds, discretionaryMinutes, status, warnings, assessedAt, expiresAt, plan,
                    journeyModes);
        }
    }

    /** Gives a quick preview of the selected free-time window (travel time and time left over) without searching for places. */
    public WindowPreview windowPreview(Long tripId, ExploreRequest input, Long actorId) {
        validate(input);
        if (input.opportunity() == null)
            throw new IllegalArgumentException("Select a detected selectedWindow.");
        Instant now = Instant.now();
        var clarifications = new HashMap<Long, String>();
        if (input.timeZoneClarifications() != null)
            input.timeZoneClarifications().forEach(c -> clarifications.put(c.itineraryItemId(), c.timeZone()));
        var snapshot = scheduleSnapshot(tripId, input, clarifications, now);
        var selectedWindow = opportunities.require(snapshot.schedule(), input.opportunity());
        validateContinuationContext(input, snapshot.schedule().revision());
        Instant requested = selectedWindow.earliestStart().isAfter(now) ? selectedWindow.earliestStart() : now;
        if (input.departureAt() != null && input.departureAt().isAfter(requested))
            requested = input.departureAt();
        final Instant requestedStart = requested;
        if (!requested.isBefore(selectedWindow.latestEnd()))
            throw new IllegalArgumentException("This window has ended.");
        var later = input.startContext() == null ? null : input.startContext().requestedAt();
        var earliest = selectedWindow.earliestStart().isAfter(now) ? selectedWindow.earliestStart() : now;
        if (later != null && (later.isBefore(earliest) || !later.isBefore(selectedWindow.latestEnd())))
            throw new IllegalArgumentException("The requested start must be current/future and within the selected opportunity.");
        ExplorationStartPlanner.validateContext(input, selectedWindow);
        var override = input.commitmentOverride();
        if (override != null && (selectedWindow.constrainingCommitment() == null
                || !selectedWindow.constrainingCommitment().itineraryItemId().equals(override.itineraryItemId())))
            throw new IllegalArgumentException("Meeting point must belong to this window.");
        try (var permit = requestGuard.beginComparison(tripId, actorId, List.of("WINDOW_PREVIEW", input), now)) {
            googleBudget.begin(input.originPlaceId() == null ? "window-preview" : "window-preview-origin", actorId);
            try {
                final var request = OriginLocationResolver.resolve(input, places, googleBudget);
                final var window = EndingLocationResolver.resolve(selectedWindow, request, now, places, googleBudget);
                CommitmentResolver.Resolution resolution = null;
                if (window.constrainingCommitment() != null) {
                    var selection = override == null
                            ? new com.ishita.tripcoordinatorplatform.intelligence.commitment.CommitmentContext.Selection(
                                    window.constrainingCommitment().itineraryItemId(), null, null, null, null)
                            : new com.ishita.tripcoordinatorplatform.intelligence.commitment.CommitmentContext.Selection(
                                    override.itineraryItemId(), override.destination(), override.timeZone(), override.arrivalLeadMinutes(),
                                    override.placeId());
                    resolution = commitments
                            .resolveScheduled(
                                    snapshot.commitments().stream()
                                            .filter(item -> item.id()
                                                    .equals(window.constrainingCommitment().itineraryItemId()))
                                            .toList(),
                                    new com.ishita.tripcoordinatorplatform.intelligence.commitment.CommitmentContext(
                                            request.userId(), null, selection, clarifications),
                                    requestedStart, now.plusSeconds(20));
                    if (!resolution.status().equals("READY"))
                        return new WindowPreview(snapshot.schedule().revision(), null, null, "PROVISIONAL", resolution.warnings())
                                .withModes(request.journeyModes());
                }
                final Instant arrivalDeadline = resolution == null ? window.latestEnd()
                        : resolution.next().requiredArrivalAt().isBefore(window.latestEnd()) ? resolution.next().requiredArrivalAt()
                                : window.latestEnd();
                var start = googleBudget.timed("repositioning",
                        () -> ExplorationStartPlanner.calculate(request, window, request.mode(), requestedStart, now, arrivalDeadline,
                                (from, to, mode, time, arrival) -> feasibility.directRoute(from, to, mode, time)));
                if (!start.status().equals("READY"))
                    return new WindowPreview(snapshot.schedule().revision(), null, null,
                            start.status().equals("INFEASIBLE") ? "NO_DISCRETIONARY_TIME" : "PROVISIONAL", start.warnings())
                            .withStart(start).withModes(request.journeyModes());
                Long seconds = 0L;
                if (resolution != null) {
                    final var next = resolution.next();
                    var route = feasibility.directRoute(request.location().coordinates(), next.destination(),
                            request.effectiveOnwardMode(request.mode()), start.explorationAt());
                    if (route.isEmpty())
                        return new WindowPreview(snapshot.schedule().revision(), null, null, "PROVISIONAL",
                                List.of("Direct travel is unavailable for this mode.")).withStart(start).withModes(request.journeyModes());
                    if (!route.get().warnings().isEmpty())
                        return new WindowPreview(snapshot.schedule().revision(), null, null, "PROVISIONAL", route.get().warnings())
                                .withStart(start).withModes(request.journeyModes());
                    seconds = Duration.between(start.explorationAt(), route.get().arrivalAt()).toSeconds();
                }
                if (!revalidateSchedule(tripId, request, clarifications, Instant.now()).revision().equals(snapshot.schedule().revision()))
                    throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.CONFLICT,
                            "Schedule changed during the preview.");
                long discretionary = Duration.between(start.explorationAt(),
                        resolution == null ? window.latestEnd()
                                : window.latestEnd().isBefore(resolution.next().requiredArrivalAt().minusSeconds(seconds))
                                        ? window.latestEnd()
                                        : resolution.next().requiredArrivalAt().minusSeconds(seconds))
                        .toMinutes();
                permit.succeeded();
                return new WindowPreview(snapshot.schedule().revision(), seconds, Math.max(0, discretionary),
                        discretionary <= 0 ? "NO_DISCRETIONARY_TIME" : "ESTIMATE", window.reasons()).withStart(start)
                        .withModes(request.journeyModes());
            } finally {
                googleBudget.end();
            }
        }
    }

    /** The traveler's preference, or 'anything' when none was given. */
    private static ExplorePreference preference(ExploreRequest request) {
        return request.preference() == null ? ExplorePreference.ANYTHING : request.preference();
    }

    /** Refuses 'explore more' when the schedule, window or journey modes have changed since the first search. */
    static void validateContinuationContext(ExploreRequest request, String revision) {
        var continuation = request.continuation();
        if (continuation != null && (!revision.equals(continuation.scheduleRevision())
                || !Objects.equals(request.windowContext(), continuation.windowContext())
                || !Objects.equals(request.journeyModes(), continuation.journeyModes())))
            throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.CONFLICT,
                    "The participant schedule changed. Refresh before exploring more.");
    }

    /**
     * Checks the request: a traveler and mode, either an observed location or a saved origin place ID (not both), and consistent
     * continuation modes.
     */
    static void validate(ExploreRequest request) {
        if (request == null || request.userId() == null || request.userId() <= 0 || request.mode() == null) {
            throw new IllegalArgumentException("Traveler and travel mode are required");
        }
        if ((request.location() == null) == (request.originPlaceId() == null))
            throw new IllegalArgumentException("Provide either an observed location or a saved origin Place ID.");
        if (request.originPlaceId() != null && !request.originPlaceId().matches("[A-Za-z0-9_-]{1,255}"))
            throw new IllegalArgumentException("Invalid origin place identity");
        if (request.continuation() != null && !Objects.equals(request.journeyModes(), request.continuation().journeyModes()))
            throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.CONFLICT,
                    "Journey modes changed or continuation predates per-leg modes. Start a fresh Discover.");
        if (request.startContext() != null && request.startContext().delayMinutes() != null
                && (request.startContext().delayMinutes() < 0 || request.startContext().delayMinutes() > 1440))
            throw new IllegalArgumentException("Start delay must be 0–1440 minutes.");
        if (request.intent() != null && request.intent().preference() != preference(request))
            throw new IllegalArgumentException("Selected intent does not belong to this activity preference");
        if (request.evaluatedPlaceIds() != null && (request.evaluatedPlaceIds().size() > 100
                || request.evaluatedPlaceIds().stream().anyMatch(id -> id == null || !id.matches("[A-Za-z0-9_-]{1,255}"))))
            throw new IllegalArgumentException("Invalid evaluated place identities");
        if (request.minimumVisitMinutes() != null && (request.minimumVisitMinutes() < 1 || request.minimumVisitMinutes() > 1440)) {
            throw new IllegalArgumentException("Minimum visiting time must be between 1 and 1440 minutes");
        }
        if (request.scheduleTimeZone() != null && !request.scheduleTimeZone().isBlank())
            ZoneId.of(request.scheduleTimeZone());
        if (request.timeZoneClarifications() != null) {
            if (request.timeZoneClarifications().size() > 100)
                throw new IllegalArgumentException("Too many item timezone clarifications");
            Set<Long> ids = new HashSet<>();
            for (var clarification : request.timeZoneClarifications()) {
                if (clarification == null || clarification.itineraryItemId() == null || clarification.itineraryItemId() <= 0
                        || clarification.timeZone() == null || clarification.timeZone().isBlank()
                        || !ids.add(clarification.itineraryItemId()))
                    throw new IllegalArgumentException("Each timezone clarification needs a distinct itinerary item and IANA timezone");
                if (!ZoneId.getAvailableZoneIds().contains(clarification.timeZone()))
                    throw new IllegalArgumentException("Use an IANA timezone for this item, for example Europe/Rome");
                ZoneId.of(clarification.timeZone());
            }
        }
        if (request.workflow() == ExploreRequest.Workflow.OPEN_ENDED
                && (request.opportunity() != null || request.commitmentOverride() != null))
            throw new IllegalArgumentException(
                    "Open-ended exploration does not accept commitment arrival settings or a bounded opportunity.");
        var continuation = request.continuation();
        if (continuation != null && (continuation.pendingPlaceIds() == null || continuation.pendingPlaceIds().size() > 40
                || continuation.pendingPlaceIds().stream().anyMatch(id -> id == null || !id.matches("[A-Za-z0-9_-]{1,255}"))
                || new HashSet<>(continuation.pendingPlaceIds()).size() != continuation.pendingPlaceIds().size()
                || continuation.scheduleRevision() == null))
            throw new IllegalArgumentException("Invalid discovery continuation. Start a fresh search.");
        var override = request.commitmentOverride();
        if (override != null) {
            if (override.itineraryItemId() == null || override.itineraryItemId() <= 0)
                throw new IllegalArgumentException("Commitment override needs an itinerary item ID");
            if (override.placeId() != null && !override.placeId().matches("[A-Za-z0-9_-]{1,255}"))
                throw new IllegalArgumentException("Invalid selected place identity");
            if (override.placeId() != null && override.destination() != null)
                throw new IllegalArgumentException("Choose a place or explicit coordinates, not both");
            if (override.timeZone() != null && !override.timeZone().isBlank())
                ZoneId.of(override.timeZone());
            if (override.arrivalLeadMinutes() != null && (override.arrivalLeadMinutes() < 0 || override.arrivalLeadMinutes() > 1440)) {
                throw new IllegalArgumentException("Arrival margin must be between 0 and 1440 minutes");
            }
        }
    }
}
