Relay Temporal overlay for Minecraft Java 1.21.11. Place ABOVE the existing Bliss resource pack. The supplied archive was an overlay without pack.mcmeta; this release includes metadata. Existing glyphs, finale shaders/overlays, textures and sounds are preserved. Adds blissgems:temporal_clock and temporal_hand item models. Set mythics.relay.temporal.dilate.display-models=false and temporal.hud.use-glyphs=false for a pack-free fallback.

Antique palette revision: Time Dilate uses #5A3A1C main markings, #92613B secondary rings, #C99855 restrained accents. The two existing model IDs, geometry, UVs, interpolation and rotation remain unchanged. Existing full-bright displays keep the gold accents readable at night; this does not add shader bloom or world lighting. Replace/reload the client overlay for the model recolor, no new server JAR is needed for that. The optional updated JAR also recolors the pack-free particle fallback.

Texture path fix: the three temporal_*.png material textures now live in assets/blissgems/textures/item/ (was textures/effect/, a folder Minecraft does not load for models). Colours, geometry and model IDs are unchanged.

Rift + Time Dilate restyle: adds blissgems:rift_0..rift_5 (the six rift frames, 40x96, vertical plane models), blissgems:rift_light (ground light spill) and HUD glyph U+E043 (blissgems:icons/relay_rift.png, 33x20). Time Dilate clock lines are 1.5x thicker and recoloured to glowing brown #B0703A / #8C5530 / #F0C48C; the brown Glowing outline is set by the plugin.

The Revenant: adds blissgems:revenant_mace (item definition, handheld_mace model, the supplied 16x16 texture) and HUD glyphs U+E046 Plunge, U+E047 Rising, U+E048 Undertow (blissgems:icons/revenant_*.png, 33x20).

Auratus rebuild: no new assets. The gem uses the main pack's chain and Aegis HUD glyphs (U+22B5, U+22B4) and echo shard models 12348, 12349, 12345 and 12346.

Finale: adds item models blissgems:finale_pedestal_auratus, blissgems:finale_pedestal_heretic (vanilla blackstone with gold or red trim) and blissgems:finale_seal (textures/item/finale_seal.png, a clock face laid on the ground under the merge).

Finale overlay as camera overlays: the plugin now shows the haze frames (textures/overlay/gold_0..5, red_0..5) as a camera overlay, the way a carved pumpkin's blur is drawn, through a fake helmet only that player sees. Titles were drawn at the GUI scale, so the frames ended up larger than the screen and invisible. Camera overlays are stretched to the screen at any GUI scale, in first person only. Fades step through gold_fade_1..4 and red_fade_1..4 (20-80% of frame 0's opacity, made by tools/pack/overlay_fades.py); with an older pack that lacks them, set finale.overlay.fade-steps to 0. The E100-E115 glyphs are no longer used by the plugin; E120 (the brown flash) still is.
