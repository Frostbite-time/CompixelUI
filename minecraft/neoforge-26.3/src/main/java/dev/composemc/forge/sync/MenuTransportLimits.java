package dev.composemc.forge.sync;

/** Physical custom-payload envelopes, including 256 bytes reserved for framing/identifiers. */
public final class MenuTransportLimits {
    public static final int MAX_STATE_BATCH_BYTES = 1024 * 1024 - 256;
    public static final int MAX_ACTION_FRAGMENT_BYTES = 32767 - 256;
    private MenuTransportLimits() {}
}
