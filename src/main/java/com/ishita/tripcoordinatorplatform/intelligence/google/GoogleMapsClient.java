package com.ishita.tripcoordinatorplatform.intelligence.google;

import com.ishita.tripcoordinatorplatform.intelligence.explore.ProviderUnavailableException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;

import java.net.http.HttpClient;
import java.time.Duration;

@Component
class GoogleMapsClient {
    private final RestClient client;
    private final String apiKey;
    private final boolean enabled;
    private final GoogleRequestBudget budget;
    private record RequestKey(String url, String mask, Object body) {}
    java.time.Instant departureAt(java.time.Instant requested) {return budget.departureAt(requested);}
    private RequestKey key(String url,String mask,Object body) {
        // Masks are sets. Body maps have structural equality; preserve all time/mode/location distinctions.
        return new RequestKey(url,String.join(",",new java.util.TreeSet<>(java.util.Arrays.asList(mask.split(",")))),body);
    }

    @org.springframework.beans.factory.annotation.Autowired
    GoogleMapsClient(@Value("${app.explore.google.api-key:}") String apiKey,
                     @Value("${app.explore.google.enabled:false}") boolean enabled, GoogleRequestBudget budget) {
        this.budget = budget;
        this.apiKey = apiKey;
        this.enabled = enabled;
        var factory = new JdkClientHttpRequestFactory(HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(3)).build());
        factory.setReadTimeout(Duration.ofSeconds(8));
        client = RestClient.builder().requestFactory(factory).build();
    }

    // Controlled HTTP verification can supply an intercepting client without any live provider I/O.
    GoogleMapsClient(String apiKey,boolean enabled,GoogleRequestBudget budget,RestClient client) {
        this.apiKey=apiKey;this.enabled=enabled;this.budget=budget;this.client=client;
    }

    JsonNode get(String url, String mask) {
        requireConfigured();
        return budget.call("places.details", key(url,mask,null), () -> { try {
            var result = client.get().uri(url).header("X-Goog-Api-Key", apiKey)
                    .header("X-Goog-FieldMask", mask).retrieve().body(JsonNode.class);
            if (result == null) throw new ProviderUnavailableException("Google Maps returned an empty response");
            return result;
        } catch (org.springframework.web.client.RestClientResponseException exception) {
            throw failure(exception);
        } catch (RuntimeException exception) {
            throw new ProviderUnavailableException("Google place details lookup failed; check access, quota and connectivity");
        } });
    }
    private ProviderUnavailableException failure(org.springframework.web.client.RestClientResponseException failure) {
        if(failure.getStatusCode().value()!=429) return new ProviderUnavailableException("Google provider request failed (HTTP "+failure.getStatusCode().value()+"); no automatic retry was performed.");
        java.time.Duration delay=java.time.Duration.ofSeconds(60);
        String header=failure.getResponseHeaders()==null ? null:failure.getResponseHeaders().getFirst("Retry-After");
        if(header!=null) try {
            delay=header.matches("[0-9]+") ? java.time.Duration.ofSeconds(Long.parseLong(header))
                : java.time.Duration.between(java.time.Instant.now(),java.time.ZonedDateTime.parse(header,java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME).toInstant());
        } catch(RuntimeException ignored) { /* Use sanitized conservative backoff, never provider bodies. */ }
        delay=java.time.Duration.ofSeconds(Math.max(1,Math.min(300,delay.toSeconds())));
        return new ProviderUnavailableException("Google provider quota exhausted (HTTP 429). Retry no earlier than "+java.time.Instant.now().plus(delay)+"; no automatic retry was performed. Previous route/hour evidence was not refreshed.","GOOGLE_QUOTA_EXHAUSTED",java.time.Instant.now().plus(delay));
    }
    private void requireConfigured() {
        if (!enabled || apiKey == null || apiKey.isBlank()) throw new ProviderUnavailableException("Google Maps is not configured for EXPLORE");
    }
    record RouteReply(JsonNode response, java.time.Instant departureAt) {}
    JsonNode post(String url,String mask,Object body) {return postReply(url,mask,body).response();}
    RouteReply postRoute(String url,String mask,Object body) {return postReply(url,mask,body);}
    private RouteReply postReply(String url, String mask, Object body) {
        requireConfigured();
        String operation = url.contains("computeRoutes") ? "routes.compute" : url.contains("searchNearby") ? "places.nearby" : "places.text";
        return budget.call(operation, key(url,mask,body), () -> { try {
            Object dispatchedBody=body;
            java.time.Instant sentDeparture=null;
            if(operation.equals("routes.compute") && body instanceof java.util.Map<?,?> fields && fields.get("departureTime") instanceof String time) {
                sentDeparture=java.time.Instant.parse(time);
                var earliest=java.time.Instant.now().plusSeconds(2);
                if(sentDeparture.isBefore(earliest)) {
                    var corrected=new java.util.HashMap<Object,Object>(fields);sentDeparture=earliest;
                    corrected.put("departureTime",sentDeparture.toString());dispatchedBody=corrected;
                }
            }
            var result = client.post().uri(url).header("X-Goog-Api-Key", apiKey)
                    .header("X-Goog-FieldMask", mask).contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                    .body(dispatchedBody).retrieve().body(JsonNode.class);
            if (result == null) throw new ProviderUnavailableException("Google Maps returned an empty response");
            return new RouteReply(result,sentDeparture);
        } catch (ProviderUnavailableException exception) {
            throw exception;
        } catch (org.springframework.web.client.RestClientResponseException exception) {
            throw failure(exception);
        } catch (RuntimeException exception) {
            throw new ProviderUnavailableException("Google Maps lookup failed; check provider access, quota and connectivity");
        } });
    }
}
