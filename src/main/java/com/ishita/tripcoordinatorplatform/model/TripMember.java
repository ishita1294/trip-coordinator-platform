package com.ishita.tripcoordinatorplatform.model;


import jakarta.persistence.*;

@Entity
@Table(
        name = "trip_members",
        uniqueConstraints = {
                @UniqueConstraint(columnNames = {"trip_id", "user_id"})
        }
)
public class TripMember {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private TripMemberRole role;

    @ManyToOne
    @JoinColumn(name = "trip_id", nullable = false)
    private Trip trip;

    @ManyToOne
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    public void setId(Long id) {
        this.id = id;
    }

    public TripMember() {
    }

    public Long getId() {
        return id;
    }

    public Trip getTrip() {
        return trip;
    }

    public void setTrip(Trip trip) {
        this.trip = trip;
    }

    public User getUser() {
        return user;
    }

    public void setUser(User user) {
        this.user = user;
    }

    public TripMemberRole getRole() {
        return role;
    }

    public void setRole(TripMemberRole role) {
        this.role = role;
    }
}
