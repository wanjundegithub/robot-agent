package robot.agent.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.SmartLifecycle;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.netty.DisposableServer;
import reactor.netty.http.server.HttpServer;
import reactor.netty.http.server.HttpServerRequest;
import reactor.netty.http.websocket.WebsocketInbound;
import reactor.netty.http.websocket.WebsocketOutbound;

import java.net.URI;

@Component
public class NettyGatewayServer implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(NettyGatewayServer.class);

    private final GatewayActionService gatewayActionService;
    private final NettyGatewayHub gatewayHub;
    private final int gatewayPort;

    private volatile boolean running;
    private DisposableServer server;

    public NettyGatewayServer(
            GatewayActionService gatewayActionService,
            NettyGatewayHub gatewayHub,
            @Value("${robot.gateway.port:8091}") int gatewayPort
    ) {
        this.gatewayActionService = gatewayActionService;
        this.gatewayHub = gatewayHub;
        this.gatewayPort = gatewayPort;
    }

    @Override
    public void start() {
        if (running) {
            return;
        }
        log.info("gateway.server.start port={}", gatewayPort);
        server = HttpServer.create()
                .port(gatewayPort)
                .route(routes -> routes.get("/ws/robot",
                        (request, response) -> response.sendWebsocket((in, out) -> handle(request, in, out))))
                .bindNow();
        running = true;
    }

    private Mono<Void> handle(HttpServerRequest request, WebsocketInbound inbound, WebsocketOutbound outbound) {
        String sessionId = resolveQueryParam(request.uri(), "session_id");
        String executionId = resolveQueryParam(request.uri(), "execution_id");
        NettyGatewayHub.GatewayConnection connection = gatewayHub.register(sessionId, executionId);
        log.info("gateway.connection.open connectionId={} sessionId={} executionId={} uri={}", connection.connectionId(), sessionId, executionId, request.uri());

        Mono<Void> inboundFlow = inbound.receive()
                .asString()
                .concatMap(message -> gatewayActionService.handle(message, connection))
                .then();

        Mono<Void> outboundFlow = outbound.sendString(connection.outboundFlux()).then();

        return Mono.when(inboundFlow, outboundFlow)
                .doFinally(signalType -> {
                    log.info("gateway.connection.close connectionId={} sessionId={} executionId={} signal={}", connection.connectionId(), sessionId, executionId, signalType);
                    gatewayHub.unregister(connection.connectionId());
                });
    }

    private String resolveQueryParam(String rawUri, String key) {
        try {
            URI uri = URI.create(rawUri);
            if (uri.getQuery() == null || uri.getQuery().isBlank()) {
                return null;
            }
            for (String part : uri.getQuery().split("&")) {
                String[] items = part.split("=", 2);
                if (items.length == 2 && key.equals(items[0])) {
                    return items[1];
                }
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    @Override
    public void stop() {
        running = false;
        if (server != null) {
            log.info("gateway.server.stop port={}", gatewayPort);
            server.disposeNow();
            server = null;
        }
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    @Override
    public int getPhase() {
        return Integer.MAX_VALUE;
    }
}
