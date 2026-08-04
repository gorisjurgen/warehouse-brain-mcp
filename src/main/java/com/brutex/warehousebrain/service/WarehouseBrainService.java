package com.brutex.warehousebrain.service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import com.brutex.warehousebrain.config.NotionProperties;
import com.brutex.warehousebrain.notion.NotionClient;
import com.brutex.warehousebrain.notion.NotionException;
import com.brutex.warehousebrain.notion.NotionMarkdown;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springaicommunity.mcp.annotation.McpTool;
import org.springaicommunity.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Service;

/**
 * MCP tools for the Warehouse Brain PARA workspace: projects live in the
 * Projects data source, completed ones are moved to Archives, and every
 * project is linked to a Jira ticket through the "Jira" property.
 */
@Service
public class WarehouseBrainService {

    private final NotionClient notion;
    private final NotionProperties props;
    private final ObjectMapper mapper;
    private final NotionMarkdown markdown;

    public WarehouseBrainService(NotionClient notion, NotionProperties props, ObjectMapper mapper,
            NotionMarkdown markdown) {
        this.notion = notion;
        this.props = props;
        this.mapper = mapper;
        this.markdown = markdown;
    }

    public record ProjectInfo(String id, String url, String title, String jira, String started, String location) {}

    public record ToolResult(String status, String message, List<ProjectInfo> projects) {

        static ToolResult ok(String message, List<ProjectInfo> projects) {
            return new ToolResult("ok", message, projects);
        }

        static ToolResult notFound(String message) {
            return new ToolResult("not_found", message, List.of());
        }

        static ToolResult error(String message) {
            return new ToolResult("error", message, List.of());
        }
    }

    @McpTool(name = "findProject",
            description = "Find a Warehouse Brain project by Jira ticket key. "
                    + "Searches both the Projects and the Archives databases.")
    public ToolResult findProject(
            @McpToolParam(description = "Jira ticket key, e.g. WMS-1234", required = true) String jiraTicket) {
        try {
            List<ProjectInfo> hits = new ArrayList<>(search(props.projectsDataSourceId(), "Projects", jiraTicket));
            if (props.hasArchives()) {
                hits.addAll(search(props.archivesDataSourceId(), "Archives", jiraTicket));
            }
            if (hits.isEmpty()) {
                String scope = props.hasArchives() ? "Projects or Archives" : "Projects (Archives not configured)";
                return ToolResult.notFound("No project found for " + jiraTicket + " in " + scope + ".");
            }
            return ToolResult.ok("Found " + hits.size() + " project(s) for " + jiraTicket + ".", hits);
        } catch (NotionException e) {
            return ToolResult.error(e.getMessage());
        }
    }

    @McpTool(name = "createProject",
            description = "Create a new Warehouse Brain project page from the Warehouse Project Template. "
                    + "Refuses if a project for the Jira ticket already exists in Projects or Archives. "
                    + "Sets the Jira property, the Started date (today) and the page title. "
                    + "Template content is applied asynchronously by Notion, so the page may look empty at first.")
    public ToolResult createProject(
            @McpToolParam(description = "Jira ticket key, e.g. WMS-1234", required = true) String jiraTicket,
            @McpToolParam(description = "Project title / short description", required = true) String title) {
        try {
            ToolResult existing = findProject(jiraTicket);
            if ("ok".equals(existing.status())) {
                return ToolResult.error("A project for " + jiraTicket + " already exists: "
                        + existing.projects().get(0).url() + " (in " + existing.projects().get(0).location() + ")");
            }
            if ("error".equals(existing.status())) {
                return existing;
            }

            ObjectNode body = mapper.createObjectNode();
            body.putObject("parent")
                    .put("type", "data_source_id")
                    .put("data_source_id", props.projectsDataSourceId());
            if (props.hasTemplate()) {
                body.putObject("template")
                        .put("type", "template_id")
                        .put("template_id", props.templateId());
            }
            ObjectNode properties = body.putObject("properties");
            properties.putObject("title")
                    .putArray("title")
                    .addObject()
                    .putObject("text")
                    .put("content", title);
            ObjectNode jiraProp = properties.putObject(props.jiraPropertyName());
            if ("url".equals(props.jiraPropertyType())) {
                jiraProp.put("url", jiraTicket);
            } else {
                jiraProp.putArray("rich_text")
                        .addObject()
                        .putObject("text")
                        .put("content", jiraTicket);
            }
            properties.putObject(props.startedPropertyName())
                    .putObject("date")
                    .put("start", LocalDate.now().toString());

            JsonNode page = notion.createPage(body);
            String note = props.hasTemplate()
                    ? " Template content populates asynchronously and may take a moment to appear."
                    : " No template is configured (NOTION_TEMPLATE_ID); the page was created empty.";
            return ToolResult.ok("Created project for " + jiraTicket + "." + note,
                    List.of(toProjectInfo(page, "Projects")));
        } catch (NotionException e) {
            return ToolResult.error(e.getMessage());
        }
    }

