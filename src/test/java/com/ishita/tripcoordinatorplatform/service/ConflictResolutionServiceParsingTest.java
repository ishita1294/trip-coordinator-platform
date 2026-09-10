package com.ishita.tripcoordinatorplatform.service;

import com.ishita.tripcoordinatorplatform.ai.ConflictResolutionResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

@SpringBootTest
public class ConflictResolutionServiceParsingTest {


    @Autowired
    private ConflictResolutionService conflictResolutionService;

    @Test
    void parsesConflictResolutionResponse() {

        String responseJson = """
                {
                  "options": [
                    {
                      "itemId": 1,
                      "proposedStartDateTime": "2026-12-10T08:00:00",
                      "proposedEndDateTime": "2026-12-10T09:00:00",
                      "reason": "Move breakfast earlier.",
                      "verificationRequired": false,
                      "verificationReason": null
                    }
                  ]
                }
                """;

        ConflictResolutionResponse response =
                conflictResolutionService.parseResponse(responseJson);

        assertEquals(1, response.getOptions().size());
        assertEquals(1L, response.getOptions().get(0).getItemId());
        assertEquals(
                "Move breakfast earlier.",
                response.getOptions().get(0).getReason()
        );
        assertFalse(response.getOptions().get(0).isVerificationRequired());
    }
}
