package com.ishita.tripcoordinatorplatform.controller;

import com.ishita.tripcoordinatorplatform.model.DocumentProcessingOutbox;
import com.ishita.tripcoordinatorplatform.model.TravelDocument;
import com.ishita.tripcoordinatorplatform.model.TravelDocumentProcessingStatus;
import com.ishita.tripcoordinatorplatform.repository.DocumentProcessingOutboxRepository;
import com.ishita.tripcoordinatorplatform.repository.TravelDocumentRepository;
import com.ishita.tripcoordinatorplatform.service.DocumentProcessingQueueService;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.Optional;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class TravelDocumentProcessingControllerTest {

    @Test
    void acceptsProcessRequestByQueueingWithoutRunningStorageOrAiPipeline() throws Exception {
        var documents = mock(TravelDocumentRepository.class);
        var outbox = mock(DocumentProcessingOutboxRepository.class);
        TravelDocument document = new TravelDocument();
        document.setId(10L);
        document.setProcessingStatus(TravelDocumentProcessingStatus.QUEUED);
        when(documents.claimForQueue(eq(10L), eq(1L), eq(TravelDocumentProcessingStatus.QUEUED), anyList()))
                .thenReturn(1, 0);
        when(documents.findByIdAndTrip_Id(10L, 1L)).thenReturn(Optional.of(document));
        var mvc = MockMvcBuilders.standaloneSetup(new TravelDocumentProcessingController(
                new DocumentProcessingQueueService(documents, outbox))).build();

        mvc.perform(post("/trips/1/documents/10/process"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.documentId").value(10))
                .andExpect(jsonPath("$.processingStatus").value("QUEUED"));

        mvc.perform(post("/trips/1/documents/10/process"))
                .andExpect(status().isConflict())
                .andExpect(status().reason("Travel document cannot be queued in its current status"));

        verify(outbox).save(any(DocumentProcessingOutbox.class));
        verify(documents, times(2)).claimForQueue(eq(10L), eq(1L), eq(TravelDocumentProcessingStatus.QUEUED), anyList());
        verify(documents, times(2)).findByIdAndTrip_Id(10L, 1L);
        verifyNoMoreInteractions(documents, outbox);
    }
}
