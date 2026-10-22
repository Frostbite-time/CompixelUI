# CompixelUI 0.1.14

- Changed: the release JAR is about 6 MB smaller, for example 38.7 MB instead of 44.9 MB on 1.21.1, or 40.9 MB instead of 48.2 MB with Kotlin. The JAR now compresses each embedded library bundle as a whole, the native libraries of each CPU architecture are compressed together, and the Compixel font ships xz-compressed.
- Changed: on the first start after installing or updating, systems other than Windows x64 and Apple silicon Macs take up to about a second longer to unpack their native library.
- Changed: on 1.20.1 and 1.21.1, the loader keeps embedded libraries in memory, which now takes about 22 MB more, or 27 MB more with Kotlin; on 26.x it unpacks them into its cache on disk instead. CompixelUI also unpacks its font while the game loads and keeps it in memory, about 8 MB, instead of reading it again whenever Compose reloads it.
