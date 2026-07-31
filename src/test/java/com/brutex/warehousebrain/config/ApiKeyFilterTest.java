package com.brutex.warehousebrain.config;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.servlet.ServletException;
import java.io.IOException;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class ApiKeyFilterTest {

    private static final String KEY = "s3cret";

    private MockHttpServletResponse filter(String apiKey, MockHttpServletRequest request)
            throws ServletException, IOException {
        ApiKeyFilter filter = new ApiKeyFilter(new McpAuthProperties(apiKey));
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, new MockFilterChain());
        return response;
    }

    @Test
    void missingKeyIsRejected() throws Exception {
        MockHttpServletResponse response = filter(KEY, new MockHttpServletRequest("POST", "/mcp"));

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getHeader("WWW-Authenticate")).isEqualTo("Bearer");
        assertThat(response.getContentAsString()).contains("unauthorized");
    }

    @Test
    void wrongKeyIsRejected() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/mcp");
        request.addHeader("Authorization", "Bearer nope");

        assertThat(filter(KEY, request).getStatus()).isEqualTo(401);
    }

    @Test
    void bearerKeyIsAccepted() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/mcp");
        request.addHeader("Authorization", "Bearer " + KEY);

        assertThat(filter(KEY, request).getStatus()).isEqualTo(200);
    }

    @Test
    void xApiKeyHeaderIsAccepted() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/mcp");
        request.addHeader("X-API-Key", KEY);

        assertThat(filter(KEY, request).getStatus()).isEqualTo(200);
    }

    @Test
    void blankConfigDisablesAuth() throws Exception {
        assertThat(filter("", new MockHttpServletRequest("POST", "/mcp")).getStatus()).isEqualTo(200);
        assertThat(filter(null, new MockHttpServletRequest("POST", "/mcp")).getStatus()).isEqualTo(200);
    }
}
