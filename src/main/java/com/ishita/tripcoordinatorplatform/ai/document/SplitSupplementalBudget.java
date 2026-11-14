package com.ishita.tripcoordinatorplatform.ai.document;

import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;

import static com.ishita.tripcoordinatorplatform.ai.document.ReadingAssertion.Role;
import static com.ishita.tripcoordinatorplatform.ai.document.BookingFact.Basis;
import static com.ishita.tripcoordinatorplatform.ai.document.BookingFact.Status;

/**
 * Makes the merged supplemental facts of a split extraction fit the document limits (24 facts, 16,000 characters)
 * instead of failing the whole split. The calls write their facts in parallel and cannot see each other, so together
 * they can overshoot even when each follows its own share.
 *
 * <p>Nothing happens while the list fits. When it does not, two steps run in this order and stop as soon as it fits:
 * <ol>
 *   <li><b>Collapse:</b> one fact that several bookings each repeated with the same meaning (for example "two adults
 *       are listed") becomes a single fact that applies to all of them. Nothing is lost and no text is written: the
 *       kept wording is one of the originals, and only if it names no particular booking.</li>
 *   <li><b>Trim:</b> only if it still does not fit, whole facts of the lowest-value kinds (role OTHER, then METADATA)
 *       are dropped, largest first. Notices, policies and every booking fact or condition are never dropped.</li>
 * </ol>
 * Trimming is never silent: the merger adds a "SUPPLEMENTAL TRIMMED" coverage warning. If the list still does not fit,
 * it is returned as it is and the normal whole-document validation sends the job to the single call.
 */
final class SplitSupplementalBudget {
    static final String TRIMMED_MARKER = "SUPPLEMENTAL TRIMMED:";
    private static final double SIMILAR = 0.85;
    private static final int MAX_EVIDENCE = 4;
    private static final Pattern BOOKING_NUMBER = Pattern.compile("\\bbookings?\\s+\\d+\\b");
    private static final Pattern WORDS = Pattern.compile("[\\p{L}\\p{N}]+");
    /** Words that point at one particular booking; a fact that uses them cannot stand in for the others. */
    private static final Set<String> BOOKING_WORDS = Set.of("booking", "bookings", "replacement", "original", "cancelled",
            "canceled", "superseded", "replaced", "first", "second", "third", "fourth", "fifth", "sixth");

    private SplitSupplementalBudget() {}

    /** The fitted list plus what was done to it: how many facts were merged away and how many were dropped. */
    record Fitted(List<SupplementalReadingFact> facts, int collapsed, int trimmed) {}

    static Fitted fit(List<SupplementalReadingFact> facts, BookingInventory inventory) {
        if (fits(facts)) return new Fitted(facts, 0, 0);
        var working = new ArrayList<>(facts);
        int collapsed = collapse(working, inventory);
        int trimmed = fits(working) ? 0 : trim(working);
        LoggerFactory.getLogger(SplitSupplementalBudget.class).info(
                "Document split supplemental fit before={}/{} collapsed={} trimmed={} after={}/{}", facts.size(),
                ReadingAssertionValidator.serializedCharacters(facts), collapsed, trimmed, working.size(),
                ReadingAssertionValidator.serializedCharacters(working));
        return new Fitted(List.copyOf(working), collapsed, trimmed);
    }

    /** The warning text to add when facts were dropped: the marker and the number, then any warning the calls gave. */
    static String trimmedWarning(int trimmed, String existing, int maxLength) {
        String marker = TRIMMED_MARKER + " " + trimmed + (trimmed == 1 ? " fact" : " facts") + " omitted for size.";
        if (existing == null) return marker;
        String joined = marker + " " + existing;
        return joined.length() <= maxLength ? joined : joined.substring(0, maxLength - 1) + "…";
    }

    private static boolean fits(List<SupplementalReadingFact> facts) {
        return facts.size() <= SupplementalReadingFact.MAX_FACTS
                && ReadingAssertionValidator.serializedCharacters(facts) <= SupplementalReadingFact.MAX_SERIALIZED_CHARACTERS;
    }

    // ---- step 1: collapse ------------------------------------------------------------------------------------

