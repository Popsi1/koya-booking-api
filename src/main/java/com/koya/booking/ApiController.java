package com.koya.booking;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/**
 * The booking API. Every route is mapped twice: at the spec path (X-API-Key required, see ApiKeyFilter)
 * and under /web/api for the bundled web page.
 */
@RestController
public class ApiController {

    private static final int RATE_LIMIT_ATTEMPTS = 5;
    private static final Duration RATE_LIMIT_WINDOW = Duration.ofMinutes(1);
    private static final int DEFAULT_PAGE_SIZE = 10;
    private static final int MAX_PAGE_SIZE = 50;
    private static final Pattern EMAIL = Pattern.compile("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$");

    private final Store store;
    private final TransactionTemplate tx;

    public ApiController(Store store, TransactionTemplate tx) {
        this.store = store;
        this.tx = tx;
    }

    // ---------- helpers ----------

    private static JsonNode parseObject(String body) {
        if (body == null || body.isBlank()) {
            throw new ApiException(400, "invalid JSON body");
        }
        try {
            JsonNode n = Json.MAPPER.readTree(body);
            if (n == null || !n.isObject()) {
                throw new ApiException(400, "invalid JSON body");
            }
            return n;
        } catch (JsonProcessingException e) {
            throw new ApiException(400, "invalid JSON body");
        }
    }

    /** A string field; missing or null reads as "", any other type is a 400. */
    private static String str(JsonNode obj, String field) {
        JsonNode v = obj.get(field);
        if (v == null || v.isNull()) {
            return "";
        }
        if (!v.isTextual()) {
            throw new ApiException(400, field + " must be a string");
        }
        return v.asText();
    }

    private static String sha256(String s) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    // ---------- POST /reset ----------

    @PostMapping({"/reset", "/web/api/reset"})
    public ResponseEntity<String> reset(@RequestBody(required = false) String body) {
        JsonNode req = parseObject(body);
        JsonNode arr = req.get("slots");
        if (arr == null || !arr.isArray()) {
            throw new ApiException(400, "slots is required");
        }
        List<Store.Slot> slots = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        int i = 0;
        for (JsonNode sj : arr) {
            if (!sj.isObject()) {
                throw new ApiException(400, "slot " + i + ": must be an object");
            }
            String id = str(sj, "id");
            String provider = str(sj, "provider");
            if (id.isBlank() || provider.isBlank()) {
                throw new ApiException(400, "slot " + i + ": id and provider are required");
            }
            if (!seen.add(id)) {
                throw new ApiException(400, "duplicate slot id: " + id);
            }
            Instant start, end;
            try {
                start = OffsetDateTime.parse(str(sj, "start")).toInstant();
                end = OffsetDateTime.parse(str(sj, "end")).toInstant();
            } catch (DateTimeParseException e) {
                throw new ApiException(400, "slot " + id + ": start and end must be RFC 3339 date-times");
            }
            slots.add(new Store.Slot(id, provider, start, end));
            i++;
        }
        store.reset(slots);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("status", "ok");
        out.put("slots", slots.size());
        return Json.respond(200, out);
    }

    // ---------- GET /slots ----------

    @GetMapping({"/slots", "/web/api/slots"})
    public ResponseEntity<String> listSlots(HttpServletRequest request) {
        List<Map<String, Object>> out = store.listSlots(request.getParameter("provider"))
                .stream().map(Json::slot).toList();
        return Json.respond(200, Map.of("slots", out));
    }

    // ---------- POST /bookings ----------

    private record Outcome(int status, Object body) {
        static Outcome fail(int status, String msg) {
            return new Outcome(status, Json.error(msg));
        }
    }

    @PostMapping({"/bookings", "/web/api/bookings"})
    public ResponseEntity<String> createBooking(@RequestBody(required = false) String body,
                                                @RequestHeader(value = "Idempotency-Key", required = false) String idemKey) {
        JsonNode raw = parseObject(body);
        String rawSlot = str(raw, "slot_id"), rawName = str(raw, "name"), rawEmail = str(raw, "email");

        // The fingerprint covers the body as sent, before normalisation.
        Map<String, String> fp = new LinkedHashMap<>();
        fp.put("slot_id", rawSlot);
        fp.put("name", rawName);
        fp.put("email", rawEmail);
        String reqHash = sha256(Json.write(fp));

        String slotId = rawSlot.trim();
        String name = rawName.trim();
        String email = rawEmail.trim().toLowerCase(Locale.ROOT);
        boolean hasKey = idemKey != null && !idemKey.isEmpty();

        // One transaction per request: idempotency lock, rate-limit count, the booking and the stored
        // response all commit together. It commits even for 4xx outcomes, because failed attempts count
        // toward the rate limit. Unexpected errors roll everything back.
        return tx.execute(status -> {
            if (hasKey) {
                Optional<Store.IdempotencyRecord> rec = store.lockIdempotencyKey(idemKey);
                if (rec.isPresent()) {
                    if (!rec.get().requestHash().equals(reqHash)) {
                        return Json.error(422, "Idempotency-Key was already used with a different request body");
                    }
                    return ResponseEntity.status(rec.get().statusCode())
                            .header("Content-Type", "application/json")
                            .header("Idempotent-Replayed", "true")
                            .body(rec.get().body());
                }
            }

            if (!email.isEmpty()) {
                Store.RateResult rl = store.registerAttempt(email, RATE_LIMIT_ATTEMPTS, RATE_LIMIT_WINDOW);
                if (!rl.allowed()) {
                    long secs = Math.max(1, (rl.retryAfter().toMillis() + 999) / 1000);
                    return ResponseEntity.status(429)
                            .header("Content-Type", "application/json")
                            .header("Retry-After", Long.toString(secs))
                            .body(Json.write(Json.error("too many booking attempts for this email, try again later")));
                }
            }

            Outcome res = book(slotId, name, email);
            String json = Json.write(res.body());
            if (hasKey) {
                store.saveIdempotencyKey(idemKey, new Store.IdempotencyRecord(reqHash, res.status(), json));
            }
            return Json.raw(res.status(), json);
        });
    }

