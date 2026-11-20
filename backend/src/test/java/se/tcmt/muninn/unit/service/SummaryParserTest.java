package se.tcmt.muninn.unit.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;
import se.tcmt.muninn.exception.InvalidSummaryException;
import se.tcmt.muninn.service.SummaryParser;
import se.tcmt.muninn.service.SummaryParser.ParsedSummary;

class SummaryParserTest {

    private final SummaryParser parser = new SummaryParser();

    @Test
    void parsesJson() {
        ParsedSummary s = parser.parse(
                """
                {"summary": "Weekly sync about the launch.", "actionItems": ["Email Bob", "Book room"]}""");
        assertThat(s.summary()).isEqualTo("Weekly sync about the launch.");
        assertThat(s.actionItems()).containsExactly("Email Bob", "Book room");
    }

    @Test
    void stripsMarkdownFence() {
        ParsedSummary s = parser.parse(
                """
                ```json
                {"summary": "Fenced", "actionItems": ["one"]}
                ```""");
        assertThat(s.summary()).isEqualTo("Fenced");
        assertThat(s.actionItems()).containsExactly("one");

        assertThat(parser.parse("```\n{\"summary\": \"Bare fence\"}\n```").summary())
                .isEqualTo("Bare fence");
    }

    @Test
    void parsesJsonWithSurroundingProse() {
        ParsedSummary s = parser.parse(
                """
                Sure! Here is the {requested} summary:
                {"summary": "Talked about {braces} and \\"quotes\\"", "actionItems": ["Fix }"]}
                Let me know if you need anything else. {"summary": "second"}""");
        assertThat(s.summary()).isEqualTo("Talked about {braces} and \"quotes\"");
        assertThat(s.actionItems()).containsExactly("Fix }");
    }

    @Test
    void rejectsGarbage() {
        for (String garbage :
                List.of("", "   ", "hello there", "{not json}", "[1, 2]", "{\"summary\": ", "```json\n```")) {
            assertThatThrownBy(() -> parser.parse(garbage)).as(garbage).isInstanceOf(InvalidSummaryException.class);
        }
        assertThatThrownBy(() -> parser.parse(null)).isInstanceOf(InvalidSummaryException.class);
    }

    @Test
    void rejectsWrongTypes() {
        for (String bad : List.of(
                "{\"summary\": 42, \"actionItems\": []}",
                "{\"summary\": \"   \", \"actionItems\": []}",
                "{\"actionItems\": [\"x\"]}",
                "{\"summary\": null}",
                "{\"summary\": \"ok\", \"actionItems\": \"do it\"}",
                "{\"summary\": \"ok\", \"actionItems\": [1, 2]}",
                "{\"summary\": \"ok\", \"actionItems\": [{\"task\": \"x\"}]}",
                "{\"summary\": {\"text\": \"nested\"}}")) {
            assertThatThrownBy(() -> parser.parse(bad)).as(bad).isInstanceOf(InvalidSummaryException.class);
        }
    }

    @Test
    void missingActionItemsGivesEmpty() {
        assertThat(parser.parse("{\"summary\": \"Just a note\"}").actionItems()).isEmpty();
        assertThat(parser.parse("{\"summary\": \"Just a note\", \"actionItems\": null}")
                        .actionItems())
                .isEmpty();
    }

    @Test
    void capsSummaryLengthAndItemCount() {
        String longText = "a".repeat(SummaryParser.MAX_SUMMARY_CHARS + 500);
        StringBuilder items = new StringBuilder();
        for (int i = 0; i < SummaryParser.MAX_ACTION_ITEMS + 20; i++) {
            items.append(i == 0 ? "" : ",").append("\"item ").append(i).append('"');
        }
        ParsedSummary s = parser.parse("{\"summary\": \"" + longText + "\", \"actionItems\": [" + items + "]}");
        assertThat(s.summary()).hasSize(SummaryParser.MAX_SUMMARY_CHARS);
        assertThat(s.actionItems()).hasSize(SummaryParser.MAX_ACTION_ITEMS).startsWith("item 0");
    }

    @Test
    void trimsAndDropsBlankActionItems() {
        ParsedSummary s = parser.parse("{\"summary\": \"  padded  \", \"actionItems\": [\" a \", \"\", \"  \"]}");
        assertThat(s.summary()).isEqualTo("padded");
        assertThat(s.actionItems()).containsExactly("a");
    }
}
