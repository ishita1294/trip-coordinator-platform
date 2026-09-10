package com.ishita.tripcoordinatorplatform.model;


import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.LocalDateTime;

import jakarta.validation.constraints.AssertTrue;

@Entity
@Table(name = "itinerary_items")
public class ItineraryItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    public ItineraryItem() {
    }

    public Long getId() {
        return id;
    }

    @ManyToOne
    @JoinColumn(name = "itinerary_id", nullable = false)
    private Itinerary itinerary;
    public Itinerary getItinerary() {
        return itinerary;
    }
    public void setItinerary(Itinerary itinerary) {
        this.itinerary = itinerary;
    }

    @NotNull(message = "Start time is required")
    @Column(nullable = false)
    private LocalDateTime startDateTime;


    public LocalDateTime getStartDateTime() {
        return startDateTime;
    }

    public void setStartDateTime(LocalDateTime startDateTime) {
        this.startDateTime = startDateTime;
    }

    @NotNull(message = "End time is required")
    @Column(nullable = false)
    private LocalDateTime endDateTime;

    public void setId(Long id) {
        this.id = id;
    }

    public LocalDateTime getEndDateTime() {
        return endDateTime;
    }
    public void setEndDateTime(LocalDateTime endDateTime) {
        this.endDateTime = endDateTime;


    }

    @ManyToOne
    @JoinColumn(name = "activity_id")
    private Activity activity;

    public Activity getActivity() {
        return activity;
    }
    public void setActivity(Activity activity) {
        this.activity = activity;
    }

    @ManyToOne
    @JoinColumn(name = "reservation_id")
    private Reservation reservation;

    public Reservation getReservation() {
        return reservation;
    }
    public void setReservation(Reservation reservation) {
        this.reservation = reservation;
    }

    @JsonIgnore
    @AssertTrue(message = "Itinerary item cannot reference both an activity and a reservation")
    public boolean isSourceValid() {
        return activity == null || reservation == null;
    }

    @NotBlank(message = "Title is required")
    @Column(nullable = false)
    private String title;

    public String getTitle() {
        return title;
    }
    public void setTitle(String title) {
        this.title = title;
    }

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ItineraryItemFlexibility flexibility = ItineraryItemFlexibility.FLEXIBLE;

    public ItineraryItemFlexibility getFlexibility() {
        return flexibility;
    }
    public void setFlexibility(ItineraryItemFlexibility flexibility) {
        this.flexibility = flexibility;
    }
}
