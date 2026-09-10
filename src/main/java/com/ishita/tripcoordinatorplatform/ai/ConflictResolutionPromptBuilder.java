package com.ishita.tripcoordinatorplatform.ai;

import org.springframework.stereotype.Component;

/**
 * Builds the prompt sent to the AI using verified conflict data
 * from the application.
 */
@Component
public class ConflictResolutionPromptBuilder {

    /**
     * Converts conflict context into instructions and data
     * that the AI can use to suggest resolution options.
     */
    public String buildPrompt(ConflictResolutionContext context) {

        StringBuilder prompt = new StringBuilder();

        prompt.append("""
                You are an AI travel itinerary conflict resolver.

                Suggest up to 3 practical ways to resolve the conflict.

                Rules:
                - Use only the facts provided.
                - Do not invent external availability or other facts.
                - FLEXIBLE items may be moved.
                - TIME_CONSTRAINED items may only be moved within known constraints.
                - FIXED items must not be moved unless verified alternatives are provided.
                - If an option requires information that is not known, mark it as requiring verification.
                - Prefer preserving existing itinerary items.

                """);

        prompt.append("Conflict type: ")
                .append(context.getConflictType())
                .append("\n");

        prompt.append("Affected member: ")
                .append(context.getMemberName())
                .append("\n\n");

        appendItem(prompt, "Item 1", context.getFirstItem());
        appendItem(prompt, "Item 2", context.getSecondItem());

        prompt.append("""
                
                Return only JSON in this format:
                {
                  "options": [
                    {
                      "itemId": 1,
                      "proposedStartDateTime": "2026-12-10T08:00:00",
                      "proposedEndDateTime": "2026-12-10T09:00:00",
                      "reason": "...",
                      "verificationRequired": false,
                      "verificationReason": null
                    }
                  ]
                }
                """);

        return prompt.toString();
    }

    /**
     * Adds one itinerary item's verified information to the prompt.
     */
    private void appendItem(
            StringBuilder prompt,
            String label,
            ConflictResolutionItemContext item
    ) {

        prompt.append(label).append(":\n");
        prompt.append("ID: ").append(item.getItemId()).append("\n");
        prompt.append("Title: ").append(item.getTitle()).append("\n");
        prompt.append("Start: ").append(item.getStartDateTime()).append("\n");
        prompt.append("End: ").append(item.getEndDateTime()).append("\n");
        prompt.append("Flexibility: ").append(item.getFlexibility()).append("\n\n");
    }

}
