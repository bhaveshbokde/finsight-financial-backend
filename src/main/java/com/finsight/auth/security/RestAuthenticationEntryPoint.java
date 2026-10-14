package com.finsight.auth.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.finsight.common.error.ApiError;
import com.finsight.common.error.ApiException;
import com.finsight.common.error.ErrorCode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.UUID;

/**
 * Produces the 401 body for unauthenticated requests to protected endpoints.
 *
 * <p>Exists because {@code GlobalExceptionHandler} cannot help here: the security
 * filter chain runs <em>before</em> the {@code DispatcherServlet}, so nothing a
 * filter throws ever reaches a {@code @RestControllerAdvice}. Without this class
 * the caller would get Spring Security's default empty 401 — a different shape
 * from every other error this API returns.
 *
 * <p>It reads the attribute set by {@link JwtAuthenticationFilter} so the response
 * distinguishes an <em>expired</em> token (client should refresh) from an
 * <em>invalid</em> one (client should re-authenticate), which a bare 401 cannot.
 *
 * <p>The {@code WWW-Authenticate} header is set per RFC 6750 §3.
 */
@Component
public class RestAuthenticationEntryPoint implements AuthenticationEntryPoint {

    private static final Logger log = LoggerFactory.getLogger(RestAuthenticationEntryPoint.class);

    private final ObjectMapper objectMapper;

    public RestAuthenticationEntryPoint(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response,
                         AuthenticationException authException) throws IOException {

        ErrorCode code = ErrorCode.AUTHENTICATION_REQUIRED;
        String message = "Authentication is required to access this resource";

        if (request.getAttribute(JwtAuthenticationFilter.ATTR_AUTH_ERROR) instanceof ApiException tokenError) {
            code = tokenError.errorCode();
            message = tokenError.getMessage();
        }

        String traceId = shortId();
        log.warn("401 {} [traceId={}] on {} {}", code, traceId, request.getMethod(), request.getRequestURI());

        response.setStatus(code.status().value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        response.setHeader("WWW-Authenticate",
                "Bearer realm=\"finsight\", error=\"%s\"".formatted(code.name().toLowerCase()));

        ApiError body = ApiError.of(code, message, request.getRequestURI(), traceId);
        objectMapper.writeValue(response.getOutputStream(), body);
    }

    private String shortId() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    }
}
