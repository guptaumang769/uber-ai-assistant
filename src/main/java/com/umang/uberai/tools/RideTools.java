package com.umang.uberai.tools;

import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;
import org.springframework.web.util.UriComponentsBuilder;

import com.fasterxml.jackson.databind.JsonNode;
import com.umang.uberai.http.BackendClient;

@Component
public class RideTools {

    private static final Logger log = LoggerFactory.getLogger(RideTools.class);

    private final BackendClient backend;

    public RideTools(BackendClient uberBackendClient) {
        this.backend = uberBackendClient;
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
        JsonNode data = backend.getJson(path);
        if (data == null) {
            return backend.unavailableMessage("estimate the fare");
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
        JsonNode data = backend.getJson(path);
        if (data == null) {
            return backend.unavailableMessage("find nearby drivers");
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
        JsonNode data = backend.postJson("/api/v1/rides", body);
        if (data == null) {
            return backend.unavailableMessage("request the ride");
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
        JsonNode data = backend.getJson("/api/v1/rides/" + rideId);
        if (data == null) {
            return backend.unavailableMessage("look up ride " + rideId);
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
        JsonNode data = backend.getJson("/api/v1/trips/" + tripId);
        if (data == null) {
            return backend.unavailableMessage("look up trip " + tripId);
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
}
