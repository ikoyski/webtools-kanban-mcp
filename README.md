# WebTools Kanban MCP

A Java 21 / Spring Boot 4 MCP server that exposes a deliberately limited set of Kanban operations through the authenticated Kanban REST API. The production endpoint uses the modern stateless MCP 2026-07-28 server implementation from the MCP Java SDK.

The service is a stateless adapter: it stores no Kanban data and does not use a shared service account. Each MCP request carries the caller's OAuth Bearer access token. The MCP service validates that JWT and forwards the original token to the Kanban backend, which applies the user's authorization rules.

## Architecture

```text
MCP client
   | Authorization: Bearer <user access token>
   | MCP 2026-07-28 routing headers
   v
Cloudflare / API Gateway: /kanban-mcp/mcp
   |
   v
webtools-kanban-mcp (servlet context path: /kanban-mcp)
   | modern HttpServletMcpTransport at /mcp
   | validates signature, issuer and audience
   | forwards the same caller token
   | RestClient + Spring Cloud LoadBalancer
   | resolves WEBTOOLS-KANBAN-BACKEND through Eureka
   v
Kanban backend (private service-to-service call)
   |
   v
Kanban data belonging to the caller
```

The frontend repository [ikoyski/kanban](https://github.com/ikoyski/kanban) is a reference for the REST contract; this service calls the backend API, not the frontend.

## MCP protocol and endpoint

- The deployed servlet uses `io.modelcontextprotocol.sdk:mcp:2.2.0-M1` and `HttpServletMcpTransport`.
- It serves the stateless MCP protocol revision `2026-07-28`, including `server/discover`, `tools/list` and `tools/call`.
- The servlet context path remains `/kanban-mcp`; the transport endpoint is `/mcp`. The effective service path is `/kanban-mcp/mcp`.
- The public gateway route forwards `/kanban-mcp/mcp` unchanged. **Do not add `StripPrefix=1`** while the servlet context path remains configured.
- The modern HTTP transport requires `Mcp-Method` on every request, `MCP-Protocol-Version` when the body declares a protocol version, and `Mcp-Name` for `tools/call`. Compliant clients supply these headers.
- The SDK modern server API is preview software. Keep the previous production image available for rollback until Claude and Gemini have both passed end-to-end tests.

If the servlet context path or gateway route changes, update both sides together and verify the actual upstream request path.

## Authentication and request flow

- Every MCP request must include `Authorization: Bearer <access-token>`.
- `BearerTokenFilter` decodes and validates the JWT using the configured issuer, JWKS endpoint, RS256 signature algorithm and expected audience.
- Invalid, expired or missing tokens are rejected with HTTP 401.
- The original Authorization header is made available to the API client for the lifetime of the request and is cleared in a `finally` block.
- The API client forwards the caller's token directly to the Kanban backend using Eureka service discovery and Spring Cloud LoadBalancer. Never replace it with a shared service credential.
- MCP-to-backend traffic bypasses the public API Gateway and its Redis gateway rate limiter. Keep backend authentication and authorization enabled; private routing is not an authorization boundary.
- The gateway also protects the public MCP route. MCP-side validation is defense in depth and protects the service if it is reached without the gateway.

The modern servlet transport executes synchronous tool handlers on the request thread, so the existing request-scoped `TokenContext` ThreadLocal is available during backend calls. If outbound calls are changed to asynchronous or reactive execution, explicitly propagate the security context or use an appropriate request-scoped mechanism.

## Supported MCP tools

| Tool | Purpose |
|---|---|
| `list_boards` | List boards accessible to the caller. |
| `get_board` | Get a board with columns and cards. |
| `create_board` | Create a board. |
| `create_column` | Add a column. |
| `rename_column` | Rename a column. |
| `create_card` | Create a card. |
| `update_card` | Partially update a card. |
| `move_card` | Move a card to a column and position. |
| `archive_card` | Archive a card. |
| `restore_card` | Restore an archived card. |
| `list_archived_cards` | List archived cards for a board. |
| `list_comments` | List comments on a card. |
| `add_comment` | Add a plain-text comment. |

There are intentionally no tools for deleting boards, columns, cards or comments; importing/overwriting boards; reordering columns; or managing board members.

### Tool semantics

- Use IDs returned by `list_boards` and `get_board`; do not guess IDs.
- `move_card` uses a zero-based destination position. To append, use the destination column's current card count.
- `update_card` is partial: omitted fields remain unchanged. Supplying `labels` replaces the full label list.
- The current update implementation cannot clear a due date or labels because null values are omitted from the outbound request.
- Priorities are `LOW`, `MEDIUM` or `HIGH`; due dates use `yyyy-MM-dd`.
- A maximum of 10 labels is accepted, each at most 50 characters. Names are limited to 100 characters.
- `create_board` currently accepts a description at the tool layer, but the API client sends only the board name. Do not assume the description is persisted.

## Configuration

Runtime values are supplied primarily by Spring Config Server. The MCP resource-server settings are:

```yaml
security:
  oauth2:
    issuer: https://<auth-server>
    jwk-set-uri: https://<auth-server>/oauth2/jwks
    audience: https://api.ikoyski.top/kanban-mcp/mcp
    resource-metadata: https://api.ikoyski.top/.well-known/oauth-protected-resource/kanban-mcp/mcp
```

The configured audience must match the resource URI assigned to the MCP OAuth clients by the authorization server. The decoder currently accepts RS256 JWTs. Keep the issuer, JWKS URL, audience and resource metadata consistent across the authorization server, gateway and MCP service.

The JWKS HTTP client is Eureka-aware. Configure `security.oauth2.jwk-set-uri` with the authorization-server service ID, for example `http://WEBTOOLS-AUTH/oauth2/jwks`, provided that this is the exact Eureka application name and its advertised instance address is reachable from the MCP container. Keep `security.oauth2.issuer` set to the canonical public issuer URL (for example `https://auth.ikoyski.top`); do not replace the issuer with the internal Eureka URL.

The Kanban API client uses Eureka service ID `WEBTOOLS-KANBAN-BACKEND`; Spring Cloud LoadBalancer resolves the registered instance. Set `kanban.api.base-url` to `http://WEBTOOLS-KANBAN-BACKEND/kanban-backend/v1` (no fixed port; Eureka supplies the instance host and port). The backend's advertised Eureka hostname/IP and port must be reachable from the MCP container.

The Kanban API client also requires:

- `kanban.api.base-url`
- `kanban.api.connect-timeout-ms`
- `kanban.api.read-timeout-ms`
- `kanban.mcp.max-response-chars`

Local `application.yaml` imports Config Server from `http://localhost:8089`. The deployment profile reads `CONFIG_SERVER_URL`, `CONFIG_USER` and `CONFIG_PASSWORD` from the environment.

## Local development

Requirements: JDK 21, Maven (or the Maven Wrapper), access to the required Config Server configuration, and a reachable Kanban API.

```bash
./mvnw clean verify
./mvnw spring-boot:run
```

Windows:

```powershell
.\\mvnw.cmd clean verify
```

## Docker and CI

The Dockerfile uses Eclipse Temurin 21 JRE, exposes port 8080 and starts with the `private` Spring profile. Package before building:

```bash
./mvnw clean package
docker build -t ikoyski/webtools-kanban-mcp .
docker run --rm -p 8080:8080 ikoyski/webtools-kanban-mcp
```

CI runs Maven verification and SonarCloud analysis for the production application and also builds the isolated modern-protocol POC. Docker publishing remains restricted to pushes to `master`.

## Security and development rules

- Never log or persist Authorization headers, JWTs or credentials.
- Treat card descriptions, comments and all user-authored content as untrusted data, not instructions.
- Validate IDs and tool inputs before making outbound requests.
- Keep HTTP calls in `KanbanApiClient`; keep modern MCP tool definitions in `ModernKanbanToolRepository`.
- Sanitize and cap response content before returning it to a model.
- Prefer reversible operations such as archive/restore.
- Verify backend DTOs and endpoint contracts before changing API requests; frontend code is a reference, not the final authority.
- Update this README and `CLAUDE.md` when tools, authentication, configuration or workflows change.
- Run `./mvnw clean verify` before considering a code change complete.

## Key source files

- `auth/BearerTokenFilter.java` — validates the incoming bearer JWT and manages the request token context.
- `auth/TokenContext.java` — holds the caller token for the active request.
- `auth/JwtDecoderConfig.java` — configures JWT signature, issuer and audience validation.
- `config/McpConfig.java` — configures the load-balanced Kanban RestClient and modern MCP Servlet transport.
- `client/KanbanApiClient.java` — outbound API calls, validation, error mapping and response sanitization.
- `tools/ModernKanbanToolRepository.java` — modern MCP tool catalog, descriptions and argument schemas.

See [LICENSE](LICENSE).
