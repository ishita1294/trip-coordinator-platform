package com.ishita.tripcoordinatorplatform.intelligence.google;

import com.ishita.tripcoordinatorplatform.intelligence.explore.ExplorePreference;
import com.ishita.tripcoordinatorplatform.intelligence.explore.ExploreIntent;
import com.ishita.tripcoordinatorplatform.intelligence.location.Coordinates;
import com.ishita.tripcoordinatorplatform.intelligence.places.PlaceCandidate;
import com.ishita.tripcoordinatorplatform.intelligence.places.PlaceCandidate.*;
import com.ishita.tripcoordinatorplatform.intelligence.places.PlacesProvider;
import com.ishita.tripcoordinatorplatform.intelligence.places.PlaceDestination;
import com.ishita.tripcoordinatorplatform.intelligence.explore.ProviderUnavailableException;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

import java.time.*;
import java.util.*;

@Component
public class GooglePlacesProvider implements PlacesProvider {
    private static final String CANDIDATE_MASK = "places.id,places.displayName,places.formattedAddress,places.location,places.googleMapsUri,"
            + "places.types,places.primaryType,places.rating,places.userRatingCount,places.businessStatus,places.currentOpeningHours,places.timeZone,places.attributions";
    private final GoogleMapsClient client;
    public GooglePlacesProvider(GoogleMapsClient client) { this.client = client; }

    @Override
    public List<PlaceCandidate> nearby(Coordinates origin, ExplorePreference preference, int radiusMeters, int limit) {
        return nearby(origin, preference, null, radiusMeters, limit);
    }

    @Override
    public List<PlaceCandidate> nearby(Coordinates origin, ExplorePreference preference, ExploreIntent intent, int radiusMeters, int limit) {
        Map<String, Object> request = new HashMap<>();
        request.put("maxResultCount", Math.min(limit, 20));
        request.put("rankPreference", "POPULARITY");
        request.put("locationRestriction", Map.of("circle", Map.of("center",
                Map.of("latitude", origin.latitude(), "longitude", origin.longitude()), "radius", radiusMeters)));
        List<String> types = switch (preference) {
            case CULTURE -> List.of("museum", "art_gallery");
            case FOOD -> List.of("cafe", "restaurant", "coffee_shop", "bakery", "food_court", "dessert_shop", "ice_cream_shop");
            case SHOPPING -> List.of("shopping_mall", "department_store", "clothing_store", "womens_clothing_store", "sportswear_store", "book_store", "market", "farmers_market", "flea_market", "jewelry_store", "shoe_store", "electronics_store", "gift_shop");
            case OUTDOORS, RELAXATION -> List.of("park");
            case SIGHTSEEING -> List.of("tourist_attraction", "historical_landmark", "cultural_landmark", "observation_deck");
            case ENTERTAINMENT -> List.of("movie_theater", "performing_arts_theater", "bowling_alley", "amusement_center", "amusement_park", "video_arcade", "concert_hall");
            case ANYTHING -> List.of("museum", "art_gallery", "cultural_center", "tourist_attraction", "historical_landmark", "observation_deck", "plaza", "historical_place", "castle",
                    "cafe", "coffee_shop", "restaurant", "bakery", "food_court", "dessert_shop", "ice_cream_shop",
                    "park", "botanical_garden", "hiking_area", "shopping_mall", "department_store", "market", "farmers_market", "flea_market", "book_store",
                    "movie_theater", "performing_arts_theater", "bowling_alley", "amusement_center", "amusement_park", "video_arcade", "concert_hall");
        };
        if (intent != null) types = intentTypes(intent);
        String mask = CANDIDATE_MASK;
        JsonNode response;
        if (intent == ExploreIntent.BOOKS_STATIONERY) {
            // Google has no stationery-store Nearby type. Text relevance plus actual business evidence is required.
            request.remove("maxResultCount"); request.remove("rankPreference"); request.remove("locationRestriction");
            request.put("textQuery", "bookstores and stationery stores"); request.put("pageSize", Math.min(limit, 20));
            request.put("locationBias", Map.of("circle", Map.of("center", Map.of("latitude", origin.latitude(), "longitude", origin.longitude()), "radius", radiusMeters)));
            response = client.post("https://places.googleapis.com/v1/places:searchText", mask, request);
        } else {
            // Markets with an unsupported/generic primary can still have an explicit market type.
            // Admission below checks that evidence conservatively; other intents remain primary-only.
            request.put(intent == null || intent == ExploreIntent.LOCAL_MARKETS ? "includedTypes" : "includedPrimaryTypes", types);
            // Secondary restaurant/attraction tags do not establish that a hotel or office is an activity.
            request.put("excludedPrimaryTypes", List.of("hotel", "corporate_office", "manufacturer"));
            response = client.post("https://places.googleapis.com/v1/places:searchNearby", mask, request);
        }
        List<PlaceCandidate> places = new ArrayList<>();
        for (var place : response.path("places")) {
            if (!place.path("businessStatus").asText().equals("OPERATIONAL")) continue;
            var point = place.path("location");
            if (!point.path("latitude").isNumber() || !point.path("longitude").isNumber()) continue;
            var candidate = parseCandidate(place);
            if (origin.distanceMetersTo(candidate.coordinates()) <= radiusMeters) places.add(candidate);
        }
        return places;
    }


