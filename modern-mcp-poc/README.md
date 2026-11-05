# Modern MCP protocol POC

This is an **isolated, loopback-only proof of concept** for the MCP 2026-07-28 stateless
protocol using the preview `io.modelcontextprotocol.sdk:mcp:2.2.0-M1` implementation.

It is deliberately separate from the production Spring Boot 3.4.2 / Spring AI 1.1.7
application. It does not change the production MCP endpoint, gateway, auth server, or
Kanban backend.

## Scope

The first spike verifies only:

1. `server/discover` is handled by the modern SDK server.
2. The server advertises and executes a `list_boards` fixture tool.

The returned board is fake fixture data. This POC does **not** connect to the Kanban API,
does **not** validate JWTs, and is **not suitable for deployment**. It binds only to
`127.0.0.1` to avoid exposing an unauthenticated endpoint.

The modern SDK transport uses Jakarta Servlet 6.1. The SDK's own reference conformance
server uses Tomcat 11. This standalone module therefore avoids forcing Servlet 6.1 into
the existing application's Tomcat 10 / Servlet 6.0 runtime.

## Requirements

- JDK 17+
- Maven 3.9+

## Run

From this directory:

```bash
mvn clean compile
mvn exec:java
```

The endpoint listens on:

```text
http://127.0.0.1:8099/mcp
```

## Smoke tests

The modern Streamable HTTP transport requires routing headers that match the JSON-RPC
method and, for tool calls, the tool name.

### 1. Discover server capabilities

```bash
curl -i http://127.0.0.1:8099/mcp \
  -H 'Content-Type: application/json' \
  -H 'Accept: application/json' \
  -H 'Mcp-Method: server/discover' \
  -H 'MCP-Protocol-Version: 2026-07-28' \
  --data-raw '{
    "jsonrpc":"2.0",
    "id":1,
    "method":"server/discover",
    "params":{
      "_meta":{
        "io.modelcontextprotocol/protocolVersion":"2026-07-28",
        "io.modelcontextprotocol/clientCapabilities":{}
      }
    }
  }'
```

Expected: HTTP 200 with a JSON-RPC result describing the server and its tools capability.

### 2. List tools

```bash
curl -i http://127.0.0.1:8099/mcp \
  -H 'Content-Type: application/json' \
  -H 'Accept: application/json' \
  -H 'Mcp-Method: tools/list' \
  -H 'MCP-Protocol-Version: 2026-07-28' \
  --data-raw '{
    "jsonrpc":"2.0",
    "id":2,
    "method":"tools/list",
    "params":{
      "_meta":{
        "io.modelcontextprotocol/protocolVersion":"2026-07-28",
        "io.modelcontextprotocol/clientCapabilities":{}
      }
    }
  }'
```

Expected: HTTP 200 and a single `list_boards` tool.

### 3. Call the fixture tool

```bash
curl -i http://127.0.0.1:8099/mcp \
  -H 'Content-Type: application/json' \
  -H 'Accept: application/json' \
  -H 'Mcp-Method: tools/call' \
  -H 'MCP-Protocol-Version: 2026-07-28' \
  -H 'Mcp-Name: list_boards' \
  --data-raw '{
    "jsonrpc":"2.0",
    "id":3,
    "method":"tools/call",
    "params":{
      "name":"list_boards",
      "arguments":{},
      "_meta":{
        "io.modelcontextprotocol/protocolVersion":"2026-07-28",
        "io.modelcontextprotocol/clientCapabilities":{}
      }
    }
  }'
```

Expected: HTTP 200 and the fixture board in the tool result.

## Integration gates

- Do not point Claude or the public API Gateway at this POC. It has no authentication.
- Do not replace the production Spring AI starter yet.
- After the protocol smoke tests pass, the next stage is to integrate JWT validation as a
  Servlet Filter, forward the caller's bearer token through the Kanban API client, and
  port the existing 13 tool definitions and their argument schemas.
- The SDK's modern server API is preview software in 2.2.0-M1. Pinning it here is for
  evaluation only; review newer stable releases before production adoption.
