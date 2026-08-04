package com.brutex.warehousebrain.notion;

import java.util.List;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;

/**
 * Converts between Notion block trees and a pragmatic Markdown subset:
 * headings 1-3, paragraphs, bulleted/numbered lists, to-dos, fenced code,
 * quotes, dividers and images. Rich-text formatting (bold, links, colors)
 * is flattened to plain text on read and not produced on write; nested
 * lists are rendered with indentation on read but parsed flat on write.
 */
@Component
public class NotionMarkdown {

    /** Notion caps a single rich-text content object at 2000 characters. */
    static final int MAX_TEXT_LENGTH = 2000;

    private static final Pattern NUMBERED_ITEM = Pattern.compile("^\\d+[.)] (.*)$");

    private final ObjectMapper mapper;

    public NotionMarkdown(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    /**
     * Renders blocks as Markdown. {@code childLoader} is called for blocks
     * with {@code has_children} so the caller controls HTTP access; child
     * blocks are indented by two spaces per level.
     */
    public String toMarkdown(List<JsonNode> blocks, Function<String, List<JsonNode>> childLoader) {
        StringBuilder sb = new StringBuilder();
        render(blocks, childLoader, "", sb);
        return sb.toString().stripTrailing();
    }

    private void render(List<JsonNode> blocks, Function<String, List<JsonNode>> childLoader,
            String indent, StringBuilder sb) {
        for (JsonNode block : blocks) {
            String type = block.path("type").asText();
            String text = joinRichText(block.path(type).path("rich_text"));
            String line = switch (type) {
                case "heading_1" -> "# " + text;
                case "heading_2" -> "## " + text;
                case "heading_3" -> "### " + text;
                case "paragraph" -> text;
                case "bulleted_list_item", "toggle" -> "- " + text;
                case "numbered_list_item" -> "1. " + text;
                case "to_do" -> (block.path(type).path("checked").asBoolean(false) ? "- [x] " : "- [ ] ") + text;
                case "quote" -> "> " + text;
                case "divider" -> "---";
                case "code" -> "```" + block.path(type).path("language").asText("")
                        + "\n" + text + "\n```";
                case "image" -> "![image](" + imageUrl(block.path(type)) + ")";
                case "child_page" -> "<!-- child page: " + block.path(type).path("title").asText() + " -->";
                default -> "<!-- unsupported: " + type + " -->";
            };
            if (line.isEmpty() && !"paragraph".equals(type)) {
                continue;
            }
            sb.append(indent).append(line.replace("\n", "\n" + indent)).append('\n');
            if (block.path("has_children").asBoolean(false) && !"child_page".equals(type)) {
                render(childLoader.apply(block.path("id").asText()), childLoader, indent + "  ", sb);
            }
        }
    }

    private String imageUrl(JsonNode image) {
        return switch (image.path("type").asText()) {
            case "external" -> image.path("external").path("url").asText("");
            case "file" -> image.path("file").path("url").asText("");
            default -> "";
        };
    }

    private String joinRichText(JsonNode richTextArray) {
        StringBuilder sb = new StringBuilder();
        richTextArray.forEach(node -> sb.append(node.path("plain_text").asText()));
        return sb.toString();
    }

    /** Parses Markdown into an array of Notion block objects. */
    public ArrayNode toBlocks(String markdown) {
        ArrayNode blocks = mapper.createArrayNode();
        String[] lines = markdown.replace("\r\n", "\n").split("\n", -1);
        StringBuilder paragraph = new StringBuilder();
        int i = 0;
        while (i < lines.length) {
            String line = lines[i];
            String trimmed = line.strip();

            if (trimmed.startsWith("```")) {
                flushParagraph(paragraph, blocks);
                String language = trimmed.substring(3).strip();
                StringBuilder code = new StringBuilder();
                i++;
                while (i < lines.length && !lines[i].strip().startsWith("```")) {
                    if (!code.isEmpty()) {
                        code.append('\n');
                    }
                    code.append(lines[i]);
                    i++;
                }
                i++; // skip closing fence
                ObjectNode block = block(blocks, "code");
                ObjectNode body = block.putObject("code");
                addRichText(body.putArray("rich_text"), code.toString());
                body.put("language", language.isEmpty() ? "plain text" : language);
                continue;
            }

            Matcher numbered = NUMBERED_ITEM.matcher(trimmed);
            if (trimmed.isEmpty()) {
                flushParagraph(paragraph, blocks);
            } else if (trimmed.equals("---") || trimmed.equals("***")) {
                flushParagraph(paragraph, blocks);
                block(blocks, "divider").putObject("divider");
            } else if (trimmed.startsWith("### ")) {
                flushParagraph(paragraph, blocks);
                textBlock(blocks, "heading_3", trimmed.substring(4));
            } else if (trimmed.startsWith("## ")) {
                flushParagraph(paragraph, blocks);
                textBlock(blocks, "heading_2", trimmed.substring(3));
            } else if (trimmed.startsWith("# ")) {
                flushParagraph(paragraph, blocks);
                textBlock(blocks, "heading_1", trimmed.substring(2));
            } else if (trimmed.startsWith("- [ ] ") || trimmed.startsWith("- [x] ") || trimmed.startsWith("- [X] ")) {
                flushParagraph(paragraph, blocks);
                ObjectNode body = textBlock(blocks, "to_do", trimmed.substring(6));
                body.put("checked", !trimmed.startsWith("- [ ]"));
            } else if (trimmed.startsWith("- ") || trimmed.startsWith("* ")) {
                flushParagraph(paragraph, blocks);
                textBlock(blocks, "bulleted_list_item", trimmed.substring(2));
            } else if (numbered.matches()) {
                flushParagraph(paragraph, blocks);
                textBlock(blocks, "numbered_list_item", numbered.group(1));
            } else if (trimmed.startsWith("> ")) {
                flushParagraph(paragraph, blocks);
                textBlock(blocks, "quote", trimmed.substring(2));
            } else {
                if (!paragraph.isEmpty()) {
                    paragraph.append('\n');
                }
                paragraph.append(trimmed);
            }
            i++;
        }
        flushParagraph(paragraph, blocks);
        return blocks;
    }

    private void flushParagraph(StringBuilder paragraph, ArrayNode blocks) {
        if (paragraph.isEmpty()) {
            return;
        }
        textBlock(blocks, "paragraph", paragraph.toString());
        paragraph.setLength(0);
    }

    private ObjectNode block(ArrayNode blocks, String type) {
        ObjectNode block = blocks.addObject();
        block.put("object", "block");
        block.put("type", type);
        return block;
    }

    private ObjectNode textBlock(ArrayNode blocks, String type, String text) {
        ObjectNode body = block(blocks, type).putObject(type);
        addRichText(body.putArray("rich_text"), text);
        return body;
    }

    private void addRichText(ArrayNode richText, String text) {
        for (int start = 0; start < text.length() || start == 0; start += MAX_TEXT_LENGTH) {
            String chunk = text.substring(start, Math.min(text.length(), start + MAX_TEXT_LENGTH));
            richText.addObject()
                    .putObject("text")
                    .put("content", chunk);
            if (text.isEmpty()) {
                break;
            }
        }
    }
}