    /** One group of look-alike facts from different bookings: the fact that stays, and the others that merge into it. */
    private static final class Group {
        final SupplementalReadingFact first;
        final Set<String> words;
        final Set<String> qualificationWords;
        final List<SupplementalReadingFact> members = new ArrayList<>();
        final Set<Integer> bookings = new TreeSet<>();

        Group(SupplementalReadingFact first, Set<String> words, Set<String> qualificationWords) {
            this.first = first;
            this.words = words;
            this.qualificationWords = qualificationWords;
        }
    }

    /** Merges look-alike facts in place and returns how many facts disappeared. */
    private static int collapse(List<SupplementalReadingFact> facts, BookingInventory inventory) {
        var labels = inventoryWords(inventory);
        var groups = new ArrayList<Group>();
        for (var fact : facts) {
            if (!eligible(fact)) continue;
            int booking = bookingOf(fact);
            var words = words(description(fact), labels);
            var qualification = words(text(fact.fact().value().qualification()), labels);
            var home = groups.stream().filter(g -> !g.bookings.contains(booking) && alike(g, fact, words, qualification))
                    .findFirst().orElse(null);
            if (home == null) {
                home = new Group(fact, words, qualification);
                groups.add(home);
            }
            home.members.add(fact);
            home.bookings.add(booking);
        }
        int removed = 0;
        for (var group : groups) {
            var stays = representative(group, labels);
            if (group.members.size() < 2 || stays == null) continue;
            var merged = merge(group, stays, inventory.count());
            int at = facts.indexOf(group.members.getFirst());
            group.members.forEach(facts::remove);
            facts.add(Math.min(at, facts.size()), merged);
            removed += group.members.size() - 1;
        }
        return removed;
    }

    /** A plain, fully clear, single-booking fact that states one thing and relies on nothing else. */
    private static boolean eligible(SupplementalReadingFact item) {
        var scope = item.applicability().value();
        var fact = item.fact();
        if (item.applicability().status() != Status.CLEAR || scope == null
                || scope.kind() != SupplementalReadingFact.ScopeKind.BOOKINGS || scope.bookingIndexes().size() != 1) return false;
        if (fact.status() != Status.CLEAR || fact.value() == null || !fact.alternatives().isEmpty()) return false;
        if (fact.value().role() == Role.METADATA || fact.interpretation().basis() != Basis.EXPLICIT) return false;
        return fact.interpretation().dependencies().isEmpty() && fact.interpretation().calculation() == null;
    }

    private static int bookingOf(SupplementalReadingFact item) {
        return item.applicability().value().bookingIndexes().getFirst();
    }

    /** Same kind of statement (role, modality, conditionality, trigger, detail), same numbers, and nearly the same words. */
    private static boolean alike(Group group, SupplementalReadingFact item, Set<String> words, Set<String> qualification) {
        var a = group.first.fact().value();
        var b = item.fact().value();
        if (a.role() != b.role() || a.modality() != b.modality() || a.conditionality() != b.conditionality()
                || !java.util.Objects.equals(a.trigger(), b.trigger()) || !java.util.Objects.equals(a.detail(), b.detail())) return false;
        return similar(group.words, words) && similar(group.qualificationWords, qualification);
    }

    /** Identical numbers (two adults is never three adults) and at least 85% of the words in common. */
    private static boolean similar(Set<String> a, Set<String> b) {
        if (!numbers(a).equals(numbers(b))) return false;
        if (a.isEmpty() && b.isEmpty()) return true;
        var common = new HashSet<>(a);
        common.retainAll(b);
        var all = new HashSet<>(a);
        all.addAll(b);
        return all.isEmpty() || (double) common.size() / all.size() >= SIMILAR;
    }

    private static Set<String> numbers(Set<String> words) {
        var numbers = new TreeSet<String>();
        for (var word : words) if (Character.isDigit(word.charAt(0))) numbers.add(word);
        return numbers;
    }

    /** The member whose own wording names no particular booking, so it is true for every booking it will now cover. */
    private static SupplementalReadingFact representative(Group group, Set<String> labels) {
        return group.members.stream().filter(m -> namesNoBooking(description(m), labels)
                && namesNoBooking(text(m.fact().value().qualification()), labels)).findFirst().orElse(null);
    }

