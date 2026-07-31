package com.brutex.warehousebrain.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Rejects requests that do not carry the configured API key as
 * {@code Authorization: Bearer <key>} or {@code X-API-Key: <key>}.
 */
@Component
public class ApiKeyFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(ApiKeyFilter.class);
    private static final String BEARER_PREFIX = "Bearer ";

    private final McpAuthProperties properties;

    public ApiKeyFilter(McpAuthProperties properties) {
        this.properties = properties;
        if (!properties.enabled()) {
            log.warn("MCP_API_KEY is not set - API-key auth is DISABLED, all requests are accepted");
        }
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {
        if (!properties.enabled() || matchesKey(request)) {
            filterChain.doFilter(request, response);
            return;
        }
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setHeader(HttpHeaders.WWW_AUTHENTICATE, "Bearer");
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.getWriter().write("{\"error\":\"unauthorized\"}");
    }

    private boolean matchesKey(HttpServletRequest request) {
        String candidate = null;
        String authorization = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (authorization != null && authorization.startsWith(BEARER_PREFIX)) {
            candidate = authorization.substring(BEARER_PREFIX.length());
        } else if (request.getHeader("X-API-Key") != null) {
            candidate = request.getHeader("X-API-Key");
        }
        return candidate != null && MessageDigest.isEqual(
                candidate.getBytes(StandardCharsets.UTF_8),
                properties.apiKey().getBytes(StandardCharsets.UTF_8));
    }
}
