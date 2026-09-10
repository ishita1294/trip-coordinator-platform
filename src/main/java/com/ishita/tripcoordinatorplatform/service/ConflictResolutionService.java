package com.ishita.tripcoordinatorplatform.service;


import com.ishita.tripcoordinatorplatform.ai.*;
import com.ishita.tripcoordinatorplatform.model.ItineraryConflictStatus;
import com.ishita.tripcoordinatorplatform.model.ItineraryItemFlexibility;
import com.ishita.tripcoordinatorplatform.request.ApplyConflictResolutionRequest;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import com.ishita.tripcoordinatorplatform.model.ItineraryConflict;
import com.ishita.tripcoordinatorplatform.model.ItineraryItem;
import com.ishita.tripcoordinatorplatform.repository.ItineraryConflictRepository;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDateTime;
import java.util.Objects;


@Service
public class ConflictResolutionService {

    private final ItineraryConflictRepository conflictRepository;
    private final ConflictResolutionPromptBuilder promptBuilder;
    private final ObjectMapper objectMapper;
    private final ItineraryConflictService itineraryConflictService;
    private final ConflictResolutionResolver conflictResolutionResolver;
    private final ItineraryItemService itineraryItemService;

    public ConflictResolutionService(
            ItineraryConflictRepository conflictRepository,
            ConflictResolutionPromptBuilder promptBuilder,
            ObjectMapper objectMapper,
            ItineraryConflictService itineraryConflictService,
            ConflictResolutionResolver conflictResolutionResolver,
            ItineraryItemService itineraryItemService

    ) {
        this.conflictRepository = conflictRepository;
        this.promptBuilder = promptBuilder;
        this.objectMapper = objectMapper;
        this.itineraryConflictService = itineraryConflictService;
        this.conflictResolutionResolver = conflictResolutionResolver;
        this.itineraryItemService = itineraryItemService;
    }


    /**
     * Builds the verified conflict data that will later be provided
     * to the AI resolver.
     */
    public ConflictResolutionContext buildContext(Long conflictId) {

        ItineraryConflict conflict = conflictRepository.findById(conflictId)
                .orElseThrow(() ->
                        new IllegalArgumentException("Itinerary conflict not found"));

        ConflictResolutionContext context = new ConflictResolutionContext();

        context.setConflictId(conflict.getId());
        context.setConflictType(conflict.getType());
        context.setMemberName(conflict.getTripMember().getUser().getName());

        context.setFirstItem(toItemContext(conflict.getFirstItem()));

        context.setSecondItem(toItemContext(conflict.getSecondItem()));

        return context;
    }


    /**
     * Converts a database itinerary item into only the information
     * needed by the AI resolver.
     */
    private ConflictResolutionItemContext toItemContext(ItineraryItem item) {

        ConflictResolutionItemContext itemContext = new ConflictResolutionItemContext();

        itemContext.setItemId(item.getId());
        itemContext.setTitle(item.getTitle());
        itemContext.setStartDateTime(item.getStartDateTime());
        itemContext.setEndDateTime(item.getEndDateTime());
        itemContext.setFlexibility(item.getFlexibility());

        return itemContext;
    }

    /**
     * Builds the complete AI prompt for a stored itinerary conflict.
     */
    public String buildPrompt(Long conflictId) {

        ConflictResolutionContext context = buildContext(conflictId);

        return promptBuilder.buildPrompt(context);
    }


    /**
     * Converts the AI's JSON response into a structured Java response.
     */
    public ConflictResolutionResponse parseResponse(String responseJson) {
        try {
            return objectMapper.readValue(
                    responseJson,
                    ConflictResolutionResponse.class
            );
        } catch (JacksonException e) {
            throw new IllegalArgumentException(
                    "Invalid conflict resolution response from AI",
                    e
            );
        }
    }

    /**
     * Validates that AI suggestions only modify items
     * involved in the specified conflict.
     */
    public void validateResponse(
            Long tripId,
            Long conflictId,
            ConflictResolutionResponse response
    ) {
        ConflictResolutionContext context = buildContext(conflictId);

        Long firstItemId = context.getFirstItem().getItemId();
        Long secondItemId = context.getSecondItem().getItemId();

        if (response.getOptions() == null
                || response.getOptions().isEmpty()) {
            throw new IllegalArgumentException(
                    "AI response must contain at least one resolution option"
            );
        }

        for (ConflictResolutionOption option : response.getOptions()) {

            boolean belongsToConflict =
                    Objects.equals(option.getItemId(), firstItemId)
                            || Objects.equals(option.getItemId(), secondItemId);

            if (!belongsToConflict) {
                throw new IllegalArgumentException(
                        "AI suggested an item that does not belong to this conflict"
                );
            }

            ConflictResolutionItemContext itemContext =
                    Objects.equals(option.getItemId(), firstItemId)
                            ? context.getFirstItem()
                            : context.getSecondItem();

            if (isFixed(itemContext.getFlexibility())) {
                throw new IllegalArgumentException(
                        "AI cannot move a FIXED itinerary item"
                );
            }

            ConflictResolutionItemContext otherItemContext =
                    Objects.equals(option.getItemId(), firstItemId)
                            ? context.getSecondItem()
                            : context.getFirstItem();

            validateProposedTimes(
                    option.getProposedStartDateTime(),
                    option.getProposedEndDateTime()
            );

            boolean stillOverlaps =
                    option.getProposedStartDateTime()
                            .isBefore(otherItemContext.getEndDateTime())
                            && option.getProposedEndDateTime()
                            .isAfter(otherItemContext.getStartDateTime());

            if (stillOverlaps) {
                throw new IllegalArgumentException(
                        "AI suggestion does not resolve the existing conflict"
                );
            }

            boolean createsAnotherConflict =
                    itineraryConflictService.wouldCreateParticipantConflict(
                            tripId,
                            option.getItemId(),
                            option.getProposedStartDateTime(),
                            option.getProposedEndDateTime()
                    );

            if (createsAnotherConflict) {
                throw new IllegalArgumentException(
                        "AI suggestion would create another participant conflict"
                );
            }


        }
    }