    private static boolean namesNoBooking(String text, Set<String> labels) {
        return rawWords(text).stream().noneMatch(w -> BOOKING_WORDS.contains(w) || labels.contains(w))
                && !BOOKING_NUMBER.matcher(text.toLowerCase(Locale.ROOT)).find();
    }

    /** The staying fact, now applying to every booking in the group, with up to four of the group's evidence rows. */
    private static SupplementalReadingFact merge(Group group, SupplementalReadingFact stays, int bookingCount) {
        var scope = group.bookings.size() == bookingCount
                ? new SupplementalReadingFact.Scope(SupplementalReadingFact.ScopeKind.ALL_BOOKINGS, List.of())
                : new SupplementalReadingFact.Scope(SupplementalReadingFact.ScopeKind.BOOKINGS, List.copyOf(group.bookings));
        var applicability = new SupplementalReadingFact.Applicability(Status.CLEAR, scope, List.of(), null);
        var ordered = new ArrayList<>(group.members);
        ordered.remove(stays);
        ordered.addFirst(stays);
        var evidence = new ArrayList<ReadingFact.SourceEvidence>();
        for (int row = 0; evidence.size() < MAX_EVIDENCE; row++) {
            boolean any = false;
            for (var member : ordered) {
                var rows = member.fact().evidence();
                if (row >= rows.size()) continue;
                any = true;
                if (evidence.size() < MAX_EVIDENCE && !evidence.contains(rows.get(row))) evidence.add(rows.get(row));
            }
            if (!any) break;
        }
        var fact = stays.fact();
        return new SupplementalReadingFact(applicability, new ReadingFact<>(fact.status(), fact.value(), fact.alternatives(),
                evidence, fact.uncertainty(), fact.sourceExpressions(), fact.interpretation()));
    }

    // ---- step 2: trim ----------------------------------------------------------------------------------------

    /** Drops whole OTHER facts, then METADATA facts, largest first, until the list fits. Returns how many were dropped. */
    private static int trim(List<SupplementalReadingFact> facts) {
        int dropped = 0;
        while (!fits(facts)) {
            var victim = facts.stream().filter(f -> rank(f) >= 0).max(Comparator
                    .<SupplementalReadingFact>comparingInt(f -> -rank(f))
                    .thenComparingInt(f -> ReadingAssertionValidator.serializedCharacters(List.of(f)))
                    .thenComparingInt(facts::lastIndexOf)).orElse(null);
            if (victim == null) break;
            facts.remove(victim);
            dropped++;
        }
        return dropped;
    }

    /** 0 = drop first (OTHER), 1 = drop next (METADATA), -1 = never dropped. */
    private static int rank(SupplementalReadingFact item) {
        var value = item.fact().value();
        if (value == null) return -1;
        return value.role() == Role.OTHER ? 0 : value.role() == Role.METADATA ? 1 : -1;
    }

    // ---- wording helpers -------------------------------------------------------------------------------------

    private static String description(SupplementalReadingFact item) { return text(item.fact().value().description()); }

    private static String text(String value) { return value == null ? "" : value; }

    private static List<String> rawWords(String text) {
        var words = new ArrayList<String>();
        var matcher = WORDS.matcher(text.toLowerCase(Locale.ROOT));
        while (matcher.find()) words.add(matcher.group());
        return words;
    }

    /** The words of a text without the parts that only name a booking: "booking 3", labels, references and booking words. */
    private static Set<String> words(String text, Set<String> labels) {
        var words = new LinkedHashSet<String>();
        for (var word : rawWords(BOOKING_NUMBER.matcher(text.toLowerCase(Locale.ROOT)).replaceAll(" ")))
            if (!BOOKING_WORDS.contains(word) && !labels.contains(word)) words.add(word);
        return words;
    }

    private static Set<String> inventoryWords(BookingInventory inventory) {
        var words = new HashSet<String>();
        for (var entry : inventory.bookings()) {
            words.addAll(rawWords(text(entry.label())));
            words.addAll(rawWords(text(entry.reference())));
        }
        return words;
    }
}
