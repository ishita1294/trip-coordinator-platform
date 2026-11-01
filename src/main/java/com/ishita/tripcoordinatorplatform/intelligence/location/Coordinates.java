package com.ishita.tripcoordinatorplatform.intelligence.location;

public record Coordinates(double latitude, double longitude) {
    public Coordinates {
        if (!Double.isFinite(latitude) || !Double.isFinite(longitude)
                || latitude < -90 || latitude > 90 || longitude < -180 || longitude > 180) {
            throw new IllegalArgumentException("Valid latitude and longitude are required");
        }
    }
    public double distanceMetersTo(Coordinates other) {
        double lat = Math.toRadians(other.latitude - latitude), lon = Math.toRadians(other.longitude - longitude);
        double a = Math.pow(Math.sin(lat / 2), 2) + Math.cos(Math.toRadians(latitude)) * Math.cos(Math.toRadians(other.latitude)) * Math.pow(Math.sin(lon / 2), 2);
        return 6371000 * 2 * Math.asin(Math.sqrt(Math.min(1, a)));
    }
}

