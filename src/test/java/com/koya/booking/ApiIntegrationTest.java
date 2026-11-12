package com.koya.booking;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

/**
 * Integration tests against a real Postgres. Run with
 * TEST_DATABASE_URL=postgres://postgres:pg@localhost:5433/koya_java_test ./mvnw test
 * (the tests wipe that database).
 */
@EnabledIfEnvironmentVariable(named = "TEST_DATABASE_URL", matches = ".+")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"app.database-url=${TEST_DATABASE_URL}", "app.api-key=test-key"})
class ApiIntegrationTest {

    private static final String KEY = "test-key";
    private static final ObjectMapper M = new ObjectMapper();
    private static final HttpClient HTTP = HttpClient.newHttpClient();

    @LocalServerPort
    int port;

    record Resp(int status, HttpResponse<String> raw, JsonNode body) {}

    Resp call(String method, String path, Object body, String apiKey, Map<String, String> headers) throws Exception {
        String json = body == null ? null : body instanceof String s ? s : M.writeValueAsString(body);
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .method(method, json == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(json))
                .header("Content-Type", "application/json");
        if (apiKey != null) b.header("X-API-Key", apiKey);
        headers.forEach(b::header);
        HttpResponse<String> r = HTTP.send(b.build(), HttpResponse.BodyHandlers.ofString());
        JsonNode node = r.body().isBlank() ? M.nullNode() : M.readTree(r.body());
        return new Resp(r.statusCode(), r, node);
    }

    Resp call(String method, String path, Object body) throws Exception {
        return call(method, path, body, KEY, Map.of());
    }

    Resp book(String slot, String email, Map<String, String> headers) throws Exception {
        return call("POST", "/bookings", Map.of("slot_id", slot, "name", "Ada Obi", "email", email), KEY, headers);
    }

    static String future(int hours) {
        return Instant.now().truncatedTo(ChronoUnit.SECONDS).plus(hours, ChronoUnit.HOURS).toString();
    }

    void reset(int n) throws Exception {
        List<Map<String, String>> slots = new ArrayList<>();
        for (int i = 1; i <= n; i++) {
            slots.add(Map.of("id", "s" + i, "provider", i % 2 == 0 ? "Dr Bello" : "Dr Ade",
                    "start", future(100 - i), "end", future(101 - i)));
        }
        slots.add(Map.of("id", "past", "provider", "Dr Ade", "start", future(-2), "end", future(-1)));
        assertEquals(200, call("POST", "/reset", Map.of("slots", slots)).status());
    }

    @Test
    void auth() throws Exception {
        Resp r = call("GET", "/slots", null, null, Map.of());
        assertEquals(401, r.status());
        assertTrue(r.body().has("error"));
        assertEquals(401, call("GET", "/slots", null, "wrong", Map.of()).status());
        assertEquals(200, call("GET", "/web/api/slots", null, null, Map.of()).status());
        assertEquals(200, call("GET", "/healthz", null, null, Map.of()).status());
    }

    @Test
    void slotsAndBooking() throws Exception {
        reset(4);
        JsonNode slots = call("GET", "/slots", null).body().get("slots");
        assertEquals(5, slots.size());
        assertEquals("past", slots.get(0).get("id").asText());
        assertEquals("s4", slots.get(1).get("id").asText());
        assertEquals(2, call("GET", "/slots?provider=Dr%20Bello", null).body().get("slots").size());

        Resp r = call("POST", "/bookings", Map.of("slot_id", "s1", "name", "  Ada Obi ", "email", " ADA@Example.com "));
        assertEquals(201, r.status(), r.raw().body());
        assertEquals("confirmed", r.body().get("status").asText());
        assertEquals("ada@example.com", r.body().get("email").asText());
        assertEquals("Ada Obi", r.body().get("name").asText());
        String id = r.body().get("id").asText();

        Resp g = call("GET", "/bookings/" + id, null);
        assertEquals(200, g.status());
        assertEquals(id, g.body().get("id").asText());
        assertEquals(404, call("GET", "/bookings/nope", null).status());

        for (JsonNode s : call("GET", "/slots", null).body().get("slots")) {
            if (s.get("id").asText().equals("s1")) assertFalse(s.get("available").asBoolean());
        }
    }

    @Test
    void validation() throws Exception {
        reset(2);
        Object[][] cases = {
                {Map.of("name", "A", "email", "a@b.co"), 400},
                {Map.of("slot_id", "s1", "name", "  ", "email", "a@b.co"), 400},
                {Map.of("slot_id", "s1", "name", "A", "email", ""), 400},
                {Map.of("slot_id", "s1", "name", "A", "email", "not-an-email"), 400},
                {Map.of("slot_id", "s1", "name", "A", "email", "a@b"), 400},
                {"{\"slot_id\":", 400},
                {Map.of("slot_id", "zzz", "name", "A", "email", "a@b.co"), 404},
                {Map.of("slot_id", "past", "name", "A", "email", "a@b.co"), 400},
        };
        for (int i = 0; i < cases.length; i++) {
            Resp r = call("POST", "/bookings", cases[i][0]);
            assertEquals(cases[i][1], r.status(), "case " + i + ": " + r.raw().body());
            assertTrue(r.body().has("error"), "case " + i);
        }
    }

