package robot.agent.channel.dispatch;

import org.springframework.stereotype.Component;
import robot.agent.channel.handler.BusinessEventHandler;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Component
public class UserEventHandlerRegistry {

    private final Map<String, BusinessEventHandler> businessHandlers;

    public UserEventHandlerRegistry(List<BusinessEventHandler> handlers) {
        Map<String, BusinessEventHandler> values = new LinkedHashMap<>();
        handlers.stream()
                .sorted(Comparator.comparingInt(BusinessEventHandler::order))
                .forEach(handler -> {
                    BusinessEventHandler previous = values.put(handler.eventType(), handler);
                    if (previous != null) {
                        throw new IllegalStateException("Duplicate business event handler: " + handler.eventType());
                    }
                });
        this.businessHandlers = Map.copyOf(values);
    }

    public Optional<BusinessEventHandler> businessHandler(String eventType) {
        return Optional.ofNullable(businessHandlers.get(eventType));
    }
}
