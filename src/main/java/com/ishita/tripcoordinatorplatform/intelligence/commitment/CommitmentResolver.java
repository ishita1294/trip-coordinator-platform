package com.ishita.tripcoordinatorplatform.intelligence.commitment;

import com.ishita.tripcoordinatorplatform.intelligence.places.*;
import com.ishita.tripcoordinatorplatform.intelligence.location.Coordinates;
import com.ishita.tripcoordinatorplatform.intelligence.explore.ProviderUnavailableException;
import org.springframework.stereotype.Service;
import java.time.*;
import java.util.*;

/**
 * Works out the next fixed commitment (a booking with a place and an arrival time) that Explore must get the traveler to, resolving its
 * time zone and meeting place, and says what is still missing when it cannot.
 */
@Service
public class CommitmentResolver {
    /** The outcome: the commitment found (if any), a status, warnings and, when the place is unclear, choices for the user. */
    public record Resolution(Commitment next, String status, List<String> warnings, List<PlaceDestination> destinationChoices) {}

    private final CommitmentReader reader;
    private final PlacesProvider places;

    /** Creates the resolver with the schedule reader and the places provider. */
    public CommitmentResolver(CommitmentReader reader, PlacesProvider places) {
        this.reader = reader;
        this.places = places;
    }

    /** Finds the traveler's next commitment on the trip as of the given time. */
    public Resolution resolve(Long tripId, CommitmentContext context, Instant now, Instant requestDeadline) {
        return resolveScheduled(reader.read(tripId, context.userId()), context, now, requestDeadline);
    }

    /** Resolves one chosen itinerary item as the commitment. */
    public Resolution resolveItem(Long tripId, CommitmentContext context, Long itemId, Instant departure, Instant deadline) {
        return resolveScheduled(reader.read(tripId, context.userId()).stream().filter(i -> i.id().equals(itemId)).toList(), context,
                departure, deadline);
    }

    /** Resolves the next commitment among the given scheduled items; a place lookup is allowed. */
    public Resolution resolveScheduled(List<CommitmentReader.Scheduled> items, CommitmentContext context, Instant now,
            Instant requestDeadline) {
        return resolveScheduled(items, context, now, requestDeadline, true);
    }

    /** Fast authoritative preview: do not hold the opportunity response behind provider I/O. */
    public Resolution previewScheduled(List<CommitmentReader.Scheduled> items, CommitmentContext context, Instant now,
            Instant requestDeadline) {
        return resolveScheduled(items, context, now, requestDeadline, false);
    }

