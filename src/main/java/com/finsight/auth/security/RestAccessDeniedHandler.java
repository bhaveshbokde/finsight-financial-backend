package com.finsight.auth.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.finsight.common.error.ApiError;
import com.finsight.common.error.ErrorCode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.UUID;

/**
 * Produces the 403 body when an <em>authenticated</em> caller lacks the required
 * authority — for example {@code ROLE_USER} hitting an admin-only Actuator endpoint.
 *
 * <p>The distinction from {@link RestAuthenticationEntryPoint} matters: 401 means
 * "I do not know who you are", 403 means "I know who you are and the answer is no".
 * Returning 401 for the second case would send a correctly-authenticated client
 * into a pointless re-login loop.
 *
 * <p>Note this handler is never reached for "this row belongs to another user" —
 * those lookups are scoped by {@code userId} in the query and surface as 404 by
 * design, so the API does not confirm that someone else's id exists.
 */
@Component
public class RestAccessDeniedHandler implements AccessDeniedHandler {

    private static final Logger log = LoggerFactory.getLogger(RestAccessDeniedHandler.class);

    private final ObjectMapper objectMapper;

    public RestAccessDeniedHandler(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response,
                       AccessDeniedException ex) throws IOException {

        String traceId = UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        Object principal = SecurityContextHolder.getContext().getAuthentication() == null
                ? null : SecurityContextHolder.getContext().getAuthentication().getPrincipal();

        log.warn("403 ACCESS_DENIED [traceId={}] principal={} on {} {}",
                traceId, principal, request.getMethod(), request.getRequestURI());

        response.setStatus(ErrorCode.ACCESS_DENIED.status().value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");

        ApiError body = ApiError.of(ErrorCode.ACCESS_DENIED,
                ErrorCode.ACCESS_DENIED.defaultMessage(), request.getRequestURI(), traceId);
        objectMapper.writeValue(response.getOutputStream(), body);
    }
}
