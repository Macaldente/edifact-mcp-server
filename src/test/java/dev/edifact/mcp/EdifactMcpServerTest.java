package dev.edifact.mcp;

import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

class EdifactMcpServerTest {

    // ── Test data ───────────────────────────────────────────────────────

    static String loadResource(String name) {
        try (var is = EdifactMcpServerTest.class.getResourceAsStream("/" + name)) {
            if (is == null) throw new IllegalStateException("Resource not found: " + name);
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private static String text(McpSchema.CallToolResult result) {
        return ((McpSchema.TextContent) result.content().get(0)).text();
    }

    // ── validate_edifact ────────────────────────────────────────────────

    @Nested
    class ValidateTests {

        @Test
        void validOrdersMessage() {
            var result = EdifactMcpServer.doValidate(loadResource("sample-orders.edi"));

            assertFalse(result.isError());
            String output = text(result);
            assertTrue(output.contains("EDIFACT"), "Should detect EDIFACT standard");
            assertTrue(output.contains("UNOA"), "Should detect UNOA version");
        }

        @Test
        void invalidMessageReportsErrors() {
            var result = EdifactMcpServer.doValidate(loadResource("sample-invalid.edi"));

            assertFalse(result.isError()); // isError=false because the tool itself succeeded
            String output = text(result);
            assertTrue(output.contains("Fehler"), "Should report validation errors");
        }

        @Test
        void nullInputReturnsError() {
            var result = EdifactMcpServer.doValidate(null);

            assertTrue(result.isError());
            assertTrue(text(result).contains("ERROR"));
        }

        @Test
        void emptyInputReturnsError() {
            var result = EdifactMcpServer.doValidate("   ");

            assertTrue(result.isError());
            assertTrue(text(result).contains("ERROR"));
        }

        @Test
        void garbageInputDoesNotThrow() {
            var result = EdifactMcpServer.doValidate("this is not EDI data at all");

            assertFalse(result.isError());
            // Should still produce some output without crashing
            assertNotNull(text(result));
        }
    }

    // ── parse_edifact ───────────────────────────────────────────────────

    @Nested
    class ParseTests {

        @Test
        void parsesSegments() {
            var result = EdifactMcpServer.doParse(loadResource("sample-orders.edi"));

            assertFalse(result.isError());
            String output = text(result);
            assertTrue(output.contains("UNB"), "Should contain UNB segment");
            assertTrue(output.contains("UNH"), "Should contain UNH segment");
            assertTrue(output.contains("BGM"), "Should contain BGM segment");
            assertTrue(output.contains("UNZ"), "Should contain UNZ segment");
        }

        @Test
        void parsesElementValues() {
            var result = EdifactMcpServer.doParse(loadResource("sample-orders.edi"));

            String output = text(result);
            assertTrue(output.contains("PO-12345"), "Should contain order number");
            assertTrue(output.contains("ORDERS"), "Should contain message type in UNB");
        }

        @Test
        void reportsSegmentCount() {
            var result = EdifactMcpServer.doParse(loadResource("sample-orders.edi"));

            String output = text(result);
            assertTrue(output.contains("13 segment(s)"), "Should count all segments");
        }

        @Test
        void nullInputReturnsError() {
            var result = EdifactMcpServer.doParse(null);
            assertTrue(result.isError());
        }
    }

    // ── describe_edifact ────────────────────────────────────────────────

    @Nested
    class DescribeTests {

        @Test
        void detectsEdifactStandard() {
            var result = EdifactMcpServer.doDescribe(loadResource("sample-orders.edi"));

            assertFalse(result.isError());
            String output = text(result);
            assertTrue(output.contains("EDIFACT"), "Should detect EDIFACT");
        }

        @Test
        void detectsVersion() {
            var result = EdifactMcpServer.doDescribe(loadResource("sample-orders.edi"));

            String output = text(result);
            assertTrue(output.contains("UNOA"), "Should detect UNOA version");
        }

        @Test
        void listsMessageTypes() {
            var result = EdifactMcpServer.doDescribe(loadResource("sample-orders.edi"));

            String output = text(result);
            assertTrue(output.contains("ORDERS"), "Should detect ORDERS message type");
        }

        @Test
        void listsUniqueSegments() {
            var result = EdifactMcpServer.doDescribe(loadResource("sample-orders.edi"));

            String output = text(result);
            assertTrue(output.contains("UNB"), "Should list UNB");
            assertTrue(output.contains("BGM"), "Should list BGM");
            assertTrue(output.contains("DTM"), "Should list DTM");
            assertTrue(output.contains("NAD"), "Should list NAD");
        }

        @Test
        void nullInputReturnsError() {
            var result = EdifactMcpServer.doDescribe(null);
            assertTrue(result.isError());
        }
    }
}
