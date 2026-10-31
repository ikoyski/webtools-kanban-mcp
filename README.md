# WebTools Kanban MCP

A Spring Boot MCP server that exposes a safe, focused set of operations for the Kanban application in [ikoyski/kanban](https://github.com/ikoyski/kanban) through its existing authenticated Kanban API.

The MCP server is a stateless adapter: it does not store Kanban data and it does not act as a shared service account. The caller's Bearer token is forwarded to the existing Kanban API so the normal gateway/backend authentication and authorization rules remain in effect.

> **Repository relationship:** `ikoyski/kanban` is the user-facing Kanban frontend. The MCP server does **not** call that frontend repository directly; it calls the Kanban REST API exposed through `api.ikoyski.top/kanban-backend`. The frontend's API client is a useful reference for the API contract.

## Architecture

```text
┌──────────────────────────┐
│ MCP client / AI assistant│
└────────────┬─────────────┘
             │ Authorization: Bearer <user JWT>
             ▼
┌──────────────────────────┐
│ webtools-kanban-mcp      │
│                          │
│ MCP tools                │
│ Bearer token filter      │
│ Kanban API client        │
└────────────┬─────────────┘
             │ same caller JWT
             ▼
┌──────────────────────────┐
│ API Gateway              │
│ api.ikoyski.top          │
└────────────┬─────────────┘
             │ /kanban-backend/v1
             ▼
┌──────────────────────────┐
│ Kanban backend API       │
└────────────┬─────────────┘
             ▲
             │ REST API
             │
┌──────────────────────────┐
│ ikoyski/kanban           │
│ Kanban frontend          │
└──────────────────────────┘
```

### Authentication model

Every MCP request must include an `Authorization: Bearer <token>` header. The MCP server checks that the header is present, keeps it only for the lifetime of the request, and forwards the exact header to the Kanban API.

The MCP server intentionally does **not** validate the JWT itself. The downstream gateway/backend is responsible for token validation and authorization. This preserves the user's existing Kanban security model and means the MCP operates with the permissions of the calling user.

> **Current integration note:** the MCP is stateless and currently expects the client to provide the user's JWT. An OAuth/OIDC-based MCP authentication flow can be introduced later without changing the Kanban tool layer.

## Supported MCP tools

The current MCP surface contains 13 tools:

| Tool | Purpose |
|---|---|
| `list_boards` | List boards accessible to the current user. |
| `get_board` | Get a board including its columns and cards. |
| `create_board` | Create a board. |
| `create_column` | Add a column to a board. |
| `rename_column` | Rename a column. |
| `create_card` | Create a card with optional description, priority, due date and labels. |
| `update_card` | Partially update a card. |
| `move_card` | Move a card to a destination column and position. |
| `archive_card` | Archive a card. |
| `restore_card` | Restore an archived card. |
| `list_archived_cards` | List archived cards for a board. |
| `list_comments` | List comments on a card. |
| `add_comment` | Add a plain-text comment to a card. |

### Intentionally unsupported operations

The MCP does not currently expose tools for:

- Deleting boards, columns, cards or comments
- Importing/overwriting a board
- Reordering columns
- Managing board members

The tool surface favors useful and reversible Kanban operations, particularly `archive_card` / `restore_card`, rather than exposing destructive operations directly to an AI agent.

## Tool usage notes

- Use board IDs returned by `list_boards` and card/column IDs returned by `get_board` rather than guessing IDs.
- `create_column` uses the backend's default placement.
- `move_card` requires a zero-based destination position. To place a card last, use the number of cards already in the destination column.
- `update_card` is a partial update: omitted fields are left unchanged.
- Supplying `labels` to `update_card` replaces the entire label list.
- The current update implementation cannot clear a due date or labels because null values are omitted from the request.
- Card priority must be `LOW`, `MEDIUM` or `HIGH`.
- Due dates use `yyyy-MM-dd`.
- A maximum of 10 labels is accepted, with a maximum length of 50 characters per label.
- Names are limited to 100 characters.
- Card/comment content returned from the API is treated as untrusted user data. It is sanitized and capped before being returned to the model.

## Kanban API integration

The MCP targets the existing Kanban REST API using the base URL configured for the application. The frontend repository, [ikoyski/kanban](https://github.com/ikoyski/kanban), currently uses the following API base and `/v1` routes in its JavaScript API client:

```text
https://api.ikoyski.top/kanban-backend
```

Examples of routes relevant to the MCP include:

```text
GET    /v1/boards
GET    /v1/boards/{id}
POST   /v1/boards
POST   /v1/columns
PATCH  /v1/columns/{id}
POST   /v1/cards
PATCH  /v1/cards/{id}
PATCH  /v1/cards/{id}/move
POST   /v1/cards/{id}/archive
POST   /v1/cards/{id}/restore
GET    /v1/cards/board/{boardId}/archived
GET    /v1/comments/card/{cardId}
POST   /v1/comments/card/{cardId}
```

The frontend API client contains additional operations that are deliberately not exposed through the MCP. When changing the MCP API client, verify the actual backend contract rather than assuming every frontend operation should become an AI tool.

## Technology

- Java 21
- Spring Boot 3.4.2
- Spring AI 1.1.7
- Spring Cloud 2024.0.0
- Spring AI MCP Server WebMVC
- Spring Cloud Config Client
- Netflix Eureka Client
- Spring `RestClient`
- Spring Boot Actuator
- Micrometer Prometheus registry and tracing
- Maven
- Docker / Eclipse Temurin 21 JRE

## Configuration

Runtime application configuration is primarily supplied by the Spring Config Server.

The application expects these Kanban/MCP properties:

```yaml
kanban:
  api:
    base-url: https://api.ikoyski.top/kanban-backend/v1
    connect-timeout-ms: <configured value>
    read-timeout-ms: <configured value>
  mcp:
    max-response-chars: <configured value>
```

The exact production values are intentionally not committed to this repository.

### Local configuration

`src/main/resources/application.yaml` imports the Config Server from:

```text
http://localhost:8089
```

### Deployment configuration

`src/main/resources/application-public.yaml` uses environment variables:

```text
CONFIG_SERVER_URL
CONFIG_USER
CONFIG_PASSWORD
```

Do not commit Config Server credentials, JWTs or other secrets.

## Running locally

### Prerequisites

- JDK 21
- Maven, or use the included Maven Wrapper
- Access to the required Config Server configuration
- A reachable Kanban API

Build and verify:

```bash
./mvnw clean verify
```

On Windows:

```powershell
.\mvnw.cmd clean verify
```

Run with Maven:

```bash
./mvnw spring-boot:run
```

The MCP server uses the standard Spring AI WebMVC MCP server endpoint exposed by the application. The caller must supply a Bearer token on MCP requests.

## Docker

The Dockerfile uses Eclipse Temurin 21 JRE, exposes port `8080`, and starts the application with the `private` Spring profile.

Package the application first because the Dockerfile copies the built JAR from `target/`:

```bash
./mvnw clean package
docker build -t ikoyski/webtools-kanban-mcp .
docker run --rm -p 8080:8080 ikoyski/webtools-kanban-mcp
```

The published image produced by CI is:

```text
ikoyski/webtools-kanban-mcp:latest
```

## CI/CD

GitHub Actions runs on pushes and pull requests targeting `master`.

The workflow:

1. Uses JDK 21 / Temurin.
2. Runs Maven `clean verify`.
3. Runs SonarCloud analysis.
4. Logs in to Docker Hub using repository secrets.
5. Builds and pushes `ikoyski/webtools-kanban-mcp:latest`.

Required CI secrets include the SonarCloud and Docker Hub credentials referenced by `.github/workflows/maven.yml`.

## Security considerations

This project is designed to put an AI tool layer in front of an existing authenticated API, so security boundaries are important:

- The MCP layer never uses a shared Kanban service account.
- Bearer tokens are forwarded only for the current request.
- The token context is cleared after request processing.
- Path IDs are validated before being inserted into API URLs.
- Tool inputs are validated before outbound requests.
- Common API errors are converted into concise safe messages rather than returning stack traces.
- User-authored Kanban content is untrusted data and must not be treated as instructions.
- Response content is sanitized and size-limited before being returned to the model.
- Credentials and tokens must never be committed or logged.

## Project structure

```text
src/main/java/com/ikoyski/webtools/kanban/mcp/
├── KanbanMcpApplication.java
├── auth/
│   ├── BearerTokenFilter.java
│   └── TokenContext.java
├── client/
│   └── KanbanApiClient.java
├── config/
│   └── McpConfig.java
└── tools/
    └── KanbanTools.java
```

### Responsibilities

- **`BearerTokenFilter`** — requires a Bearer token for MCP requests and places it in the request token context.
- **`TokenContext`** — holds the caller token for the active request.
- **`McpConfig`** — configures the Kanban `RestClient` and registers the MCP tool callbacks.
- **`KanbanApiClient`** — owns all HTTP communication with the Kanban API, validation, error mapping and response sanitization.
- **`KanbanTools`** — defines the MCP-facing tool names, descriptions and parameters.

## Development guidelines

When adding or changing functionality:

1. Keep HTTP calls inside `KanbanApiClient`.
2. Expose operations through `KanbanTools` using Spring AI `@Tool` methods.
3. Validate inputs before calling the backend.
4. Preserve caller-token forwarding through `McpConfig`.
5. Never log or persist Authorization headers.
6. Treat all card, description and comment text as untrusted data.
7. Prefer reversible operations over destructive operations.
8. Keep tool descriptions precise enough for an AI agent to understand required IDs and side effects.
9. Update this README and `CLAUDE.md` when the tool surface, security model, configuration or development workflow changes.
10. Run `clean verify` before considering a change complete.

## Known implementation detail

`create_board` currently accepts an optional description at the MCP tool layer, but the current `KanbanApiClient` sends only the board name to the backend. The description should not be documented as persisted until the backend request contract is verified and the client is updated.

The API client also contains a TODO around verifying some backend DTO field names. When changing those requests, verify the actual Kanban backend contract rather than assuming the current field names are authoritative.

## License

See [LICENSE](LICENSE).


## OAuth resource-server configuration

The MCP service validates access tokens itself, even though the API Gateway also validates them. Configure these properties through the Spring Config Server:

```yaml
security:
  oauth2:
    issuer: https://<your-auth-server>
    jwk-set-uri: https://<your-auth-server>/oauth2/jwks
    audience: https://api.ikoyski.top/kanban-mcp/mcp
    resource-metadata: https://api.ikoyski.top/.well-known/oauth-protected-resource/kanban-mcp/mcp
```

Use the exact issuer and JWKS URL already configured in the API Gateway. Do not copy placeholder values into production.

### Gateway path forwarding

Spring AI's default MCP endpoint is `/mcp`, while the public URL includes the `/kanban-mcp` prefix. The gateway route must remove that first segment before forwarding. Add this filter to the `webtools-kanban-mcp` route:

```yaml
- StripPrefix=1
```

Keep the external URL `https://api.ikoyski.top/kanban-mcp/mcp` and the protected-resource metadata URL unchanged. The metadata controller remains at the API Gateway.

### Configuring additional public OAuth clients

The authorization server can seed optional public MCP clients from Config Server configuration. Each client requires a unique client ID and its exact OAuth redirect URI. Configure only callback URLs supplied by the respective MCP client; do not use wildcard redirect URIs.

```yaml
auth:
  client:
    mcp:
      resource: https://api.ikoyski.top/kanban-mcp/mcp
    chatgpt:
      client-id: kanban-chatgpt
      client-secret: ${AUTH_CHATGPT_CLIENT_SECRET:}
      redirect-uris:
        - ${CHATGPT_OAUTH_REDIRECT_URI}
    gemini:
      client-id: kanban-gemini
      client-secret: ${AUTH_GEMINI_CLIENT_SECRET:}
      redirect-uris:
        - ${GEMINI_OAUTH_REDIRECT_URI}
```

The ChatGPT and Gemini entries are optional and are registered only when their redirect URI lists are populated. Both require PKCE. Without a client secret they are public clients and do not receive refresh tokens; if the provider supports confidential OAuth, configure a secret to enable rotating refresh tokens. Verify that the specific ChatGPT/Gemini product and account tier support remote MCP connections and OAuth before enabling its client entry.
