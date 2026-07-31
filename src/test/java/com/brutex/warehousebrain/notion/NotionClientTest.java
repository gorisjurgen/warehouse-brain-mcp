package com.brutex.warehousebrain.notion;

import java.util.List;

import com.brutex.warehousebrain.config.NotionClientConfig;
import com.brutex.warehousebrain.config.NotionProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class NotionClientTest {

    private static final String BASE = "https://notion.test/v1";

    private final ObjectMapper mapper = new ObjectMapper();
    private MockRestServiceServer server;
    private NotionClient client;

    @BeforeEach
    void setUp() {
        NotionProperties props = new NotionProperties(
                "secret-token", BASE, "2026-03-11",
                "ds-projects", "ds-archives", "tpl-1",
                "Jira", "rich_text", "Started");
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        RestClient rest = new NotionClientConfig().notionRestClient(props, builder, mapper);
        client = new NotionClient(rest, mapper);
    }

    @Test
    void queryDataSourceSendsAuthAndVersionHeaders() {
        server.expect(requestTo(BASE + "/data_sources/ds-projects/query"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization", "Bearer secret-token"))
                .andExpect(header("Notion-Version", "2026-03-11"))
                .andRespond(withSuccess("{\"results\":[],\"has_more\":false}", MediaType.APPLICATION_JSON));

        List<JsonNode> results = client.queryDataSource("ds-projects", null);

        assertThat(results).isEmpty();
        server.verify();
    }

    @Test
    void queryDataSourceFollowsPagination() {
        server.expect(requestTo(BASE + "/data_sources/ds-projects/query"))
                .andExpect(jsonPath("$.start_cursor").doesNotExist())
                .andRespond(withSuccess(
                        "{\"results\":[{\"id\":\"page-1\"}],\"has_more\":true,\"next_cursor\":\"cur-2\"}",
                        MediaType.APPLICATION_JSON));
        server.expect(requestTo(BASE + "/data_sources/ds-projects/query"))
                .andExpect(jsonPath("$.start_cursor").value("cur-2"))
                .andRespond(withSuccess(
                        "{\"results\":[{\"id\":\"page-2\"}],\"has_more\":false}",
                        MediaType.APPLICATION_JSON));

        List<JsonNode> results = client.queryDataSource("ds-projects", mapper.createObjectNode());

        assertThat(results).extracting(n -> n.path("id").asText()).containsExactly("page-1", "page-2");
        server.verify();
    }

    @Test
    void createPagePostsBodyVerbatim() {
        server.expect(requestTo(BASE + "/pages"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(jsonPath("$.parent.data_source_id").value("ds-projects"))
                .andRespond(withSuccess("{\"id\":\"new-page\"}", MediaType.APPLICATION_JSON));

        ObjectNode body = mapper.createObjectNode();
        body.putObject("parent").put("type", "data_source_id").put("data_source_id", "ds-projects");
        JsonNode page = client.createPage(body);

        assertThat(page.path("id").asText()).isEqualTo("new-page");
        server.verify();
    }

    @Test
    void movePageTargetsDataSourceParent() {
        server.expect(requestTo(BASE + "/pages/page-1/move"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().json(
                        "{\"parent\":{\"type\":\"data_source_id\",\"data_source_id\":\"ds-archives\"}}"))
                .andRespond(withSuccess("{\"id\":\"page-1\"}", MediaType.APPLICATION_JSON));

        client.movePage("page-1", "ds-archives");

        server.verify();
    }

    @Test
    void notionErrorBodyBecomesNotionException() {
        server.expect(requestTo(BASE + "/data_sources/ds-projects/query"))
                .andRespond(withStatus(org.springframework.http.HttpStatus.BAD_REQUEST)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"object\":\"error\",\"code\":\"validation_error\",\"message\":\"bad filter\"}"));

        assertThatThrownBy(() -> client.queryDataSource("ds-projects", null))
                .isInstanceOf(NotionException.class)
                .hasMessageContaining("validation_error")
                .hasMessageContaining("bad filter");
    }
}
