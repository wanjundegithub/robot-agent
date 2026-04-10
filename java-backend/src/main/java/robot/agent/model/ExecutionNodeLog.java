package robot.agent.model;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "execution_node_log")
public class ExecutionNodeLog {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "execution_id", length = 64, nullable = false)
    private String executionId;

    @Column(name = "node_id", length = 64, nullable = false)
    private String nodeId;

    @Column(name = "node_type", length = 32, nullable = false)
    private String nodeType;

    @Column(name = "status", length = 20, nullable = false)
    private String status;

    @Column(name = "input", columnDefinition = "JSON")
    private String input;

    @Column(name = "output", columnDefinition = "JSON")
    private String output;

    @Column(name = "error", columnDefinition = "TEXT")
    private String error;

    @Column(name = "started_at")
    private LocalDateTime startedAt;

    @Column(name = "completed_at")
    private LocalDateTime completedAt;

    @Column(name = "metrics", columnDefinition = "JSON")
    private String metrics;

    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getExecutionId() { return executionId; }
    public void setExecutionId(String executionId) { this.executionId = executionId; }

    public String getNodeId() { return nodeId; }
    public void setNodeId(String nodeId) { this.nodeId = nodeId; }

    public String getNodeType() { return nodeType; }
    public void setNodeType(String nodeType) { this.nodeType = nodeType; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public String getInput() { return input; }
    public void setInput(String input) { this.input = input; }

    public String getOutput() { return output; }
    public void setOutput(String output) { this.output = output; }

    public String getError() { return error; }
    public void setError(String error) { this.error = error; }

    public LocalDateTime getStartedAt() { return startedAt; }
    public void setStartedAt(LocalDateTime startedAt) { this.startedAt = startedAt; }

    public LocalDateTime getCompletedAt() { return completedAt; }
    public void setCompletedAt(LocalDateTime completedAt) { this.completedAt = completedAt; }

    public String getMetrics() { return metrics; }
    public void setMetrics(String metrics) { this.metrics = metrics; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
