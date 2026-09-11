package lhqm.furimeo.wisper.grpc;

import java.io.File;
import java.io.IOException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

import io.grpc.Server;
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder;

/**
 * Starts and stops the gRPC server nodes dial into.
 *
 * <p>A {@link SmartLifecycle} rather than a {@code CommandLineRunner}: the socket must be
 * open while the context is running and closed before the beans behind it are torn down,
 * and a runner gives neither half. The phase is late so every handler this serves exists
 * before a node can reach one.
 *
 * <h2>Keepalive is the load-bearing configuration</h2>
 *
 * <p>These streams live inside a tunnel that can die without either end being told, so a
 * dead peer is found by the application or not at all. Nodes ping every twenty seconds;
 * {@code permitKeepAliveTime} has to be at or below that, or the server answers a
 * reasonable ping with {@code GOAWAY ENHANCE_YOUR_CALM} and kills the connection the ping
 * was proving alive. {@link GrpcSettings} refuses a configuration that gets that backwards.
 *
 * <h2>Virtual threads</h2>
 *
 * <p>Handlers block - they write to PostgreSQL - and blocking is what virtual threads are
 * for. It is also what lets a node with two hundred containers hold a control stream, a
 * stats stream, a log stream and a file stream without four platform threads per machine.
 *
 * <h2>Shutdown</h2>
 *
 * <p>{@code shutdown()} then a short wait then {@code shutdownNow()}. Every open stream is
 * a node that will reconnect within its backoff, so there is nothing to drain and waiting
 * politely for streams that are open by design would hang the process for as long as the
 * timeout allowed.
 */
@Component
public class NodeGrpcServer implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(NodeGrpcServer.class);

    /** How long to let in-flight unary calls finish before the socket is torn down. */
    private static final int SHUTDOWN_GRACE_SECONDS = 5;

    private final GrpcSettings settings;
    private final NodeServiceEndpoint endpoint;
    private final AuthenticateNodeCall authentication;

    private Server server;

    public NodeGrpcServer(GrpcSettings settings, NodeServiceEndpoint endpoint,
                          AuthenticateNodeCall authentication) {
        this.settings = settings;
        this.endpoint = endpoint;
        this.authentication = authentication;
    }

    @Override
    public synchronized void start() {
        if (server != null) {
            return;
        }
        NettyServerBuilder builder = NettyServerBuilder.forPort(settings.port())
                .addService(endpoint)
                .intercept(authentication)
                .executor(Executors.newVirtualThreadPerTaskExecutor())
                .keepAliveTime(settings.keepAliveTime().toMillis(), TimeUnit.MILLISECONDS)
                .keepAliveTimeout(settings.keepAliveTimeout().toMillis(), TimeUnit.MILLISECONDS)
                .permitKeepAliveTime(settings.permitKeepAliveTime().toMillis(),
                        TimeUnit.MILLISECONDS)
                .permitKeepAliveWithoutCalls(settings.permitKeepAliveWithoutCalls())
                .maxInboundMessageSize((int) settings.maxInboundMessageSize().toBytes())
                // A control stream is open for the life of the daemon and a file transfer
                // for the life of a download. Ending either on a timer would be the panel
                // inventing an outage.
                .maxConnectionIdle(Long.MAX_VALUE, TimeUnit.NANOSECONDS)
                .maxConnectionAge(Long.MAX_VALUE, TimeUnit.NANOSECONDS);

        if (settings.terminatesTls()) {
            builder.useTransportSecurity(new File(settings.certificateChainFile()),
                    new File(settings.privateKeyFile()));
        }

        try {
            server = builder.build().start();
        } catch (IOException cannotBind) {
            // Fatal, and it must be: a panel that came up without its node port is a panel
            // that looks healthy while every machine it manages is unreachable.
            throw new IllegalStateException("Could not open the gRPC port "
                    + settings.port() + " nodes dial into", cannotBind);
        }
        log.info("gRPC listening on :{} for nodes ({})", settings.port(),
                settings.terminatesTls() ? "TLS terminated here"
                        : "plaintext; TLS is terminated in front of the panel");
    }

    @Override
    public synchronized void stop() {
        if (server == null) {
            return;
        }
        server.shutdown();
        try {
            if (!server.awaitTermination(SHUTDOWN_GRACE_SECONDS, TimeUnit.SECONDS)) {
                server.shutdownNow();
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            server.shutdownNow();
        }
        server = null;
        log.info("gRPC stopped. Every node keeps running what it was given and will reconnect.");
    }

    @Override
    public synchronized boolean isRunning() {
        return server != null && !server.isShutdown();
    }

    /**
     * Late enough that every handler exists before a node can reach one, and therefore
     * early enough on the way down that the socket closes before they are destroyed.
     */
    @Override
    public int getPhase() {
        return Integer.MAX_VALUE - 100;
    }
}
