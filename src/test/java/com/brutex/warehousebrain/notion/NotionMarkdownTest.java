package com.brutex.warehousebrain.notion;

import java.util.List;
import java.util.Map;
import java.util.function.Function;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class NotionMarkdownTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final NotionMarkdown markdown = new NotionMarkdown(mapper);

    private static final Function<String, List<JsonNode>> NO_CHILDREN = id -> List.of();

    private JsonNode block(String json) {
        try {
            return mapper.readTree(json);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    private JsonNode textBlock(String type, String text) {
        return block("""
                {"type": "%s", "%s": {"rich_text": [{"plain_text": "%s"}]}}
                """.formatted(type, type, text));
    }

    @Test
    void rendersCommonBlockTypes() {
        List<JsonNode> blocks = List.of(
                textBlock("heading_1", "Title"),
                textBlock("paragraph", "Some text"),
                textBlock("bulleted_list_item", "a bullet"),
                textBlock("numbered_list_item", "a number"),
                block("{\"type\":\"to_do\",\"to_do\":{\"rich_text\":[{\"plain_text\":\"task\"}],\"checked\":true}}"),
                textBlock("quote", "wise words"),
                block("{\"type\":\"divider\",\"divider\":{}}"),
                block("{\"type\":\"code\",\"code\":{\"rich_text\":[{\"plain_text\":\"x = 1\"}],\"language\":\"python\"}}"));

        String md = markdown.toMarkdown(blocks, NO_CHILDREN);

        assertThat(md).isEqualTo("""
                # Title
                Some text
                - a bullet
                1. a number
                - [x] task
                > wise words
                ---
                ```python
                x = 1
                ```""");
    }

    @Test
    void rendersChildrenIndented() {
        JsonNode parent = block("""
                {"id": "b1", "type": "bulleted_list_item", "has_children": true,
                 "bulleted_list_item": {"rich_text": [{"plain_text": "parent"}]}}
                """);
        Map<String, List<JsonNode>> children =
                Map.of("b1", List.of(textBlock("bulleted_list_item", "child")));

        String md = markdown.toMarkdown(List.of(parent), children::get);

        assertThat(md).isEqualTo("- parent\n  - child");
    }

    @Test
    void marksUnsupportedBlocks() {
        String md = markdown.toMarkdown(List.of(block("{\"type\":\"table\",\"table\":{}}")), NO_CHILDREN);

        assertThat(md).isEqualTo("<!-- unsupported: table -->");
    }

    @Test
    void parsesCommonMarkdown() {
        ArrayNode blocks = markdown.toBlocks("""
                # Title
                ## Sub

                First paragraph
                still first paragraph

                - bullet
                - [ ] open task
                - [x] done task
                1. numbered
                > quoted
                ---
                ```java
                int x = 1;
                ```
                """);

        assertThat(blocks).extracting(b -> b.path("type").asText()).containsExactly(
                "heading_1", "heading_2", "paragraph", "bulleted_list_item",
                "to_do", "to_do", "numbered_list_item", "quote", "divider", "code");
        assertThat(blocks.get(0).at("/heading_1/rich_text/0/text/content").asText()).isEqualTo("Title");
        assertThat(blocks.get(2).at("/paragraph/rich_text/0/text/content").asText())
                .isEqualTo("First paragraph\nstill first paragraph");
        assertThat(blocks.get(4).at("/to_do/checked").asBoolean()).isFalse();
        assertThat(blocks.get(5).at("/to_do/checked").asBoolean()).isTrue();
        assertThat(blocks.get(9).at("/code/language").asText()).isEqualTo("java");
        assertThat(blocks.get(9).at("/code/rich_text/0/text/content").asText()).isEqualTo("int x = 1;");
    }

    @Test
    void splitsLongTextIntoChunks() {
        String longText = "a".repeat(NotionMarkdown.MAX_TEXT_LENGTH + 5);

        ArrayNode blocks = markdown.toBlocks(longText);

        JsonNode richText = blocks.get(0).at("/paragraph/rich_text");
        assertThat(richText).hasSize(2);
        assertThat(richText.at("/0/text/content").asText()).hasSize(NotionMarkdown.MAX_TEXT_LENGTH);
        assertThat(richText.at("/1/text/content").asText()).hasSize(5);
    }

    @Test
    void roundTripsSimpleDocument() {
        String original = """
                # Notes
                A paragraph
                - one
                - two""";

        ArrayNode blocks = markdown.toBlocks(original);
        // simulate Notion's response shape: text objects carry plain_text
        blocks.forEach(b -> b.path(b.path("type").asText()).path("rich_text").forEach(rt ->
                ((com.fasterxml.jackson.databind.node.ObjectNode) rt)
                        .put("plain_text", rt.at("/text/content").asText())));

        assertThat(markdown.toMarkdown(
                List.of(blocks.get(0), blocks.get(1), blocks.get(2), blocks.get(3)), NO_CHILDREN))
                .isEqualTo(original);
    }
}
