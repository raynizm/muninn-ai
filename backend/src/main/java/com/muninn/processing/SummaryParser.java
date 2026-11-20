package com.muninn.processing;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Reads {@code {"summary": string, "actionItems": string[]}} out of a chat model's reply. Models wrap JSON in
 * markdown fences or chatty prose, so the first balanced top-level object that parses is used. The result is
 * plain data: nothing in it is ever interpreted as instructions.
 */
@Component
public class SummaryParser {

    public static final int MAX_SUMMARY_CHARS = 20_000;
    public static final int MAX_ACTION_ITEMS = 100;
    static final int MAX_ACTION_ITEM_CHARS = 1_000;
    /** Larger replies are rejected outright rather than scanned. */
    static final int MAX_OUTPUT_CHARS = 1_000_000;
    /** Candidate objects tried before giving up, which bounds the scan on adversarial input. */
    private static final int MAX_CANDIDATES = 20;

    private static final ObjectMapper JSON = new ObjectMapper();

    public record ParsedSummary(String summary, List<String> actionItems) {}

    public ParsedSummary parse(String modelOutput) {
        if (modelOutput == null || modelOutput.isBlank()) {
            throw new InvalidSummaryException("empty model output");
        }
        if (modelOutput.length() > MAX_OUTPUT_CHARS) {
            throw new InvalidSummaryException("model output too large");
        }
        JsonNode object = firstObject(modelOutput);
        return validate(object);
    }

    private static JsonNode firstObject(String text) {
        int from = 0;
        for (int tries = 0; tries < MAX_CANDIDATES; tries++) {
            int start = text.indexOf('{', from);
            if (start < 0) {
                break;
            }
            int end = matchingBrace(text, start);
            from = start + 1;
            if (end < 0) {
                continue; // a stray unclosed brace in prose; a later one may still open the object
            }
            try {
                JsonNode node = JSON.readTree(text.substring(start, end + 1));
                if (node != null && node.isObject()) {
                    return node;
                }
            } catch (JsonProcessingException e) {
                // not JSON (e.g. "{requested}" in prose); try the next opening brace
            }
        }
        throw new InvalidSummaryException("no JSON object in model output");
    }

    /** Index of the brace closing the one at {@code start}, skipping braces inside JSON strings; -1 if none. */
    private static int matchingBrace(String text, int start) {
        int depth = 0;
        boolean inString = false;
        for (int i = start; i < text.length(); i++) {
            char c = text.charAt(i);
            if (inString) {
                if (c == '\\') {
                    i++;
                } else if (c == '"') {
                    inString = false;
                }
            } else if (c == '"') {
                inString = true;
            } else if (c == '{') {
                depth++;
            } else if (c == '}' && --depth == 0) {
                return i;
            }
        }
        return -1;
    }

    private static ParsedSummary validate(JsonNode object) {
        JsonNode summary = object.get("summary");
        if (summary == null || !summary.isTextual() || summary.asText().isBlank()) {
            throw new InvalidSummaryException("summary must be a non-blank string");
        }
        JsonNode items = object.get("actionItems");
        List<String> actionItems = new ArrayList<>();
        if (items != null && !items.isNull()) {
            if (!items.isArray()) {
                throw new InvalidSummaryException("actionItems must be an array of strings");
            }
            for (JsonNode item : items) {
                if (!item.isTextual()) {
                    throw new InvalidSummaryException("actionItems must be an array of strings");
                }
                String text = item.asText().strip();
                if (!text.isEmpty() && actionItems.size() < MAX_ACTION_ITEMS) {
                    actionItems.add(cap(text, MAX_ACTION_ITEM_CHARS));
                }
            }
        }
        return new ParsedSummary(cap(summary.asText().strip(), MAX_SUMMARY_CHARS), List.copyOf(actionItems));
    }

    private static String cap(String s, int max) {
        if (s.length() <= max) {
            return s;
        }
        int end = Character.isHighSurrogate(s.charAt(max - 1)) ? max - 1 : max; // never split a surrogate pair
        return s.substring(0, end);
    }
}
