# CompixelUI 0.1.13

- Changed: the release JAR is about 28 MB smaller, for example 44.9 MB instead of 73.4 MB on 1.21.1. CompixelUI now stores Skiko's native libraries xz-compressed and keeps the ICU text data, which Skia embeds in each Linux and macOS library, only once. When the game starts, CompixelUI unpacks the library for the current system into Skiko's cache, `.skiko` in the user's home directory, where Skiko used to unpack it itself; a library already there is used as it is. The first start after installing or updating takes less than a second longer.
- Changed: `ComposeRuntime` and `KotlinRuntime` moved to `dev.compixel.platform.internal` and left the API reference. They only serve CompixelUI's own startup checks.
