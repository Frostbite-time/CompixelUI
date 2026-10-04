package dev.compixel.sync.session;

/**
 * Codec context for one side, entered around each encode and decode of menu values and actions. Minecraft adapters use
 * it to give native codecs the registries of the side that is synchronizing; hosts without such context use
 * {@link #NONE}.
 */
@FunctionalInterface
public interface CodecScope {
    CodecScope NONE = () -> () -> {};

    /** Makes the context current on this thread until the returned handle closes. */
    Entered enter();

    /** An entered context; closing it restores the previous one. */
    interface Entered extends AutoCloseable {
        @Override
        void close();
    }
}
