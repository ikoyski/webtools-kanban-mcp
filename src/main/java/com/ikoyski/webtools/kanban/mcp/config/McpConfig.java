package com.ikoyski.webtools.kanban.mcp.config;

import java.net.http.HttpClient;
import java.time.Duration;

import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.client.loadbalancer.LoadBalanced;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import com.ikoyski.webtools.kanban.mcp.auth.TokenContext;
import com.ikoyski.webtools.kanban.mcp.tools.KanbanTools;

@Configuration
public class McpConfig {

    /**
     * Enables Eureka service-ID resolution for RestClient URLs such as
     * http://WEBTOOLS-KANBAN-BACKEND/...
     */
    @Bean
    @LoadBalanced
    public RestClient.Builder loadBalancedRestClientBuilder() {
        return RestClient.builder();
    }

    @Bean
    public RestClient kanbanRestClient(
            RestClient.Builder restClientBuilder,
            @Value("${kanban.api.base-url}") String baseUrl,
            @Value("${kanban.api.connect-timeout-ms}") int connectTimeoutMs,
            @Value("${kanban.api.read-timeout-ms}") int readTimeoutMs) {

        // Keep the JDK client: it supports PATCH, which card/column updates require.
        var httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(connectTimeoutMs))
                .build();
        var factory = new JdkClientHttpRequestFactory(httpClient);
        factory.setReadTimeout(Duration.ofMillis(readTimeoutMs));

        return restClientBuilder
                .baseUrl(baseUrl)
                .requestFactory(factory)
                .requestInterceptor((request, body, execution) -> {
                    // Forward the caller's own token. Never use a shared service account.
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
