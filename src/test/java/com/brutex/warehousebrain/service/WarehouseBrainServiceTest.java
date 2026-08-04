package com.brutex.warehousebrain.service;

import java.time.LocalDate;
import java.util.List;

import com.brutex.warehousebrain.config.NotionProperties;
import com.brutex.warehousebrain.notion.NotionClient;
import com.brutex.warehousebrain.notion.NotionMarkdown;
import com.brutex.warehousebrain.service.WarehouseBrainService.ContentResult;
import com.brutex.warehousebrain.service.WarehouseBrainService.ToolResult;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WarehouseBrainServiceTest {

    private static final String PROJECTS_DS = "ds-projects";
    private static final String ARCHIVES_DS = "ds-archives";

    private final ObjectMapper mapper = new ObjectMapper();

    @Mock
    private NotionClient notion;

    private WarehouseBrainService service(String archivesDsId, String templateId) {
        NotionProperties props = new NotionProperties(
                "token", "https://notion.test/v1", "2026-03-11",
                PROJECTS_DS, archivesDsId, templateId,
                "Jira", "rich_text", "Started",
                "ticket", "State", "Not started");
        return new WarehouseBrainService(notion, props, mapper, new NotionMarkdown(mapper));
    }

    private JsonNode textBlock(String id, String type, String text) {
        try {
            return mapper.readTree("""
                    {"id": "%s", "type": "%s", "%s": {"rich_text": [{"plain_text": "%s"}]}}
                    """.formatted(id, type, type, text));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    private JsonNode page(String id, String title, String jira) {
        try {
            return mapper.readTree("""
                    {
                      "id": "%s",
                      "url": "https://notion.so/%s",
                      "properties": {
                        "Name": {"type": "title", "title": [{"plain_text": "%s"}]},
                        "Jira": {"type": "rich_text", "rich_text": [{"plain_text": "%s"}]},
                        "Started": {"type": "date", "date": {"start": "2026-07-01"}}
                      }
                    }
                    """.formatted(id, id, title, jira));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void findProjectSearchesProjectsAndArchives() {
        when(notion.queryDataSource(eq(PROJECTS_DS), any())).thenReturn(List.of());
        when(notion.queryDataSource(eq(ARCHIVES_DS), any()))
                .thenReturn(List.of(page("p1", "Old project", "WMS-1")));

        ToolResult result = service(ARCHIVES_DS, null).findProject("WMS-1");

        assertThat(result.status()).isEqualTo("ok");
        assertThat(result.projects()).hasSize(1);
        assertThat(result.projects().get(0).location()).isEqualTo("Archives");
        assertThat(result.projects().get(0).title()).isEqualTo("Old project");
        assertThat(result.projects().get(0).started()).isEqualTo("2026-07-01");
    }

    @Test
    void findProjectUsesJiraContainsFilter() {
        when(notion.queryDataSource(eq(PROJECTS_DS), any())).thenReturn(List.of());

        service(null, null).findProject("WMS-1");

        ArgumentCaptor<ObjectNode> query = ArgumentCaptor.forClass(ObjectNode.class);
        verify(notion).queryDataSource(eq(PROJECTS_DS), query.capture());
        assertThat(query.getValue().at("/filter/property").asText()).isEqualTo("Jira");
        assertThat(query.getValue().at("/filter/rich_text/contains").asText()).isEqualTo("WMS-1");
    }

    @Test
    void findProjectReportsNotFound() {
        when(notion.queryDataSource(any(), any())).thenReturn(List.of());

        ToolResult result = service(ARCHIVES_DS, null).findProject("WMS-404");

        assertThat(result.status()).isEqualTo("not_found");
    }

    @Test
    void createProjectRefusesDuplicates() {
        when(notion.queryDataSource(eq(PROJECTS_DS), any()))
                .thenReturn(List.of(page("p1", "Existing", "WMS-1")));

        ToolResult result = service(null, "tpl-1").createProject("WMS-1", "New project", null);

        assertThat(result.status()).isEqualTo("error");
        assertThat(result.message()).contains("already exists");
        verify(notion, never()).createPage(any());
    }

    @Test
    void createProjectBuildsTemplatePageWithProperties() {
        when(notion.queryDataSource(eq(PROJECTS_DS), any())).thenReturn(List.of());
        when(notion.createPage(any())).thenReturn(page("new-1", "New project", "WMS-2"));

        ToolResult result = service(null, "tpl-1")
                .createProject("WMS-2", "New project", "https://jira.test/browse/WMS-2");

        assertThat(result.status()).isEqualTo("ok");
        ArgumentCaptor<ObjectNode> body = ArgumentCaptor.forClass(ObjectNode.class);
        verify(notion).createPage(body.capture());
        JsonNode sent = body.getValue();
        assertThat(sent.at("/parent/data_source_id").asText()).isEqualTo(PROJECTS_DS);
        assertThat(sent.at("/template/template_id").asText()).isEqualTo("tpl-1");
        assertThat(sent.at("/properties/title/title/0/text/content").asText()).isEqualTo("New project");
        assertThat(sent.at("/properties/Jira/rich_text/0/text/content").asText()).isEqualTo("WMS-2");
        assertThat(sent.at("/properties/Started/date/start").asText()).isEqualTo(LocalDate.now().toString());
        assertThat(sent.at("/properties/ticket/url").asText()).isEqualTo("https://jira.test/browse/WMS-2");
        assertThat(sent.at("/properties/State/status/name").asText()).isEqualTo("Not started");
    }

    @Test
    void createProjectOmitsTicketUrlWhenNotGiven() {
        when(notion.queryDataSource(eq(PROJECTS_DS), any())).thenReturn(List.of());
        when(notion.createPage(any())).thenReturn(page("new-1", "New project", "WMS-2"));

        service(null, null).createProject("WMS-2", "New project", null);

        ArgumentCaptor<ObjectNode> body = ArgumentCaptor.forClass(ObjectNode.class);
        verify(notion).createPage(body.capture());
        assertThat(body.getValue().at("/properties/ticket").isMissingNode()).isTrue();
        assertThat(body.getValue().at("/properties/State/status/name").asText()).isEqualTo("Not started");
    }

    @Test
    void archiveProjectRequiresArchivesConfiguration() {
        ToolResult result = service(null, null).archiveProject("WMS-1");

        assertThat(result.status()).isEqualTo("error");
        assertThat(result.message()).contains("NOTION_ARCHIVES_DS_ID");
        verify(notion, never()).movePage(any(), any());
    }

    @Test
    void archiveProjectMovesPageToArchives() {
        when(notion.queryDataSource(eq(PROJECTS_DS), any()))
                .thenReturn(List.of(page("p1", "Done project", "WMS-1")));

        ToolResult result = service(ARCHIVES_DS, null).archiveProject("WMS-1");

        assertThat(result.status()).isEqualTo("ok");
        assertThat(result.projects().get(0).location()).isEqualTo("Archives");
        verify(notion).movePage("p1", ARCHIVES_DS);
    }

    @Test
    void getProjectContentRendersBlocksAsMarkdown() {
        when(notion.queryDataSource(eq(PROJECTS_DS), any()))
                .thenReturn(List.of(page("p1", "My project", "WMS-1")));
        when(notion.listBlockChildren("p1")).thenReturn(List.of(
                textBlock("b1", "heading_1", "Notes"),
                textBlock("b2", "paragraph", "Hello")));

        ContentResult result = service(null, null).getProjectContent("WMS-1");

        assertThat(result.status()).isEqualTo("ok");
        assertThat(result.content()).isEqualTo("# Notes\nHello");
        assertThat(result.project().title()).isEqualTo("My project");
    }

    @Test
    void getProjectContentReportsNotFound() {
        when(notion.queryDataSource(any(), any())).thenReturn(List.of());

        ContentResult result = service(ARCHIVES_DS, null).getProjectContent("WMS-404");

        assertThat(result.status()).isEqualTo("not_found");
    }

    @Test
    void updateProjectContentAppendsParsedBlocks() {
        when(notion.queryDataSource(eq(PROJECTS_DS), any()))
                .thenReturn(List.of(page("p1", "My project", "WMS-1")));

        ContentResult result = service(null, null)
                .updateProjectContent("WMS-1", "# Update\nnew text", "append", null);

        assertThat(result.status()).isEqualTo("ok");
        ArgumentCaptor<ArrayNode> children = ArgumentCaptor.forClass(ArrayNode.class);
        verify(notion).appendBlockChildren(eq("p1"), children.capture(), isNull());
        verify(notion, never()).deleteBlock(any());
        assertThat(children.getValue()).extracting(b -> b.path("type").asText())
                .containsExactly("heading_1", "paragraph");
    }

    @Test
    void updateProjectContentInsertsAfterHeadingAndItsDivider() {
        when(notion.queryDataSource(eq(PROJECTS_DS), any()))
                .thenReturn(List.of(page("p1", "My project", "WMS-1")));
        when(notion.listBlockChildren("p1")).thenReturn(List.of(
                textBlock("h1", "heading_1", "Project Description"),
                textBlock("d1", "divider", ""),
                textBlock("h2", "heading_1", "Tags")));
        when(notion.appendBlockChildren(eq("p1"), any(), eq("d1")))
                .thenReturn(mapper.createObjectNode().set("results", mapper.createArrayNode()));

        ContentResult result = service(null, null)
                .updateProjectContent("WMS-1", "from Jira", "append", "Project Description");

        assertThat(result.status()).isEqualTo("ok");
        verify(notion).appendBlockChildren(eq("p1"), any(), eq("d1"));
    }

    @Test
    void updateProjectContentReportsMissingHeading() {
        when(notion.queryDataSource(eq(PROJECTS_DS), any()))
                .thenReturn(List.of(page("p1", "My project", "WMS-1")));
        when(notion.listBlockChildren("p1")).thenReturn(List.of(
                textBlock("h1", "heading_1", "Tags")));

        ContentResult result = service(null, null)
                .updateProjectContent("WMS-1", "text", "append", "Project Description");

        assertThat(result.status()).isEqualTo("error");
        assertThat(result.message()).contains("Project Description").contains("Tags");
        verify(notion, never()).appendBlockChildren(any(), any(), any());
    }

    @Test
    void updateProjectContentRejectsAfterHeadingWithReplace() {
        ContentResult result = service(null, null)
                .updateProjectContent("WMS-1", "text", "replace", "Project Description");

        assertThat(result.status()).isEqualTo("error");
        assertThat(result.message()).contains("afterHeading");
    }

    @Test
    void updateProjectContentReplaceDeletesExistingBlocksFirst() {
        when(notion.queryDataSource(eq(PROJECTS_DS), any()))
                .thenReturn(List.of(page("p1", "My project", "WMS-1")));
        when(notion.listBlockChildren("p1")).thenReturn(List.of(
                textBlock("b1", "paragraph", "old"),
                textBlock("b2", "paragraph", "content")));

        ContentResult result = service(null, null)
                .updateProjectContent("WMS-1", "fresh", "replace", null);

        assertThat(result.status()).isEqualTo("ok");
        verify(notion).deleteBlock("b1");
        verify(notion).deleteBlock("b2");
        verify(notion).appendBlockChildren(eq("p1"), any(), isNull());
    }

    @Test
    void updateProjectContentRejectsUnknownMode() {
        ContentResult result = service(null, null).updateProjectContent("WMS-1", "text", "prepend", null);

        assertThat(result.status()).isEqualTo("error");
        assertThat(result.message()).contains("prepend");
        verify(notion, never()).appendBlockChildren(any(), any(), any());
    }

    @Test
    void updateProjectContentRefusesAmbiguousMatches() {
        when(notion.queryDataSource(eq(PROJECTS_DS), any()))
                .thenReturn(List.of(page("p1", "One", "WMS-1"), page("p2", "Two", "WMS-1")));

        ContentResult result = service(null, null).updateProjectContent("WMS-1", "text", "append", null);

        assertThat(result.status()).isEqualTo("error");
        assertThat(result.message()).contains("Multiple projects");
        verify(notion, never()).appendBlockChildren(any(), any(), any());
    }

    @Test
    void listActiveProjectsSortsByStartedDescending() {
        when(notion.queryDataSource(eq(PROJECTS_DS), any()))
                .thenReturn(List.of(page("p1", "Project A", "WMS-1"), page("p2", "Project B", "WMS-2")));

        ToolResult result = service(null, null).listActiveProjects();

        assertThat(result.status()).isEqualTo("ok");
        assertThat(result.projects()).hasSize(2);
        ArgumentCaptor<ObjectNode> query = ArgumentCaptor.forClass(ObjectNode.class);
        verify(notion).queryDataSource(eq(PROJECTS_DS), query.capture());
        assertThat(query.getValue().at("/sorts/0/property").asText()).isEqualTo("Started");
        assertThat(query.getValue().at("/sorts/0/direction").asText()).isEqualTo("descending");
    }
}
