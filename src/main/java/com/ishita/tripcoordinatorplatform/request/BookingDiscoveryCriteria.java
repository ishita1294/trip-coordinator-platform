package com.ishita.tripcoordinatorplatform.request;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.ishita.tripcoordinatorplatform.model.ReservationType;
import java.time.LocalDate;

/** FILTERED requires search criteria or booking context; TRIP_WIDE explicitly lists the trip without filters. Pages are zero-based; omitted page/pageSize default to 0/25. No natural-language interpretation is performed. */
public record BookingDiscoveryCriteria(Long reservationId, String bookingReference, String name, String provider,
        ReservationType type, LocalDate date, LocalDate fromDate, LocalDate toDate, Long contextReservationId,
        Integer page, Integer pageSize, Mode mode) {
    public enum Mode { FILTERED, TRIP_WIDE }
    public static final int DEFAULT_PAGE_SIZE=25;
    public BookingDiscoveryCriteria {
        mode=mode==null?Mode.FILTERED:mode;
        page=page==null?0:page;
        pageSize=pageSize==null?DEFAULT_PAGE_SIZE:pageSize;
    }
    public BookingDiscoveryCriteria(Long reservationId,String bookingReference,String name,String provider,
            ReservationType type,LocalDate date,LocalDate fromDate,LocalDate toDate,Long contextReservationId,
            Integer page,Integer pageSize){
        this(reservationId,bookingReference,name,provider,type,date,fromDate,toDate,contextReservationId,page,pageSize,null);
    }
    public BookingDiscoveryCriteria(Long reservationId,String bookingReference,String name,String provider,
            ReservationType type,LocalDate date,LocalDate fromDate,LocalDate toDate,Long contextReservationId){
        this(reservationId,bookingReference,name,provider,type,date,fromDate,toDate,contextReservationId,null,null);
    }
    @JsonAnySetter
    public void rejectUnknownField(String field,Object value) {
        throw new IllegalArgumentException("Unsupported booking search field: "+field);
    }
}
