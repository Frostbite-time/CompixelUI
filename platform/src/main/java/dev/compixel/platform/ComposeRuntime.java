package dev.compixel.platform;

import java.io.IOException;
import java.util.Properties;

/**
 * Checks, before entering Kotlin code, that the installed Compose/Skiko runtime bundle is the one this adapter
 * was built for; safe to load on a server.
 */
public final class ComposeRuntime {
    private ComposeRuntime() {}

    public static void requireAvailable() {
        var expected = new Properties();
        try (var record = ComposeRuntime.class.getResourceAsStream("/META-INF/compixel/runtime.properties")) {
            if (record == null) throw new IllegalStateException("CompixelUI is missing its runtime record");
            expected.load(record);
        } catch (IOException failure) {
            throw new IllegalStateException("Cannot read the CompixelUI runtime record", failure);
        }
        var profile = expected.getProperty("profile");
        var version = expected.getProperty("version");
        String found;
        try {
            var info = Class.forName("dev.compixel.runtime.RuntimeInfo", false, ComposeRuntime.class.getClassLoader());
            found = info.getField("PROFILE").get(null) + " "
                    + info.getField("VERSION").get(null);
        } catch (ReflectiveOperationException | LinkageError missing) {
            found = null;
        }
        if (!(profile + " " + version).equals(found)) {
            throw new IllegalStateException("CompixelUI needs its " + profile + " runtime " + version
                    + (found == null ? ", which is not installed" : ", but found " + found)
                    + ". Players install the CompixelUI release JAR, which includes it. Development "
                    + "builds get it from the Maven dependency on compixel-runtime-" + profile + ".");
        }
    }
}
