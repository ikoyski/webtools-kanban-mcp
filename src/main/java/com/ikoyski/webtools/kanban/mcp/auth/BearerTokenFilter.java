package com.ikoyski.webtools.kanban.mcp.auth;

import java.io.IOException;

import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Rejects any /mcp request without a Bearer token (including tools/list), and stores
 * the header for the outbound API calls made while handling this request.
 *
 * This only checks that a token is PRESENT. The gateway validates it and the backend
 * enforces board access, so a bad or expired token fails there with 401/403.
 */
@Component
public class BearerTokenFilter extends OncePerRequestFilter {

    private static final int MAX_HEADER_LENGTH = 4096;

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
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setHeader("WWW-Authenticate", "Bearer");
            response.setContentType("application/json");
            response.getWriter().write("{\"error\":\"missing or invalid bearer token\"}");
            return;
        }
        TokenContext.set(header);
        try {
            chain.doFilter(request, response);
        } finally {
            TokenContext.clear();
        }
    }
}
