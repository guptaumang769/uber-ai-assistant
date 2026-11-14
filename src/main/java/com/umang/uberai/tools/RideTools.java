package com.umang.uberai.tools;

import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.util.UriComponentsBuilder;

import com.fasterxml.jackson.databind.JsonNode;

import reactor.core.publisher.Mono;

/**
 * The AGENT'S HANDS.
 *
 * <p>Each public method annotated with {@link Tool} is a "tool" the LLM can decide to invoke while
 * planning. Spring AI turns each method into a JSON function schema (name + description + typed
 * params) that is advertised to the model. When the model emits a tool call, Spring AI deserializes
 * the arguments, invokes the method, and feeds the returned String back into the conversation so the
 * model can OBSERVE the result and continue planning (plan -> act -> observe loop).</p>
 *
 * <p>The {@code description} on each {@link Tool} is what the model actually reads to decide WHEN to
 * call a tool, so it is written for the model, not for humans.</p>
 *
 * <p>These call the real Uber platform api-gateway over HTTP via a {@link WebClient}. We deliberately
 * do NOT import any uber-platform classes — the backend is treated as an opaque external HTTP service
 * returning an {@code ApiResponse<T>} envelope {@code {success, data, error}} which we unwrap here into
 * concise, model-friendly Strings. Every method degrades gracefully: if the backend is down we return a
 * friendly "backend unavailable" string rather than throwing, so the agent can still respond.</p>
 */
@Component
public class RideTools {

    private static final Logger log = LoggerFactory.getLogger(RideTools.class);

    private final WebClient webClient;

    public RideTools(WebClient uberWebClient) {
        this.webClient = uberWebClient;
    }

    @Tool(description = "Estimate the fare for a ride between a pickup and a drop location. "
            + "Call this when the user asks about the price/fare/cost of a ride, or before booking so they know the fare. "
            + "Requires pickup and drop coordinates (latitude/longitude). "
            + "Returns the distance in km, the current surge multiplier, and the estimated fare.")
    public String estimateFare(
            @ToolParam(description = "Pickup latitude, e.g. 12.97") double pickupLat,
            @ToolParam(description = "Pickup longitude, e.g. 77.59") double pickupLng,
            @ToolParam(description = "Drop latitude, e.g. 13.0") double dropLat,
            @ToolParam(description = "Drop longitude, e.g. 77.6") double dropLng) {
        log.info("[tool] estimateFare(pickup=[{},{}], drop=[{},{}])", pickupLat, pickupLng, dropLat, dropLng);
        String path = UriComponentsBuilder.fromPath("/api/v1/pricing/estimate")
                .queryParam("pickupLat", pickupLat)
                .queryParam("pickupLng", pickupLng)
                .queryParam("dropLat", dropLat)
                .queryParam("dropLng", dropLng)
                .toUriString();
        JsonNode data = getJson(path);
        if (data == null) {
            return backendUnavailable("estimate the fare");
        }
        String distance = data.path("distanceKm").asText("?");
        String surge = data.path("surgeMultiplier").asText("1.0");
        String fare = data.path("fare").asText("?");
        return "Fare estimate: distance " + distance + " km, surge " + surge + "x, fare ₹" + fare + ".";
    }

    @Tool(description = "Find Uber drivers near a location. "
            + "Call this when the user asks about nearby/available drivers or how far the nearest driver is. "
            + "Requires the latitude and longitude to search around. "
            + "Returns the number of nearby drivers and the nearest few distances.")
    public String nearbyDrivers(
            @ToolParam(description = "Latitude to search around, e.g. 12.97") double lat,
            @ToolParam(description = "Longitude to search around, e.g. 77.59") double lng) {
        log.info("[tool] nearbyDrivers(lat={}, lng={})", lat, lng);
        String path = UriComponentsBuilder.fromPath("/api/v1/locations/nearby")
                .queryParam("lat", lat)
                .queryParam("lng", lng)
                .queryParam("radiusKm", 5)
                .toUriString();
        JsonNode data = getJson(path);
        if (data == null) {
            return backendUnavailable("find nearby drivers");
        }
        if (!data.isArray() || data.isEmpty()) {
            return "No drivers are currently available near " + lat + "," + lng + ".";
        }
        StringBuilder sb = new StringBuilder(data.size() + " driver(s) near " + lat + "," + lng + ":\n");
        int shown = 0;
        for (JsonNode d : data) {
            if (shown++ >= 5) {
                break;
            }
            sb.append("- driverId=").append(d.path("driverId").asText(d.path("id").asText("?")));
            if (d.hasNonNull("distanceKm")) {
                sb.append(" | ").append(d.path("distanceKm").asText()).append(" km away");
            } else if (d.hasNonNull("distance")) {
                sb.append(" | ").append(d.path("distance").asText()).append(" km away");
            }
            sb.append("\n");
        }
        return sb.toString().trim();
    }

