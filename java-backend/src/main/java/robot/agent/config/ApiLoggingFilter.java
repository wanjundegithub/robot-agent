package robot.agent.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ContentCachingRequestWrapper;
import org.springframework.web.util.ContentCachingResponseWrapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class ApiLoggingFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(ApiLoggingFilter.class);
    private static final int MAX_BODY_LOG_LENGTH = 2048;

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

        try {
            filterChain.doFilter(requestWrapper, responseWrapper);
        } finally {
            long durationMs = System.currentTimeMillis() - startedAt;
            log.info(
                    "http.api method={} uri={} query={} userId={} status={} durationMs={} requestBody={} responseBody={}",
                    requestWrapper.getMethod(),
                    requestWrapper.getRequestURI(),
                    requestWrapper.getQueryString(),
                    requestWrapper.getHeader("X-User-Id"),
                    responseWrapper.getStatus(),
                    durationMs,
                    bodyPreview(requestWrapper.getContentAsByteArray()),
                    bodyPreview(responseWrapper.getContentAsByteArray())
            );
            responseWrapper.copyBodyToResponse();
        }
    }

    private String bodyPreview(byte[] content) {
        if (content == null || content.length == 0) {
            return "";
        }
        String text = new String(content, StandardCharsets.UTF_8).replaceAll("\\s+", " ").trim();
        return text.length() <= MAX_BODY_LOG_LENGTH ? text : text.substring(0, MAX_BODY_LOG_LENGTH) + "...";
    }
}
