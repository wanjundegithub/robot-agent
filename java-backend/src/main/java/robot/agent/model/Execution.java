package robot.agent.model;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "execution")
public class Execution {
    @Id
    @Column(name = "id", length = 64)
    private String id;

    @Column(name = "session_id", length = 64, nullable = false)
    private String sessionId;

    @Column(name = "workflow_code", length = 64, nullable = false)
    private String workflowCode;

    @Column(name = "workflow_version", length = 32, nullable = false)
    private String workflowVersion;

    @Column(name = "client_message_id", length = 64)
    private String clientMessageId;

    @Convert(converter = ExecutionStatusConverter.class)
    @Column(name = "status", length = 20, nullable = false)
    private ExecutionStatus status = ExecutionStatus.PENDING;

    @Column(name = "current_node_id", length = 64)
    private String currentNodeId;

    @Column(name = "input_variables", columnDefinition = "JSON")
    private String inputVariables;

    @Column(name = "output_variables", columnDefinition = "JSON")
    private String outputVariables;

    @Column(name = "variables", columnDefinition = "JSON")
    private String variables;

    @Column(name = "started_at")
    private LocalDateTime startedAt;

    @Column(name = "completed_at")
    private LocalDateTime completedAt;

    @Column(name = "error", columnDefinition = "TEXT")
    private String error;

    @Column(name = "metrics", columnDefinition = "JSON")
    private String metrics;

    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    // Getters and Setters
    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getSessionId() { return sessionId; }
    public void setSessionId(String sessionId) { this.sessionId = sessionId; }

    public String getWorkflowCode() { return workflowCode; }
    public void setWorkflowCode(String workflowCode) { this.workflowCode = workflowCode; }

    public String getWorkflowVersion() { return workflowVersion; }
    public void setWorkflowVersion(String workflowVersion) { this.workflowVersion = workflowVersion; }

    public String getClientMessageId() { return clientMessageId; }
    public void setClientMessageId(String clientMessageId) { this.clientMessageId = clientMessageId; }

    public ExecutionStatus getStatus() { return status; }
    public void setStatus(ExecutionStatus status) { this.status = status; }

    public String getCurrentNodeId() { return currentNodeId; }
    public void setCurrentNodeId(String currentNodeId) { this.currentNodeId = currentNodeId; }

    public String getInputVariables() { return inputVariables; }
    public void setInputVariables(String inputVariables) { this.inputVariables = inputVariables; }

    public String getOutputVariables() { return outputVariables; }
    public void setOutputVariables(String outputVariables) { this.outputVariables = outputVariables; }

    public String getVariables() { return variables; }
    public void setVariables(String variables) { this.variables = variables; }

    public LocalDateTime getStartedAt() { return startedAt; }
    public void setStartedAt(LocalDateTime startedAt) { this.startedAt = startedAt; }

    public LocalDateTime getCompletedAt() { return completedAt; }
    public void setCompletedAt(LocalDateTime completedAt) { this.completedAt = completedAt; }

    public String getError() { return error; }
    public void setError(String error) { this.error = error; }

    public String getMetrics() { return metrics; }
    public void setMetrics(String metrics) { this.metrics = metrics; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
