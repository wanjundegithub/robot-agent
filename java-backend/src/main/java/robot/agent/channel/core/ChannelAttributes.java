package robot.agent.channel.core;

import io.netty.util.AttributeKey;

public final class ChannelAttributes {

    public static final AttributeKey<UserChannelContext> USER_CONTEXT = AttributeKey.valueOf("robot.user.context");
    public static final AttributeKey<String> USER_ID = AttributeKey.valueOf("robot.user.id");
    public static final AttributeKey<String> CONNECTION_ID = AttributeKey.valueOf("robot.connection.id");

    private ChannelAttributes() {
    }
}
