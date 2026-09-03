package com.ishita.tripcoordinatorplatform.model;

import jakarta.persistence.*;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.LocalDateTime;

@Entity
@Table(name = "reservations")
public class Reservation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    public Reservation() {
    }

    public Long getId() {
        return id;
    }

    @ManyToOne
    @JoinColumn(name = "trip_id", nullable = false)
    private Trip trip;

    public Trip getTrip() {
        return trip;
    }

    public void setTrip(Trip trip) {
        this.trip = trip;
    }

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ReservationType type;

    public ReservationType getType() {
        return type;
    }
    public void setType(ReservationType type) {
        this.type = type;
    }

    @NotBlank(message = "Reservation name is required")
    @Column(nullable = false)
    private String name;

    public String getName() {
        return name;
    }
    public void setName(String name) {
        this.name = name;
    }

    @NotNull(message = "Reservation start time is required")
    @Column(nullable = false)
    private LocalDateTime startDateTime;

    public LocalDateTime getStartDateTime() {
        return startDateTime;
    }
    public void setStartDateTime(LocalDateTime startDateTime) {
        this.startDateTime = startDateTime;
    }

    private LocalDateTime endDateTime;

    public LocalDateTime getEndDateTime() {
        return endDateTime;
    }
    public void setEndDateTime(LocalDateTime endDateTime) {
        this.endDateTime = endDateTime;
    }

    private String provider;

    public String getProvider() {
        return provider;
    }
    public void setProvider(String provider) {
        this.provider = provider;
    }

    private String confirmationNumber;

    public String getConfirmationNumber() {
        return confirmationNumber;
    }
    public void setConfirmationNumber(String confirmationNumber) {
        this.confirmationNumber = confirmationNumber;

    }

    private String location;

    public String getLocation() {
        return location;
    }
    public void setLocation(String location) {
        this.location = location;
    }

    @Enumerated(EnumType.STRING)
    private ReservationStatus status;
    public ReservationStatus getStatus() {
        return status;
    }
    public void setStatus(ReservationStatus status) {
        this.status = status;
    }
}