    /** Validates and creates the booking. Business failures come back as an Outcome. */
    private Outcome book(String slotId, String name, String email) {
        if (slotId.isEmpty()) return Outcome.fail(400, "slot_id is required");
        if (name.isEmpty()) return Outcome.fail(400, "name is required");
        if (email.isEmpty()) return Outcome.fail(400, "email is required");
        if (!EMAIL.matcher(email).matches()) return Outcome.fail(400, "email is not valid");

        Optional<Store.Slot> slot = store.lockSlot(slotId);
        if (slot.isEmpty()) return Outcome.fail(404, "slot not found");
        if (!Instant.now().isBefore(slot.get().start())) return Outcome.fail(400, "slot has already started");

        return store.insertBooking(slotId, name, email)
                .map(b -> new Outcome(201, Json.booking(b)))
                .orElseGet(() -> Outcome.fail(409, "slot already booked"));
    }

    // ---------- GET /bookings ----------

    private static String encodeCursor(long seq) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(Long.toString(seq).getBytes(StandardCharsets.UTF_8));
    }

    private static long decodeCursor(String c) {
        try {
            long n = Long.parseLong(new String(Base64.getUrlDecoder().decode(c), StandardCharsets.UTF_8));
            if (n < 0) throw new NumberFormatException();
            return n;
        } catch (IllegalArgumentException e) { // includes NumberFormatException
            throw new ApiException(400, "invalid cursor");
        }
    }

    @GetMapping({"/bookings", "/web/api/bookings"})
    public ResponseEntity<String> listBookings(HttpServletRequest request) {
        String emailParam = request.getParameter("email");
        String email = emailParam == null ? "" : emailParam.trim().toLowerCase(Locale.ROOT);
        if (email.isEmpty()) {
            throw new ApiException(400, "email is required");
        }
        int limit = DEFAULT_PAGE_SIZE;
        String limitParam = request.getParameter("limit");
        if (limitParam != null) {
            try {
                limit = Integer.parseInt(limitParam);
            } catch (NumberFormatException e) {
                limit = -1;
            }
            if (limit < 1 || limit > MAX_PAGE_SIZE) {
                throw new ApiException(400, "limit must be an integer from 1 to 50");
            }
        }
        String cursor = request.getParameter("cursor");
        long after = cursor == null || cursor.isEmpty() ? 0 : decodeCursor(cursor);

        List<Store.Booking> list = store.listBookingsByEmail(email, after, limit);
        boolean more = list.size() > limit;
        if (more) {
            list = list.subList(0, limit);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("bookings", list.stream().map(Json::booking).toList());
        out.put("next_cursor", more ? encodeCursor(list.get(list.size() - 1).seq()) : null);
        return Json.respond(200, out);
    }

    // ---------- GET/DELETE /bookings/{id} ----------

    @GetMapping({"/bookings/{id}", "/web/api/bookings/{id}"})
    public ResponseEntity<String> getBooking(@PathVariable String id) {
        return store.getBooking(id)
                .map(b -> Json.respond(200, Json.booking(b)))
                .orElseGet(() -> Json.error(404, "booking not found"));
    }

    @DeleteMapping({"/bookings/{id}", "/web/api/bookings/{id}"})
    public ResponseEntity<String> cancelBooking(@PathVariable String id) {
        try {
            return Json.respond(200, Json.booking(store.cancelBooking(id)));
        } catch (Store.NotFoundException e) {
            return Json.error(404, "booking not found");
        } catch (Store.AlreadyCancelledException e) {
            return Json.error(409, "booking already cancelled");
        }
    }

    // ---------- health ----------

    @GetMapping("/healthz")
    public ResponseEntity<String> health() {
        try {
            store.ping();
            return Json.respond(200, Map.of("status", "ok"));
        } catch (RuntimeException e) {
            return Json.error(503, "database unavailable");
        }
    }
}
