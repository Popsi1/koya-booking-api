package com.koya.booking;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Builds the connection pool from DATABASE_URL, which on Render is a postgres:// URL, not a JDBC one. */
@Configuration
public class DatabaseConfig {

    @Bean
    public DataSource dataSource(@Value("${app.database-url}") String url) {
        if (url == null || url.isBlank()) {
            throw new IllegalStateException("DATABASE_URL must be set");
        }
        HikariConfig cfg = new HikariConfig();
        applyUrl(url.trim(), cfg);
        cfg.setMaximumPoolSize(10);
        return new HikariDataSource(cfg);
    }

    static void applyUrl(String url, HikariConfig cfg) {
        if (url.startsWith("jdbc:")) {
            cfg.setJdbcUrl(url);
            return;
        }
        URI u = URI.create(url);
        String scheme = u.getScheme();
        if (!"postgres".equals(scheme) && !"postgresql".equals(scheme)) {
            throw new IllegalStateException("DATABASE_URL must be a postgres:// or jdbc:postgresql:// URL");
        }
        int port = u.getPort() == -1 ? 5432 : u.getPort();
        String db = u.getPath() == null ? "" : u.getPath().replaceFirst("^/", "");
        String jdbc = "jdbc:postgresql://" + u.getHost() + ":" + port + "/" + db;
        if (u.getRawQuery() != null) {
            jdbc += "?" + u.getRawQuery();
        }
        cfg.setJdbcUrl(jdbc);
        String info = u.getRawUserInfo();
        if (info != null) {
            int i = info.indexOf(':');
            cfg.setUsername(decode(i < 0 ? info : info.substring(0, i)));
            if (i >= 0) {
                cfg.setPassword(decode(info.substring(i + 1)));
            }
        }
    }

    private static String decode(String s) {
        return URLDecoder.decode(s, StandardCharsets.UTF_8);
    }
}