    @Tool(description = "Request (book) a ride for a rider from a pickup location to a drop location. "
            + "This creates a ride request and attempts to match a driver. "
            + "Call this once the user wants to actually book a ride and you know the riderId and both coordinates. "
            + "Requires riderId plus pickup and drop coordinates. "
            + "Returns the new ride request id and its status; share the ride id with the user.")
    public String requestRide(
            @ToolParam(description = "The id of the rider requesting the ride") long riderId,
            @ToolParam(description = "Pickup latitude, e.g. 12.97") double pickupLat,
            @ToolParam(description = "Pickup longitude, e.g. 77.59") double pickupLng,
            @ToolParam(description = "Drop latitude, e.g. 13.0") double dropLat,
            @ToolParam(description = "Drop longitude, e.g. 77.6") double dropLng) {
        log.info("[tool] requestRide(riderId={}, pickup=[{},{}], drop=[{},{}])",
                riderId, pickupLat, pickupLng, dropLat, dropLng);
        Map<String, Object> body = Map.of(
                "riderId", riderId,
                "pickupLat", pickupLat,
                "pickupLng", pickupLng,
                "dropLat", dropLat,
                "dropLng", dropLng);
        JsonNode data = postJson("/api/v1/rides", body);
        if (data == null) {
            return backendUnavailable("request the ride");
        }
        String rideId = data.path("id").asText(data.path("rideId").asText("?"));
        String status = data.path("status").asText("REQUESTED");
        return "Ride requested. rideId=" + rideId + ", status=" + status
                + " (pickup " + pickupLat + "," + pickupLng + " → drop " + dropLat + "," + dropLng
                + "). I'll match you with a nearby driver.";
    }

    @Tool(description = "Look up the current status/details of an existing ride request by its id. "
            + "Call this when the user asks about the state of a ride they already requested. Requires the numeric rideId.")
    public String rideStatus(@ToolParam(description = "The numeric id of the ride request, as returned by requestRide") long rideId) {
        log.info("[tool] rideStatus(rideId={})", rideId);
        JsonNode data = getJson("/api/v1/rides/" + rideId);
        if (data == null) {
            return backendUnavailable("look up ride " + rideId);
        }
        String status = data.path("status").asText("UNKNOWN");
        StringBuilder sb = new StringBuilder("Ride id=" + rideId + " status=" + status);
        if (data.hasNonNull("driverId")) {
            sb.append(", driverId=").append(data.path("driverId").asText());
        }
        if (data.hasNonNull("tripId")) {
            sb.append(", tripId=").append(data.path("tripId").asText());
        }
        return sb.toString();
    }

    @Tool(description = "Look up the current status/details of a trip (an in-progress or completed journey) by its id. "
            + "Call this when the user wants to track a trip or asks where their driver/trip is. Requires the numeric tripId.")
    public String tripStatus(@ToolParam(description = "The numeric id of the trip, as returned by rideStatus") long tripId) {
        log.info("[tool] tripStatus(tripId={})", tripId);
        JsonNode data = getJson("/api/v1/trips/" + tripId);
        if (data == null) {
            return backendUnavailable("look up trip " + tripId);
        }
        String status = data.path("status").asText("UNKNOWN");
        StringBuilder sb = new StringBuilder("Trip id=" + tripId + " status=" + status);
        if (data.hasNonNull("driverId")) {
            sb.append(", driverId=").append(data.path("driverId").asText());
        }
        if (data.hasNonNull("fare")) {
            sb.append(", fare=₹").append(data.path("fare").asText());
        }
        return sb.toString();
    }

    // --- HTTP helpers: call the backend and unwrap the ApiResponse<T> { success, data, error } envelope. ---

    /** GET the path, return the {@code data} node of the ApiResponse envelope, or null if the backend is unreachable. */
    private JsonNode getJson(String path) {
        try {
            JsonNode envelope = webClient.get()
                    .uri(path)
                    .retrieve()
                    .bodyToMono(JsonNode.class)
                    .onErrorResume(ex -> {
                        log.warn("[tool] GET {} failed: {}", path, ex.toString());
                        return Mono.empty();
                    })
                    .block();
            return unwrap(envelope);
        } catch (Exception ex) {
            log.warn("[tool] GET {} threw: {}", path, ex.toString());
            return null;
        }
    }

    /** POST the body to the path, return the {@code data} node of the ApiResponse envelope, or null if unreachable. */
    private JsonNode postJson(String path, Object body) {
        try {
            JsonNode envelope = webClient.post()
                    .uri(path)
                    .bodyValue(body)
                    .retrieve()
                    .bodyToMono(JsonNode.class)
                    .onErrorResume(ex -> {
                        log.warn("[tool] POST {} failed: {}", path, ex.toString());
                        return Mono.empty();
                    })
                    .block();
            return unwrap(envelope);
        } catch (Exception ex) {
            log.warn("[tool] POST {} threw: {}", path, ex.toString());
            return null;
        }
    }

    /**
     * Unwrap the {@code ApiResponse<T> { success, data, error }} envelope. Returns the {@code data} node,
     * or null if the envelope is missing/unsuccessful. If the response is not enveloped (plain body),
     * returns it as-is so the tools still work against a non-enveloped mock.
     */
    private JsonNode unwrap(JsonNode envelope) {
        if (envelope == null || envelope.isNull()) {
            return null;
        }
        if (envelope.has("success") || envelope.has("data")) {
            if (envelope.has("success") && !envelope.path("success").asBoolean(true)) {
                log.warn("[tool] backend returned success=false, error={}", envelope.path("error").toString());
                return null;
            }
            JsonNode data = envelope.get("data");
            return (data == null || data.isNull()) ? null : data;
        }
        return envelope; // not an envelope; use as-is
    }

    private String backendUnavailable(String action) {
        return "The Uber backend is currently unavailable, so I couldn't " + action
                + ". Please make sure the uber-platform api-gateway is running (default http://localhost:8080) and try again.";
    }
}
