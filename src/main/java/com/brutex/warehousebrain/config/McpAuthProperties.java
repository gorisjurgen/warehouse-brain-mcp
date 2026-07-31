package com.brutex.warehousebrain.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * API-key protection for the MCP endpoint.
 *
 * <p>When {@code apiKey} is blank the filter is disabled and all requests pass
 * through, so local development needs no extra configuration.
 */
@ConfigurationProperties(prefix = "mcp.auth")
public record McpAuthProperties(String apiKey) {

    public boolean enabled() {
        return apiKey != null && !apiKey.isBlank();
    }
}
