package robot.agent.apicenter.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

@Entity
@Table(name = "api_item")
public class ApiItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "group_id", nullable = false)
    private Long groupId;

    @Column(name = "api_name", length = 128, nullable = false)
    private String apiName;

    @Column(name = "description", length = 500)
    private String description;

    @Column(name = "enabled", nullable = false)
    private Boolean enabled = true;

    @Column(name = "request_url", length = 1000, nullable = false)
    private String requestUrl;

    @Column(name = "request_method", length = 16, nullable = false)
    private String requestMethod;

    @Column(name = "auth_mode", length = 32, nullable = false)
    private String authMode = "INHERIT";

    @Lob
    @Column(name = "headers_ciphertext", columnDefinition = "TEXT")
    private String headersCiphertext;

    @Lob
    @Column(name = "input_schema", columnDefinition = "TEXT")
    private String inputSchema;

    @Lob
    @Column(name = "output_schema", columnDefinition = "TEXT")
    private String outputSchema;

    @Column(name = "last_test_status", length = 32)
    private String lastTestStatus;

    @Column(name = "last_test_time")
    private LocalDateTime lastTestTime;

    @Column(name = "last_test_error_message", length = 1000)
    private String lastTestErrorMessage;

    @Column(name = "last_test_token", length = 64)
    private String lastTestToken;

    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    @Column(name = "updated_at")
    private LocalDateTime updatedAt = LocalDateTime.now();

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getGroupId() {
        return groupId;
    }

    public void setGroupId(Long groupId) {
        this.groupId = groupId;
    }

    public String getApiName() {
        return apiName;
    }

    public void setApiName(String apiName) {
        this.apiName = apiName;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public Boolean getEnabled() {
        return enabled;
    }

    public void setEnabled(Boolean enabled) {
        this.enabled = enabled;
    }

    public String getRequestUrl() {
        return requestUrl;
    }

    public void setRequestUrl(String requestUrl) {
        this.requestUrl = requestUrl;
    }

    public String getRequestMethod() {
        return requestMethod;
    }

    public void setRequestMethod(String requestMethod) {
        this.requestMethod = requestMethod;
    }

    public String getAuthMode() {
        return authMode;
    }

    public void setAuthMode(String authMode) {
        this.authMode = authMode;
    }

    public String getHeadersCiphertext() {
        return headersCiphertext;
    }

    public void setHeadersCiphertext(String headersCiphertext) {
        this.headersCiphertext = headersCiphertext;
    }

    public String getInputSchema() {
        return inputSchema;
    }

    public void setInputSchema(String inputSchema) {
        this.inputSchema = inputSchema;
    }

    public String getOutputSchema() {
        return outputSchema;
    }

    public void setOutputSchema(String outputSchema) {
        this.outputSchema = outputSchema;
    }

    public String getLastTestStatus() {
        return lastTestStatus;
    }

    public void setLastTestStatus(String lastTestStatus) {
        this.lastTestStatus = lastTestStatus;
    }

    public LocalDateTime getLastTestTime() {
        return lastTestTime;
    }

    public void setLastTestTime(LocalDateTime lastTestTime) {
        this.lastTestTime = lastTestTime;
    }

    public String getLastTestErrorMessage() {
        return lastTestErrorMessage;
    }

    public void setLastTestErrorMessage(String lastTestErrorMessage) {
        this.lastTestErrorMessage = lastTestErrorMessage;
    }

    public String getLastTestToken() {
        return lastTestToken;
    }

    public void setLastTestToken(String lastTestToken) {
        this.lastTestToken = lastTestToken;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(LocalDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }
}
