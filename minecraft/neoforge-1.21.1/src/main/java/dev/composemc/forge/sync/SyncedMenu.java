package dev.composemc.forge.sync;

/** Implement on a menu to opt into automatic, server-authoritative synchronization. */
public interface SyncedMenu {
    MenuSync<?> menuSync();
}
