# edifact-mcp-server

[![CI](https://github.com/Macaldente/edifact-mcp-server/actions/workflows/ci.yml/badge.svg)](https://github.com/Macaldente/edifact-mcp-server/actions/workflows/ci.yml)

Ein lokaler [MCP](https://modelcontextprotocol.io/)-Server (Model Context Protocol) für **UN/EDIFACT**- und **X12**-Nachrichten mit echter Schema-Validierung.

Basiert auf:
- [StAEDI](https://github.com/xlate/staedi) (v1.26.2) — Streaming API for EDI in Java
- [MCP Java SDK](https://github.com/modelcontextprotocol/java-sdk) (v1.1.2) — STDIO-Transport

## Voraussetzungen

- **Java 17+** (getestet mit Temurin 26)
- **Maven 3.6+**

## Installation

```bash
git clone https://github.com/Macaldente/edifact-mcp-server.git
cd edifact-mcp-server
mvn clean package -q
```

Das erzeugt ein ausführbares Uber-JAR unter `target/edifact-mcp-server-1.0.0.jar`.

## MCP-Client konfigurieren

### Warp

Erstelle oder ergänze `~/.warp/.mcp.json`:

```json
{
  "mcpServers": {
    "edifact": {
      "command": "java",
      "args": ["-jar", "/absoluter/pfad/zu/edifact-mcp-server/target/edifact-mcp-server-1.0.0.jar"]
    }
  }
}
```

### Zed

Öffne `~/.config/zed/settings.json` (`Cmd+Shift+P` → `zed: open settings`) und ergänze den `context_servers`-Schlüssel:

```json
{
  "context_servers": {
    "edifact": {
      "command": "java",
      "args": ["-jar", "/absoluter/pfad/zu/edifact-mcp-server/target/edifact-mcp-server-1.0.0.jar"]
    }
  }
}
```

> **Hinweis:** Zed verwendet `context_servers` statt `mcpServers`. Nach dem Speichern startet der Server automatisch.

**Status prüfen:** Agent Panel öffnen (`Cmd+Shift+A`) → Einstellungen → Statuspunkt neben `edifact` (grün = aktiv). Bei Problemen: `Cmd+Shift+P` → `zed: open logs`.

### Claude Desktop / Cursor / andere MCP-Clients

Gleiche Konfiguration – passe den Dateinamen der Config-Datei an den jeweiligen Client an (`claude_desktop_config.json`, `.cursor/mcp.json`, etc.):

```json
{
  "mcpServers": {
    "edifact": {
      "command": "java",
      "args": ["-jar", "/absoluter/pfad/zu/edifact-mcp-server/target/edifact-mcp-server-1.0.0.jar"]
    }
  }
}
```

## Tools

Der Server stellt drei Tools über das MCP-Protokoll bereit:

### `validate_edifact`

Validiert EDI-Daten mit dem eingebauten StAEDI-Kontrollschema. Prüft Segmentstruktur, Elementtypen, Längenbeschränkungen und Code-Werte.

**Parameter:**
- `edi_data` (string, required) — Roh-EDI-Nachricht

**Beispiel-Eingabe:**

```
UNB+UNOA:3+SENDER+RECEIVER+210101:1200+00000000000001++ORDERS'
UNH+1+ORDERS:D:96A:UN'
BGM+220+PO-12345+9'
DTM+137:20210101:102'
UNT+4+1'
UNZ+1+00000000000001'
```

**Beispiel-Ausgabe:**

```
Standard: EDIFACT | Version: UNOA.3
✓ Validierung erfolgreich – keine Fehler gefunden.
```

### `parse_edifact`

Zerlegt EDI-Daten in eine strukturierte Liste von Segmenten mit ihren Elementen und Composites.

**Beispiel-Ausgabe:**

```
Parsed 6 segment(s):

UNB
  [1] UNOA:3
  [2] SENDER
  [3] RECEIVER
  [4] 210101:1200
  [5] 00000000000001
  [6]
  [7] ORDERS

UNH
  [1] 1
  [2] ORDERS:D:96A:UN

BGM
  [1] 220
  [2] PO-12345
  [3] 9
...
```

### `describe_edifact`

Gibt Metadaten über die EDI-Nachricht zurück: erkannter Standard, Version, Nachrichtentypen und Segment-Übersicht.

**Beispiel-Ausgabe:**

```
EDI Message Description
═══════════════════════
Standard     : EDIFACT
Version      : UNOA.3
Total segments: 6
Message types : ORDERS
Unique segments: UNB, UNH, BGM, DTM, UNT, UNZ
```

## Unterstützte Standards

StAEDI erkennt den Standard automatisch anhand der Interchange-Header:

| Standard   | Header-Segment | Beispiel-Version |
|------------|---------------|------------------|
| EDIFACT    | `UNB`         | `UNOA:3`         |
| X12        | `ISA`         | `00501`          |
| TRADACOMS  | `STX`         | —                |

## Eigene Transaktions-Schemas

Für Validierung über die Kontrollstruktur hinaus (z.B. branchenspezifische Implementierungen wie EANCOM oder HIPAA) kann StAEDI benutzerdefinierte XML-Schemas laden. Siehe die [StAEDI-Wiki-Seite zur Validierung](https://github.com/xlate/staedi/wiki/Validation) für Details zum Schema-Format.

## Tests

Das Projekt enthält 14 JUnit 5 Tests, die alle drei Tools abdecken:

```bash
mvn test
```

| Testgruppe       | Tests | Prüft                                                    |
|------------------|-------|----------------------------------------------------------|
| `ValidateTests`  | 5     | Gültige/ungültige Nachrichten, Fehlerreport, Edge Cases  |
| `ParseTests`     | 4     | Segmenterkennung, Elementwerte, Segmentanzahl            |
| `DescribeTests`  | 5     | Standard, Version, Nachrichtentypen, Segmentliste        |

CI läuft automatisch auf GitHub Actions mit Temurin 17 und 21.

## Projektstruktur

```
edifact-mcp-server/
├── pom.xml                                          # Maven-Build mit MCP SDK + StAEDI
├── .github/workflows/ci.yml                         # GitHub Actions CI
├── src/main/java/dev/edifact/mcp/
│   └── EdifactMcpServer.java                        # MCP-Server mit 3 Tools
├── src/main/resources/
│   └── logback.xml                                  # Logging → stderr (STDIO-safe)
└── src/test/
    ├── java/dev/edifact/mcp/
    │   └── EdifactMcpServerTest.java                # 14 JUnit 5 Tests
    └── resources/
        ├── sample-orders.edi                        # Gültige ORDERS-Nachricht
        └── sample-invalid.edi                       # Ungültige Nachricht
```

## Lizenz

MIT
