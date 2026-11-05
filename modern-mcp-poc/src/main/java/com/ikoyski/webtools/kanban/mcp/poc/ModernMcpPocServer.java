package com.ikoyski.webtools.kanban.mcp.poc;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import io.modelcontextprotocol.modern.McpSchema.CallToolOutcome;
import io.modelcontextprotocol.modern.McpSchema.CallToolRequest;
import io.modelcontextprotocol.modern.McpSchema.CallToolResult;
import io.modelcontextprotocol.modern.McpSchema.Implementation;
import io.modelcontextprotocol.modern.McpSchema.TextContent;
import io.modelcontextprotocol.modern.McpSchema.Tool;
import io.modelcontextprotocol.modern.server.McpRequestContext;
import io.modelcontextprotocol.modern.server.McpServer;
import io.modelcontextprotocol.modern.server.McpSyncResponse;
import io.modelcontextprotocol.modern.server.feature.McpSyncToolRepository;
import io.modelcontextprotocol.modern.server.feature.ToolsFeature;
import io.modelcontextprotocol.modern.server.feature.ToolsPage;
import io.modelcontextprotocol.modern.server.transport.HttpServletMcpTransport;
import org.apache.catalina.Context;
import org.apache.catalina.LifecycleException;
import org.apache.catalina.Wrapper;
import org.apache.catalina.startup.Tomcat;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Loopback-only transport spike. This intentionally has no JWT filter and does not call
 * the Kanban API. It proves that the 2026-07-28 protocol can answer server/discover and
 * execute a tool before authentication and production tool integration are attempted.
 */
public final class ModernMcpPocServer {

    private static final Logger log = LoggerFactory.getLogger(ModernMcpPocServer.class);
    private static final int PORT = Integer.getInteger("server.port", 8099);
    private static final String ENDPOINT = "/mcp";

    private ModernMcpPocServer() {
    }

    public static void main(String[] args) throws Exception {
        var repository = new PocToolRepository();
        var mcpServer = McpServer.builder()
                .serverInfo(Implementation.builder("webtools-kanban-mcp-modern-poc", "0.0.1-SNAPSHOT").build())
                .instructions("Local protocol compatibility test only. The returned board is fixture data.")
                .feature(ToolsFeature.ofSync(repository))
                .build();

        var transport = HttpServletMcpTransport.builder(mcpServer)
                .endpoint(ENDPOINT)
                .build();

        Tomcat tomcat = createTomcat(transport);
        try {
            tomcat.start();
            log.info("Modern MCP POC listening on http://127.0.0.1:{}{}", PORT, ENDPOINT);
            log.warn("This local POC has no authentication and must not be exposed to a network.");
            tomcat.getServer().await();
        }
        catch (LifecycleException ex) {
            log.error("Unable to start the modern MCP POC server", ex);
            throw ex;
        }
        finally {
            transport.closeGracefully();
            try {
                tomcat.stop();
                tomcat.destroy();
            }
            catch (LifecycleException ex) {
                log.warn("Error while stopping embedded Tomcat", ex);
            }
        }
    }

    private static Tomcat createTomcat(HttpServletMcpTransport transport) throws Exception {
        Path baseDir = Files.createTempDirectory("kanban-mcp-modern-poc-");
        Tomcat tomcat = new Tomcat();
        tomcat.setPort(PORT);
        tomcat.setHostname("127.0.0.1");
        tomcat.setBaseDir(baseDir.toString());

        Context context = tomcat.addContext("", baseDir.toString());
        Wrapper servlet = context.createWrapper();
        servlet.setName("modernMcpTransport");
        servlet.setServlet(transport);
        servlet.setLoadOnStartup(1);
        servlet.setAsyncSupported(true);
        context.addChild(servlet);
        context.addServletMappingDecoded("/*", "modernMcpTransport");
        tomcat.getConnector().setAsyncTimeout(30_000);
        return tomcat;
    }

    private static final class PocToolRepository implements McpSyncToolRepository {

        private static final Tool LIST_BOARDS = Tool.builder(
                "list_boards",
                Map.of("type", "object", "properties", Map.of(), "additionalProperties", false))
                .description("Return a fixture board to verify modern MCP tool discovery and invocation.")
                .build();

        @Override
        public ToolsPage list(McpRequestContext context, String cursor) {
            return ToolsPage.of(List.of(LIST_BOARDS));
        }

        @Override
        public Tool find(McpRequestContext context, String name) {
            return "list_boards".equals(name) ? LIST_BOARDS : null;
        }

        @Override
        public McpSyncResponse<CallToolOutcome> call(McpRequestContext context, CallToolRequest request) {
            if (!"list_boards".equals(request.name())) {
                throw new IllegalArgumentException("Unknown POC tool: " + request.name());
            }

            String fixture = """
                    [
                      {
                        "id": "poc-board-001",
                        "name": "Modern MCP Protocol Test Board"
                      }
                    ]
                    """.strip();

            return McpSyncResponse.result(CallToolResult.builder()
                    .addContent(TextContent.builder(fixture).build())
                    .build());
        }
    }
}