    /**
     * Does the resolving: fills in each item's time zone from the item, the user's answers or the selection, and picks the next
     * commitment. Place lookups happen only when allowed.
     */
    private Resolution resolveScheduled(List<CommitmentReader.Scheduled> items, CommitmentContext context, Instant now,
            Instant requestDeadline, boolean lookupAllowed) {
        var scheduled = new ArrayList<>(items.stream().map(item -> {
            String zone = !blank(item.timeZone()) ? item.timeZone()
                    : !blank(clarification(item, context)) ? clarification(item, context)
                            : context.selection() != null && Objects.equals(context.selection().itineraryItemId(), item.id())
                                    ? context.selection().timeZone()
                                    : null;
            return item.withArrivalZone(zone);
        }).toList());
        // UTC +/-18 bounds are deliberately conservative, not a guessed timezone.
        scheduled.sort(Comparator.comparing(item -> item.start().toInstant(ZoneOffset.MAX).minusSeconds(lead(item, context) * 60L)));
        Commitment next = null;
        int lookups = 0;
        for (var item : scheduled) {
            boolean explicitPlan = context.selection() != null && Objects.equals(context.selection().itineraryItemId(), item.id());
            if (!item.hard() && !explicitPlan)
                continue;
            if (item.start() == null)
                return pending(item, "NEEDS_SCHEDULE", "This activity has no scheduled start.", List.of(), context);
            if (!item.start().toInstant(ZoneOffset.MIN).isAfter(now))
                continue; // Past in every valid offset.
            if (item.arrivalRequirementIssue() != null && !item.arrivalRequirementIssue().startsWith("Confirm the activity timezone"))
                return pending(item, "NEEDS_ARRIVAL_REVIEW", item.arrivalRequirementIssue(), List.of(), context);
            if (next != null
                    && !item.start().toInstant(ZoneOffset.MAX).minusSeconds(lead(item, context) * 60L).isBefore(next.requiredArrivalAt()))
                continue;
            var selected = context.selection();
            boolean matches = selected != null && Objects.equals(selected.itineraryItemId(), item.id());
            String zone = item.timeZone(), zoneSource = item.zoneSource();
            // Interpret source timezones before network lookup; never let clarification override them.
            if (!blank(zone)) {
                var interpreted = interpret(item, zone, zoneSource, null, null, context);
                if (interpreted == null)
                    return pending(item, "NEEDS_TIMEZONE_RESOLUTION",
                            "Commitment time is invalid or ambiguous in its source timezone; explicit resolution is required.", List.of(),
                            context);
                if (!interpreted.startsAt().isAfter(now))
                    continue;
            }
            if (blank(zone) && !blank(clarification(item, context))) {
                zone = clarification(item, context);
                zoneSource = "TRAVELER_ITEM_TIMEZONE";
            }
            Coordinates point = item.coordinates();
            String pointSource = point == null ? "UNRESOLVED" : "RESERVATION";
            if (matches && selected.destination() != null) {
                point = selected.destination();
                pointSource = "TRAVELER_SELECTED";
            }
            PlaceDestination resolved = null;
            boolean needsPlace = point == null || blank(zone);
            if (matches && !blank(selected.placeId()))
                needsPlace = true;
            if (needsPlace && !lookupAllowed)
                return pending(item, "CONTEXT_LOOKUP_REQUIRED",
                        "Meeting-point or timezone evidence needs provider resolution; opportunities can be viewed while that lookup "
                                + "completes.",
                        List.of(), context);
            if (needsPlace) {
                if (++lookups > 3 || Instant.now().isAfter(requestDeadline))
                    return pending(item, "DESTINATION_RESOLUTION_NEEDED",
                            "Destination lookup budget reached; provide an item-specific destination/timezone before proceeding.",
                            List.of(), context);
                try {
                    if (matches && !blank(selected.placeId())) {
                        // Explicit selected identity: one details call verifies current evidence; no repeated text search.
                        resolved = places.destination(selected.placeId());
                        if (!plausible(item, resolved, pointFor(item, context)))
                            return pending(item, "DESTINATION_RESOLUTION_NEEDED",
                                    "Selected place details do not match the commitment context; select the correct meeting point.",
                                    List.of(), context);
                    } else {
                        var query = new PlacesProvider.DestinationQuery(item.placeName(), item.address(), item.locality(), point);
                        var choices = places.findDestinations(query).stream()
                                .filter(place -> plausible(item, place, pointFor(item, context))).toList();
                        if (choices.size() == 1 && strong(item, choices.getFirst(), point))
                            resolved = choices.getFirst();
                        else if (!choices.isEmpty() && point != null && blank(zone))
                            return pending(item, "NEEDS_TIMEZONE", "The meeting point is known; confirm the timezone for this commitment.",
                                    List.of(), context);
                        else if (!choices.isEmpty())
                            return pending(item, "DESTINATION_RESOLUTION_NEEDED",
                                    "Confirm the actual meeting/entry place; text matches are not authoritative.", choices, context);
                    }
                } catch (ProviderUnavailableException failure) {
                    // An explicit destination and item timezone can bypass lookup without guessing.
                    if (blank(zone) && !blank(clarification(item, context))) {
                        zone = clarification(item, context);
                        zoneSource = "TRAVELER_ITEM_TIMEZONE";
                    }
                    if (!blank(zone)) {
                        var clarified = interpret(item, zone, zoneSource, point, pointSource, context);
                        if (clarified != null && !clarified.startsAt().isAfter(now))
                            continue;
                    }
                    if (point != null && blank(zone))
                        return pending(item, "NEEDS_TIMEZONE",
                                "Meeting point is known, but its timezone could not be verified. Confirm the commitment timezone.",
                                List.of(), context);
                    if (point == null || (matches && !blank(selected.placeId())))
                        return pending(item, "DESTINATION_LOOKUP_UNAVAILABLE", failure.getMessage(), List.of(), context);
                }
            }
            if (resolved != null) {
                if (point == null) {
                    point = resolved.coordinates();
                    pointSource = "GOOGLE_MATCH";
                }
                if (blank(zone) && !blank(resolved.timeZone())) {
                    zone = resolved.timeZone();
                    zoneSource = "GOOGLE_DESTINATION_TIMEZONE";
                }
            }
            if (blank(zone) && !blank(clarification(item, context))) {
                zone = clarification(item, context);
                zoneSource = "TRAVELER_ITEM_TIMEZONE";
            }
            if (blank(zone))
                return pending(item, "NEEDS_TIMEZONE",
                        "Supply the timezone for this specific commitment; a trip-wide timezone cannot safely interpret it.", List.of(),
                        context);
            var commitment = interpret(item, zone, zoneSource, point, pointSource, context);
            if (commitment == null)
                return pending(item, "NEEDS_TIMEZONE_RESOLUTION",
                        "This local commitment time is ambiguous/nonexistent during a daylight-saving transition.", List.of(), context);
            if (!commitment.startsAt().isAfter(now))
                continue;
            if (point == null)
                return new Resolution(commitment, "DESTINATION_RESOLUTION_NEEDED",
                        List.of("No validated destination match; provide the actual meeting/entry coordinates."), List.of());
            if (next == null || commitment.requiredArrivalAt().isBefore(next.requiredArrivalAt()))
                next = commitment;
        }
        return new Resolution(next, next == null ? "NO_FUTURE_COMMITMENT" : "READY",
                next == null ? List.of("No assigned fixed/time-constrained future commitment.") : List.of(), List.of());
    }

