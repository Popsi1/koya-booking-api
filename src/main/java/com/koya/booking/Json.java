package com.koya.booking;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

/** JSON helpers. Responses are serialised here so key order and content type are always explicit. */
final class Json {

    static final ObjectMapper MAPPER = new ObjectMapper()
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    private Json() {}

    static String write(Object body) {
        try {
            return MAPPER.writeValueAsString(body);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    static ResponseEntity<String> raw(int status, String body) {
        return ResponseEntity.status(status).contentType(MediaType.APPLICATION_JSON).body(body);
    }

    static ResponseEntity<String> respond(int status, Object body) {
        return raw(status, write(body));
    }

    static Map<String, Object> error(String message) {
        return Map.of("error", message);
    }

    static ResponseEntity<String> error(int status, String message) {
        return respond(status, error(message));
    }

    static String time(Instant t) {
        return t.toString(); // RFC 3339 in UTC, e.g. 2026-12-10T09:00:00Z
    }

    static Map<String, Object> booking(Store.Booking b) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", b.id());
        m.put("slot_id", b.slotId());
        m.put("name", b.name());
        m.put("email", b.email());
        m.put("status", b.status());
        m.put("created_at", time(b.createdAt()));
        return m;
    }

    static Map<String, Object> slot(Store.SlotAvailability sa) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", sa.slot().id());
        m.put("provider", sa.slot().provider());
        m.put("start", time(sa.slot().start()));
        m.put("end", time(sa.slot().end()));
        m.put("available", sa.available());
        return m;
    }
}
