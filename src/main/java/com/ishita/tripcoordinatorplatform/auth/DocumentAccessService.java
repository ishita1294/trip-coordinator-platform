package com.ishita.tripcoordinatorplatform.auth;

import com.ishita.tripcoordinatorplatform.repository.TravelDocumentRepository;
import com.ishita.tripcoordinatorplatform.response.TravelDocumentSummaryResponse;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import java.util.List;

/** Limits source documents to their uploader, who must also have the required trip role. Documents without a recorded uploader are not exposed; confirmed trip records remain shared. */
@Service
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class DocumentAccessService {
    private final CurrentUser current;
    private final TripAccessService trips;
    private final TravelDocumentRepository documents;
    public DocumentAccessService(CurrentUser current, TripAccessService trips, TravelDocumentRepository documents) {
        this.current=current; this.trips=trips; this.documents=documents;
    }
    public Long upload(Long tripId) {
        Long actor=current.require().getId(); trips.requireEditor(tripId,actor); return actor;
    }
    public void require(Long tripId, Long documentId, boolean write) {
        Long actor=current.require().getId();
        if(write) trips.requireEditor(tripId,actor); else trips.requireMember(tripId,actor);
        if(!documents.existsByIdAndTrip_IdAndUploadedByUserId(documentId,tripId,actor))
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Document not found");
    }
    public List<TravelDocumentSummaryResponse> list(Long tripId) {
        Long actor=current.require().getId(); trips.requireMember(tripId,actor);
        return documents.findAllByTrip_IdAndUploadedByUserIdOrderByUploadedAtDescIdDesc(tripId,actor)
                .stream().map(TravelDocumentSummaryResponse::from).toList();
    }
}
