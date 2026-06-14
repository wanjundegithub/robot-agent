package robot.agent.dto.response;

import robot.agent.model.KnowledgeTask;
import robot.agent.model.KnowledgeTaskStage;
import robot.agent.model.KnowledgeTaskStatus;

import java.time.LocalDateTime;

public class KnowledgeTaskResponse {
    private String taskId;
    private String docId;
    private String kbCode;
    private KnowledgeTaskStage stage;
    private KnowledgeTaskStatus status;
    private Integer progress;
    private String errorMessage;
    private Integer retryCount;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private LocalDateTime startedAt;
    private LocalDateTime completedAt;

    public static KnowledgeTaskResponse fromEntity(KnowledgeTask task) {
        KnowledgeTaskResponse response = new KnowledgeTaskResponse();
        response.setTaskId(task.getTaskId());
        response.setDocId(task.getDocId());
        response.setKbCode(task.getKbCode());
        response.setStage(task.getStage());
        response.setStatus(task.getStatus());
        response.setProgress(task.getProgress());
        response.setErrorMessage(task.getErrorMessage());
        response.setRetryCount(task.getRetryCount());
        response.setCreatedAt(task.getCreatedAt());
        response.setUpdatedAt(task.getUpdatedAt());
        response.setStartedAt(task.getStartedAt());
        response.setCompletedAt(task.getCompletedAt());
        return response;
    }

    public String getTaskId() { return taskId; }
    public void setTaskId(String taskId) { this.taskId = taskId; }
    public String getDocId() { return docId; }
    public void setDocId(String docId) { this.docId = docId; }
    public String getKbCode() { return kbCode; }
    public void setKbCode(String kbCode) { this.kbCode = kbCode; }
    public KnowledgeTaskStage getStage() { return stage; }
    public void setStage(KnowledgeTaskStage stage) { this.stage = stage; }
    public KnowledgeTaskStatus getStatus() { return status; }
    public void setStatus(KnowledgeTaskStatus status) { this.status = status; }
    public Integer getProgress() { return progress; }
    public void setProgress(Integer progress) { this.progress = progress; }
    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }
    public Integer getRetryCount() { return retryCount; }
    public void setRetryCount(Integer retryCount) { this.retryCount = retryCount; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
    public LocalDateTime getStartedAt() { return startedAt; }
    public void setStartedAt(LocalDateTime startedAt) { this.startedAt = startedAt; }
    public LocalDateTime getCompletedAt() { return completedAt; }
    public void setCompletedAt(LocalDateTime completedAt) { this.completedAt = completedAt; }
}
