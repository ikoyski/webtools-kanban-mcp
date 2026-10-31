package com.ikoyski.webtools.kanban.mcp.auth;

import java.io.IOException;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Validates the caller's signed access token before any MCP request (including tools/list).
 * The gateway performs its own issuer/signature validation; this second check also enforces
 * the MCP audience so direct access to the service cannot bypass the resource boundary.
 *
 * The original bearer token is retained only for this request and forwarded to the Kanban API.
 */
@Component
public class BearerTokenFilter extends OncePerRequestFilter {

    private static final int MAX_HEADER_LENGTH = 8192;

    private final JwtDecoder jwtDecoder;
    private final String resourceMetadata;

    public BearerTokenFilter(JwtDecoder jwtDecoder,
                             @Value("${security.oauth2.resource-metadata}") String resourceMetadata) {
        this.jwtDecoder = jwtDecoder;
        this.resourceMetadata = resourceMetadata;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI().substring(request.getContextPath().length());
        return path.equals("/actuator") || path.startsWith("/actuator/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String header = request.getHeader("Authorization");
        if (header == null || !header.startsWith("Bearer ") || header.length() > MAX_HEADER_LENGTH) {
            unauthorized(response, "invalid_request", "missing or invalid bearer token");
            return;
        }

        try {
            Jwt jwt = jwtDecoder.decode(header.substring(7));
            if (jwt == null) {
                unauthorized(response, "invalid_token", "invalid bearer token");
                return;
            }
        } catch (JwtException ex) {
            unauthorized(response, "invalid_token", "token validation failed");
            return;
        }

        TokenContext.set(header);
        try {
            chain.doFilter(request, response);
        } finally {
            TokenContext.clear();
        }
    }

    private void unauthorized(HttpServletResponse response, String error, String message) throws IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setHeader("WWW-Authenticate",
                "Bearer error=\"" + error + "\", resource_metadata=\"" + resourceMetadata + "\"");
        response.setContentType("application/json");
        response.getWriter().write("{\"error\":\"" + message + "\"}");
    }
}
