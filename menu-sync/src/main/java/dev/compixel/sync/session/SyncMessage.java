package dev.compixel.sync.session;

import dev.compixel.sync.action.ActionFailure;
import dev.compixel.sync.action.ActionStatus;
import dev.compixel.sync.state.SyncBatch;
import dev.compixel.sync.state.SyncLimits;
import java.util.Objects;
import java.util.UUID;

/**
 * A protocol message between the server's sessions and a client's session. Each names the menu by its network id, the
 * session by the server's nonce, request number and token, and is checked when constructed, so a decoder that builds
 * one from untrusted bytes rejects invalid headers. Adapters carry messages in their own payloads.
 */
public sealed interface SyncMessage {
    /** The token of a client session that has not attached to the server's yet. */
    UUID NO_SESSION = new UUID(0, 0);

    int menu();

    UUID nonce();

    long request();

    UUID session();

    /** Client to server: confirms the transport policy, acknowledges a state batch, or closes the session. */
    record Control(int kind, int menu, UUID nonce, long request, UUID session, long revision, int batch)
            implements SyncMessage {
        public static final int ACK = 0, CLOSE = 1, READY = 2;

        public Control {
            if (kind < ACK || kind > READY || menu < 0 || request < 1)
                throw new IllegalArgumentException("Invalid menu control");
            Objects.requireNonNull(nonce);
            Objects.requireNonNull(session);
        }
    }

    /** Server to client: the first message for an opened menu, naming its schema and transport policy. */
    record Bootstrap(
            int menu, UUID nonce, long request, UUID session, String schema, String fingerprint, SyncLimits limits)
            implements SyncMessage {
        public Bootstrap {
            if (menu < 0 || request < 1 || NO_SESSION.equals(session))
                throw new IllegalArgumentException("Invalid menu bootstrap");
            Objects.requireNonNull(nonce);
            Objects.requireNonNull(schema);
            Objects.requireNonNull(fingerprint);
            Objects.requireNonNull(limits);
        }
    }

    /** Server to client: a state batch, or with no batch, why the session failed. */
    record Data(int menu, UUID nonce, long request, UUID session, SyncBatch batch, String failure)
            implements SyncMessage {
        public Data {
            if (menu < 0 || request < 1) throw new IllegalArgumentException("Invalid menu data");
            Objects.requireNonNull(nonce);
            Objects.requireNonNull(session);
            Objects.requireNonNull(failure);
        }
    }

    /** Client to server: an action that fits in one fragment. */
    record ActionRequest(int menu, UUID nonce, long request, UUID session, long sequence, String action, byte[] data)
            implements SyncMessage {
        public ActionRequest {
            Objects.requireNonNull(nonce);
            Objects.requireNonNull(session);
            Objects.requireNonNull(action);
            Objects.requireNonNull(data);
        }
    }

    /** Client to server: one part of a larger action, at {@code offset} of {@code total} bytes. */
    record ActionFragment(
            int menu,
            UUID nonce,
            long request,
            UUID session,
            long sequence,
            String action,
            int total,
            int offset,
            byte[] data)
            implements SyncMessage {
        public ActionFragment {
            Objects.requireNonNull(nonce);
            Objects.requireNonNull(session);
            Objects.requireNonNull(action);
            if (total < 1 || offset < 0 || offset >= total || data.length < 1 || data.length > total - offset)
                throw new IllegalArgumentException("Invalid menu action fragment");
        }
    }

    /** Server to client: the result of one action. */
    record ActionReply(
            int menu,
            UUID nonce,
            long request,
            UUID session,
            long sequence,
            ActionStatus status,
            ActionFailure failure,
            long actual,
            long limit,
            String detail)
            implements SyncMessage {
        public ActionReply {
            Objects.requireNonNull(nonce);
            Objects.requireNonNull(session);
            Objects.requireNonNull(status);
            Objects.requireNonNull(failure);
            Objects.requireNonNull(detail);
        }
    }
}
