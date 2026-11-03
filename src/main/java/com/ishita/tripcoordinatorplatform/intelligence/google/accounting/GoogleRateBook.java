package com.ishita.tripcoordinatorplatform.intelligence.google.accounting;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/** Exact reviewed endpoint/mask mapping. A newly introduced field must be reviewed before use. */
public final class GoogleRateBook {
    public static final String VERSION = "google-india-2026-10-11-proposed";
    private static final Set<String> PRO_FIELDS = Set.of("id", "displayName", "formattedAddress", "location", "timeZone", "googleMapsUri", "attributions");
    private static final Set<String> ENTERPRISE_FIELDS = Set.of("types", "primaryType", "rating", "userRatingCount", "businessStatus", "currentOpeningHours");
    private static final Set<String> ROUTE_FIELDS = Set.of("routes.duration", "routes.distanceMeters", "routes.warnings",
            "routes.legs.steps.staticDuration", "routes.legs.steps.transitDetails.stopDetails.departureTime",
            "routes.legs.steps.transitDetails.stopDetails.arrivalTime", "routes.legs.steps.transitDetails.transitLine.agencies");
    public static final String DIGEST = digest(manifest());
    private GoogleRateBook() {}
    public static String manifest() {
        StringBuilder value = new StringBuilder(VERSION).append("\nUSD\nprotocol=1\n");
        for (var sku : GoogleSku.values()) value.append(sku.name()).append('|').append(sku.operationKind).append('|').append(sku.skuId).append('|').append(sku.unitMicros).append('\n');
        value.append("places.pro=").append(String.join(",",new TreeSet<>(PRO_FIELDS))).append('\n');
        value.append("places.enterprise=").append(String.join(",",new TreeSet<>(ENTERPRISE_FIELDS))).append('\n');
        value.append("routes=").append(String.join(",",new TreeSet<>(ROUTE_FIELDS))).append("\nDRIVE=TRAFFIC_AWARE\n");
        return value.toString();
    }
    public static String digest(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    public static GoogleSku classify(String method, String url, String mask, Object body) {
        final URI uri;
        try { uri=URI.create(url); } catch(RuntimeException invalid) { throw unsupported(); }
        if (!"https".equals(uri.getScheme()) || uri.getRawQuery()!=null || uri.getUserInfo()!=null) throw unsupported();
        if ("routes.googleapis.com".equals(uri.getHost()) && "/directions/v2:computeRoutes".equals(uri.getPath()) && "POST".equals(method)) {
            if (!(body instanceof Map<?,?> request) || !ROUTE_FIELDS.containsAll(fields(mask,false))) throw unsupported();
            Set<String> allowed = Set.of("origin","destination","travelMode","computeAlternativeRoutes","departureTime","arrivalTime","routingPreference");
            if (!allowed.containsAll(request.keySet()) || !Boolean.FALSE.equals(request.get("computeAlternativeRoutes"))) throw unsupported();
            Object mode=request.get("travelMode");
            if ("DRIVE".equals(mode) && "TRAFFIC_AWARE".equals(request.get("routingPreference"))) return GoogleSku.ROUTES_PRO;
            if (("WALK".equals(mode)||"TRANSIT".equals(mode)) && !request.containsKey("routingPreference")) return GoogleSku.ROUTES_ESSENTIALS;
            throw unsupported();
        }
        if (!"places.googleapis.com".equals(uri.getHost())) throw unsupported();
        boolean details="GET".equals(method) && uri.getPath().matches("/v1/places/[A-Za-z0-9_-]{1,255}");
        boolean text="POST".equals(method) && "/v1/places:searchText".equals(uri.getPath());
        boolean nearby="POST".equals(method) && "/v1/places:searchNearby".equals(uri.getPath());
        if (!details && !text && !nearby) throw unsupported();
        var fields=fields(mask,!details);
        var allowed=new HashSet<>(PRO_FIELDS); allowed.addAll(ENTERPRISE_FIELDS);
        if (!allowed.containsAll(fields)) throw unsupported();
        boolean enterprise=fields.stream().anyMatch(f -> Set.of("rating","userRatingCount","currentOpeningHours").contains(f));
        // Reviewed discovery mask intentionally retains opening hours; do not silently downgrade its SKU.
        if (nearby) { if (!enterprise) throw unsupported(); return GoogleSku.NEARBY_ENTERPRISE; }
        return details ? enterprise ? GoogleSku.DETAILS_ENTERPRISE : GoogleSku.DETAILS_PRO
                : enterprise ? GoogleSku.TEXT_ENTERPRISE : GoogleSku.TEXT_PRO;
    }
    private static Set<String> fields(String mask, boolean prefixed) {
        if (mask==null || mask.isBlank()) throw unsupported();
        Set<String> result=new HashSet<>();
        for (String field : mask.split(",",-1)) {
            if (prefixed && !field.startsWith("places.")) throw unsupported();
            result.add(prefixed ? field.substring(7) : field);
        }
        return result;
    }
    private static GoogleAccountingException unsupported() { return new GoogleAccountingException("UNSUPPORTED_SKU", "Google operation or field mask has no reviewed cost classification; no request was authorized."); }
}
