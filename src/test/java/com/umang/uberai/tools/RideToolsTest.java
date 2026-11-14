package com.umang.uberai.tools;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.ExchangeFunction;
import org.springframework.web.reactive.function.client.WebClient;

import reactor.core.publisher.Mono;

/**
 * Pure unit tests for {@link RideTools} — no network, no API key.
 *
 * <p>We build a real {@link WebClient} on a stub {@link ExchangeFunction} that captures the outgoing
 * request (so we can assert the HTTP method + path) and returns a canned {@code ApiResponse} envelope
 * (so we can assert the tools UNWRAP {@code data} correctly and degrade gracefully on errors).</p>
 */
class RideToolsTest {

    private final String baseUrl = "http://localhost:8080";

    /** Build RideTools whose WebClient always returns the given JSON body, capturing the request. */
    private RideTools toolsReturning(String jsonBody, AtomicReference<ClientRequest> captured) {
        ExchangeFunction exchange = request -> {
            captured.set(request);
            ClientResponse response = ClientResponse.create(org.springframework.http.HttpStatus.OK)
                    .header("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                    .body(jsonBody)
                    .build();
            return Mono.just(response);
        };
        WebClient client = WebClient.builder().baseUrl(baseUrl).exchangeFunction(exchange).build();
        return new RideTools(client);
    }

    /** Build RideTools whose WebClient always errors (simulates backend down). */
    private RideTools toolsFailing() {
        ExchangeFunction exchange = request -> Mono.error(new RuntimeException("connection refused"));
        WebClient client = WebClient.builder().baseUrl(baseUrl).exchangeFunction(exchange).build();
        return new RideTools(client);
    }

    @Test
    void estimateFare_hitsPricingEstimatePath_withCoords_andUnwrapsEnvelope() {
        AtomicReference<ClientRequest> captured = new AtomicReference<>();
        String envelope = """
                {"success":true,"data":{"distanceKm":4.2,"surgeMultiplier":1.5,"fare":180},"error":null}
                """;
        RideTools tools = toolsReturning(envelope, captured);

        String result = tools.estimateFare(12.97, 77.59, 13.0, 77.6);

        assertThat(captured.get().method()).isEqualTo(HttpMethod.GET);
        assertThat(captured.get().url().getPath()).isEqualTo("/api/v1/pricing/estimate");
        String query = captured.get().url().getQuery();
        assertThat(query).contains("pickupLat=12.97").contains("pickupLng=77.59")
                .contains("dropLat=13.0").contains("dropLng=77.6");
        // Unwrapped from the envelope's data object:
        assertThat(result).contains("4.2").contains("1.5").contains("180");
    }

    @Test
    void nearbyDrivers_buildsNearbyPath_andCountsDrivers() {
        AtomicReference<ClientRequest> captured = new AtomicReference<>();
        String envelope = """
                {"success":true,"data":[
                    {"driverId":1,"distanceKm":0.4},
                    {"driverId":2,"distanceKm":1.1}
                ],"error":null}
                """;
        RideTools tools = toolsReturning(envelope, captured);

        String result = tools.nearbyDrivers(12.97, 77.59);

        assertThat(captured.get().method()).isEqualTo(HttpMethod.GET);
        assertThat(captured.get().url().getPath()).isEqualTo("/api/v1/locations/nearby");
        assertThat(captured.get().url().getQuery()).contains("lat=12.97").contains("lng=77.59");
        assertThat(result).contains("2 driver(s)").contains("driverId=1").contains("0.4");
    }

    @Test
    void requestRide_postsToRides_andUnwrapsRideId() {
        AtomicReference<ClientRequest> captured = new AtomicReference<>();
        String envelope = "{\"success\":true,\"data\":{\"id\":555,\"status\":\"REQUESTED\"},\"error\":null}";
        RideTools tools = toolsReturning(envelope, captured);

        String result = tools.requestRide(1L, 12.97, 77.59, 13.0, 77.6);

        assertThat(captured.get().method()).isEqualTo(HttpMethod.POST);
        assertThat(captured.get().url().getPath()).isEqualTo("/api/v1/rides");
        assertThat(result).contains("rideId=555").contains("REQUESTED");
    }

    @Test
    void rideStatus_buildsRidePath_andReadsStatus() {
        AtomicReference<ClientRequest> captured = new AtomicReference<>();
        RideTools tools = toolsReturning("{\"success\":true,\"data\":{\"status\":\"MATCHED\",\"driverId\":7}}", captured);

        String result = tools.rideStatus(3L);

        assertThat(captured.get().url().getPath()).isEqualTo("/api/v1/rides/3");
        assertThat(result).contains("status=MATCHED").contains("driverId=7");
    }

    @Test
    void tripStatus_buildsTripPath_andReadsStatus() {
        AtomicReference<ClientRequest> captured = new AtomicReference<>();
        RideTools tools = toolsReturning("{\"success\":true,\"data\":{\"status\":\"IN_PROGRESS\",\"fare\":180}}", captured);

        String result = tools.tripStatus(9L);

        assertThat(captured.get().url().getPath()).isEqualTo("/api/v1/trips/9");
        assertThat(result).contains("status=IN_PROGRESS");
    }

    @Test
    void backendDown_returnsFriendlyMessage_doesNotThrow() {
        RideTools tools = toolsFailing();

        String result = tools.estimateFare(12.97, 77.59, 13.0, 77.6);

        assertThat(result).contains("unavailable");
    }
}
