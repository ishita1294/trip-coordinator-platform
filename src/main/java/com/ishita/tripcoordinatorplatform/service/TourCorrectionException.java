package com.ishita.tripcoordinatorplatform.service;

import org.springframework.http.HttpStatus;

/** Expected lifecycle failure only. Unrelated IllegalArgument/IllegalState faults are not HTTP-mapped. */
public final class TourCorrectionException extends IllegalArgumentException {
    private final HttpStatus status;
    private final String code;
    public TourCorrectionException(HttpStatus status,String code,String message){super(message);this.status=status;this.code=code;}
    public HttpStatus status(){return status;}
    public String code(){return code;}
    public static TourCorrectionException invalid(String message){return new TourCorrectionException(HttpStatus.BAD_REQUEST,"INVALID_CORRECTION_REQUEST",message);}
    public static TourCorrectionException validation(String code,String message){return new TourCorrectionException(HttpStatus.UNPROCESSABLE_ENTITY,code,message);}
    public static TourCorrectionException stale(String message){return new TourCorrectionException(HttpStatus.CONFLICT,"CORRECTION_REVIEW_STALE",message);}
}
