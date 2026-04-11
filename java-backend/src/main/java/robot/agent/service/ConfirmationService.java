package robot.agent.service;

import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

@Service
public class ConfirmationService {

    private static final Duration CONFIRMATION_TTL = Duration.ofMinutes(5);

    private final AccessControlService accessControlService;
    private final ConcurrentMap<String, ConfirmationTicket> tickets = new ConcurrentHashMap<>();

    public ConfirmationService(AccessControlService accessControlService) {
        this.accessControlService = accessControlService;
    }

    public ConfirmationEvaluation evaluate(
            String sessionId,
            String userId,
            String content,
            String requestedToolCode,
            String confirmationId,
            boolean cancelConfirmation
    ) {
        String resolvedToolCode = resolveRequestedToolCode(requestedToolCode, content);
        if (!accessControlService.isHighRiskTool(resolvedToolCode)) {
            return ConfirmationEvaluation.proceed(resolvedToolCode);
        }

        evictExpiredTickets();
        if (cancelConfirmation && confirmationId != null && !confirmationId.isBlank()) {
            ConfirmationTicket ticket = tickets.remove(confirmationId);
            if (ticket != null) {
                return ConfirmationEvaluation.cancelled(ticket.toolCode());
            }
        }

        if (confirmationId != null && !confirmationId.isBlank()) {
            ConfirmationTicket ticket = tickets.get(confirmationId);
            if (ticket != null
                    && !ticket.isExpired()
                    && ticket.matches(sessionId, userId, resolvedToolCode)) {
                tickets.remove(confirmationId);
                return ConfirmationEvaluation.approved(ticket.toolCode(), ticket.id());
            }
        }

        ConfirmationTicket ticket = createTicket(sessionId, userId, resolvedToolCode, content);
        return ConfirmationEvaluation.required(ticket);
    }

    public String resolveRequestedToolCode(String requestedToolCode, String content) {
        if (requestedToolCode != null && !requestedToolCode.isBlank()) {
            return requestedToolCode;
        }
        String normalized = content == null ? "" : content.toLowerCase(Locale.ROOT);
        if (normalized.contains("取消订单") || normalized.contains("cancel order")) {
            return "cancel_order";
        }
        if (normalized.contains("更新权限") || normalized.contains("update permission")) {
            return "update_permission";
        }
        if (normalized.contains("删除账号") || normalized.contains("delete account")) {
            return "delete_account";
        }
        if (normalized.contains("转账") || normalized.contains("transfer money")) {
            return "transfer_money";
        }
        return null;
    }

    private ConfirmationTicket createTicket(String sessionId, String userId, String toolCode, String content) {
        ConfirmationTicket ticket = new ConfirmationTicket(
                UUID.randomUUID().toString(),
                sessionId,
                userId == null || userId.isBlank() ? "anonymous" : userId,
                toolCode,
                maskPreview(content),
                LocalDateTime.now().plus(CONFIRMATION_TTL)
        );
        tickets.put(ticket.id(), ticket);
        return ticket;
    }

    private String maskPreview(String content) {
        if (content == null || content.isBlank()) {
            return "";
        }
        String normalized = content.replaceAll("\\s+", " ").trim();
        if (normalized.length() <= 24) {
            return normalized;
        }
        return normalized.substring(0, 24) + "...";
    }

    private void evictExpiredTickets() {
        tickets.entrySet().removeIf(entry -> entry.getValue().isExpired());
    }

    public record ConfirmationTicket(
            String id,
            String sessionId,
            String userId,
            String toolCode,
            String preview,
            LocalDateTime expiresAt
    ) {
        boolean isExpired() {
            return expiresAt.isBefore(LocalDateTime.now());
        }

        boolean matches(String candidateSessionId, String candidateUserId, String candidateToolCode) {
            String effectiveUserId = candidateUserId == null || candidateUserId.isBlank() ? "anonymous" : candidateUserId;
            return sessionId.equals(candidateSessionId)
                    && userId.equals(effectiveUserId)
                    && toolCode.equals(candidateToolCode);
        }
    }

    public record ConfirmationEvaluation(
            String status,
            String toolCode,
            String confirmationId,
            String confirmationExpiresAt,
            String preview
    ) {
        static ConfirmationEvaluation proceed(String toolCode) {
            return new ConfirmationEvaluation("approved", toolCode, null, null, null);
        }

        static ConfirmationEvaluation approved(String toolCode, String confirmationId) {
            return new ConfirmationEvaluation("approved", toolCode, confirmationId, null, null);
        }

        static ConfirmationEvaluation cancelled(String toolCode) {
            return new ConfirmationEvaluation("cancelled", toolCode, null, null, null);
        }

        static ConfirmationEvaluation required(ConfirmationTicket ticket) {
            return new ConfirmationEvaluation(
                    "required",
                    ticket.toolCode(),
                    ticket.id(),
                    ticket.expiresAt().toString(),
                    ticket.preview()
            );
        }

        public boolean requiresConfirmation() {
            return "required".equals(status);
        }

        public boolean cancelled() {
            return "cancelled".equals(status);
        }

        public Map<String, Object> asAuditPayload() {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("tool_code", toolCode);
            if (confirmationId != null) {
                payload.put("confirmation_id", confirmationId);
            }
            if (confirmationExpiresAt != null) {
                payload.put("confirmation_expires_at", confirmationExpiresAt);
            }
            payload.put("preview", preview == null ? "" : preview);
            return payload;
        }
    }
}