    /** Explicit bounded name search: show choices even for a single strong match; confirmation is separate. */
    public Resolution meetingPoints(Long tripId, Long travelerId, Long itemId, String query, Instant now) {
        if (itemId == null || query == null || query.trim().length() < 2 || query.length() > 160)
            throw new IllegalArgumentException("Enter a meeting-point name (2–160 characters).");
        var item = reader.read(tripId, travelerId).stream().filter(i -> i.id().equals(itemId)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "The selected commitment is not in this traveler's schedule. Refresh the schedule."));
        var choices = places.findDestinations(new PlacesProvider.DestinationQuery(query.trim(), null, null, item.coordinates())).stream()
                .filter(p -> p != null && !blank(p.id()) && !blank(p.name()) && p.coordinates() != null
                        && plausible(item, p, item.coordinates()))
                .toList();
        return pending(item, "DESTINATION_RESOLUTION_NEEDED",
                choices.isEmpty() ? "No verified meeting points matched this booking. Try the venue name and city."
                        : "Select the actual meeting point, then confirm it.",
                choices, new CommitmentContext(travelerId, null, null, Map.of()));
    }

    /** Builds a result that says the commitment cannot be used yet and what the user must clarify, with place choices if any. */
    private Resolution pending(CommitmentReader.Scheduled item, String status, String message, List<PlaceDestination> choices,
            CommitmentContext context) {
        String zone = item.timeZone();
        String source = item.zoneSource();
        if (blank(zone) && !blank(clarification(item, context))) {
            zone = clarification(item, context);
            source = "TRAVELER_ITEM_TIMEZONE";
        }
        item = item.withArrivalZone(zone);
        var known = blank(zone) || item.arrivalRequirementIssue() != null ? null
                : interpret(item, zone, source, pointFor(item, context), "UNRESOLVED", context);
        var provisional = known != null ? known
                : new Commitment(item.id(), item.title(), null, null, pointFor(item, context), zone,
                        blank(source) ? "UNRESOLVED" : source, "ARRIVAL_TIME_UNRESOLVED", lead(item, context), "UNRESOLVED", item.start());
        String detail = item.title() + " (itinerary item " + item.id() + ", scheduled local time " + item.start() + "): " + message;
        return new Resolution(provisional, status, List.of(detail), List.copyOf(choices));
    }

    /** The time zone the user gave for an item, or the selection's or fallback zone when the item is the selected one. */
    private String clarification(CommitmentReader.Scheduled item, CommitmentContext context) {
        if (context.timeZoneClarifications() != null && !blank(context.timeZoneClarifications().get(item.id())))
            return context.timeZoneClarifications().get(item.id());
        var selected = context.selection();
        if (selected == null || !Objects.equals(selected.itineraryItemId(), item.id()))
            return null;
        return blank(selected.timeZone()) ? context.fallbackTimeZone() : selected.timeZone();
    }

    /**
     * The destination point for an item: the user's chosen destination when it is the selected item, otherwise the item's own coordinates.
     */
    private Coordinates pointFor(CommitmentReader.Scheduled item, CommitmentContext context) {
        var selection = context.selection();
        return selection != null && Objects.equals(selection.itineraryItemId(), item.id()) && selection.destination() != null
                ? selection.destination()
                : item.coordinates();
    }

    /**
     * Turns a scheduled item into a commitment with an exact arrival time; returns null when the item has an arrival problem or its local
     * time is ambiguous or does not exist in the zone.
     */
    private Commitment interpret(CommitmentReader.Scheduled item, String zoneText, String source, Coordinates point, String pointSource,
            CommitmentContext context) {
        item = item.withArrivalZone(zoneText);
        if (item.arrivalRequirementIssue() != null)
            return null;
        try {
            var offsets = ZoneId.of(zoneText).getRules().getValidOffsets(item.start());
            if (offsets.size() != 1)
                return null;
            Instant start = item.start().toInstant(offsets.getFirst());
            int lead = lead(item, context);
            String policy = !item.hard() ? "PLANNED_ACTIVITY_BOUNDARY_NOT_FIXED"
                    : item.arrivalSource() != null && item.arrivalSource().reviewedArrivalAt() != null ? "USER_CONFIRMED_REQUIRED_ARRIVAL"
                            : item.documentedArrivalLeadMinutes() != null ? "DOCUMENTED_REQUIRED_ARRIVAL"
                                    : item.flight() ? "FLIGHT_120_MINUTE_PLANNING_POLICY_NOT_AIRLINE_REQUIREMENT"
                                            : "ARRIVE_AT_SCHEDULED_START_POLICY";
            if (item.hard() && context.selection() != null && Objects.equals(context.selection().itineraryItemId(), item.id())
                    && context.selection().arrivalLeadMinutes() != null && (item.documentedArrivalLeadMinutes() == null
                            || context.selection().arrivalLeadMinutes() >= item.documentedArrivalLeadMinutes()))
                policy = "TRAVELER_SPECIFIED_ARRIVAL_MARGIN";
            return new Commitment(item.id(), item.title(), start, start.minusSeconds(lead * 60L), point, ZoneId.of(zoneText).getId(),
                    source, policy, lead, pointSource, item.start());
        } catch (DateTimeException invalid) {
            return null;
        }
    }

    /**
     * Minutes of lead time before the item: the larger of the user's choice and the document's figure when both exist, otherwise the
     * document's figure, otherwise 120 for a flight and 0 for anything else.
     */
    private int lead(CommitmentReader.Scheduled item, CommitmentContext context) {
        var selection = context.selection();
        return selection != null && Objects.equals(selection.itineraryItemId(), item.id()) && selection.arrivalLeadMinutes() != null
                ? Math.max(selection.arrivalLeadMinutes(),
                        item.documentedArrivalLeadMinutes() == null ? 0 : item.documentedArrivalLeadMinutes())
                : item.documentedArrivalLeadMinutes() != null ? item.documentedArrivalLeadMinutes() : item.flight() ? 120 : 0;
    }

    /**
     * True when a place clearly is the item's place: within 250 metres of its point, or the same name and an exact address or locality
     * match.
     */
    private boolean strong(CommitmentReader.Scheduled item, PlaceDestination place, Coordinates point) {
        if (point != null)
            return distance(point, place.coordinates()) <= 250;
        return normalized(item.placeName()).equals(normalized(place.name()))
                && ((!blank(item.address()) && overlap(item.address(), place.address()) == 1)
                        || (!blank(item.locality()) && overlap(item.locality(), place.address()) == 1));
    }

    /**
     * True when a place could be the item's place. It needs an ID, name and coordinates, must be within 250 metres of the item's point (if
     * known) and match its locality. A matching venue name and locality is enough; otherwise any street numbers in the address must appear
     * and the name or address must be close enough.
     */
    private boolean plausible(CommitmentReader.Scheduled item, PlaceDestination place, Coordinates point) {
        if (place == null || blank(place.id()) || blank(place.name()) || place.coordinates() == null)
            return false;
        if (point != null && distance(point, place.coordinates()) > 250)
            return false;
        if (!blank(item.locality()) && overlap(item.locality(), place.address()) < 1)
            return false;
        boolean venueMatch = normalized(item.placeName()).equals(normalized(place.name()))
                || namedVenue(item.placeName(), place.name());
        // Venue listings need not reproduce a booking's entrance address or postcode.
        // Require independent locality evidence before relaxing that address comparison.
        // Reservation-only entries may have no linked activity/destination locality.
        // In that case use the booking's explicit comma-separated address area,
        // rather than requiring its entrance postcode in the venue listing.
        String[] addressParts = blank(item.address()) ? new String[0] : item.address().split(",");
        String addressArea = addressParts.length > 1 ? addressParts[1].trim() : null;
        boolean localityMatch = !blank(item.locality()) ? overlap(item.locality(), place.address()) == 1
                : !blank(addressArea) && overlap(addressArea, place.address()) == 1;
        if (venueMatch && localityMatch)
            return true;
        if (!blank(item.address())) {
            var numbers = Arrays.stream(normalized(item.address()).split(" ")).filter(token -> token.matches("[0-9]+[a-z]?")).toList();
            var actual = new HashSet<>(Arrays.asList(normalized(place.address()).split(" ")));
            if (!actual.containsAll(numbers))
                return false;
        }
        return (point != null || overlap(item.placeName(), place.name()) >= 0.5 || namedVenue(item.placeName(), place.name())
                || (!blank(item.address()) && overlap(item.address(), place.address()) >= 0.6));
    }

    /** Explicit same-as-meeting enrichment requires both venue identity and independent geographic evidence. */
    public static boolean matchesConfirmedMeeting(com.ishita.tripcoordinatorplatform.model.Location meeting, PlaceDestination place) {
        if (meeting == null || place == null || blank(place.id()) || place.coordinates() == null || blank(meeting.getName())
                || blank(place.name()))
            return false;
        if (!normalized(meeting.getName()).equals(normalized(place.name())) && !namedVenue(meeting.getName(), place.name()))
            return false;
        String[] parts = blank(meeting.getAddress()) ? new String[0] : meeting.getAddress().split(",");
        String area = parts.length > 1 ? parts[1].trim() : parts.length == 1 ? parts[0].trim() : null;
        if (!blank(area) && overlap(area, place.address()) < 1)
            return false;
        if (meeting.getLatitude() != null && meeting.getLongitude() != null)
            return distance(new Coordinates(meeting.getLatitude().doubleValue(), meeting.getLongitude().doubleValue()),
                    place.coordinates()) <= 250;
        return !blank(area) && overlap(area, place.address()) == 1;
    }

    /**
     * True when the meeting point starts with the venue's name (of at least two words), as when the entrance is added to the venue name.
     */
    private static boolean namedVenue(String meetingPoint, String venue) {
        String name = normalized(venue);
        // A named venue may omit the entrance suffix. Address/locality checks above still apply.
        return name.split(" ").length >= 2 && normalized(meetingPoint).startsWith(name + " ");
    }

    /** The share of the expected text's words that also appear in the actual text. */
    private static double overlap(String expected, String actual) {
        var tokens = Arrays.stream(normalized(expected).split(" ")).filter(v -> !v.isBlank()).toList();
        var actualTokens = new HashSet<>(Arrays.asList(normalized(actual).split(" ")));
        return tokens.isEmpty() ? 0 : (double) tokens.stream().filter(actualTokens::contains).count() / tokens.size();
    }

    /** Lower-cases text and reduces it to words separated by single spaces. */
    private static String normalized(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]+", " ").strip();
    }

    /** True when the text is null or blank. */
    private static boolean blank(String value) { return value == null || value.isBlank(); }

    /** The distance in metres between two points on the earth. */
    private static double distance(Coordinates a, Coordinates b) {
        double lat = Math.toRadians(b.latitude() - a.latitude()), lon = Math.toRadians(b.longitude() - a.longitude());
        double h = Math.pow(Math.sin(lat / 2), 2)
                + Math.cos(Math.toRadians(a.latitude())) * Math.cos(Math.toRadians(b.latitude())) * Math.pow(Math.sin(lon / 2), 2);
        return 6371000 * 2 * Math.asin(Math.sqrt(Math.min(1, h)));
    }
}