    @McpTool(name = "archiveProject",
            description = "Archive a Warehouse Brain project: moves its page from the Projects database "
                    + "to the Archives database (PARA-style archive, the page is not deleted).")
    public ToolResult archiveProject(
            @McpToolParam(description = "Jira ticket key, e.g. WMS-1234", required = true) String jiraTicket) {
        try {
            if (!props.hasArchives()) {
                return ToolResult.error("Archives data source is not configured. "
                        + "Set NOTION_ARCHIVES_DS_ID (or notion.archives-data-source-id).");
            }
            List<ProjectInfo> hits = search(props.projectsDataSourceId(), "Projects", jiraTicket);
            if (hits.isEmpty()) {
                return ToolResult.notFound("No active project found for " + jiraTicket + " in Projects.");
            }
            if (hits.size() > 1) {
                return ToolResult.error("Multiple active projects match " + jiraTicket
                        + "; archive manually or refine the ticket key. Matches: "
                        + hits.stream().map(ProjectInfo::url).toList());
            }
            ProjectInfo project = hits.get(0);
            notion.movePage(project.id(), props.archivesDataSourceId());
            return ToolResult.ok("Archived project '" + project.title() + "' (" + jiraTicket + ").",
                    List.of(new ProjectInfo(project.id(), project.url(), project.title(),
                            project.jira(), project.started(), "Archives")));
        } catch (NotionException e) {
            return ToolResult.error(e.getMessage());
        }
    }

    @McpTool(name = "listActiveProjects",
            description = "List all active projects in the Warehouse Brain Projects database, "
                    + "most recently started first.")
    public ToolResult listActiveProjects() {
        try {
            ObjectNode query = mapper.createObjectNode();
            query.putArray("sorts")
                    .addObject()
                    .put("property", props.startedPropertyName())
                    .put("direction", "descending");
            List<ProjectInfo> projects = notion.queryDataSource(props.projectsDataSourceId(), query).stream()
                    .map(page -> toProjectInfo(page, "Projects"))
                    .toList();
            if (projects.isEmpty()) {
                return ToolResult.ok("The Projects database is empty.", projects);
            }
            return ToolResult.ok(projects.size() + " active project(s).", projects);
        } catch (NotionException e) {
            return ToolResult.error(e.getMessage());
        }
    }

    public record ContentResult(String status, String message, ProjectInfo project, String content) {

        static ContentResult ok(String message, ProjectInfo project, String content) {
            return new ContentResult("ok", message, project, content);
        }

        static ContentResult failure(String status, String message) {
            return new ContentResult(status, message, null, null);
        }
    }

    @McpTool(name = "getProjectContent",
            description = "Read the full page content (body) of a Warehouse Brain project as Markdown. "
                    + "Looks the project up by Jira ticket key in Projects and Archives.")
    public ContentResult getProjectContent(
            @McpToolParam(description = "Jira ticket key, e.g. WMS-1234", required = true) String jiraTicket) {
        try {
            Object resolved = resolveSingle(jiraTicket);
            if (resolved instanceof ContentResult failure) {
                return failure;
            }
            ProjectInfo project = (ProjectInfo) resolved;
            String content = markdown.toMarkdown(notion.listBlockChildren(project.id()),
                    notion::listBlockChildren);
            return ContentResult.ok("Content of '" + project.title() + "' (" + project.location() + ").",
                    project, content);
        } catch (NotionException e) {
            return ContentResult.failure("error", e.getMessage());
        }
    }

