package dev.edifact.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.modelcontextprotocol.json.jackson2.JacksonMcpJsonMapper;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.transport.StdioServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema;

import io.xlate.edi.schema.Schema;
import io.xlate.edi.schema.SchemaFactory;
import io.xlate.edi.stream.EDIInputFactory;
import io.xlate.edi.stream.EDIStreamEvent;
import io.xlate.edi.stream.EDIStreamReader;
import io.xlate.edi.stream.EDIStreamValidationError;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class EdifactMcpServer {

    private static final McpJsonMapper JSON_MAPPER = new JacksonMcpJsonMapper(new ObjectMapper());

    public static void main(String[] args) {
        var transportProvider = new StdioServerTransportProvider(JSON_MAPPER);

        McpSyncServer server = McpServer.sync(transportProvider)
                .serverInfo("edifact-mcp-server", "1.0.0")
                .capabilities(McpSchema.ServerCapabilities.builder()
                        .tools(true)
                        .build())
                .tools(validateTool(), parseTool(), describeTool())
                .build();

        // Server blocks on STDIO — keep running
        Runtime.getRuntime().addShutdownHook(new Thread(server::close));
    }

    // ── Tool definitions ────────────────────────────────────────────────

    private static McpServerFeatures.SyncToolSpecification validateTool() {
        String schema = """
                {
                  "type": "object",
                  "properties": {
                    "edi_data": {
                      "type": "string",
                      "description": "Raw UN/EDIFACT (or X12) message data to validate"
                    }
                  },
                  "required": ["edi_data"]
                }
                """;

        McpSchema.Tool tool = McpSchema.Tool.builder()
                .name("validate_edifact")
                .description("Validates EDI data (EDIFACT / X12) using StAEDI with built-in control schema validation. Returns detected errors with location info.")
                .inputSchema(JSON_MAPPER, schema)
                .build();

        return McpServerFeatures.SyncToolSpecification.builder()
                .tool(tool)
                .callHandler((exchange, request) -> {
                    String ediData = (String) request.arguments().get("edi_data");
                    return doValidate(ediData);
                })
                .build();
    }

    private static McpServerFeatures.SyncToolSpecification parseTool() {
        String schema = """
                {
                  "type": "object",
                  "properties": {
                    "edi_data": {
                      "type": "string",
                      "description": "Raw UN/EDIFACT (or X12) message data to parse into segments and elements"
                    }
                  },
                  "required": ["edi_data"]
                }
                """;

        McpSchema.Tool tool = McpSchema.Tool.builder()
                .name("parse_edifact")
                .description("Parses EDI data into a structured list of segments with their elements and composite values.")
                .inputSchema(JSON_MAPPER, schema)
                .build();

        return McpServerFeatures.SyncToolSpecification.builder()
                .tool(tool)
                .callHandler((exchange, request) -> {
                    String ediData = (String) request.arguments().get("edi_data");
                    return doParse(ediData);
                })
                .build();
    }

    private static McpServerFeatures.SyncToolSpecification describeTool() {
        String schema = """
                {
                  "type": "object",
                  "properties": {
                    "edi_data": {
                      "type": "string",
                      "description": "Raw UN/EDIFACT (or X12) message data to describe"
                    }
                  },
                  "required": ["edi_data"]
                }
                """;

        McpSchema.Tool tool = McpSchema.Tool.builder()
                .name("describe_edifact")
                .description("Returns metadata about an EDI message: detected standard (EDIFACT/X12/TRADACOMS), version, and segment list.")
                .inputSchema(JSON_MAPPER, schema)
                .build();

        return McpServerFeatures.SyncToolSpecification.builder()
                .tool(tool)
                .callHandler((exchange, request) -> {
                    String ediData = (String) request.arguments().get("edi_data");
                    return doDescribe(ediData);
                })
                .build();
    }

    // ── Tool implementations ────────────────────────────────────────────

    private static McpSchema.CallToolResult doValidate(String ediData) {
        if (ediData == null || ediData.isBlank()) {
            return errorResult("edi_data darf nicht leer sein.");
        }

        List<String> errors = new ArrayList<>();
        String standard = "unknown";
        String[] version = {};

        try {
            EDIInputFactory factory = EDIInputFactory.newFactory();
            // Control structure + code validation enabled by default
            var stream = new ByteArrayInputStream(ediData.getBytes(StandardCharsets.UTF_8));
            EDIStreamReader reader = factory.createEDIStreamReader(stream);

            while (reader.hasNext()) {
                EDIStreamEvent event = reader.next();

                if (event == EDIStreamEvent.START_INTERCHANGE) {
                    standard = reader.getStandard();
                    version = reader.getVersion();

                    // Attach built-in control schema for the detected standard/version
                    try {
                        SchemaFactory sf = SchemaFactory.newFactory();
                        Schema controlSchema = sf.getControlSchema(standard, version);
                        reader.setControlSchema(controlSchema);
                    } catch (Exception e) {
                        // If no built-in schema found, continue with basic validation
                    }
                }

                if (event == EDIStreamEvent.SEGMENT_ERROR
                        || event == EDIStreamEvent.ELEMENT_DATA_ERROR
                        || event == EDIStreamEvent.ELEMENT_OCCURRENCE_ERROR) {
                    EDIStreamValidationError errorType = reader.getErrorType();
                    String location = reader.getLocation().toString();
                    errors.add("[%s] %s @ %s".formatted(event.name(), errorType, location));
                }
            }
            reader.close();
        } catch (Exception e) {
            errors.add("Parse-Fehler: " + e.getMessage());
        }

        var sb = new StringBuilder();
        sb.append("Standard: %s | Version: %s\n".formatted(standard, String.join(".", version)));

        if (errors.isEmpty()) {
            sb.append("✓ Validierung erfolgreich – keine Fehler gefunden.");
        } else {
            sb.append("✗ %d Fehler gefunden:\n".formatted(errors.size()));
            for (int i = 0; i < errors.size(); i++) {
                sb.append("  %d. %s\n".formatted(i + 1, errors.get(i)));
            }
        }

        return McpSchema.CallToolResult.builder()
                .content(List.of(new McpSchema.TextContent(sb.toString().strip())))
                .isError(false)
                .build();
    }

    private static McpSchema.CallToolResult doParse(String ediData) {
        if (ediData == null || ediData.isBlank()) {
            return errorResult("edi_data darf nicht leer sein.");
        }

        var segments = new ArrayList<Map<String, Object>>();

        try {
            EDIInputFactory factory = EDIInputFactory.newFactory();
            factory.setProperty(EDIInputFactory.EDI_VALIDATE_CONTROL_CODE_VALUES, false);
            var stream = new ByteArrayInputStream(ediData.getBytes(StandardCharsets.UTF_8));
            EDIStreamReader reader = factory.createEDIStreamReader(stream);

            Map<String, Object> currentSegment = null;
            List<String> currentElements = null;
            List<String> currentComposite = null;

            while (reader.hasNext()) {
                EDIStreamEvent event = reader.next();

                switch (event) {
                    case START_SEGMENT -> {
                        currentSegment = new LinkedHashMap<>();
                        currentSegment.put("segment", reader.getText());
                        currentElements = new ArrayList<>();
                    }
                    case END_SEGMENT -> {
                        if (currentSegment != null) {
                            currentSegment.put("elements", currentElements);
                            segments.add(currentSegment);
                        }
                        currentSegment = null;
                        currentElements = null;
                    }
                    case START_COMPOSITE -> {
                        currentComposite = new ArrayList<>();
                    }
                    case END_COMPOSITE -> {
                        if (currentComposite != null && currentElements != null) {
                            currentElements.add(String.join(":", currentComposite));
                        }
                        currentComposite = null;
                    }
                    case ELEMENT_DATA -> {
                        String text = reader.getText();
                        if (currentComposite != null) {
                            currentComposite.add(text != null ? text : "");
                        } else if (currentElements != null) {
                            currentElements.add(text != null ? text : "");
                        }
                    }
                    default -> { /* ignore other events */ }
                }
            }
            reader.close();
        } catch (Exception e) {
            return errorResult("Parse-Fehler: " + e.getMessage());
        }

        // Format as readable text
        var sb = new StringBuilder();
        sb.append("Parsed %d segment(s):\n\n".formatted(segments.size()));
        for (var seg : segments) {
            sb.append(seg.get("segment"));
            @SuppressWarnings("unchecked")
            var elems = (List<String>) seg.get("elements");
            if (elems != null && !elems.isEmpty()) {
                for (int i = 0; i < elems.size(); i++) {
                    sb.append("\n  [%d] %s".formatted(i + 1, elems.get(i)));
                }
            }
            sb.append("\n\n");
        }

        return McpSchema.CallToolResult.builder()
                .content(List.of(new McpSchema.TextContent(sb.toString().strip())))
                .isError(false)
                .build();
    }

    private static McpSchema.CallToolResult doDescribe(String ediData) {
        if (ediData == null || ediData.isBlank()) {
            return errorResult("edi_data darf nicht leer sein.");
        }

        String standard = "unknown";
        String[] version = {};
        var segmentNames = new ArrayList<String>();
        var messageTypes = new ArrayList<String>();
        int segmentCount = 0;

        try {
            EDIInputFactory factory = EDIInputFactory.newFactory();
            factory.setProperty(EDIInputFactory.EDI_VALIDATE_CONTROL_CODE_VALUES, false);
            var stream = new ByteArrayInputStream(ediData.getBytes(StandardCharsets.UTF_8));
            EDIStreamReader reader = factory.createEDIStreamReader(stream);

            boolean inMessageHeader = false;
            int headerElementIndex = 0;

            while (reader.hasNext()) {
                EDIStreamEvent event = reader.next();

                switch (event) {
                    case START_INTERCHANGE -> {
                        standard = reader.getStandard();
                        version = reader.getVersion();
                    }
                    case START_SEGMENT -> {
                        String name = reader.getText();
                        segmentCount++;
                        if (!segmentNames.contains(name)) {
                            segmentNames.add(name);
                        }
                        // Track UNH (EDIFACT) or ST (X12) to extract message type
                        inMessageHeader = "UNH".equals(name) || "ST".equals(name);
                        headerElementIndex = 0;
                    }
                    case ELEMENT_DATA -> {
                        if (inMessageHeader) {
                            headerElementIndex++;
                            String text = reader.getText();
                            // For X12 ST: element 1 is transaction set ID
                            // For EDIFACT UNH: element 2 (composite) first component is message type
                            if ("X12".equals(standard) && headerElementIndex == 1 && text != null) {
                                if (!messageTypes.contains(text)) messageTypes.add(text);
                            }
                        }
                    }
                    case START_COMPOSITE -> {
                        if (inMessageHeader) headerElementIndex++;
                    }
                    case END_SEGMENT -> {
                        inMessageHeader = false;
                    }
                    default -> { /* ignore */ }
                }

                // For EDIFACT UNH: message type is first component of the second element (composite S009)
                if (inMessageHeader && "EDIFACT".equals(standard)
                        && event == EDIStreamEvent.ELEMENT_DATA
                        && headerElementIndex == 2) {
                    String text = reader.getText();
                    if (text != null && !text.isBlank() && !messageTypes.contains(text)) {
                        messageTypes.add(text);
                    }
                    // Only capture first component
                    inMessageHeader = false;
                }
            }
            reader.close();
        } catch (Exception e) {
            return errorResult("Parse-Fehler: " + e.getMessage());
        }

        var sb = new StringBuilder();
        sb.append("EDI Message Description\n");
        sb.append("═══════════════════════\n");
        sb.append("Standard     : %s\n".formatted(standard));
        sb.append("Version      : %s\n".formatted(String.join(".", version)));
        sb.append("Total segments: %d\n".formatted(segmentCount));
        if (!messageTypes.isEmpty()) {
            sb.append("Message types : %s\n".formatted(String.join(", ", messageTypes)));
        }
        sb.append("Unique segments: %s\n".formatted(String.join(", ", segmentNames)));

        return McpSchema.CallToolResult.builder()
                .content(List.of(new McpSchema.TextContent(sb.toString().strip())))
                .isError(false)
                .build();
    }

    // ── Helpers ─────────────────────────────────────────────────────────

    private static McpSchema.CallToolResult errorResult(String message) {
        return McpSchema.CallToolResult.builder()
                .content(List.of(new McpSchema.TextContent("ERROR: " + message)))
                .isError(true)
                .build();
    }
}