    /**
     * Parses an AI response and validates its proposed itinerary changes
     * against application rules before it can be used.
     */
    public ConflictResolutionResponse processAiResponse(
            Long tripId,
            Long conflictId,
            String responseJson
    ) {
        ConflictResolutionResponse response = parseResponse(responseJson);

        validateResponse(
                tripId,
                conflictId,
                response
        );

        return response;
    }

    /**
     * Generates and validates temporary mock AI suggestions
     * for an itinerary conflict.
     */
    public ConflictResolutionResponse suggestResolutions(
            Long tripId,
            Long tripMemberId,
            Long conflictId
    ) {
        validateConflictScope(tripId, tripMemberId, conflictId);
        String prompt = buildPrompt(conflictId);

        String responseJson = conflictResolutionResolver.resolve(prompt);

        return processAiResponse(
                tripId,
                conflictId,
                responseJson
        );
    }

    @Transactional
    public ItineraryItem applyConflictResolution(
            Long tripId,
            Long tripMemberId,
            Long conflictId,
            ApplyConflictResolutionRequest request
    ) {

        ItineraryConflict conflict =
                validateConflictScope(
                        tripId,
                        tripMemberId,
                        conflictId
                );

        if (conflict.getStatus() != ItineraryConflictStatus.OPEN) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Itinerary conflict is no longer open"
            );
        }


        ItineraryItem itemToMove =
                getConflictItem(
                        conflict,
                        request.getItineraryItemId()
                );

        if (isFixed(itemToMove.getFlexibility())) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "FIXED itinerary item cannot be moved"
            );
        }

        try {
            validateProposedTimes(
                    request.getProposedStartDateTime(),
                    request.getProposedEndDateTime()
            );
        } catch (IllegalArgumentException exception) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    exception.getMessage(),
                    exception
            );
        }

        itineraryItemService.validateWithinTripDates(
                itemToMove.getItinerary().getTrip(),
                request.getProposedStartDateTime(),
                request.getProposedEndDateTime()
        );

        ItineraryItem otherConflictItem =
                getOtherConflictItem(
                        conflict,
                        itemToMove.getId()
                );

        boolean stillOverlaps =
                itineraryConflictService.overlaps(
                        request.getProposedStartDateTime(),
                        request.getProposedEndDateTime(),
                        otherConflictItem.getStartDateTime(),
                        otherConflictItem.getEndDateTime()
                );

        if (stillOverlaps) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Proposed time does not resolve the existing conflict"
            );
        }

        boolean createsAnotherConflict =
                itineraryConflictService.wouldCreateParticipantConflict(
                        tripId,
                        itemToMove.getId(),
                        request.getProposedStartDateTime(),
                        request.getProposedEndDateTime()
                );

        if (createsAnotherConflict) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Proposed time would create another participant conflict"
            );
        }

        return itineraryItemService.updateItineraryItemTimes(
                tripId,
                itemToMove,
                request.getProposedStartDateTime(),
                request.getProposedEndDateTime()
        );
    }

    private ItineraryConflict validateConflictScope(
            Long tripId,
            Long tripMemberId,
            Long conflictId
    ) {
        ItineraryConflict conflict = conflictRepository.findById(conflictId)
                .orElseThrow(() ->
                        new ResponseStatusException(
                                HttpStatus.NOT_FOUND,
                                "Itinerary conflict not found"
                        )
                );

        Long conflictTripId =
                conflict.getFirstItem()
                        .getItinerary()
                        .getTrip()
                        .getId();

        Long conflictTripMemberId =
                conflict.getTripMember().getId();

        if (!conflictTripId.equals(tripId)
                || !conflictTripMemberId.equals(tripMemberId)) {

            throw new ResponseStatusException(
                    HttpStatus.NOT_FOUND,
                    "Itinerary conflict not found"
            );
        }

        return conflict;
    }


    private ItineraryItem getConflictItem(
            ItineraryConflict conflict,
            Long itineraryItemId
    ) {

        if (Objects.equals(conflict.getFirstItem().getId(), itineraryItemId)) {
            return conflict.getFirstItem();
        }

        if (Objects.equals(conflict.getSecondItem().getId(), itineraryItemId)) {
            return conflict.getSecondItem();
        }

        throw new ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "Itinerary item does not belong to this conflict"
        );
    }

    private boolean isFixed(ItineraryItemFlexibility flexibility) {
        return flexibility == ItineraryItemFlexibility.FIXED;
    }

    private void validateProposedTimes(
            LocalDateTime proposedStartDateTime,
            LocalDateTime proposedEndDateTime
    ) {

        if (proposedStartDateTime == null
                || proposedEndDateTime == null) {
            throw new IllegalArgumentException(
                    "Proposed start and end time are required"
            );
        }

        if (!proposedEndDateTime.isAfter(proposedStartDateTime)) {
            throw new IllegalArgumentException(
                    "Proposed end time must be after start time"
            );
        }
    }

    private ItineraryItem getOtherConflictItem(
            ItineraryConflict conflict,
            Long itineraryItemId
    ) {

        if (Objects.equals(
                conflict.getFirstItem().getId(),
                itineraryItemId
        )) {
            return conflict.getSecondItem();
        }

        return conflict.getFirstItem();
    }

}
