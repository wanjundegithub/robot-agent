package robot.agent.model;

public enum ExecutionStatus {
    PENDING("pending"),
    RUNNING("running"),
    WAITING_USER("waiting_user"),
    WAITING_TOOL("waiting_tool"),
    SUSPENDED("suspended"),
    COMPLETED("completed"),
    FAILED("failed"),
    CANCELLED("cancelled");

    private final String value;

    ExecutionStatus(String value) {
        this.value = value;
    }

    public String getValue() {
        return value;
    }

    public boolean isTerminal() {
        return this == COMPLETED || this == FAILED || this == CANCELLED;
    }
}
