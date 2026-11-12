package com.ishita.tripcoordinatorplatform.intelligence.planning;

import com.ishita.tripcoordinatorplatform.intelligence.commitment.*;
import com.ishita.tripcoordinatorplatform.intelligence.location.Coordinates;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;
import java.time.*;
import java.util.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/** Derived participant schedule, not an itinerary write or cached provider response. */
@Service
public class FreeTimeOpportunities {
    /**
     * A booking that starts or ends a free-time gap: its local and exact times, time zone, place and whether it is a flight or a fixed
     * commitment.
     */
    public record Boundary(Long itineraryItemId, String title, LocalDateTime localStart, LocalDateTime localEnd,
            Instant start, Instant end, String timeZone, String timezoneSource, Coordinates coordinates, boolean flight, boolean hard,
            boolean originRequiresConfirmation, String locationName, CommitmentReader.EndingEvidence endingEvidence) {
        public Boundary(Long id, String title, LocalDateTime localStart, LocalDateTime localEnd, Instant start, Instant end, String zone,
                String source, Coordinates coordinates, boolean flight, boolean hard, boolean confirmation, String name) {
            this(id, title, localStart, localEnd, start, end, zone, source, coordinates, flight, hard, confirmation, name, null);
        }

        public Boundary(Long itineraryItemId, String title, LocalDateTime localStart, LocalDateTime localEnd, Instant start, Instant end,
                String timeZone, String timezoneSource, Coordinates coordinates, boolean flight, boolean hard) {
            this(itineraryItemId, title, localStart, localEnd, start, end, timeZone, timezoneSource, coordinates, flight, hard, false,
                    title);
        }

        public Boundary(Long itineraryItemId, String title, LocalDateTime localStart, LocalDateTime localEnd, Instant start, Instant end,
                String timeZone, String timezoneSource, Coordinates coordinates, boolean flight) {
            this(itineraryItemId, title, localStart, localEnd, start, end, timeZone, timezoneSource, coordinates, flight, true);
        }

        public Boundary(Long itineraryItemId, String title, LocalDateTime localStart, LocalDateTime localEnd, Instant start, Instant end,
                String timeZone, String timezoneSource, Coordinates coordinates) {
            this(itineraryItemId, title, localStart, localEnd, start, end, timeZone, timezoneSource, coordinates, false);
        }
    }

