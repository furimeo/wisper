package lhqm.furimeo.wisper.grpc;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.util.unit.DataSize;

/**
 * Everything under {@code wisper.grpc}.
 *
 * <p>Two ports rather than one because the two audiences share nothing: 8080 carries
 * session cookies and CSRF, 9090 carries node credentials and streams that stay open for
 * weeks, and the tunnel in front of them is configured differently for each
 * (panel-configuration.md).
 *
 * <h2>Why the keepalive numbers are not decoration</h2>
 *
 * <p>The control stream lives inside a tunnel. A tunnel that dies takes the TCP connection
 * with it without either end being told, so a dead peer has to be found by the application
 * or a node sits "connected" forever while its spec goes nowhere. Nodes ping every twenty
 * seconds; {@code permitKeepAliveTime} has to be at or below that or the server answers a
 * perfectly reasonable ping with {@code GOAWAY ENHANCE_YOUR_CALM} and drops the very
 * connection the ping was proving alive - which produces a reconnect loop that looks like
 * a network fault and is not.
 *
 * @param port                        where nodes dial in
 * @param keepAliveTime               how often the panel pings an idle stream
 * @param keepAliveTimeout            how long it waits for the answer before giving up on
 *                                    the connection
 * @param permitKeepAliveTime         the fastest client ping the panel tolerates
 * @param permitKeepAliveWithoutCalls whether a client may ping with no RPC in flight. It
 *                                    must: the control stream is one long call and the
 *                                    node has to keep the path warm between frames.
 * @param maxInboundMessageSize       a status batch from a node with hundreds of workloads
 *                                    is the largest thing that arrives here
 * @param certificateChainFile        PEM chain, when the panel terminates TLS itself.
 *                                    Empty is the normal deployment: a tunnel terminates
 *                                    TLS and forwards plaintext, and the panel has no
 *                                    certificate of its own to show. Set it when nodes
 *                                    reach the panel directly - which is also the only
 *                                    arrangement where the certificate pinning in design
 *                                    §7.1 has anything to pin, so the two go together.
 * @param privateKeyFile              PKCS#8 key for that chain
 */
@ConfigurationProperties("wisper.grpc")
public record GrpcSettings(
        @DefaultValue("9090") int port,
        @DefaultValue("20s") Duration keepAliveTime,
        @DefaultValue("10s") Duration keepAliveTimeout,
        @DefaultValue("10s") Duration permitKeepAliveTime,
        @DefaultValue("true") boolean permitKeepAliveWithoutCalls,
        @DefaultValue("16MB") DataSize maxInboundMessageSize,
        @DefaultValue("") String certificateChainFile,
        @DefaultValue("") String privateKeyFile) {

    /** Whether the panel terminates TLS on the gRPC port itself. */
    public boolean terminatesTls() {
        return !certificateChainFile.isBlank() && !privateKeyFile.isBlank();
    }

    public GrpcSettings {
        if (port < 1 || port > 65535) {
            throw new IllegalArgumentException("wisper.grpc.port must be a port, not " + port);
        }
        if (permitKeepAliveTime.compareTo(keepAliveTime) > 0) {
            throw new IllegalArgumentException("wisper.grpc.permit-keep-alive-time ("
                    + permitKeepAliveTime + ") is longer than wisper.grpc.keep-alive-time ("
                    + keepAliveTime + "), so the panel would refuse pings at the rate it asks "
                    + "for them and drop every node that obeyed it");
        }
        if (certificateChainFile.isBlank() != privateKeyFile.isBlank()) {
            throw new IllegalArgumentException("wisper.grpc.certificate-chain-file and "
                    + "wisper.grpc.private-key-file are set together or not at all; one "
                    + "without the other is a server that cannot start");
        }
    }
}
