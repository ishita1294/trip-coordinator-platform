package com.ishita.tripcoordinatorplatform.model;

import jakarta.persistence.*;

@Entity
@Table(name = "itineraries")
public class Itinerary {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    public Itinerary() {
    }

    public Long getId() {
        return id;
    }

    @OneToOne
    @JoinColumn(name = "trip_id", nullable = false, unique = true)
    private Trip trip;

    public Trip getTrip() {
        return trip;
    }

    public void setTrip(Trip trip) {
        this.trip = trip;
    }
}
