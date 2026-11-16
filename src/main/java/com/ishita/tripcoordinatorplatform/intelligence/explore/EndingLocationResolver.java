package com.ishita.tripcoordinatorplatform.intelligence.explore;

import com.ishita.tripcoordinatorplatform.intelligence.planning.FreeTimeOpportunities;
import com.ishita.tripcoordinatorplatform.intelligence.commitment.CommitmentReader;
import com.ishita.tripcoordinatorplatform.intelligence.places.PlacesProvider;
import com.ishita.tripcoordinatorplatform.intelligence.google.GoogleRequestBudget;

/** Fresh, request-only provider evidence. Persistent booking state retains Google IDs only. */
public final class EndingLocationResolver {
    private EndingLocationResolver() {}

    public static FreeTimeOpportunities.Opportunity resolve(FreeTimeOpportunities.Opportunity window, ExploreRequest request,
            java.time.Instant now, PlacesProvider places, GoogleRequestBudget budget) {
        ExplorationStartPlanner.validateContext(request, window);
        if (ExplorationStartPlanner.hasTrustedUnderwayOrigin(request, window, now))
            return window;
        if (window == null || window.before() == null || window.before().endingEvidence() == null
                || window.before().endingEvidence().placeId() == null)
            return window;
        var before = window.before();
        var saved = before.endingEvidence();
        var place = places.destination(saved.placeId());
        if (place == null || !saved.placeId().equals(place.id()) || place.coordinates() == null)
            throw new ProviderUnavailableException(
                    "The confirmed activity ending place could not be verified. Refresh its location before exploring.");
        var evidence = new CommitmentReader.EndingEvidence(saved.reservationId(), place.coordinates(), place.name(), place.address(),
                saved.placeId(), saved.version(), saved.confirmed());
        var origin = new FreeTimeOpportunities.Boundary(before.itineraryItemId(), before.title(), before.localStart(), before.localEnd(),
                before.start(), before.end(), before.timeZone(), before.timezoneSource(), place.coordinates(), before.flight(),
                before.hard(), !saved.confirmed(), place.name(), evidence);
        return new FreeTimeOpportunities.Opportunity(window.id(), window.localDate(), window.timeZone(), window.earliestStart(),
                window.latestEnd(), window.calendarMinutes(), origin, window.after(), place.coordinates(), window.status(),
                window.reasons(), window.localEarliestStart(), window.localLatestEnd(), window.originBoundary(), window.overnight(),
                window.constrainingCommitment());
    }
}
