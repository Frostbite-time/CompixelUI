package dev.compixel.sync.session;

import dev.compixel.sync.action.ActionFailure;
import dev.compixel.sync.action.ActionStatus;
import dev.compixel.sync.state.SyncBatch;
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

    /**
     * Client to server: acknowledges a state batch, closes the session, or asks to start the state over because the
     * menu opened on the client after its first batches arrived.
     */
    record Control(int kind, int menu, UUID nonce, long request, UUID session, long revision, int batch)
            implements SyncMessage {
        public static final int ACK = 0, CLOSE = 1, RESTART = 2;

        public Control {
            if (kind < ACK || kind > RESTART || menu < 0 || request < 1)
                throw new IllegalArgumentException("Invalid menu control");
            Objects.requireNonNull(nonce);
            Objects.requireNonNull(session);
        }
    }

    /** Server to client: starts a menu's state, naming its schema; the state's batches follow right behind it. */
    record Bootstrap(int menu, UUID nonce, long request, UUID session, String schema, String fingerprint)
            implements SyncMessage {
        public Bootstrap {
            if (menu < 0 || request < 1 || NO_SESSION.equals(session))
                throw new IllegalArgumentException("Invalid menu bootstrap");
            Objects.requireNonNull(nonce);
            Objects.requireNonNull(schema);
            Objects.requireNonNull(fingerprint);
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

    /** Server to client: how many bytes of a larger action have arrived; its reply follows once all have. */
    record ActionProgress(int menu, UUID nonce, long request, UUID session, long sequence, int received)
            implements SyncMessage {
        public ActionProgress {
            Objects.requireNonNull(nonce);
            Objects.requireNonNull(session);
            if (received < 1) throw new IllegalArgumentException("Invalid menu action progress");
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
