package dev.compixel.platform.internal;

import java.util.Locale;

/** Checks the client Kotlin runtime before entering Kotlin code; safe to load on a server. */
public final class KotlinRuntime {
    private static final int MIN_MAJOR = 2;
    private static final int MIN_MINOR = 2;
    private static final int MIN_PATCH = 21;
    private static final String MIN_VERSION = MIN_MAJOR + "." + MIN_MINOR + "." + MIN_PATCH;

    public enum Problem {
        MISSING_KOTLIN,
        OUTDATED_KOTLIN,
        MISSING_COROUTINES,
        MISSING_SERIALIZATION
    }

    public record Issue(Problem problem, String foundVersion, Throwable cause) {
        public String displayMessage() {
            return displayMessage(Locale.getDefault().getLanguage().equals("zh"));
        }

        public String displayMessage(boolean chinese) {
            return chinese ? chineseMessage() : englishMessage();
        }

        private String englishMessage() {
            return switch (problem) {
                case MISSING_KOTLIN ->
                    "§eCompixelUI cannot start: no Kotlin runtime mod is installed.§r\n"
                            + "§7CompixelUI needs Kotlin "
                            + MIN_VERSION
                            + " or newer, along with Coroutines and Serialization.\n"
                            + "§aHow to fix:§r Install a compatible Kotlin runtime mod for this Minecraft version "
                            + "(for example, Kotlin for Forge or another compatible Kotlin provider) in your mods folder, "
                            + "then restart Minecraft.";
                case OUTDATED_KOTLIN ->
                    "§eCompixelUI cannot start: your Kotlin runtime is too old (found "
                            + foundVersion
                            + ", need "
                            + MIN_VERSION
                            + " or newer).§r\n"
                            + "§aHow to fix:§r Update your Kotlin runtime mod (such as Kotlin for Forge or another "
                            + "compatible Kotlin provider) to a newer version for this Minecraft version, "
                            + "then restart Minecraft.";
                case MISSING_COROUTINES ->
                    "§eCompixelUI cannot start: your Kotlin runtime is missing a compatible Kotlin Coroutines library.§r\n"
                            + "§aHow to fix:§r Update your Kotlin runtime mod, or switch to a provider that includes "
                            + "Kotlin "
                            + MIN_VERSION
                            + "+, Coroutines and Serialization (for example, Kotlin for Forge), then restart Minecraft.";
                case MISSING_SERIALIZATION ->
                    "§eCompixelUI cannot start: your Kotlin runtime is missing the Kotlin Serialization library.§r\n"
                            + "§aHow to fix:§r Update your Kotlin runtime mod, or switch to a provider that includes "
                            + "Kotlin "
                            + MIN_VERSION
                            + "+, Coroutines and Serialization (for example, Kotlin for Forge), then restart Minecraft.";
            };
        }

        private String chineseMessage() {
            return switch (problem) {
                case MISSING_KOTLIN ->
                    "§eCompixelUI 无法启动：未安装 Kotlin 运行时模组。§r\n"
                            + "§7CompixelUI 需要 Kotlin "
                            + MIN_VERSION
                            + " 或更高版本（包含 Coroutines 与 Serialization）。\n"
                            + "§a解决方法：§r下载适配当前 Minecraft 版本的 Kotlin 运行时模组"
                            + "（例如 Kotlin for Forge 或其他兼容的 Kotlin 运行库），放入 mods 文件夹后重启游戏。";
                case OUTDATED_KOTLIN ->
                    "§eCompixelUI 无法启动：已安装的 Kotlin 运行时版本过低（当前为 "
                            + foundVersion
                            + "，需要 "
                            + MIN_VERSION
                            + " 或更高版本）。§r\n"
                            + "§a解决方法：§r将 Kotlin 运行时模组（例如 Kotlin for Forge 或其他兼容的 Kotlin 运行库）"
                            + "更新至适配当前 Minecraft 版本的最新版本，然后重启游戏。";
                case MISSING_COROUTINES ->
                    "§eCompixelUI 无法启动：当前 Kotlin 运行时缺少兼容的 Coroutines（协程）库。§r\n"
                            + "§a解决方法：§r更新 Kotlin 运行时模组，或更换为包含 Kotlin "
                            + MIN_VERSION
                            + "+、Coroutines 与 Serialization 的运行库（例如 Kotlin for Forge），然后重启游戏。";
                case MISSING_SERIALIZATION ->
                    "§eCompixelUI 无法启动：当前 Kotlin 运行时缺少 Serialization（序列化）库。§r\n"
                            + "§a解决方法：§r更新 Kotlin 运行时模组，或更换为包含 Kotlin "
                            + MIN_VERSION
                            + "+、Coroutines 与 Serialization 的运行库（例如 Kotlin for Forge），然后重启游戏。";
            };
        }
    }

    private KotlinRuntime() {}

    public static Issue check() {
        return check(KotlinRuntime.class.getClassLoader());
    }

    static Issue check(ClassLoader loader) {
        try {
            var version = Class.forName("kotlin.KotlinVersion", true, loader);
            var current = version.getField("CURRENT").get(null);
            if (!(boolean) version.getMethod("isAtLeast", int.class, int.class, int.class)
                    .invoke(current, MIN_MAJOR, MIN_MINOR, MIN_PATCH)) {
                return new Issue(Problem.OUTDATED_KOTLIN, String.valueOf(current), null);
            }
        } catch (ReflectiveOperationException | LinkageError failure) {
            return new Issue(Problem.MISSING_KOTLIN, null, failure);
        }
        try {
            Class.forName("kotlinx.coroutines.CoroutineDispatcher", false, loader)
                    .getMethod("limitedParallelism", int.class, String.class);
        } catch (ReflectiveOperationException | LinkageError failure) {
            return new Issue(Problem.MISSING_COROUTINES, null, failure);
        }
        try {
            Class.forName("kotlinx.serialization.KSerializer", false, loader).getMethod("getDescriptor");
        } catch (ReflectiveOperationException | LinkageError failure) {
            return new Issue(Problem.MISSING_SERIALIZATION, null, failure);
        }
        return null;
    }
}
