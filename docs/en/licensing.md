# Licensing and redistribution

[简体中文](../zh-CN/licensing.md) · [Documentation](../README.md)

Compose MC's original source and documentation use the [MIT License](../../LICENSE), with copyright attributed to Frostbite-Time. MIT permits use, modification and redistribution, including commercial use, subject to retaining its copyright and permission notice. It does not replace licenses of bundled libraries, fonts or game assets.

## Distribution contents

Both installation variants include Compose, AndroidX, Skiko, the Swing dispatcher integration, atomicfu and Monocraft. `with-kotlin` also includes Kotlin stdlib, Coroutines Core and Serialization Core; the standard JAR obtains those libraries from an external provider. The `dev` artifact contains the complete JVM compile API and its notices. The optional `development` mod has its own MIT notice and accompanying third-party acknowledgments.

KFF's implementation is LGPL-2.1 and is installed separately. Compose MC does not copy or embed KFF. Minecraft, Forge/NeoForge and LWJGL come from the game installation. The Gradle Wrapper is a build tool, not part of an installed mod.

See [third-party notices](../../THIRD-PARTY-NOTICES.md) for the full component map and acknowledgments. Most JVM dependencies declare Apache-2.0. Native Skia components additionally use BSD, MIT/Old MIT, FTL, Unicode, IJG, libpng, zlib and Adobe DNG SDK terms. FreeType is distributed under its FTL option. Monocraft retains OFL-1.1. Screenshots showing Minecraft assets do not grant additional rights to those assets or trademarks.

## Files shipped with artifacts

- `META-INF/composemc/LICENSE`: the project's MIT text.
- `META-INF/composemc-third-party/THIRD-PARTY-NOTICES.md` and `licenses/`: third-party acknowledgments and retained native/common license texts.
- Inside the runtime bundle (flattened into `dev`), `META-INF/composemc-third-party/dependencies.json` and `dependencies.txt`: exact bundled coordinates and license declarations. Each coordinate has a directory containing a license copy, its upstream POM where applicable, and any original LICENSE/NOTICE files found in the dependency JAR.
- `dev/composemc/ui/ore/Monocraft-LICENSE.txt`: the font's original OFL notice alongside the font.

When redistributing an unchanged JAR, retain these contents. When extracting or repackaging dependencies, retain the notices that apply to the components you distribute; the MIT license alone does not cover third-party components. The native DNG SDK license is Adobe's own agreement and includes conditions beyond a generic MIT notice.

## Updating dependencies

The runtime notice task reads the actual resolved artifact POMs. All currently bundled external JVM artifacts declare Apache-2.0; a new or missing license declaration fails the task until its distribution requirements are reviewed and implemented. This avoids silently assuming that all future artifacts from the same organization use the same license.

[Native provenance](../../licenses/provenance.json) records source references and hashes for retained texts. The two pinned Skia source trees share the same DEPS revisions; platform/backend subsets differ. The Polyfrost source reference identifies the inspected fork revision rather than a release tag. Review the linker inputs and native notices on a Skiko upgrade, update the provenance, and run `buildAllMods` plus `node tools/check_docs.mjs`. These checks validate packaged metadata and coverage, not a blanket legal certification.