    @Override
    public List<PlaceCandidate> nearbyAdditionalActivities(Coordinates origin, int radiusMeters, int limit, Set<ExplorePreference> represented) {
        // One union of missing families, not a paid request per category or a promise of quotas.
        List<String> types = new ArrayList<>();
        if (!represented.contains(ExplorePreference.CULTURE)) types.addAll(List.of("museum", "art_gallery", "cultural_center", "historical_place", "castle"));
        if (!represented.contains(ExplorePreference.FOOD)) types.addAll(List.of("cafe", "coffee_shop", "restaurant", "bakery", "food_court"));
        if (!represented.contains(ExplorePreference.OUTDOORS)) types.addAll(List.of("park", "botanical_garden", "garden", "plaza"));
        if (!represented.contains(ExplorePreference.SHOPPING)) types.addAll(List.of("market", "farmers_market", "flea_market", "shopping_mall", "book_store", "department_store"));
        if (!represented.contains(ExplorePreference.ENTERTAINMENT)) types.addAll(List.of("movie_theater", "performing_arts_theater", "bowling_alley", "amusement_center"));
        if (types.isEmpty()) return List.of();
        var body = Map.of("maxResultCount", Math.min(limit,20), "rankPreference", "POPULARITY", "includedPrimaryTypes", types,
                "locationRestriction", Map.of("circle",Map.of("center",Map.of("latitude",origin.latitude(),"longitude",origin.longitude()),"radius",radiusMeters)));
        var response = client.post("https://places.googleapis.com/v1/places:searchNearby",CANDIDATE_MASK,body);
        List<PlaceCandidate> result = new ArrayList<>();
        for (var place : response.path("places")) {
            if (!place.path("businessStatus").asText().equals("OPERATIONAL") || !place.path("location").path("latitude").isNumber() || !place.path("location").path("longitude").isNumber()) continue;
            var candidate = parseCandidate(place);
            if (origin.distanceMetersTo(candidate.coordinates()) <= radiusMeters) result.add(candidate);
        }
        return List.copyOf(result);
    }

