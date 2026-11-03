package com.ishita.tripcoordinatorplatform.response;

import com.ishita.tripcoordinatorplatform.model.TravelDocumentType;
import tools.jackson.databind.JsonNode;

import java.time.Instant;

public record TravelDocumentExtractionResponse(Long extractionId,
                                               Long documentId,
                                               TravelDocumentType documentType,
                                               Instant createdAt,
                                               JsonNode extractedData, java.util.List<com.ishita.tripcoordinatorplatform.intelligence.commitment.ConfirmedArrivalEvidence.Review> arrivalReview,boolean endingLocationReviewSupported,
                                               SemanticTourReview semanticReview,
                                               @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL) com.ishita.tripcoordinatorplatform.ai.document.SourceBookingReview.Review sourceBookingReview,
                                               @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL) com.ishita.tripcoordinatorplatform.ai.document.StoredSourceBooking.Provenance sourceBookingProvenance) {
    public TravelDocumentExtractionResponse {
        if(com.ishita.tripcoordinatorplatform.ai.document.StoredSourceBooking.isNativeVersion(extractedData)) {
            if(sourceBookingReview==null||sourceBookingProvenance==null||semanticReview!=null||sourceBookingReview.schemaVersion()!=4
                    ||sourceBookingReview.confirmationSupported()||!com.ishita.tripcoordinatorplatform.ai.document.StoredSourceBooking.isStored(extractedData))
                throw new IllegalArgumentException("Native read-only review identity mismatch");
            arrivalReview=java.util.List.of();endingLocationReviewSupported=false;
        } else if ((documentType==TravelDocumentType.TOUR_BOOKING || documentType==TravelDocumentType.ATTRACTION_TICKET)
                && extractedData!=null && com.ishita.tripcoordinatorplatform.ai.document.TourExtractionCodec.schemaVersion(extractedData)>=2) {
            arrivalReview=java.util.List.of(); endingLocationReviewSupported=false;
            if (semanticReview!=null && (!java.util.Objects.equals(documentId,semanticReview.documentId())
                    || !java.util.Objects.equals(extractionId,semanticReview.extractionId())
                    || semanticReview.schemaVersion()!=com.ishita.tripcoordinatorplatform.ai.document.TourExtractionCodec.schemaVersion(extractedData)))
                throw new IllegalArgumentException("Semantic review identity mismatch");
        }
    }
    public TravelDocumentExtractionResponse(Long extractionId,Long documentId,TravelDocumentType type,Instant createdAt,JsonNode data,java.util.List<com.ishita.tripcoordinatorplatform.intelligence.commitment.ConfirmedArrivalEvidence.Review> arrivals,boolean endingSupported,SemanticTourReview semantic){this(extractionId,documentId,type,createdAt,data,arrivals,endingSupported,semantic,null,null);}
    public TravelDocumentExtractionResponse(Long extractionId,Long documentId,TravelDocumentType type,Instant createdAt,JsonNode data,java.util.List<com.ishita.tripcoordinatorplatform.intelligence.commitment.ConfirmedArrivalEvidence.Review> arrivals,boolean endingSupported){this(extractionId,documentId,type,createdAt,data,arrivals,endingSupported,null);}
    public TravelDocumentExtractionResponse(Long extractionId,Long documentId,TravelDocumentType type,Instant createdAt,JsonNode data,java.util.List<com.ishita.tripcoordinatorplatform.intelligence.commitment.ConfirmedArrivalEvidence.Review> arrivals){this(extractionId,documentId,type,createdAt,data,arrivals,true);}
    public TravelDocumentExtractionResponse(Long extractionId,Long documentId,TravelDocumentType type,Instant createdAt,JsonNode extractedData){this(extractionId,documentId,type,createdAt,extractedData,java.util.List.of());}
    public record EndingLocationReview(int bookingIndex,String choice) {}
    public record ArrivalCandidate(int bookingIndex,java.time.LocalDateTime requiredArrivalLocal,String timeZone,String basis) {}
    /** Legacy structured arithmetic only; does not resolve documentary contradictions or parse instructions. */
    @com.fasterxml.jackson.annotation.JsonProperty("arrivalCandidates")
    public java.util.List<ArrivalCandidate> arrivalCandidates(){
        if(extractionSchemaVersion()==null || extractionSchemaVersion()!=1)return java.util.List.of();
        var result=new java.util.ArrayList<ArrivalCandidate>();
        var decoded=new com.ishita.tripcoordinatorplatform.ai.document.TourExtractionCodec(new tools.jackson.databind.json.JsonMapper()).decode(extractedData.toString());
        for(var review:arrivalReview){
            if(review.issue()==null || review.issue().isEmpty())continue;
            var b=decoded.legacy().bookings().get(review.bookingIndex());
            if(b.startDate()==null || b.startTime()==null || b.timezone()==null || b.arrivalLeadMinutes()==null || b.arrivalLeadMinutes()<0)continue;
            try{
                var zone=java.time.ZoneId.of(b.timezone());var start=b.startDate().atTime(b.startTime());
                var offsets=zone.getRules().getValidOffsets(start);if(offsets.size()!=1)continue;
                var candidate=java.time.LocalDateTime.ofInstant(start.toInstant(offsets.getFirst()).minusSeconds(b.arrivalLeadMinutes()*60L),zone);
                if(zone.getRules().getValidOffsets(candidate).size()!=1)continue;
                result.add(new ArrivalCandidate(review.bookingIndex(),candidate,b.timezone(),"STRUCTURED_START_MINUS_ARRIVAL_LEAD"));
            }catch(java.time.DateTimeException invalid){/* No trustworthy arithmetic candidate. */}
        }
        return java.util.List.copyOf(result);
    }
    @com.fasterxml.jackson.annotation.JsonProperty("extractionSchemaVersion")
    public Integer extractionSchemaVersion(){
        return documentType==TravelDocumentType.TOUR_BOOKING || documentType==TravelDocumentType.ATTRACTION_TICKET
                ? com.ishita.tripcoordinatorplatform.ai.document.StoredSourceBooking.isNativeVersion(extractedData)?4:com.ishita.tripcoordinatorplatform.ai.document.TourExtractionCodec.schemaVersion(extractedData) : null;
    }
    @com.fasterxml.jackson.annotation.JsonProperty("confirmationSupported")
    public boolean confirmationSupported(){return extractionSchemaVersion()==null || extractionSchemaVersion()==1 || extractionSchemaVersion()>=2 && semanticReview!=null && semanticReview.confirmationSupported();}
    /** Derived confirmation defaults are separate from the immutable extractedData payload. */
    @com.fasterxml.jackson.annotation.JsonProperty("endingLocationReview")
    public java.util.List<EndingLocationReview> endingLocationReview(){
        if((documentType!=TravelDocumentType.TOUR_BOOKING && documentType!=TravelDocumentType.ATTRACTION_TICKET) || extractedData==null || !extractedData.path("bookings").isArray() || !confirmationSupported())return java.util.List.of();
        var result=new java.util.ArrayList<EndingLocationReview>();int index=0;
        for(var booking:extractedData.path("bookings"))result.add(new EndingLocationReview(index++,com.ishita.tripcoordinatorplatform.service.TourConfirmationPolicy.initialEndpointChoice(booking)));
        return java.util.List.copyOf(result);
    }
}
