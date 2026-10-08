package com.ishita.tripcoordinatorplatform.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;

import java.math.BigDecimal;

@Embeddable
public class Location {

    @Column(name = "location_name")
    private String name;

    @Column(name = "location_address")
    private String address;

    @DecimalMin("-90")
    @DecimalMax("90")
    @Column(name = "location_latitude", precision = 10, scale = 7)
    private BigDecimal latitude;

    @DecimalMin("-180")
    @DecimalMax("180")
    @Column(name = "location_longitude", precision = 10, scale = 7)
    private BigDecimal longitude;

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getAddress() {
        return address;
    }

    public void setAddress(String address) {
        this.address = address;
    }

    public BigDecimal getLatitude() {
        return latitude;
    }

    public void setLatitude(BigDecimal latitude) {
        this.latitude = latitude;
    }

    public BigDecimal getLongitude() {
        return longitude;
    }

    public void setLongitude(BigDecimal longitude) {
        this.longitude = longitude;
    }

    @JsonIgnore
    @AssertTrue(message = "Latitude and longitude must be supplied together")
    public boolean isCoordinatePairValid() {
        return (latitude == null) == (longitude == null);
    }
}
