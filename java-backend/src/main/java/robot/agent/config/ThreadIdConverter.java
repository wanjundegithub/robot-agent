package robot.agent.config;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.pattern.ClassicConverter;

public class ThreadIdConverter extends ClassicConverter {
    @Override
    public String convert(ILoggingEvent event) {
        return String.valueOf(Thread.currentThread().threadId());
    }
}
