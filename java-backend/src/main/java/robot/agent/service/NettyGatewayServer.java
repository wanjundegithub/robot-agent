package robot.agent.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;
import robot.agent.gateway.RobotChannelInitializer;

@Component
public class NettyGatewayServer implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(NettyGatewayServer.class);

    private final ObjectMapper objectMapper;
    private final GatewayActionService gatewayActionService;
    private final NettyGatewayHub gatewayHub;
    private final int gatewayPort;

    private volatile boolean running;
    private EventLoopGroup bossGroup;
    private EventLoopGroup workerGroup;
    private Channel serverChannel;

    public NettyGatewayServer(
            ObjectMapper objectMapper,
            GatewayActionService gatewayActionService,
            NettyGatewayHub gatewayHub,
            @Value("${robot.gateway.port:8091}") int gatewayPort
    ) {
        this.objectMapper = objectMapper;
        this.gatewayActionService = gatewayActionService;
        this.gatewayHub = gatewayHub;
        this.gatewayPort = gatewayPort;
    }

    @Override
    public synchronized void start() {
        if (running) {
            return;
        }

        log.info("gateway.server.start port={}", gatewayPort);
        bossGroup = new NioEventLoopGroup(1);
        workerGroup = new NioEventLoopGroup();

        try {
            ServerBootstrap bootstrap = new ServerBootstrap();
            bootstrap.group(bossGroup, workerGroup)
                    .channel(NioServerSocketChannel.class)
                    .childHandler(new RobotChannelInitializer(objectMapper, gatewayHub, gatewayActionService));
            serverChannel = bootstrap.bind(gatewayPort).sync().channel();
            running = true;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            stop();
            throw new IllegalStateException("Failed to start Netty gateway server", exception);
        } catch (RuntimeException exception) {
            stop();
            throw exception;
        }
    }

    @Override
    public synchronized void stop() {
        running = false;
        if (serverChannel != null) {
            log.info("gateway.server.stop port={}", gatewayPort);
            serverChannel.close();
            serverChannel = null;
        }
        if (workerGroup != null) {
            workerGroup.shutdownGracefully();
            workerGroup = null;
        }
        if (bossGroup != null) {
            bossGroup.shutdownGracefully();
            bossGroup = null;
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
