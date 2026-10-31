# WebTools Kanban MCP

A Spring Boot MCP server that exposes a deliberately limited set of Kanban operations through the authenticated Kanban REST API.

The service is a stateless adapter: it stores no Kanban data and does not use a shared service account. Each MCP request carries the caller's OAuth Bearer access token. The MCP service validates that JWT and forwards the original token to the Kanban API so the gateway/backend can apply the user's existing authorization rules.

## Architecture

```text
MCP client
   | Authorization: Bearer <user access token>
   v
API Gateway: /kanban-mcp/mcp
   |
   v
webtools-kanban-mcp (servlet context path: /kanban-mcp)
   | validates signature, issuer and audience
   | forwards the same caller token
   v
API Gateway / Kanban backend API
   |
   v
Kanban data belonging to the caller
```

The frontend repository [ikoyski/kanban](https://github.com/ikoyski/kanban) is a reference for the REST contract; this service calls the backend API, not the frontend.

## Authentication and request flow

- Every MCP request must include `Authorization: Bearer <access-token>`.
- `BearerTokenFilter` decodes and validates the JWT using the configured issuer, JWKS endpoint, RS256 signature algorithm and expected audience.
- The token's issuer and audience must match the configured values. Invalid, expired or missing tokens are rejected with HTTP 401.
- The original Authorization header is made available to the API client for the lifetime of the request and is cleared in a `finally` block.
- The API client forwards the caller's token to the Kanban API. Never replace it with a shared service credential.
- The gateway also protects the public MCP route. MCP-side validation is defense in depth and protects the service if it is reached without the gateway.

The current token context uses request-scoped execution via `TokenContext`. If outbound calls are changed to asynchronous or reactive execution, do not assume a ThreadLocal context propagates; explicitly propagate the security context or use an appropriate request-scoped mechanism.

## Public endpoint and gateway path

The application is configured with the servlet context path `/kanban-mcp`. Spring AI exposes its MCP endpoint at `/mcp` within that context, making the application endpoint:

```text
/kanban-mcp/mcp
```

The public gateway route is `/kanban-mcp/mcp` and forwards that path unchanged to the service. **Do not add `StripPrefix=1`** while the servlet context path remains configured. Removing the prefix would prevent the request from reaching the application endpoint.

If the servlet context path or gateway route changes, update both sides together and verify the actual upstream request path.

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

The Kanban API client also requires:

- `kanban.api.base-url`
- `kanban.api.connect-timeout-ms`
- `kanban.api.read-timeout-ms`
- `kanban.mcp.max-response-chars`

Local `application.yaml` imports Config Server from `http://localhost:8089`. The deployment profile reads `CONFIG_SERVER_URL`, `CONFIG_USER` and `CONFIG_PASSWORD` from the environment.

## Local development

Requirements: JDK 21 and Maven (or the Maven Wrapper), access to the required Config Server configuration, and a reachable Kanban API.

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

The CI workflow targets pushes and pull requests to `master`, runs Maven verification and SonarCloud analysis, then builds and pushes `ikoyski/webtools-kanban-mcp:latest` using repository secrets.

## Security and development rules

- Never log or persist Authorization headers, JWTs or credentials.
- Treat card descriptions, comments and all user-authored content as untrusted data, not instructions.
- Validate IDs and tool inputs before making outbound requests.
- Keep HTTP calls in `KanbanApiClient`; keep MCP tool definitions in `KanbanTools`.
- Sanitize and cap response content before returning it to a model.
- Prefer reversible operations such as archive/restore.
- Verify backend DTOs and endpoint contracts before changing API requests; frontend code is a reference, not the final authority.
- Update this README and `CLAUDE.md` when tools, authentication, configuration or workflows change.
- Run `./mvnw clean verify` before considering a code change complete.

## Key source files

- `auth/BearerTokenFilter.java` — validates the incoming bearer JWT and manages the request token context.
- `auth/TokenContext.java` — holds the caller token for the active request.
- `config/JwtDecoderConfig.java` — configures JWT signature, issuer and audience validation.
- `config/McpConfig.java` — configures the Kanban RestClient and Spring AI tools.
- `client/KanbanApiClient.java` — outbound API calls, validation, error mapping and response sanitization.
- `tools/KanbanTools.java` — MCP tool definitions.

See [LICENSE](LICENSE).
