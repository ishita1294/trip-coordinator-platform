package com.ishita.tripcoordinatorplatform.intelligence.places;

import com.ishita.tripcoordinatorplatform.intelligence.location.Coordinates;
import java.util.List;

// Request-scoped provider facts; never persisted to the trip domain.
public record PlaceDestination(String id, String name, String address, Coordinates coordinates,
        String timeZone, String mapsUri, List<PlaceCandidate.Attribution> attributions) {}
