package com.ishita.tripcoordinatorplatform.ai.document;

import java.util.HashSet;
import java.util.List;
import java.util.stream.IntStream;

/**
 * Short AI-derived list of the bookings a document appears to contain, in document order. It is a hint for splitting
 * extraction, never a verified fact and never stored as extraction content.
 */
public record BookingInventory(List<Entry> bookings) {
    /** The ordinal is the booking's final bookingIndex. The reference is only what the document appears to print. */
    public record Entry(int ordinal, String label, String reference, String startHint) {
        public Entry {
            reference = reference == null || reference.isBlank() ? null : reference.strip();
            startHint = startHint == null || startHint.isBlank() ? null : startHint.strip();
        }
    }

    public BookingInventory {
        bookings = bookings == null ? List.of() : List.copyOf(bookings);
    }

    public static BookingInventory empty() { return new BookingInventory(List.of()); }

    public int count() { return bookings.size(); }

    /**
     * Whether splitting is worth trying: between two and {@code maxBookings} entries, ordinals 0..n-1 in order,
     * a label on each, and no two entries with the same label and reference (the calls could not tell them apart).
     */
    public boolean usable(int maxBookings) {
        if (bookings.size() < 2 || bookings.size() > maxBookings) return false;
        boolean ordered = IntStream.range(0, bookings.size()).allMatch(i -> bookings.get(i) != null
                && bookings.get(i).ordinal() == i);
        if (!ordered || bookings.stream().anyMatch(e -> e.label() == null || e.label().isBlank())) return false;
        var identities = new HashSet<List<String>>();
        return bookings.stream().allMatch(e -> identities.add(java.util.Arrays.asList(e.label().strip(), e.reference())));
    }
}
