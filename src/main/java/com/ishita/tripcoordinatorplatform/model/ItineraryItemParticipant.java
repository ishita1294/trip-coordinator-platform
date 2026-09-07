package com.ishita.tripcoordinatorplatform.model;

import jakarta.persistence.*;

@Entity
@Table(
        name = "itinerary_item_participants",
        uniqueConstraints = {
                @UniqueConstraint(columnNames = {"itinerary_item_id", "trip_member_id"})
        }
)
public class ItineraryItemParticipant {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne
    @JoinColumn(name = "itinerary_item_id", nullable = false)
    private ItineraryItem itineraryItem;

    @ManyToOne
    @JoinColumn(name = "trip_member_id", nullable = false)
    private TripMember tripMember;

    public ItineraryItemParticipant() {

    }


    public Long getId() {
        return id;
    }
    public void setId(Long id) {
        this.id = id;
    }
    public ItineraryItem getItineraryItem() {
        return itineraryItem;
    }
    public void setItineraryItem(ItineraryItem itineraryItem) {
        this.itineraryItem = itineraryItem;
    }
    public TripMember getTripMember() {
        return tripMember;
    }
    public void setTripMember(TripMember tripMember) {
        this.tripMember = tripMember;
    }
}
