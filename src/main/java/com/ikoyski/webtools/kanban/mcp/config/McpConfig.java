package com.ikoyski.webtools.kanban.mcp.config;

import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import com.ikoyski.webtools.kanban.mcp.auth.TokenContext;
import com.ikoyski.webtools.kanban.mcp.tools.KanbanTools;

@Configuration
public class McpConfig {

    @Bean
    public RestClient kanbanRestClient(
            @Value("${kanban.api.base-url}") String baseUrl,
            @Value("${kanban.api.connect-timeout-ms}") int connectTimeoutMs,
            @Value("${kanban.api.read-timeout-ms}") int readTimeoutMs) {

        var factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(connectTimeoutMs);
        factory.setReadTimeout(readTimeoutMs);

        return RestClient.builder()
                .baseUrl(baseUrl)
                .requestFactory(factory)
                .requestInterceptor((request, body, execution) -> {
                    // Forward the caller's own token. Never a shared service account.
                    String auth = TokenContext.get();
                    if (auth != null) {
                        request.getHeaders().set(HttpHeaders.AUTHORIZATION, auth);
                    }
                    return execution.execute(request, body);
                })
                .build();
    }

    /** Registers every @Tool method on KanbanTools as an MCP tool. */
    @Bean
    public ToolCallbackProvider kanbanToolCallbacks(KanbanTools tools) {
        return MethodToolCallbackProvider.builder().toolObjects(tools).build();
    }
}
