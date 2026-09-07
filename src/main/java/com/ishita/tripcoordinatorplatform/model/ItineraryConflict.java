package com.ishita.tripcoordinatorplatform.model;

import jakarta.persistence.*;

@Entity
@Table(name = "itinerary_conflicts")
public class ItineraryConflict {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ItineraryConflictType type;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ItineraryConflictStatus status;

    @ManyToOne
    @JoinColumn(name = "first_item_id", nullable = false)
    private ItineraryItem firstItem;

    @ManyToOne
    @JoinColumn(name = "second_item_id", nullable = false)
    private ItineraryItem secondItem;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public ItineraryConflictType getType() {
        return type;
    }

    public void setType(ItineraryConflictType type) {
        this.type = type;
    }

    public ItineraryConflictStatus getStatus() {
        return status;
    }

    public void setStatus(ItineraryConflictStatus status) {
        this.status = status;
    }

    public ItineraryItem getFirstItem() {
        return firstItem;
    }

    public void setFirstItem(ItineraryItem firstItem) {
        this.firstItem = firstItem;
    }

    public ItineraryItem getSecondItem() {
        return secondItem;
    }

    public void setSecondItem(ItineraryItem secondItem) {
        this.secondItem = secondItem;
    }

    public TripMember getTripMember() {
        return tripMember;
    }

    public void setTripMember(TripMember tripMember) {
        this.tripMember = tripMember;
    }

    @ManyToOne
    @JoinColumn(name = "trip_member_id", nullable = false)
    private TripMember tripMember;

    public ItineraryConflict(){

    }

}
