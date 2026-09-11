package lhqm.furimeo.wisper.grpc;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import io.grpc.Context;
import io.grpc.Contexts;
import io.grpc.Grpc;
import io.grpc.Metadata;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import io.grpc.Status;
import lhqm.furimeo.wisper.node.AuthenticateNode;
import lhqm.furimeo.wisper.node.AuthenticatedNode;
import lhqm.furimeo.wisper.node.NodeCredentialRejected;
import lhqm.furimeo.wisper.node.NodeProtocol;

/**
 * Reads the credential out of a call's metadata and refuses the call if it does not check
 * out.
 *
 * <p>One interceptor rather than a check at the top of seven handlers, because the failure
 * mode of the second arrangement is the eighth handler. It runs before a single byte of
 * the request body is deserialised.
 *
 * <p>{@code Enroll} is the one exception, and it is named explicitly rather than inferred:
 * the request has no credential to carry, because the whole point of the call is to
 * collect one. Its proof is the bootstrap token in its body, checked by
 * {@code node.EnrolNode}.
 *
 * <p>The protocol header is checked here too, ahead of authentication, because
 * panel-ports.md §3 requires a version the panel cannot speak to end the call before any
 * frame is interpreted - and "interpreted" includes a credential lookup, which touches the
 * database. It is only enforced when the header is present: an older agent that did not
 * send it is caught by the handshake instead, which can record what it is and put "needs
 * upgrading" on its page.
 */
@Component
public class AuthenticateNodeCall implements ServerInterceptor {

    private static final Logger log = LoggerFactory.getLogger(AuthenticateNodeCall.class);

    /** The one method with no credential, by its fully-qualified gRPC name. */
    private static final String ENROLL_METHOD = "wisper.v1.NodeService/Enroll";

    private final AuthenticateNode authenticateNode;

    public AuthenticateNodeCall(AuthenticateNode authenticateNode) {
        this.authenticateNode = authenticateNode;
    }

    @Override
    public <Q, S> ServerCall.Listener<Q> interceptCall(ServerCall<Q, S> call, Metadata metadata,
                                                       ServerCallHandler<Q, S> next) {
        String remoteAddress = addressOf(call);
        Context context = Context.current()
                .withValue(NodeCallMetadata.REMOTE_ADDRESS, remoteAddress);

        String declaredProtocol = metadata.get(NodeCallMetadata.PROTOCOL);
        if (declaredProtocol != null && !speaks(declaredProtocol)) {
            call.close(Status.FAILED_PRECONDITION.withDescription(
                    NodeProtocol.mismatchMessage(parse(declaredProtocol))), new Metadata());
            return new ServerCall.Listener<>() { };
        }

        if (ENROLL_METHOD.equals(call.getMethodDescriptor().getFullMethodName())) {
            return Contexts.interceptCall(context, call, metadata, next);
        }

        try {
            AuthenticatedNode node = authenticateNode.byCredential(
                    metadata.get(NodeCallMetadata.NODE_TOKEN), remoteAddress);
            return Contexts.interceptCall(context.withValue(NodeCallMetadata.CALLER, node),
                    call, metadata, next);
        } catch (NodeCredentialRejected rejected) {
            // One message for all four refusals. A daemon that learns "that credential
            // exists but the node is suspended" has been handed a probe for which stolen
            // credentials are worth keeping.
            call.close(Status.UNAUTHENTICATED.withDescription(rejected.wireMessage()),
                    new Metadata());
            return new ServerCall.Listener<>() { };
        } catch (RuntimeException failed) {
            log.error("Authenticating a node call from {} failed", remoteAddress, failed);
            call.close(Status.UNAVAILABLE.withDescription("The panel could not check that "
                    + "credential. Retry."), new Metadata());
            return new ServerCall.Listener<>() { };
        }
    }

    private static boolean speaks(String declared) {
        int version = parse(declared);
        return version > 0 && NodeProtocol.canSpeak(version);
    }

    /** Zero for anything that is not a number, which fails the check the same way. */
    private static int parse(String declared) {
        try {
            return Integer.parseInt(declared.trim());
        } catch (NumberFormatException notANumber) {
            return 0;
        }
    }

    private static String addressOf(ServerCall<?, ?> call) {
        Object address = call.getAttributes().get(Grpc.TRANSPORT_ATTR_REMOTE_ADDR);
        if (address == null) {
            return "unknown";
        }
        // "/198.51.100.7:52344" from an InetSocketAddress; the leading slash is noise in
        // every log line and every audit row it lands in.
        String text = address.toString();
        return text.startsWith("/") ? text.substring(1) : text;
    }
}
