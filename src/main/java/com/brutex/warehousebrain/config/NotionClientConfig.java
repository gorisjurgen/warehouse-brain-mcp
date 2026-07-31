package com.brutex.warehousebrain.config;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import com.brutex.warehousebrain.notion.NotionException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;

@Configuration
public class NotionClientConfig {

    @Bean
    public RestClient notionRestClient(NotionProperties props, RestClient.Builder builder, ObjectMapper mapper) {
        return builder
                .baseUrl(props.baseUrl())
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + props.apiKey())
                .defaultHeader("Notion-Version", props.version())
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .defaultStatusHandler(HttpStatusCode::isError, (request, response) -> {
                    String body = readBody(response);
                    String code = "unknown";
                    String message = body;
                    try {
                        JsonNode node = mapper.readTree(body);
                        code = node.path("code").asText("unknown");
                        message = node.path("message").asText(body);
                    } catch (IOException ignored) {
                        // non-JSON error body; keep the raw text as message
                    }
                    throw new NotionException(response.getStatusCode().value(), code, message);
                })
                .build();
    }

    private static String readBody(org.springframework.http.client.ClientHttpResponse response) {
        try {
            return new String(response.getBody().readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return "";
        }
    }
}
