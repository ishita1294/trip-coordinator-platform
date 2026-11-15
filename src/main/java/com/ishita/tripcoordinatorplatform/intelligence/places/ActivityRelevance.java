package com.ishita.tripcoordinatorplatform.intelligence.places;

import java.util.*;

/** Evidence for a visitor activity, independent of reputation, routing and schedule fit. */
public final class ActivityRelevance {
    private ActivityRelevance() {}
    private static final Set<String> ROAD = Set.of("intersection", "route", "street_address", "road", "roundabout", "traffic_circle");
    private static final Set<String> VISITOR = Set.of("museum", "art_gallery", "history_museum", "art_museum", "castle",
            "historical_place", "cultural_center", "observation_deck", "monument", "sculpture", "park", "garden", "botanical_garden",
            "shopping_mall", "market", "cafe", "coffee_shop", "restaurant", "movie_theater", "performing_arts_theater");
    private static final Set<String> WORSHIP = Set.of("hindu_temple", "buddhist_temple", "church", "mosque", "synagogue");

    public static boolean entertainmentVenue(PlaceCandidate place) {
        return Set.of("movie_theater", "performing_arts_theater", "bowling_alley", "amusement_center", "amusement_park",
                "video_arcade", "concert_hall", "comedy_club", "aquarium", "zoo").contains(Objects.toString(place.primaryType(), ""));
    }
    public static String rejection(PlaceCandidate place) {
        String primary = Objects.toString(place.primaryType(), "");
        // Explicit road evidence wins even when Google also supplies activity classifications.
        if (ROAD.contains(primary) || place.types().stream().anyMatch(ROAD::contains))
            return "ROAD_INFRASTRUCTURE_NOT_VISITOR_ACTIVITY";
        boolean roadName = place.name().toLowerCase(Locale.ROOT).matches(".*\\b(junction|intersection|roundabout|traffic circle|chauraha|chouraha|choraha|square|chowk|chauk)\\b.*");
        // Secondary types are not independent evidence: mixed landmark/park tags caused the bypass.
        boolean specificPrimary = VISITOR.contains(primary)
                && !Set.of("monument", "sculpture").contains(primary);
        // Existing provider-neutral source-backed experience facts can establish visitor value.
        // Google currently supplies no verified experience duration; never infer one from ratings/types.
        boolean verifiedExperience = place.verifiedDuration() != null
                && !place.verifiedDuration().isNegative() && !place.verifiedDuration().isZero()
                && place.durationSource() != null && !place.durationSource().isBlank();
        if (roadName && !specificPrimary && !verifiedExperience)
            return "LANDMARK_VISITOR_VALUE_UNVERIFIED";
        if (Set.of("plaza", "fountain", "historical_landmark", "cultural_landmark").contains(primary)
                && !verifiedExperience)
            return "GENERIC_LANDMARK_VISITOR_VALUE_UNVERIFIED";
        if (place.interests().isEmpty()) return "ACTIVITY_RELEVANCE_UNVERIFIED";
        return null;
    }
    public static String family(PlaceCandidate place) {
        if (WORSHIP.contains(Objects.toString(place.primaryType(),""))) return "SPIRITUAL_HERITAGE";
        return place.interests().stream().filter(p -> p != com.ishita.tripcoordinatorplatform.intelligence.explore.ExplorePreference.RELAXATION)
                .sorted().findFirst().map(Enum::name).orElse("UNVERIFIED");
    }
    public static String evidence(PlaceCandidate place) {
        if (rejection(place) != null) return "Visitor activity evidence is insufficient.";
        return switch (Objects.toString(place.primaryType(), "")) {
            case "movie_theater" -> "A cinema option if you feel like watching a film.";
            case "performing_arts_theater", "concert_hall", "comedy_club" -> "A venue for live entertainment; check what is on before going.";
            case "bowling_alley" -> "An option for a bowling outing.";
            case "video_arcade", "amusement_center", "amusement_park" -> "An option for games or amusement activities.";
            case "shopping_mall" -> "A shopping centre for browsing several shops in one stop.";
            case "clothing_store", "womens_clothing_store", "sportswear_store", "shoe_store" -> "A place to browse fashion or footwear.";
            case "market", "flea_market", "farmers_market" -> "A market option for browsing local stalls and shops.";
            case "electronics_store" -> "A stop for browsing electronics.";
            case "book_store" -> "A place to browse books.";
            default -> switch (family(place)) {
                case "SPIRITUAL_HERITAGE" -> "A place of worship to consider if a spiritual or cultural visit interests you.";
                case "CULTURE" -> "An option for a museum, art or cultural visit.";
                case "FOOD" -> "An option for a meal, coffee or refreshment stop.";
                case "SHOPPING" -> "An option for browsing shops.";
                case "OUTDOORS" -> "An option for spending time outdoors.";
                case "ENTERTAINMENT" -> "An option for a leisure outing.";
                default -> "A sightseeing option to consider if this kind of visit interests you.";
            };
        };
    }
}
