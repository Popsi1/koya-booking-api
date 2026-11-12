package com.koya.booking;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Requires X-API-Key on the spec routes. The web page's copy of the API under /web/api, the page itself
 * and /healthz are open, so the key never has to reach a browser.
 */
@Component
public class ApiKeyFilter extends OncePerRequestFilter {

    private final byte[] apiKey;

    public ApiKeyFilter(@Value("${app.api-key}") String apiKey) {
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException("API_KEY must be set");
        }
        this.apiKey = apiKey.getBytes(StandardCharsets.UTF_8);
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String p = request.getRequestURI();
        return !(p.equals("/reset") || p.equals("/slots") || p.equals("/bookings") || p.startsWith("/bookings/"));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        String got = req.getHeader("X-API-Key");
        // MessageDigest.isEqual is constant-time.
        if (got == null || got.isEmpty() || !MessageDigest.isEqual(got.getBytes(StandardCharsets.UTF_8), apiKey)) {
            res.setStatus(401);
            res.setContentType("application/json");
            res.getWriter().write("{\"error\":\"missing or invalid API key\"}");
            return;
        }
        chain.doFilter(req, res);
    }
}
