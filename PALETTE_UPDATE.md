# Time Dilate — antique palette update

Visual-only patch to the existing 1.3.0-temporal implementation. The plugin was not rewritten.

Reviewed both attached gameplay recordings as visual reference. In 1000053323.mp4, the ground pattern is clearly visible around 10–13 seconds: broken concentric circular bands, radial marks and large moving clock hands. The short 1000053322.mp4 recording primarily shows aerial combat/transition imagery. Neither clip was used to infer damage, duration, controls, target selection or other mechanics.

As requested, the existing model geometry and animation are retained rather than remodeled from the footage. The current geometry is a stylized approximation of the reference, not its source asset.

## Exact material palette

| Material | Color | Placement |
|---|---|---|
| Dark brown | #5A3A1C | Main outer ring and hand bodies |
| Bronze | #92613B | Secondary concentric rings |
| Warm gold | #C99855 | Sparse radial ticks, hand tips and pivot |

Three dedicated 16×16 RGBA PNG material textures replace the vanilla magenta-concrete reference. All texture pixels use the exact requested RGB values. Empty space stays open because the original geometry has not been filled in. No opaque background, extra geometry, fog or glow sheet was added.

The existing full-bright item-display lighting is retained, so the sparse gold accents stay luminous in dark scenes. This is emissive-looking display brightness, not shader bloom or actual emitted world light. No shader modification or shader dependency is introduced.

Both item model IDs, all 572 model elements, face UVs, broken-ring gaps, clock-hand shapes, display transforms, interpolation, rotation/deceleration/reacceleration math, timing, mechanics, cooldowns, controls and original TXT behavior are unchanged.

The only Java source change is the pack-free Time Dilate dust palette: the same particle positions, sizes, count and frequency now use brown, bronze and sparse gold instead of magenta. No other Java class changed.

## Apply the colors

For the normal model effect, replace the old Relay overlay with Relay-Temporal-Pack.zip, keep it above the base Bliss pack, then reload/reselect client resource packs. It is compatible with the already-built 1.3.0-temporal JAR: no server/plugin restart or JAR replacement is required for the model recolor.

If your server distributes packs automatically, update the hosted ZIP and the configured pack hash/URL so clients receive the changed bytes. No server was connected to or modified during this patch.

The full bundle also contains rebuilt source and an optional JAR with the matching particle-fallback colors. Only install that JAR if you need the fallback recolored; use a normal server stop/start when replacing plugin code, not an unsafe plugin hot reload. The artifact version remains 1.3.0-temporal; identify this palette revision by PALETTE_UPDATE.md and the new checksums.

Validation: exact palette pixels, unchanged geometry/UVs, unchanged animation/gameplay source, resource references and archive integrity were checked. Maven build status is in PALETTE_BUILD.log. No Minecraft client or multiplayer rendering test was performed.