    @McpTool(name = "updateProjectContent",
            description = "Update the page content (body) of a Warehouse Brain project with Markdown. "
                    + "Mode 'append' adds the content at the end of the page; mode 'replace' DELETES the "
                    + "entire existing page body and writes the new content. Supported Markdown: headings "
                    + "1-3, paragraphs, bulleted/numbered lists, to-dos (- [ ]), fenced code, quotes, dividers.")
    public ContentResult updateProjectContent(
            @McpToolParam(description = "Jira ticket key, e.g. WMS-1234", required = true) String jiraTicket,
            @McpToolParam(description = "Markdown content to write", required = true) String content,
            @McpToolParam(description = "'append' (default) or 'replace'") String mode) {
        try {
            String effectiveMode = mode == null || mode.isBlank() ? "append" : mode.strip().toLowerCase();
            if (!"append".equals(effectiveMode) && !"replace".equals(effectiveMode)) {
                return ContentResult.failure("error", "Unknown mode '" + mode + "'; use 'append' or 'replace'.");
            }
            Object resolved = resolveSingle(jiraTicket);
            if (resolved instanceof ContentResult failure) {
                return failure;
            }
            ProjectInfo project = (ProjectInfo) resolved;

            if ("replace".equals(effectiveMode)) {
                for (JsonNode block : notion.listBlockChildren(project.id())) {
                    notion.deleteBlock(block.path("id").asText());
                }
            }
            ArrayNode blocks = markdown.toBlocks(content);
            for (int start = 0; start < blocks.size(); start += 100) {
                ArrayNode chunk = mapper.createArrayNode();
                for (int j = start; j < Math.min(blocks.size(), start + 100); j++) {
                    chunk.add(blocks.get(j));
                }
                notion.appendBlockChildren(project.id(), chunk);
            }
            String verb = "replace".equals(effectiveMode) ? "Replaced" : "Appended to";
            return ContentResult.ok(verb + " content of '" + project.title() + "' ("
                    + blocks.size() + " block(s) written).", project, null);
        } catch (NotionException e) {
            return ContentResult.failure("error", e.getMessage());
        }
    }

    /** Resolves a Jira ticket to exactly one project, or returns a failure ContentResult. */
    private Object resolveSingle(String jiraTicket) {
        List<ProjectInfo> hits = new ArrayList<>(search(props.projectsDataSourceId(), "Projects", jiraTicket));
        if (props.hasArchives()) {
            hits.addAll(search(props.archivesDataSourceId(), "Archives", jiraTicket));
        }
        if (hits.isEmpty()) {
            return ContentResult.failure("not_found", "No project found for " + jiraTicket + ".");
        }
        if (hits.size() > 1) {
            return ContentResult.failure("error", "Multiple projects match " + jiraTicket
                    + "; refine the ticket key. Matches: " + hits.stream().map(ProjectInfo::url).toList());
        }
        return hits.get(0);
    }

    private List<ProjectInfo> search(String dataSourceId, String location, String jiraTicket) {
        ObjectNode query = mapper.createObjectNode();
        ObjectNode filter = query.putObject("filter");
        filter.put("property", props.jiraPropertyName());
        filter.putObject(props.jiraPropertyType()).put("contains", jiraTicket);
        return notion.queryDataSource(dataSourceId, query).stream()
                .map(page -> toProjectInfo(page, location))
                .toList();
    }

    private ProjectInfo toProjectInfo(JsonNode page, String location) {
        JsonNode properties = page.path("properties");
        return new ProjectInfo(
                page.path("id").asText(),
                page.path("url").asText(),
                extractTitle(properties),
                plainText(properties.path(props.jiraPropertyName())),
                properties.path(props.startedPropertyName()).path("date").path("start").asText(null),
                location);
    }

    private String extractTitle(JsonNode properties) {
        for (JsonNode prop : properties) {
            if ("title".equals(prop.path("type").asText())) {
                return joinRichText(prop.path("title"));
            }
        }
        return "";
    }

    private String plainText(JsonNode prop) {
        return switch (prop.path("type").asText()) {
            case "title" -> joinRichText(prop.path("title"));
            case "rich_text" -> joinRichText(prop.path("rich_text"));
            case "url" -> prop.path("url").asText("");
            case "select" -> prop.path("select").path("name").asText("");
            default -> "";
        };
    }

    private String joinRichText(JsonNode richTextArray) {
        StringBuilder sb = new StringBuilder();
        richTextArray.forEach(node -> sb.append(node.path("plain_text").asText()));
        return sb.toString();
    }
}