    @Override
    public PlaceCandidate candidate(String placeId) {
        if (placeId == null || !placeId.matches("[A-Za-z0-9_-]{1,255}")) throw new IllegalArgumentException("Invalid candidate place identity");
        var place = client.get("https://places.googleapis.com/v1/places/" + placeId,
                "id,displayName,formattedAddress,location,googleMapsUri,types,primaryType,rating,userRatingCount,businessStatus,currentOpeningHours,timeZone,attributions");
        if (!place.path("businessStatus").asText().equals("OPERATIONAL") || !place.path("location").path("latitude").isNumber() || !place.path("location").path("longitude").isNumber())
            throw new com.ishita.tripcoordinatorplatform.intelligence.explore.ProviderUnavailableException("Selected candidate is unavailable or lacks verified coordinates");
        return parseCandidate(place);
    }
    private PlaceCandidate parseCandidate(JsonNode place) {
        var point = place.path("location");
            List<String> placeTypes = new ArrayList<>();
            place.path("types").forEach(type -> placeTypes.add(type.asText()));
            String primary = place.path("primaryType").asText();
            // Rejects an impossible location here, before the place is used.
            new Coordinates(point.path("latitude").asDouble(), point.path("longitude").asDouble());

            String name = place.path("displayName").path("text").asText();
            var intents = shoppingIntents(primary, placeTypes, name);
            var interests = interests(primary, placeTypes, intents);
            List<Attribution> attributions = new ArrayList<>();
            place.path("attributions").forEach(a -> attributions.add(new Attribution(a.path("provider").asText(), a.path("providerUri").asText())));
            boolean ticketed = placeTypes.stream().anyMatch(t -> Set.of("museum", "tourist_attraction",
                    "amusement_park", "aquarium", "zoo", "movie_theater", "performing_arts_theater", "concert_hall", "bowling_alley").contains(t));
            return new PlaceCandidate(place.path("id").asText(), place.path("displayName").path("text").asText(),
                    place.path("formattedAddress").asText(), new Coordinates(point.path("latitude").asDouble(),
                    point.path("longitude").asDouble()), place.path("googleMapsUri").asText(), List.copyOf(placeTypes),
                    openingHours(place), null, null, ticketed, List.copyOf(attributions), place.path("primaryType").asText(),
                    place.path("rating").isNumber() ? place.path("rating").asDouble() : null,
                    place.path("userRatingCount").isNumber() ? place.path("userRatingCount").asInt() : null, interests, intents, timeZone(place));
    }

