package com.ikoyski.webtools.kanban.mcp.observability;

import java.io.IOException;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Adds a validated request ID to every servlet request and emits a safe access
 * summary to stdout. Request/response bodies, query strings and credentials are
 * deliberately never logged.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestCorrelationLoggingFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(RequestCorrelationLoggingFilter.class);
    private static final String REQUEST_ID_HEADER = "X-Request-Id";
    private static final Pattern SAFE_REQUEST_ID = Pattern.compile("[A-Za-z0-9._:-]{1,64}");

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String requestId = validatedRequestId(request.getHeader(REQUEST_ID_HEADER));
        String previousRequestId = MDC.get("requestId");
        long startedAt = System.nanoTime();

        MDC.put("requestId", requestId);
        response.setHeader(REQUEST_ID_HEADER, requestId);

        Throwable failure = null;
        try {
            filterChain.doFilter(request, response);
        } catch (IOException | ServletException | RuntimeException ex) {
            failure = ex;
            throw ex;
        } finally {
            long durationMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt);
            int status = response.getStatus();
            String path = request.getRequestURI();
            String relativePath = path.startsWith(request.getContextPath())
                    ? path.substring(request.getContextPath().length()) : path;

            if (!isHealthOrMetricsPath(relativePath)) {
                if (failure != null) {
                    log.error("http_request_failed method={} path={} status={} durationMs={}",
                            request.getMethod(), path, status, durationMs, failure);
                } else if (status >= 500) {
                    log.error("http_request_completed method={} path={} status={} durationMs={}",
                            request.getMethod(), path, status, durationMs);
                } else if (status >= 400) {
                    log.warn("http_request_completed method={} path={} status={} durationMs={}",
                            request.getMethod(), path, status, durationMs);
                } else {
                    log.info("http_request_completed method={} path={} status={} durationMs={}",
                            request.getMethod(), path, status, durationMs);
                }
            }

            if (previousRequestId == null) {
                MDC.remove("requestId");
            } else {
                MDC.put("requestId", previousRequestId);
            }
        }
    }

    private static String validatedRequestId(String candidate) {
        return candidate != null && SAFE_REQUEST_ID.matcher(candidate).matches()
                ? candidate : UUID.randomUUID().toString();
    }

    private static boolean isHealthOrMetricsPath(String path) {
        return path.startsWith("/actuator/health")
                || path.equals("/actuator/prometheus");
    }
}
