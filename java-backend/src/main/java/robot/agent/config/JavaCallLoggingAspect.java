package robot.agent.config;

import jakarta.servlet.http.HttpServletRequest;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.Arrays;
import java.util.Collection;
import java.util.Map;
import java.util.stream.Collectors;

@Aspect
@Component
@ConditionalOnProperty(name = "robot.logging.java-call.enabled", havingValue = "true")
public class JavaCallLoggingAspect {

    private static final Logger log = LoggerFactory.getLogger(JavaCallLoggingAspect.class);
    private static final int MAX_VALUE_LENGTH = 200;

    @Around("within(robot.agent.controller..*) || within(robot.agent.service..*)")
    public Object logJavaCall(ProceedingJoinPoint joinPoint) throws Throwable {
        long startedAt = System.currentTimeMillis();
        String className = joinPoint.getSignature().getDeclaringType().getSimpleName();
        String methodName = joinPoint.getSignature().getName();
        String callName = className + "." + methodName;
        HttpServletRequest request = currentRequest();
        log.info(
                "java.call.start call={} requestId={} correlationId={} httpMethod={} path={} args={}",
                callName,
                MDC.get("requestId"),
                MDC.get("correlationId"),
                request == null ? "" : request.getMethod(),
                request == null ? "" : request.getRequestURI(),
                summarizeArguments(joinPoint.getArgs())
        );
        try {
            Object result = joinPoint.proceed();
            log.info(
                    "java.call.success call={} durationMs={} result={}",
                    callName,
                    System.currentTimeMillis() - startedAt,
                    summarizeValue(result)
            );
            return result;
        } catch (Throwable throwable) {
            log.error(
                    "java.call.failed call={} durationMs={} errorType={} message={}",
                    callName,
                    System.currentTimeMillis() - startedAt,
                    throwable.getClass().getSimpleName(),
                    preview(throwable.getMessage()),
                    throwable
            );
            throw throwable;
        }
    }

    private HttpServletRequest currentRequest() {
        if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes) {
            return attributes.getRequest();
        }
        return null;
    }

    private String summarizeArguments(Object[] arguments) {
        if (arguments == null || arguments.length == 0) {
            return "[]";
        }
        return Arrays.stream(arguments)
                .map(this::summarizeValue)
                .collect(Collectors.joining(", ", "[", "]"));
    }

    private String summarizeValue(Object value) {
        if (value == null) {
            return "null";
        }
        if (value instanceof CharSequence text) {
            return preview(text.toString());
        }
        if (value instanceof Number || value instanceof Boolean || value instanceof Enum<?>) {
            return String.valueOf(value);
        }
        if (value instanceof Collection<?> collection) {
            return value.getClass().getSimpleName() + "(size=" + collection.size() + ")";
        }
        if (value instanceof Map<?, ?> map) {
            return value.getClass().getSimpleName() + "(keys=" + map.keySet().stream().limit(8).toList() + ")";
        }
        if (value.getClass().isArray()) {
            return value.getClass().getComponentType().getSimpleName() + "[]";
        }
        String packageName = value.getClass().getPackageName();
        if (packageName.startsWith("robot.agent.dto") || packageName.startsWith("robot.agent.model")) {
            return value.getClass().getSimpleName();
        }
        return value.getClass().getSimpleName();
    }

    private String preview(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        String normalized = value.replaceAll("\s+", " ").trim();
        String lower = normalized.toLowerCase();
        if (lower.contains("authorization")
                || lower.contains("password")
                || lower.contains("token")
                || lower.contains("secret")
                || lower.contains("api_key")
                || lower.contains("apikey")
                || lower.contains("cookie")) {
            return "<redacted-sensitive-text>";
        }
        return normalized.length() <= MAX_VALUE_LENGTH
                ? normalized
                : normalized.substring(0, MAX_VALUE_LENGTH) + "...";
    }
}
