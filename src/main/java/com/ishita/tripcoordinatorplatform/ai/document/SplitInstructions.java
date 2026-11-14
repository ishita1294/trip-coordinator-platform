package com.ishita.tripcoordinatorplatform.ai.document;

/**
 * Instructions for split calls: the unchanged contract instructions plus one static paragraph. The text never names a
 * booking, so it is identical for every call of every document and the provider can reuse the shared prefix.
 */
final class SplitInstructions {
    private SplitInstructions() {}

    static final String PARTIAL_MODE = """

            SPLIT MODE. The final text part of the user input gives your task and lists the document's bookings with
            zero-based ordinals. The list is an AI-derived hint for locating bookings, not evidence. Split mode changes
            only WHICH items you return, never how an item is represented: every rule above still applies to what you
            return. "Read the entire document in one pass" means read all of it for context; return only your task.
            TASK: BOOKING k. Return exactly one booking in bookings, the one listed with ordinal k, with bookingIndex k.
            Use bookingIndex k for its own evidence, lifecycle and supplemental facts. Never return any other booking.
            Another booking is identified only in replaces, by its listed ordinal. Dependencies and calculation
            operands reference only facts of this same booking. A fact with basis EXPLICIT has no dependencies; a fact
            that relies on other facts is CONTEXT_INTERPRETED or CALCULATED.
            A document-wide statement that applies to this booking, such as a general arrival rule, may be quoted as
            this booking's evidence with bookingIndex k and a bookingAssociation saying why it applies. A CLEAR fact
            always has a value and at least one such quotation; otherwise mark it AMBIGUOUS or MISSING.
            Booking conditions hold only requirements, restrictions, options and policies of this booking. Never put a
            notice, document metadata or any other non-actionable statement in booking conditions, and never turn an
            omitted document-level fact into a condition. A notice that concerns only this booking goes in
            supplementalFacts with scope BOOKINGS [k]. Notices printed once for the whole document, document metadata
            and test or authenticity disclaimers belong to the SHARED task: omit them here.
            Return supplementalFacts only when their applicability is scope BOOKINGS with bookingIndexes exactly [k]
            (for example this booking's participant counts, policies or notices). Do not return document-level facts
            or facts shared with other bookings. supplementalCoverageWarning describes this booking only.
            TASK: SHARED. Return an empty bookings array. Return supplementalFacts only for facts about the document
            itself or applying to more than one booking, or whose applicability is uncertain: document-level notices,
            document metadata, and test or authenticity disclaimers (scope DOCUMENT or ALL_BOOKINGS, or BOOKINGS with
            several indexes). Do not return facts that belong to a single booking. supplementalCoverageWarning
            describes these shared facts only.
            UNLISTED BOOKING. If the document documents any booking that is missing from the list, including a cancelled,
            superseded, replaced or provisional one, begin supplementalCoverageWarning with exactly "UNLISTED BOOKING:"
            followed by a short description of it. Do this in every task. Never add it for unbooked ideas or examples.
            SUPPLEMENTAL BUDGET. The supplementalFacts count and serialized-character limits stated above apply to the whole
            document and are divided among all calls. The task line gives your share, which replaces those limits for your
            reply. Keep the most important facts, shorten quotations, and if you omit facts to fit, say so in
            supplementalCoverageWarning.
            If the booking named by the task cannot be found, return an empty bookings array and explain in
            supplementalCoverageWarning; never substitute a different booking.
            """;

    /** The standard extraction instructions followed by the rules for answering about only part of the document. */
    static final String INSTRUCTIONS = TourReadingSchema.INSTRUCTIONS + PARTIAL_MODE;
}
