package com.ishita.tripcoordinatorplatform.model;

import jakarta.persistence.*;
import java.time.Instant;

/** User-reported booking fact, never an AI source assessment or cached route observation. */
@Entity
@Table(name="activity_endpoint_history",uniqueConstraints=@UniqueConstraint(columnNames={"reservation_id","endpoint_version"}))
@org.hibernate.annotations.Immutable
public class ActivityEndpointUpdate {
    @Id @GeneratedValue(strategy=GenerationType.IDENTITY) public Long id;
    @Column(name="reservation_id",nullable=false,updatable=false) public Long reservationId;
    @Column(name="trip_id",nullable=false,updatable=false) public Long tripId;
    @Column(name="actor_id",nullable=false,updatable=false) public Long actorId;
    @Column(name="extraction_id",updatable=false) public Long extractionId;
    @Column(name="reported_at",nullable=false,updatable=false) public Instant reportedAt;
    @Column(name="previous_version",nullable=false,updatable=false) public long previousVersion;
    @Column(name="endpoint_version",nullable=false,updatable=false) public long endpointVersion;
    @Column(name="previous_json",nullable=false,updatable=false,columnDefinition="text") public String previousJson;
    @Column(name="current_json",nullable=false,updatable=false,columnDefinition="text") public String currentJson;
    @Column(nullable=false,updatable=false) public String kind;
    @Column(nullable=false,updatable=false) public String choice;
    @Column(name="reported_name",updatable=false) public String reportedName;
    @Column(name="reported_address",length=1000,updatable=false) public String reportedAddress;
    public Long getId(){return id;}
}