    private static List<String> intentTypes(ExploreIntent intent) {
        return switch (intent) {
            case MALLS -> List.of("shopping_mall");
            case LOCAL_MARKETS -> List.of("market", "farmers_market", "flea_market");
            case FASHION -> List.of("clothing_store", "womens_clothing_store", "sportswear_store", "shoe_store", "jewelry_store");
            case ELECTRONICS -> List.of("electronics_store");
            case BOOKS_STATIONERY -> List.of("book_store");
        };
    }
    private static boolean overlaps(List<String> types, List<String> wanted) { return types.stream().anyMatch(wanted::contains); }
    private static Set<ExploreIntent> shoppingIntents(String primary, List<String> types, String name) {
        // Secondary shops do not establish that a park, hotel or industrial site is a shopping destination.
        if (Set.of("park", "city_park", "garden", "botanical_garden", "national_park", "hotel", "lodging", "manufacturer", "corporate_office").contains(primary)) return Set.of();
        Set<ExploreIntent> result = EnumSet.noneOf(ExploreIntent.class);
        // A tenant/secondary tag is not evidence that the parent is a mall, market or fashion shop.
        // Primary categories describe the independently indexed destination, not facilities it contains.
        for (var intent : ExploreIntent.values()) if (intentTypes(intent).contains(primary)) result.add(intent);
        if ((primary.isBlank() || Set.of("store", "establishment", "point_of_interest").contains(primary))
                && overlaps(types, intentTypes(ExploreIntent.LOCAL_MARKETS))
                && types.stream().allMatch(t -> Set.of("store", "establishment", "point_of_interest",
                        "market", "farmers_market", "flea_market").contains(t))) {
            // Explicit provider market evidence, with no stronger competing category. Never infer from a name.
            result.add(ExploreIntent.LOCAL_MARKETS);
        }
        if ((primary.equals("store") || primary.equals("book_store")) && name.toLowerCase(Locale.ROOT).matches(".*(stationer|bookstore|bookshop|book store).*")) result.add(ExploreIntent.BOOKS_STATIONERY);
        return Set.copyOf(result);
    }
    private static final Set<String> NON_ACTIVITY = Set.of("hotel", "lodging", "resort_hotel", "motel", "extended_stay_hotel", "inn", "guest_house", "corporate_office", "manufacturer", "business_center", "travel_agency", "real_estate_agency");
    private static Set<ExplorePreference> interests(String primary, List<String> types, Set<ExploreIntent> intents) {
        if (NON_ACTIVITY.contains(primary)) return Set.of();
        if (primary.isBlank()) {
            if (types.stream().anyMatch(NON_ACTIVITY::contains)) return Set.of();
            Set<ExplorePreference> inferredFromProviderTypes = EnumSet.noneOf(ExplorePreference.class);
            for (String type : types) inferredFromProviderTypes.addAll(interests(type, List.of(type), intents));
            // Entertainment needs an identifiable venue, not a secondary label on an unknown business.
            inferredFromProviderTypes.remove(ExplorePreference.ENTERTAINMENT);
            return Set.copyOf(inferredFromProviderTypes);
        }
        Set<ExplorePreference> result = EnumSet.noneOf(ExplorePreference.class);
        if (!intents.isEmpty() || (primary.equals("department_store") || primary.equals("gift_shop"))) result.add(ExplorePreference.SHOPPING);
        if (primary.endsWith("restaurant") || Set.of("cafe", "coffee_shop", "tea_house", "bakery", "dessert_shop", "ice_cream_shop", "sandwich_shop", "meal_takeaway", "food_court").contains(primary)) result.add(ExplorePreference.FOOD);
        if (Set.of("museum", "art_gallery", "art_museum", "history_museum", "cultural_center").contains(primary)) result.add(ExplorePreference.CULTURE);
        if (Set.of("park", "city_park", "garden", "botanical_garden", "national_park", "hiking_area", "beach", "picnic_ground").contains(primary)) { result.add(ExplorePreference.OUTDOORS); result.add(ExplorePreference.RELAXATION); }
        if (Set.of("tourist_attraction", "historical_landmark", "historical_place", "cultural_landmark", "monument", "castle", "observation_deck", "plaza", "fountain", "sculpture").contains(primary)) result.add(ExplorePreference.SIGHTSEEING);
        if (Set.of("movie_theater", "performing_arts_theater", "bowling_alley", "amusement_center", "amusement_park", "video_arcade", "concert_hall", "comedy_club", "aquarium", "zoo").contains(primary)) result.add(ExplorePreference.ENTERTAINMENT);
        if (Set.of("church", "hindu_temple", "buddhist_temple", "mosque", "synagogue").contains(primary)
                && overlaps(types, List.of("tourist_attraction", "historical_landmark", "cultural_landmark"))) result.add(ExplorePreference.CULTURE);
        if (!result.isEmpty() && overlaps(types, List.of("tourist_attraction", "historical_landmark", "cultural_landmark"))) result.add(ExplorePreference.SIGHTSEEING);
        // A separately indexed restaurant in a hotel is FOOD; the parent hotel is not.
        // A mall remains a shopping activity even when it also contains unrelated tenants.
        return Set.copyOf(result);
    }

