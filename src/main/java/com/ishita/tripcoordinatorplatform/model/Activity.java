package com.ishita.tripcoordinatorplatform.model;

import jakarta.persistence.*;
import jakarta.validation.constraints.NotBlank;

import java.math.BigDecimal;

@Entity
@Table(name = "activities")
public class Activity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    public Activity() {
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


    @NotBlank(message = "Activity name is required")
    @Column(nullable = false)
    private String name;

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    private String description;


    public String getDescription() {
        return description;
    }
    public void setDescription(String description) {
        this.description = description;
    }

    @ManyToOne
    @JoinColumn(name = "destination_id", nullable = false)
    private Destination destination;

    public Destination getDestination() {
        return destination;
    }
    public void setDestination(Destination destination) {
        this.destination = destination;
    }

    @Enumerated(EnumType.STRING)
    private ActivityCategory category;

    public ActivityCategory getCategory() {
        return category;
    }
    public void setCategory(ActivityCategory category) {
        this.category = category;
    }

    private Integer estimatedDurationMinutes;

    public Integer getEstimatedDurationMinutes() {
        return estimatedDurationMinutes;
    }
    public void setEstimatedDurationMinutes(Integer estimatedDurationMinutes) {
        this.estimatedDurationMinutes = estimatedDurationMinutes;
    }

    private BigDecimal estimatedCost;

    public BigDecimal getEstimatedCost() {
        return estimatedCost;
    }
    public void setEstimatedCost(BigDecimal estimatedCost) {
        this.estimatedCost = estimatedCost;
    }
    @Enumerated(EnumType.STRING)
    private ActivityStatus status;

    public ActivityStatus getStatus() {
        return status;
    }
    public void setStatus(ActivityStatus status) {
        this.status = status;
    }
}
