package com.koya.booking;

import jakarta.annotation.PostConstruct;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Seeds demo slots on an empty database before the web server starts. The schema itself is created
 * earlier by Spring's SQL init from schema.sql (see application.properties).
 */
@Component
public class DatabaseInitializer {

    private final Store store;

    public DatabaseInitializer(Store store) {
        this.store = store;
    }

    @PostConstruct
    void init() {
        store.seedIfEmpty(demoSlots(Instant.now()));
    }

    /** Gives the web page something to show before the first /reset. */
    static List<Store.Slot> demoSlots(Instant now) {
        String[] providers = {"Dr Okafor", "Dr Mensah", "Dr Silva"};
        LocalDate today = now.atZone(ZoneOffset.UTC).toLocalDate();
        DateTimeFormatter fmt = DateTimeFormatter.BASIC_ISO_DATE;
        List<Store.Slot> out = new ArrayList<>();
        for (int d = 1; d <= 3; d++) {
            LocalDate date = today.plusDays(d);
            for (int p = 0; p < providers.length; p++) {
                for (int h = 0; h < 4; h++) {
                    Instant start = date.atStartOfDay(ZoneOffset.UTC).toInstant()
                            .plusSeconds((9L + p) * 3600 + h * 1800L);
                    out.add(new Store.Slot("demo-%s-%d-%d".formatted(date.format(fmt), p + 1, h + 1),
                            providers[p], start, start.plusSeconds(1800)));
                }
            }
        }
        return out;
    }
}
