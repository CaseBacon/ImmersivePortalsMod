# DimLib (subproject)

This directory is a fork of [iPortalTeam/DimLib](https://github.com/iPortalTeam/DimLib),
imported from branch `1.21.3` at commit `467e83b5` ("Update to 1.21.3").
Upstream has no Minecraft 26.x release, and Immersive Portals bundles it,
so it is maintained here for the 26.3 port.

Boundaries that are kept on purpose:

* It stays a separate Fabric mod: mod id `dimlib`, its own `fabric.mod.json`,
  entrypoints (`DimLibEntry`, `DimLibEntryClient`, `DimLibModmenuIntegration`)
  and mixin config (`dimlib.mixins.json`).
* It is built as the Gradle subproject `:dimlib` and nested into the
  Immersive Portals jar with Loom's `include`, the same way the published
  DimLib jar was nested before. Other mods that depend on `dimlib` keep working.
* Package `qouteall.dimlib` is unchanged, so the public `DimensionAPI` is unchanged.
* `LICENSE` is the upstream Apache-2.0 license file.

Changes made for 26.3 are listed in `changelog.md` under "26.3 port".
