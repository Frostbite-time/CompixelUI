package dev.compixel.platform.internal;

import static org.junit.jupiter.api.Assertions.*;

import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class KotlinRuntimeTest {
    @TempDir
    Path temporary;

    @Test
    void acceptsStandardLibrariesWithoutKff() {
        assertNull(KotlinRuntime.check(getClass().getClassLoader()));
    }

    @Test
    void missingKotlinExplainsCauseAndSolution() {
        var issue = KotlinRuntime.check(ClassLoader.getPlatformClassLoader());
        assertNotNull(issue);
        assertEquals(KotlinRuntime.Problem.MISSING_KOTLIN, issue.problem());
        assertInstanceOf(ClassNotFoundException.class, issue.cause());
        assertTrue(issue.displayMessage(false).contains("no Kotlin runtime mod is installed"));
        assertTrue(issue.displayMessage(false).contains("How to fix:"));
        assertTrue(issue.displayMessage(true).contains("未安装 Kotlin 运行时模组"));
        assertTrue(issue.displayMessage(true).contains("解决方法："));
    }

    @Test
    void rejectsOlderKotlinWithItsActualVersion() throws Exception {
        var source = temporary.resolve("KotlinVersion.java");
        Files.writeString(source, """
                package kotlin;
                public class KotlinVersion {
                    public static final KotlinVersion CURRENT = new KotlinVersion();
                    public boolean isAtLeast(int major, int minor, int patch) { return false; }
                    public String toString() { return "2.2.20"; }
                }
                """);
        assertEquals(
                0,
                ToolProvider.getSystemJavaCompiler()
                        .run(null, null, null, "-d", temporary.toString(), source.toString()));
        try (var loader =
                new URLClassLoader(new URL[] {temporary.toUri().toURL()}, ClassLoader.getPlatformClassLoader())) {
            var issue = KotlinRuntime.check(loader);
            assertNotNull(issue);
            assertEquals(KotlinRuntime.Problem.OUTDATED_KOTLIN, issue.problem());
            assertEquals("2.2.20", issue.foundVersion());
            assertTrue(issue.displayMessage(false).contains("2.2.20"));
            assertTrue(issue.displayMessage(false).contains("2.2.21"));
            assertTrue(issue.displayMessage(true).contains("2.2.20"));
        }
    }

    @Test
    void rejectsMissingCoroutinesAndSerialization() {
        var withoutCoroutines = new ClassLoader(getClass().getClassLoader()) {
            @Override
            protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
                if (name.startsWith("kotlinx.coroutines.")) throw new ClassNotFoundException(name);
                return super.loadClass(name, resolve);
            }
        };
        var coroutinesIssue = KotlinRuntime.check(withoutCoroutines);
        assertNotNull(coroutinesIssue);
        assertEquals(KotlinRuntime.Problem.MISSING_COROUTINES, coroutinesIssue.problem());
        assertTrue(coroutinesIssue.displayMessage(false).contains("Coroutines"));

        var withoutSerialization = new ClassLoader(getClass().getClassLoader()) {
            @Override
            protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
                if (name.startsWith("kotlinx.serialization.")) throw new ClassNotFoundException(name);
                return super.loadClass(name, resolve);
            }
        };
        var serializationIssue = KotlinRuntime.check(withoutSerialization);
        assertNotNull(serializationIssue);
        assertEquals(KotlinRuntime.Problem.MISSING_SERIALIZATION, serializationIssue.problem());
        assertTrue(serializationIssue.displayMessage(false).contains("Serialization"));
    }
}
