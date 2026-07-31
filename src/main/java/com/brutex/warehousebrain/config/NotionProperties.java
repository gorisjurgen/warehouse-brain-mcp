package com.brutex.warehousebrain.config;

import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * Notion connection and Warehouse Brain workspace configuration.
 *
 * <p>{@code archivesDataSourceId} and {@code templateId} may be blank; tools that
 * depend on them return a configuration error instead of failing at startup.
 */
@ConfigurationProperties(prefix = "notion")
@Validated
public record NotionProperties(
        @NotBlank(message = "NOTION_API_KEY environment variable must be set") String apiKey,
        @DefaultValue("https://api.notion.com/v1") @NotBlank String baseUrl,
        @DefaultValue("2026-03-11") @NotBlank String version,
        @NotBlank String projectsDataSourceId,
        String archivesDataSourceId,
        String templateId,
        @DefaultValue("Jira") @NotBlank String jiraPropertyName,
        @DefaultValue("rich_text") @NotBlank String jiraPropertyType,
        @DefaultValue("Started") @NotBlank String startedPropertyName) {

    public boolean hasArchives() {
        return archivesDataSourceId != null && !archivesDataSourceId.isBlank();
    }

    public boolean hasTemplate() {
        return templateId != null && !templateId.isBlank();
    }
}
