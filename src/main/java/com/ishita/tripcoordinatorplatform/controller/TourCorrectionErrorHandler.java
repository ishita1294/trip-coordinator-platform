package com.ishita.tripcoordinatorplatform.controller;

import com.ishita.tripcoordinatorplatform.service.TourCorrectionException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.http.*;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestControllerAdvice(assignableTypes={TourCorrectionController.class,TravelDocumentController.class})
@ConditionalOnWebApplication(type=ConditionalOnWebApplication.Type.SERVLET)
public class TourCorrectionErrorHandler {
    @ExceptionHandler(TourCorrectionException.class)
    public ResponseEntity<ProblemDetail> expected(TourCorrectionException failure){return error(failure.status(),failure.code(),failure.getMessage());}
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ProblemDetail> malformed(){return error(HttpStatus.BAD_REQUEST,"INVALID_CORRECTION_REQUEST","Supply a valid document-review request.");}
    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<ProblemDetail> scoped(ResponseStatusException failure){
        int status=failure.getStatusCode().value();
        String code=switch(status){case 401->"AUTHENTICATION_REQUIRED";case 403->"DOCUMENT_ACCESS_DENIED";case 404->"DOCUMENT_REVIEW_NOT_FOUND";case 409->"CORRECTION_REVIEW_STALE";default->"DOCUMENT_REVIEW_FAILED";};
        // Do not echo storage keys, source contents or implementation exception messages.
        String detail=switch(status){case 401->"Authentication is required.";case 403,404->"Document review is unavailable to this account.";case 409->"The document review changed. Reload before confirming.";default->"Document review could not be completed.";};
        return error(failure.getStatusCode(),code,detail);
    }
    private ResponseEntity<ProblemDetail> error(HttpStatusCode status,String code,String detail){
        var body=ProblemDetail.forStatusAndDetail(status,detail);body.setProperty("code",code);
        return ResponseEntity.status(status).body(body);
    }
}
