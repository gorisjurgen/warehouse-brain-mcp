package com.brutex.warehousebrain.notion;

import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Thin wrapper over the Notion REST API (data-source model, 2025-09-03+).
 * Payloads stay as Jackson trees; callers extract what they need.
 */
@Component
public class NotionClient {

    private final RestClient rest;
    private final ObjectMapper mapper;

    public NotionClient(RestClient notionRestClient, ObjectMapper mapper) {
        this.rest = notionRestClient;
        this.mapper = mapper;
    }

    /**
     * POST /data_sources/{id}/query — returns all result pages, following
     * {@code has_more}/{@code next_cursor} pagination.
     */
    public List<JsonNode> queryDataSource(String dataSourceId, ObjectNode query) {
        List<JsonNode> results = new ArrayList<>();
        ObjectNode body = query != null ? query.deepCopy() : mapper.createObjectNode();
        while (true) {
            JsonNode page = rest.post()
                    .uri("/data_sources/{id}/query", dataSourceId)
                    .body(body)
                    .retrieve()
                    .body(JsonNode.class);
            if (page == null) {
                break;
            }
            page.path("results").forEach(results::add);
            if (!page.path("has_more").asBoolean(false)) {
                break;
            }
            body.put("start_cursor", page.path("next_cursor").asText());
        }
        return results;
    }

    /** POST /pages — body must contain parent, properties and optionally template. */
    public JsonNode createPage(ObjectNode body) {
        return rest.post()
                .uri("/pages")
                .body(body)
                .retrieve()
                .body(JsonNode.class);
    }

    /** POST /pages/{id}/move — re-parents a page to another data source. */
    public JsonNode movePage(String pageId, String targetDataSourceId) {
        ObjectNode body = mapper.createObjectNode();
        body.putObject("parent")
                .put("type", "data_source_id")
                .put("data_source_id", targetDataSourceId);
        return rest.post()
                .uri("/pages/{id}/move", pageId)
                .body(body)
                .retrieve()
                .body(JsonNode.class);
    }

    /** GET /data_sources/{id} — schema lookup, useful for property-type discovery. */
    public JsonNode retrieveDataSource(String dataSourceId) {
        return rest.get()
                .uri("/data_sources/{id}", dataSourceId)
                .retrieve()
                .body(JsonNode.class);
    }
}
