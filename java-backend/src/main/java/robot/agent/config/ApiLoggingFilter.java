package robot.agent.config;

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
import org.springframework.web.util.ContentCachingRequestWrapper;
import org.springframework.web.util.ContentCachingResponseWrapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class ApiLoggingFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(ApiLoggingFilter.class);
    private static final String REQUEST_ID_HEADER = "X-Request-Id";
    private static final String CORRELATION_ID_HEADER = "X-Correlation-Id";

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String uri = request.getRequestURI();
        return uri == null || !uri.startsWith("/api/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        ContentCachingRequestWrapper requestWrapper = new ContentCachingRequestWrapper(request);
        ContentCachingResponseWrapper responseWrapper = new ContentCachingResponseWrapper(response);
        long startedAt = System.currentTimeMillis();
        String requestId = resolveOrGenerate(requestWrapper.getHeader(REQUEST_ID_HEADER));
        String correlationId = resolveOrGenerate(requestWrapper.getHeader(CORRELATION_ID_HEADER));
        String previousRequestId = MDC.get("requestId");
        String previousCorrelationId = MDC.get("correlationId");
        MDC.put("requestId", requestId);
        MDC.put("correlationId", correlationId);
        responseWrapper.setHeader(REQUEST_ID_HEADER, requestId);
        responseWrapper.setHeader(CORRELATION_ID_HEADER, correlationId);

        try {
            filterChain.doFilter(requestWrapper, responseWrapper);
        } finally {
            try {
                long durationMs = System.currentTimeMillis() - startedAt;
                log.info(
                        "http.inbound method={} path={} status={} durationMs={} requestId={} correlationId={} clientIp={} userAgent={}",
                        requestWrapper.getMethod(),
                        requestWrapper.getRequestURI(),
                        responseWrapper.getStatus(),
                        durationMs,
                        requestId,
                        correlationId,
                        requestWrapper.getRemoteAddr(),
                        headerPreview(requestWrapper.getHeader("User-Agent"))
                );
                responseWrapper.copyBodyToResponse();
            } finally {
                restoreMdc("requestId", previousRequestId);
                restoreMdc("correlationId", previousCorrelationId);
            }
        }
    }

    private String resolveOrGenerate(String headerValue) {
        if (headerValue == null || headerValue.isBlank()) {
            return UUID.randomUUID().toString();
        }
        return headerValue.trim();
    }

    private void restoreMdc(String key, String value) {
        if (value == null) {
            MDC.remove(key);
            return;
        }
        MDC.put(key, value);
    }

    private String headerPreview(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        String text = new String(value.getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8)
                .replaceAll("\\s+", " ").trim();
        return text.length() <= 200 ? text : text.substring(0, 200) + "...";
    }
}
