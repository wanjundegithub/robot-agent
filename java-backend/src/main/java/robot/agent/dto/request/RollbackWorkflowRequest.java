package robot.agent.dto.request;

public class RollbackWorkflowRequest {
    private String version;

    public String getVersion() {
        return version;
    }

    public void setVersion(String version) {
        this.version = version;
    }
}