    /** One free-time window between two bookings (or the trip edge) with its status, reasons and the earliest start and latest end. */
    public record Opportunity(String id, LocalDate localDate, String timeZone, Instant earliestStart,
            Instant latestEnd, Long calendarMinutes, Boundary before, Boundary after,
            Coordinates suggestedOrigin, String status, List<String> reasons, LocalDateTime localEarliestStart,
            LocalDateTime localLatestEnd, String originBoundary, boolean overnight,
            @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.ALWAYS)
            Boundary constrainingCommitment) {
        public Opportunity(String id, LocalDate localDate, String timeZone, Instant earliestStart, Instant latestEnd, Long calendarMinutes,
                Boundary before, Boundary after, Coordinates suggestedOrigin, String status, List<String> reasons,
                LocalDateTime localEarliestStart, LocalDateTime localLatestEnd, String originBoundary, boolean overnight) {
            this(id, localDate, timeZone, earliestStart, latestEnd, calendarMinutes, before, after, suggestedOrigin, status, reasons,
                    localEarliestStart, localLatestEnd, originBoundary, overnight, after);
        }

        public Opportunity(String id, LocalDate localDate, String timeZone, Instant earliestStart, Instant latestEnd, Long calendarMinutes,
                Boundary before, Boundary after, Coordinates suggestedOrigin, String status, List<String> reasons) {
            this(id, localDate, timeZone, earliestStart, latestEnd, calendarMinutes, before, after, suggestedOrigin, status, reasons,
                    earliestStart == null || timeZone == null ? null : LocalDateTime.ofInstant(earliestStart, ZoneId.of(timeZone)),
                    latestEnd == null || timeZone == null ? null : LocalDateTime.ofInstant(latestEnd, ZoneId.of(timeZone)),
                    before == null ? "NOW" : "PREVIOUS_ACTIVITY", false);
        }
    }

    /** All free-time windows of one participant on one trip, with a revision that changes when the schedule changes. */
    public record Schedule(Long tripId, Long userId, LocalDate tripStart, LocalDate tripEnd, String revision,
            Instant assessedAt, List<Opportunity> opportunities, List<String> warnings, ExplorationHours hours, long hoursVersion) {
        public Schedule(Long tripId, Long userId, LocalDate start, LocalDate end, String revision, Instant at,
                List<Opportunity> opportunities, List<String> warnings) {
            this(tripId, userId, start, end, revision, at, opportunities, warnings, ExplorationHours.defaults(), 0);
        }
    }

    /** A schedule together with the commitments it was built from. */
    public record Snapshot(Schedule schedule, List<CommitmentReader.Scheduled> commitments, Map<Long, String> clarifications) {
        public Snapshot(Schedule schedule, List<CommitmentReader.Scheduled> commitments) { this(schedule, commitments, Map.of()); }
    }

    /** The window the user picked, tied to the schedule revision it was picked from, plus any completion or expected-end times given. */
    public record Selection(String id, String revision, Map<Long, Instant> completedAt, Map<Long, Instant> expectedEnds) {
        public Selection(String id, String revision) { this(id, revision, Map.of(), Map.of()); }

        public Selection(String id, String revision, Map<Long, Instant> completedAt) { this(id, revision, completedAt, Map.of()); }

        public Selection {
            completedAt = completedAt == null ? Map.of() : Map.copyOf(completedAt);
            expectedEnds = expectedEnds == null ? Map.of() : Map.copyOf(expectedEnds);
        }
    }

    private final CommitmentReader reader;

    /** Creates the service with the reader that loads the participant's commitments. */
    public FreeTimeOpportunities(CommitmentReader reader) { this.reader = reader; }

    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.NOT_SUPPORTED)
    public Schedule read(Long tripId, Long userId, Map<Long, String> clarifications, Instant now) {
        return read(tripId, userId, clarifications, Map.of(), now);
    }

    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.NOT_SUPPORTED)
    public Schedule read(Long tripId, Long userId, Map<Long, String> clarifications, Map<Long, Instant> completedAt, Instant now) {
        return snapshot(tripId, userId, clarifications, completedAt, now).schedule();
    }

    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.NOT_SUPPORTED)
    public Snapshot snapshot(Long tripId, Long userId, Map<Long, String> clarifications, Map<Long, Instant> completedAt, Instant now) {
        return snapshot(tripId, userId, clarifications, completedAt, Map.of(), now);
    }

    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.NOT_SUPPORTED)
    public Schedule read(Long tripId, Long userId, Map<Long, String> clarifications, Map<Long, Instant> completedAt,
            Map<Long, Instant> expectedEnds, Instant now) {
        return snapshot(tripId, userId, clarifications, completedAt, expectedEnds, now).schedule();
    }

    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.NOT_SUPPORTED)
    public Snapshot snapshot(Long tripId, Long userId, Map<Long, String> clarifications, Map<Long, Instant> completedAt,
            Map<Long, Instant> expectedEnds, Instant now) {
        // The JDBC projection has one PostgreSQL statement snapshot, including authorization.
        // Suspend ambient transactions so final revalidation cannot inherit an older snapshot.
        var source = reader.snapshot(tripId, userId); // One statement includes membership and both participant sets.
        var assigned = source.assigned();
        var unassigned = source.unassigned().stream()
                .filter(i -> i.end() == null || i.end().toInstant(ZoneOffset.MIN).isAfter(now)).toList();
        var schedule = derive(tripId, userId, source.tripStart(), source.tripEnd(), assigned, clarifications, completedAt, expectedEnds,
                now, ExplorationHoursStore.decode(source.explorationHours()), source.hoursVersion());
        return withUnassigned(schedule, assigned, unassigned, clarifications, now);
    }

    /**
     * Marks windows that overlap hard commitments not assigned to any traveler as needing clarification, and adds a warning; with no such
     * commitments the schedule is returned as it is.
     */
    private static Snapshot withUnassigned(Schedule schedule, List<CommitmentReader.Scheduled> assigned,
            List<CommitmentReader.Unassigned> unassigned, Map<Long, String> clarifications, Instant now) {
        if (unassigned.isEmpty())
            return new Snapshot(schedule, assigned, clarifications);
        return new Snapshot(new Schedule(schedule.tripId(), schedule.userId(), schedule.tripStart(), schedule.tripEnd(),
                revision(schedule.revision() + unassigned), now,
                schedule.opportunities().stream().map(o -> {
                    var relevant = unassigned.stream()
                            .filter(i -> o.earliestStart() == null || o.latestEnd() == null || i.start() == null
                                    || (i.start().toInstant(ZoneOffset.MAX).isBefore(o.latestEnd())
                                            && (i.end() == null || i.end().toInstant(ZoneOffset.MIN).isAfter(o.earliestStart()))))
                            .map(CommitmentReader.Unassigned::id).toList();
                    return relevant.isEmpty() ? o
                            : new Opportunity(o.id(), o.localDate(), o.timeZone(), o.earliestStart(), o.latestEnd(), o.calendarMinutes(),
                                    o.before(), o.after(), o.suggestedOrigin(), "CLARIFICATION_REQUIRED",
                                    append(o.reasons(),
                                            "Potentially overlapping unassigned hard commitments " + relevant
                                                    + " need participant assignment before availability can be asserted."),
                                    o.localEarliestStart(), o.localLatestEnd(), o.originBoundary(), o.overnight(),
                                    o.constrainingCommitment());
                }).toList(),
                append(schedule.warnings(),
                        "Unassigned hard commitments: " + unassigned + ". They are not assumed to apply to every traveler."),
                schedule.hours(), schedule.hoursVersion()), assigned, clarifications);
    }

    public static Schedule derive(Long tripId, Long userId, LocalDate tripStart, LocalDate tripEnd,
            List<CommitmentReader.Scheduled> facts, Map<Long, String> clarifications, Instant now) {
        return derive(tripId, userId, tripStart, tripEnd, facts, clarifications, Map.of(), now);
    }

    public static Schedule derive(Long tripId, Long userId, LocalDate tripStart, LocalDate tripEnd,
            List<CommitmentReader.Scheduled> facts, Map<Long, String> clarifications, Map<Long, Instant> completedAt, Instant now) {
        return derive(tripId, userId, tripStart, tripEnd, facts, clarifications, completedAt, Map.of(), now);
    }

    public static Schedule derive(Long tripId, Long userId, LocalDate tripStart, LocalDate tripEnd,
            List<CommitmentReader.Scheduled> facts, Map<Long, String> clarifications, Map<Long, Instant> completedAt,
            Map<Long, Instant> expectedEnds, Instant now) {
        return derive(tripId, userId, tripStart, tripEnd, facts, clarifications, completedAt, expectedEnds, now,
                ExplorationHours.defaults(), 0);
    }

    /**
     * Works out the free-time windows from the commitments, the user's clarifications and the current time, and gives the result a
     * revision.
     */
    public static Schedule derive(Long tripId, Long userId, LocalDate tripStart, LocalDate tripEnd, List<CommitmentReader.Scheduled> facts,
            Map<Long, String> clarifications, Map<Long, Instant> completedAt, Map<Long, Instant> expectedEnds, Instant now,
            ExplorationHours hours, long hoursVersion) {
        if (expectedEnds == null)
            expectedEnds = Map.of();
        if (expectedEnds.size() > 100 || !facts.stream().map(CommitmentReader.Scheduled::id).collect(java.util.stream.Collectors.toSet())
                .containsAll(expectedEnds.keySet()))
            throw new IllegalArgumentException("Expected ends must belong to this traveler's commitments.");
        if (clarifications == null || clarifications.size() > 100
                || clarifications.values().stream().anyMatch(z -> z == null || !ZoneId.getAvailableZoneIds().contains(z)))
            throw new IllegalArgumentException("Use item-specific IANA timezones.");
        facts = facts.stream()
                .map(i -> i.withArrivalZone(i.timeZone() == null || i.timeZone().isBlank() ? clarifications.get(i.id()) : i.timeZone()))
                .toList();
        if (completedAt == null)
            completedAt = Map.of();
        if (completedAt.size() > 100)
            throw new IllegalArgumentException("Too many completion clarifications.");
        String revision = revision(tripId + ":" + userId + ":" + tripStart + ":" + tripEnd + ":"
                + facts.stream().sorted(Comparator.comparing(CommitmentReader.Scheduled::id)).toList() + ":" + new TreeMap<>(clarifications)
                + ":" + new TreeMap<>(completedAt) + ":" + new TreeMap<>(expectedEnds) + ":" + hours + ":" + hoursVersion);
        List<Boundary> known = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        for (var item : facts) {
            String zone = item.timeZone() == null || item.timeZone().isBlank() ? clarifications.get(item.id()) : item.timeZone();
            Instant start = strict(item.start(), zone);
            String endZone = item.endTimeZone() == null ? zone : item.endTimeZone();
            Instant end = strict(item.end(), endZone);
            var completed = completedAt.get(item.id());
            if (completed != null) {
                if (start == null || completed.isBefore(start) || completed.isAfter(now))
                    throw new IllegalArgumentException(
                            "A completion confirmation must follow the resolved start and cannot be in the future.");
                end = completed;
            }
            var expected = expectedEnds.get(item.id());
            if (expected != null) {
                if (item.end() != null || completed != null || start == null || !expected.isAfter(start))
                    throw new IllegalArgumentException("Estimate only a missing end, after the commitment's resolved start.");
                end = expected;
            }
            // Only discard unresolved historical data when past in every legal UTC offset.
            if (start == null && item.start() != null && !item.start().toInstant(ZoneOffset.MIN).isAfter(now)
                    && item.end() != null && !item.end().toInstant(ZoneOffset.MIN).isAfter(now))
                continue;
            if (item.start() == null) {
                warnings.add("Item " + item.id() + " has no scheduled start and cannot establish a calendar window.");
                continue;
            }
            if (end != null && start != null && !end.isAfter(start)) {
                end = null;
                warnings.add("Invalid end boundary for item " + item.id() + "; completion remains unknown.");
            }
            known.add(new Boundary(item.id(), item.title(), item.start(),
                    end == null ? item.end() : LocalDateTime.ofInstant(end, ZoneId.of(endZone)), start, end, zone,
                    zone == null ? "UNRESOLVED" : item.timeZone() == null ? "TRAVELER_ITEM_TIMEZONE" : item.zoneSource(),
                    item.coordinates(), item.flight(), item.hard(), item.reservationId() != null, item.placeName(), item.endingEvidence()));
        }
        Map<Long, Integer> leads = new HashMap<>();
        facts.forEach(
                i -> leads.put(i.id(), i.documentedArrivalLeadMinutes() != null ? i.documentedArrivalLeadMinutes() : i.flight() ? 120 : 0));
        // Validate estimates even when the day timezone is unresolved.
        for (var item : known)
            if (expectedEnds.containsKey(item.itineraryItemId())) {
                var next = known.stream().filter(n -> n.start() != null && item.start() != null && n.start().isAfter(item.start()))
                        .min(Comparator.comparing(Boundary::start)).orElse(null);
                if (next != null && item.end().isAfter(next.start().minusSeconds(leads.get(next.itineraryItemId()) * 60L)))
                    throw new IllegalArgumentException("Expected end overlaps the next commitment's required arrival.");
            }
        var daily = daily(tripId, userId, tripStart, tripEnd, revision, known, leads, expectedEnds.keySet(), now, hours, hoursVersion,
                warnings);
        var arrivalIssues = new HashMap<Long, String>();
        facts.forEach(i -> {
            if (i.arrivalRequirementIssue() != null)
                arrivalIssues.put(i.id(), i.arrivalRequirementIssue());
        });
        if (arrivalIssues.isEmpty())
            return daily;
        return new Schedule(tripId, userId, tripStart, tripEnd, revision, now, daily.opportunities().stream().map(o -> {
            String issue = o.constrainingCommitment() == null ? null : arrivalIssues.get(o.constrainingCommitment().itineraryItemId());
            return issue == null ? o
                    : new Opportunity(o.id(), o.localDate(), o.timeZone(), o.earliestStart(), null, null, o.before(), o.after(),
                            o.suggestedOrigin(), "CLARIFICATION_REQUIRED", append(o.reasons(), issue), o.localEarliestStart(),
                            o.localLatestEnd(), o.originBoundary(), o.overnight(), o.constrainingCommitment());
        }).toList(), daily.warnings(), hours, hoursVersion);
    }

    /** Builds the windows for each trip day between the sorted bookings, using the participant's exploration hours. */
    private static Schedule daily(Long tripId, Long userId, LocalDate tripStart, LocalDate tripEnd, String revision, List<Boundary> items,
            Map<Long, Integer> leads, Set<Long> estimated, Instant now, ExplorationHours hours, long version, List<String> warnings) {
        var results = new ArrayList<Opportunity>();
        var ids = new HashSet<String>();
        var ordered = items.stream().filter(b -> b.start() != null)
                .sorted(Comparator.comparing((Boundary b) -> b.start().minusSeconds(leads.get(b.itineraryItemId()) * 60L))
                        .thenComparing(Boundary::itineraryItemId))
                .toList();
        Instant coveredUntil = null;
        for (LocalDate date = tripStart; !date.isAfter(tripEnd); date = date.plusDays(1)) {
            var day = hours.day(date);
            String zone = day.timeZone() != null ? day.timeZone() : hours.timeZone();
            if (zone == null) {
                final var d = date;
                var zones = items.stream().filter(i -> i.localStart().toLocalDate().equals(d)).map(Boundary::timeZone)
                        .filter(Objects::nonNull).distinct().toList();
                if (zones.isEmpty())
                    zones = items.stream().map(Boundary::timeZone).filter(Objects::nonNull).distinct().toList();
                if (zones.size() == 1)
                    zone = zones.getFirst();
            }
            LocalDateTime localStart = date.atTime(day.dayStart()), localEnd = date.plusDays(day.endDayOffset()).atTime(day.dayEnd());
            Instant start = strict(localStart, zone), end = strict(localEnd, zone);
            if (start == null || end == null) {
                if (localEnd.toInstant(ZoneOffset.MIN).isAfter(now)) {
                    if (zone == null)
                        calendarOnlyDay(results, ids, localStart, localEnd, items, leads,
                                "Choose this day's timezone and resolve activity timezones before route evaluation.");
                    else
                        results.add(new Opportunity("day-" + date, date, zone, null, null, null, null, null, null,
                                "CALENDAR_NEEDS_TIMEZONE",
                                List.of("Exploration hours fall in an ambiguous or nonexistent local time. Adjust this day's hours."),
                                localStart, localEnd, "DAY_START", day.endDayOffset() == 1));
                }
                continue;
            }
            if (!end.isAfter(start))
                throw new IllegalArgumentException("Day end must follow day start.");
            if (coveredUntil != null && start.isBefore(coveredUntil))
                start = coveredUntil;
            if (coveredUntil == null || end.isAfter(coveredUntil))
                coveredUntil = end;
            if (!start.isBefore(end))
                continue;
            final Instant periodStart = start, periodEnd = end;
            var unresolved = items.stream()
                    .filter(i -> i.start() == null
                            && i.localStart().minusMinutes(leads.get(i.itineraryItemId())).toInstant(ZoneOffset.MAX).isBefore(periodEnd)
                            && (i.localEnd() == null || i.localEnd().toInstant(ZoneOffset.MIN).isAfter(periodStart)))
                    .toList();
            if (!unresolved.isEmpty()) {
                if (end.isAfter(now))
                    results.add(new Opportunity("day-" + date, date, zone, null, end, null, unresolved.getFirst(), null, null,
                            "CALENDAR_NEEDS_TIMEZONE", List.of("Resolve the timezone of "
                                    + unresolved.stream().map(Boundary::title).toList() + " before verifying this day."),
                            localStart, localEnd, "DAY_START", day.endDayOffset() == 1));
                continue;
            }
            Instant cursor = start;
            Boundary before = null, unknown = null;
            boolean ended = false;
            for (var next : ordered) {
                Instant deadline = next.start().minusSeconds(leads.get(next.itineraryItemId()) * 60L);
                if (deadline.isAfter(cursor)) {
                    Instant limit = deadline.isBefore(end) ? deadline : end;
                    if (limit.isAfter(cursor))
                        addDaily(results, ids, date, cursor, limit, unknown == null ? before : unknown, next, zone, unknown != null,
                                estimated, now, hours.minimumRestMinutes(), end, leads);
                }
                if (!deadline.isBefore(end)) {
                    ended = true;
                    break;
                }
                if (next.end() != null && !next.end().isAfter(periodStart)) {
                    // Retain an endpoint only when it establishes the window start, not an independent day-start boundary.
                    if (next.end().equals(periodStart) && (before == null || before.end() == null || next.end().isAfter(before.end())))
                        before = next;
                    if (unknown != null && !next.end().isBefore(unknown.start()))
                        unknown = null;
                } else if (next.end() == null) {
                    unknown = next;
                    if (deadline.isAfter(cursor))
                        cursor = deadline;
                } else if (!next.end().isBefore(cursor)) {
                    cursor = next.end();
                    before = next;
                    unknown = null;
                }
                // A nested interval must never move the occupied frontier backwards.
                if (!cursor.isBefore(end)) {
                    ended = true;
                    break;
                }
            }
            if (!ended && cursor.isBefore(end))
                addDaily(results, ids, date, cursor, end, unknown == null ? before : unknown, null, zone, unknown != null, estimated, now,
                        hours.minimumRestMinutes(), end, leads);
        }
        return new Schedule(tripId, userId, tripStart, tripEnd, revision, now, List.copyOf(results), List.copyOf(warnings), hours, version);
    }

    /** Where the next window should start from: the ending place of the previous booking when it is known, otherwise the booking itself. */
    private static Boundary originBoundary(Boundary before) {
        if (before == null || before.endingEvidence() == null || before.endingEvidence().reservationId() == null || before.flight())
            return before;
        var end = before.endingEvidence();
        return new Boundary(before.itineraryItemId(), before.title(), before.localStart(), before.localEnd(), before.start(), before.end(),
                before.timeZone(), before.timezoneSource(), end.coordinates(), before.flight(), before.hard(),
                !end.confirmed() || end.coordinates() == null, end.name(), end);
    }

    /** Adds one daily window between two bookings, leaving out windows already in the past, and gives it a unique ID. */
    private static void addDaily(List<Opportunity> results, Set<String> ids, LocalDate date, Instant rawStart, Instant end, Boundary before,
            Boundary after, String zone,
            boolean unknown, Set<Long> estimated, Instant now, int restMinutes, Instant dayEnd, Map<Long, Integer> leads) {
        before = originBoundary(before);
        String base = (before == null ? "trip-start" : before.itineraryItemId()) + "-"
                + (after == null ? "trip-end" : after.itineraryItemId());
        String id = ids.add(base) ? base : base + "-day-" + date;
        if (!end.isAfter(now))
            return;
        Instant start = rawStart.isBefore(now) ? now : rawStart;
        if (!start.isBefore(end))
            return;
        var reasons = new ArrayList<String>();
        if (unknown)
            reasons.add("Enter the preceding activity's expected end; this estimate will not mark it complete.");
        if (before != null && estimated.contains(before.itineraryItemId()))
            reasons.add("Availability depends on your expected end, not verified completion.");
        if (before != null && (before.coordinates() == null || before.flight()))
            reasons.add("Choose the missing starting location.");
        Boundary constraint = after != null && after.start() != null
                && !after.start().minusSeconds(leads.get(after.itineraryItemId()) * 60L).isAfter(end) ? after : null;
        if (constraint != null && constraint.coordinates() == null)
            reasons.add("Confirm the next meeting point.");
        if (after != null && after.start() != null && end.equals(dayEnd)) {
            long gap = Duration.between(end, after.start().minusSeconds(leads.get(after.itineraryItemId()) * 60L)).toMinutes();
            if (gap >= 0 && gap < restMinutes && !end.atZone(ZoneId.of(zone)).toLocalDate().isBefore(date))
                reasons.add("Only " + gap
                        + " minutes of potential rest/preparation time before the next required arrival; this is not guaranteed sleep or "
                        + "travel time.");
        }
        String status = unknown ? "CLARIFICATION_REQUIRED"
                : before != null && estimated.contains(before.itineraryItemId()) ? "ESTIMATED_AVAILABILITY" : "NEEDS_ROUTE_EVALUATION";
        results.add(new Opportunity(id, date, zone, unknown ? null : start, end, unknown ? null : Duration.between(start, end).toMinutes(),
                before, after, before == null || before.flight() ? null : before.coordinates(), status, List.copyOf(reasons),
                unknown ? null : LocalDateTime.ofInstant(rawStart, ZoneId.of(zone)), LocalDateTime.ofInstant(end, ZoneId.of(zone)),
                before != null ? "PREVIOUS_ACTIVITY" : rawStart.isBefore(now) || rawStart.equals(now) ? "NOW" : "DAY_START",
                !date.equals(end.atZone(ZoneId.of(zone)).toLocalDate()), constraint));
    }

    /** Wall-clock labels remain inspectable; they never authorize routing without resolved instants. */
    private static void calendarOnlyDay(List<Opportunity> output, Set<String> ids, LocalDateTime start, LocalDateTime end,
            List<Boundary> items, Map<Long, Integer> leads, String reason) {
        LocalDate date = start.toLocalDate();
        var ordered = items.stream().sorted(Comparator.comparing(i -> i.localStart().minusMinutes(leads.get(i.itineraryItemId()))))
                .toList();
        LocalDateTime cursor = start;
        Boundary before = null, unknown = null;
        boolean finished = false;
        for (var next : ordered) {
            var deadline = next.localStart().minusMinutes(leads.get(next.itineraryItemId()));
            if (deadline.isAfter(cursor))
                addCalendar(output, ids, date, cursor, deadline.isBefore(end) ? deadline : end, unknown == null ? before : unknown, next,
                        unknown != null, reason);
            if (!deadline.isBefore(end)) {
                finished = true;
                break;
            }
            if (next.localEnd() != null && !next.localEnd().isAfter(start)) {
                if (next.localEnd().equals(start)
                        && (before == null || before.localEnd() == null || next.localEnd().isAfter(before.localEnd())))
                    before = next;
                if (unknown != null && !next.localEnd().isBefore(unknown.localStart()))
                    unknown = null;
            } else if (next.localEnd() == null) {
                unknown = next;
                if (deadline.isAfter(cursor))
                    cursor = deadline;
            } else if (!next.localEnd().isBefore(cursor)) {
                cursor = next.localEnd();
                before = next;
                unknown = null;
            }
            if (!cursor.isBefore(end)) {
                finished = true;
                break;
            }
        }
        if (!finished && cursor.isBefore(end))
            addCalendar(output, ids, date, cursor, end, unknown == null ? before : unknown, null, unknown != null, reason);
    }

    /** Adds one calendar-based window between two bookings, if it has a positive length, and gives it a unique ID. */
    private static void addCalendar(List<Opportunity> output, Set<String> ids, LocalDate date, LocalDateTime start, LocalDateTime end,
            Boundary before, Boundary after, boolean unknown, String reason) {
        if (!start.isBefore(end))
            return;
        before = originBoundary(before);
        String base = (before == null ? "trip-start" : before.itineraryItemId()) + "-"
                + (after == null ? "trip-end" : after.itineraryItemId());
        String id = ids.add(base) ? base : base + "-day-" + date;
        output.add(new Opportunity(id, date, null, null, null, null, before, after, null,
                unknown ? "CLARIFICATION_REQUIRED" : "CALENDAR_NEEDS_TIMEZONE", List.of(reason), unknown ? null : start, end,
                before == null ? "DAY_START" : "PREVIOUS_ACTIVITY", !date.equals(end.toLocalDate())));
    }

    /**
     * Finds the selected window in the schedule; refuses if the schedule changed or the window is gone, needs clarification or has no
     * usable times.
     */
    public Opportunity require(Schedule schedule, Selection selected) {
        if (selected == null || !schedule.revision().equals(selected.revision()))
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "The participant schedule changed. Refresh opportunities before searching.");
        var opportunity = schedule.opportunities().stream().filter(o -> o.id().equals(selected.id())).findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.CONFLICT, "This opportunity is no longer available."));
        if (opportunity.status().equals("CLARIFICATION_REQUIRED") || opportunity.status().equals("CALENDAR_NEEDS_TIMEZONE")
                || opportunity.earliestStart() == null || opportunity.latestEnd() == null)
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Clarify the opportunity's schedule/completion evidence before searching.");
        return opportunity;
    }

    /** Converts a local time to an exact instant, or null when the zone is missing or the time is ambiguous or does not exist. */
    private static Instant strict(LocalDateTime local, String zone) {
        if (local == null || zone == null)
            return null;
        try {
            var offsets = ZoneId.of(zone).getRules().getValidOffsets(local);
            return offsets.size() == 1 ? local.toInstant(offsets.getFirst()) : null;
        } catch (DateTimeException invalid) {
            return null;
        }
    }

    private static List<String> append(List<String> list, String value) {
        var result = new ArrayList<>(list);
        result.add(value);
        return List.copyOf(result);
    }

    private static String revision(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
