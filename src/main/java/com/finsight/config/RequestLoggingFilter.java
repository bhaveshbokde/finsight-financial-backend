package com.finsight.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.lang.NonNull;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

/**
 * Assigns each request a trace id and logs its outcome.
 *
 * <p>Runs at {@link Ordered#HIGHEST_PRECEDENCE} so the trace id exists before the
 * security filters run — otherwise an authentication failure, which is exactly the
 * kind of event someone needs to trace, would be logged without one.
 *
 * <p>The same id is returned in every {@link com.finsight.common.error.ApiError}
 * and in the {@code X-Trace-Id} response header, so a user-reported failure maps to
 * a specific log line with no stack trace in the response.
 *
 * <h2>What is deliberately not logged</h2>
 * No request bodies, no query strings, no headers. Bodies carry passwords and
 * financial detail; the {@code Authorization} header carries a live bearer token;
 * a query string can carry a search term. Method, path, status and duration are
 * enough to find a request, and none of them is sensitive.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestLoggingFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(RequestLoggingFilter.class);

    public static final String TRACE_ID_HEADER = "X-Trace-Id";
    public static final String MDC_TRACE_ID = "traceId";

    /** Slower than this and the request is logged at WARN so it stands out. */
    private static final long SLOW_REQUEST_MILLIS = 1000;

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request,
                                    @NonNull HttpServletResponse response,
                                    @NonNull FilterChain chain) throws ServletException, IOException {

        // Honour an inbound trace id so a request crossing several services keeps
        // one identity, but sanitise it — it is attacker-controlled and ends up in
        // log lines, where a newline could forge a second entry (log injection).
        String traceId = sanitise(request.getHeader(TRACE_ID_HEADER));
        if (traceId == null) {
            traceId = UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        }

        MDC.put(MDC_TRACE_ID, traceId);
        response.setHeader(TRACE_ID_HEADER, traceId);

        long startedAt = System.nanoTime();
        try {
            chain.doFilter(request, response);
        } finally {
            long millis = (System.nanoTime() - startedAt) / 1_000_000;
            int status = response.getStatus();

            if (status >= 500 || millis > SLOW_REQUEST_MILLIS) {
                log.warn("{} {} -> {} in {}ms", request.getMethod(), request.getRequestURI(),
                        status, millis);
            } else if (log.isInfoEnabled() && !isNoisy(request.getRequestURI())) {
                log.info("{} {} -> {} in {}ms", request.getMethod(), request.getRequestURI(),
                        status, millis);
            }

            // Must be cleared: the servlet container reuses this thread, and a
            // leftover trace id would attach to an unrelated request's logs.
            MDC.remove(MDC_TRACE_ID);
        }
    }

    /** Keeps health checks and metric scrapes out of the request log. */
    private boolean isNoisy(String path) {
        return path.startsWith("/actuator/health")
                || path.startsWith("/actuator/prometheus")
                || path.startsWith("/swagger-ui")
                || path.startsWith("/v3/api-docs");
    }

    /** Allows only hex-ish identifiers of a sane length; anything else is discarded. */
    private String sanitise(String candidate) {
        if (candidate == null || candidate.isBlank()) {
            return null;
        }
        String trimmed = candidate.trim();
        if (trimmed.length() > 64 || !trimmed.matches("^[A-Za-z0-9_-]+$")) {
            return null;
        }
        return trimmed;
    }
}
