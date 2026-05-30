package robot.agent.channel.protocol;

import java.util.Arrays;
import java.util.Optional;

public enum FrameType {
    CONNECT(8),
    INTERACTIVE(9);

    private final int code;

    FrameType(int code) {
        this.code = code;
    }

    public int code() {
        return code;
    }

    public static Optional<FrameType> fromCode(int code) {
        return Arrays.stream(values())
                .filter(type -> type.code == code)
                .findFirst();
    }
}