    @Test
    void concurrentBookingAndCancel() throws Exception {
        reset(2);
        int n = 40;
        ExecutorService pool = Executors.newFixedThreadPool(n);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<Integer>> futures = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            String email = "p" + i + "@example.com";
            futures.add(pool.submit(() -> {
                go.await();
                return book("s1", email, Map.of()).status();
            }));
        }
        go.countDown();
        int created = 0, conflicts = 0;
        for (Future<Integer> f : futures) {
            int c = f.get();
            if (c == 201) created++;
            if (c == 409) conflicts++;
        }
        pool.shutdown();
        assertEquals(1, created);
        assertEquals(n - 1, conflicts);

        String id = book("s2", "c@example.com", Map.of()).body().get("id").asText();
        Resp c = call("DELETE", "/bookings/" + id, null);
        assertEquals(200, c.status());
        assertEquals("cancelled", c.body().get("status").asText());
        assertEquals(409, call("DELETE", "/bookings/" + id, null).status());
        assertEquals(404, call("DELETE", "/bookings/nope", null).status());
        assertEquals(201, book("s2", "d@example.com", Map.of()).status());
    }

    @Test
    void idempotency() throws Exception {
        reset(3);
        Map<String, String> h = Map.of("Idempotency-Key", "k1");
        Resp a = book("s1", "i@example.com", h);
        Resp b = book("s1", "i@example.com", h);
        assertEquals(201, a.status());
        assertEquals(201, b.status());
        assertEquals(a.body().get("id"), b.body().get("id"));
        assertEquals(422, book("s2", "i@example.com", h).status());
        assertEquals(1, call("GET", "/bookings?email=i@example.com", null).body().get("bookings").size());

        ExecutorService pool = Executors.newFixedThreadPool(10);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<String>> futures = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            futures.add(pool.submit(() -> {
                go.await();
                return book("s3", "j@example.com", Map.of("Idempotency-Key", "k2")).body().path("id").asText(null);
            }));
        }
        go.countDown();
        Set<String> ids = new HashSet<>();
        for (Future<String> f : futures) {
            String id = f.get();
            assertNotNull(id);
            ids.add(id);
        }
        pool.shutdown();
        assertEquals(1, ids.size());
    }

    @Test
    void pagination() throws Exception {
        reset(5);
        List<String> want = new ArrayList<>();
        for (int i = 1; i <= 5; i++) {
            want.add(book("s" + i, "Page@Example.com", Map.of()).body().get("id").asText());
        }
        List<String> got = new ArrayList<>();
        String cursor = null;
        int pages = 0;
        while (true) {
            String path = "/bookings?email=PAGE@example.com&limit=2" + (cursor == null ? "" : "&cursor=" + cursor);
            Resp r = call("GET", path, null);
            assertEquals(200, r.status(), r.raw().body());
            pages++;
            r.body().get("bookings").forEach(b -> got.add(b.get("id").asText()));
            assertTrue(r.body().has("next_cursor"), "next_cursor missing");
            JsonNode nc = r.body().get("next_cursor");
            if (nc.isNull()) break;
            cursor = nc.asText();
        }
        assertEquals(3, pages);
        assertEquals(want, got);
        for (String l : List.of("0", "51", "abc", "-1")) {
            assertEquals(400, call("GET", "/bookings?email=x@y.co&limit=" + l, null).status(), "limit=" + l);
        }
    }

    @Test
    void rateLimit() throws Exception {
        reset(2);
        for (int i = 0; i < 5; i++) {
            assertEquals(404, book("missing", "spam@example.com", Map.of()).status(), "attempt " + (i + 1));
        }
        Resp r = book("s1", "SPAM@example.com", Map.of());
        assertEquals(429, r.status());
        int ra = Integer.parseInt(r.raw().headers().firstValue("Retry-After").orElse("0"));
        assertTrue(ra >= 1 && ra <= 60, "Retry-After=" + ra);
        assertEquals(201, book("s1", "other@example.com", Map.of()).status());
    }

    @Test
    void jsonErrorsForUnknownRoutesAndMethods() throws Exception {
        Resp r = call("GET", "/nope", null);
        assertEquals(404, r.status());
        assertTrue(r.body().has("error"));
        r = call("PUT", "/slots", null);
        assertEquals(405, r.status());
        assertTrue(r.body().has("error"));
    }
}