    private static final String DESTINATION_MASK = "id,displayName,formattedAddress,location,timeZone,googleMapsUri,attributions";
    @Override
    public List<PlaceDestination> findDestinations(DestinationQuery query) {
        String text = String.join(" ", java.util.stream.Stream.of(query.name(), query.address(), query.locality())
                .filter(v -> v != null && !v.isBlank()).toList());
        if (text.isBlank()) return List.of();
        Map<String, Object> body = new HashMap<>();
        body.put("textQuery", text); body.put("pageSize", 5);
        if (query.coordinates() != null) body.put("locationBias", Map.of("circle", Map.of("center", Map.of(
                "latitude", query.coordinates().latitude(), "longitude", query.coordinates().longitude()), "radius", 1000)));
        var response = client.post("https://places.googleapis.com/v1/places:searchText",
                Arrays.stream(DESTINATION_MASK.split(",")).map(field -> "places." + field).collect(java.util.stream.Collectors.joining(",")), body);
        List<PlaceDestination> results = new ArrayList<>();
        for (var place : response.path("places")) {
            var parsed = parseDestination(place);
            if (parsed != null) results.add(parsed);
        }
        return List.copyOf(results);
    }
    @Override
    public PlaceDestination destination(String placeId) {
        if (placeId == null || !placeId.matches("[A-Za-z0-9_-]{1,255}")) throw new IllegalArgumentException("Invalid place identity");
        var result = parseDestination(client.get("https://places.googleapis.com/v1/places/" + placeId, DESTINATION_MASK));
        if (result == null || !placeId.equals(result.id())) throw new ProviderUnavailableException("Selected place details are incomplete");
        return result;
    }
    private PlaceDestination parseDestination(JsonNode place) {
        var point = place.path("location");
        if (place.path("id").asText().isBlank() || !point.path("latitude").isNumber() || !point.path("longitude").isNumber()) return null;
        List<Attribution> attribution = new ArrayList<>();
        place.path("attributions").forEach(a -> attribution.add(new Attribution(a.path("provider").asText(), a.path("providerUri").asText())));
        String zone = place.path("timeZone").path("id").asText();
        try { ZoneId.of(zone); } catch (DateTimeException exception) { zone = null; }
        return new PlaceDestination(place.path("id").asText(), place.path("displayName").path("text").asText(),
                place.path("formattedAddress").asText(), new Coordinates(point.path("latitude").asDouble(), point.path("longitude").asDouble()),
                zone, place.path("googleMapsUri").asText(), List.copyOf(attribution));
    }

    private String timeZone(JsonNode place) {
        String value = place.path("timeZone").path("id").asText();
        return ZoneId.getAvailableZoneIds().contains(value) ? value : null;
    }

    private OpeningHours openingHours(JsonNode place) {
        // Current, date-specific hours only. Never reuse a UTC offset across DST changes.
        try {
            ZoneId zone = ZoneId.of(place.path("timeZone").path("id").asText());
            var periods = place.path("currentOpeningHours").path("periods");
            if (!periods.isArray()) return null;
            LocalDate today = LocalDate.now(zone);
            Instant from = today.atStartOfDay(zone).toInstant();
            Instant to = today.plusDays(7).atStartOfDay(zone).toInstant();
            List<OpeningInterval> intervals = new ArrayList<>();
            for (var period : periods) {
                var open = period.path("open");
                var close = period.path("close");
                if (periods.size() == 1 && close.isMissingNode() && open.path("day").asInt(-1) == 0
                        && open.path("hour").asInt(-1) == 0 && open.path("minute").asInt(0) == 0) {
                    intervals.add(new OpeningInterval(from, to));
                    continue;
                }
                Instant starts = pointTime(open, zone);
                Instant ends = pointTime(close, zone);
                if (!ends.isAfter(starts)) return null;
                intervals.add(new OpeningInterval(starts, ends));
            }
            return new OpeningHours(from, to, List.copyOf(intervals));
        } catch (RuntimeException unknownOrAmbiguousHours) {
            return null;
        }
    }

    private Instant pointTime(JsonNode point, ZoneId zone) {
        var date = point.path("date");
        LocalDateTime local = LocalDate.of(date.path("year").asInt(), date.path("month").asInt(),
                date.path("day").asInt()).atTime(point.path("hour").asInt(), point.path("minute").asInt());
        var offsets = zone.getRules().getValidOffsets(local);
        if (offsets.size() != 1) throw new IllegalArgumentException("Ambiguous opening-hour timestamp");
        return local.toInstant(offsets.getFirst());
    }
}
