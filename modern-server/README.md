# Modern Kanban MCP server (replacement candidate)

This is a separate executable service using the modern MCP Java SDK and Tomcat 11 / Servlet 6.1. It intentionally does not replace or modify the existing Spring Boot MCP service or its deployment until compatibility and end-to-end client tests pass.

## Configuration

Required environment variables:
- `MCP_ISSUER`: canonical JWT issuer URL (must exactly match the token's iss claim)
- `MCP_JWK_SET_URI`: trusted authorization server JWK set URL
- `MCP_AUDIENCE`: audience assigned to the Kanban MCP resource
- `KANBAN_API_BASE_URL`: Kanban backend API base URL, including the `/v1` prefix

Optional:
- `MCP_PORT` (default 8099)
- `MCP_BIND_ADDRESS` (default 0.0.0.0)
- `MCP_RESOURCE_METADATA` (included in WWW-Authenticate challenge)
  
The service validates RS256 signature, issuer, audience, expiry and not-before. Each tool request forwards the original caller bearer token to the Kanban API. No service credential or shared user identity is used.

## Build and run

Requires JDK 21 and Maven 3.9+.

```bash
mvn -B -f modern-server/pom.xml clean verify
MCP_ISSUER=https://auth.example.test \
MCP_JWK_SET_URI=https://auth.example.test/oauth2/jwks \
MCP_AUDIENCE=kanban-mcp \
KANBAN_API_BASE_URL=https://api.example.test/kanban-backend/v1 \
mvn -f modern-server/pom.xml exec:java
```

Endpoint: `http://localhost:8099/mcp`.

The JWT filter protects every MCP request; actuator endpoints are not exposed by this standalone service. The modern SDK is currently pinned to the same preview release proven in the protocol POC. Confirm a stable SDK release and run real Claude and Gemini connector tests before production use.

## Rollout

1. Build and unit-test this module.
2. Deploy behind a private test route, with a dedicated MCP audience and JWK configuration.
3. Verify discovery, tools/list, and read/write operations using Claude and Gemini.
4. Only then switch the gateway route; retain the legacy service for rollback.
