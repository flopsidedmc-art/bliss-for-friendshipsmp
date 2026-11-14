package dev.soulbound;

import org.bukkit.Material;

/**
 * Visual bindings for the user-provided Official Bliss Pack v3.5.
 *
 * The pack uses legacy CustomModelData overrides. Normal T1 gems use
 * amethyst_shard and T2 gems use prismarine_shard. The five available
 * visual states correspond to Ruined, Shattered, Cracked, Scratched and
 * Pristine; Pristine+1 through Pristine+5 reuse the Pristine visual.
 */
public final class BlissPackVisuals {
    private BlissPackVisuals() {}

    public static Material materialFor(GemType type, int tier) {
        if (type == null || type.isMythic()) return type == GemType.AURATUS ? Material.GOLD_INGOT : Material.MAGENTA_DYE;
        return tier >= 2 ? Material.PRISMARINE_SHARD : Material.AMETHYST_SHARD;
    }

    /** Returns the exact legacy CustomModelData used by the supplied pack, or -1 when no override exists. */
    public static int modelDataFor(GemType type, int tier, int energy) {
        if (type == null || type.isMythic() || energy <= 0) return -1;
        tier = tier >= 2 ? 2 : 1;
        int state = Math.min(5, Math.max(1, energy));

        if (type == GemType.FLUX) {
            // Actual CMDs in the pack: 97/98, 117/118, 137/138, 157/158, 177/178.
            int[] t1 = {177, 157, 137, 117, 97};
            return t1[state - 1] + (tier - 1);
        }

        // Actual families are spaced by 2: Fire=1/2, Life=3/4, ... Astra=13/14.
        int familyBase = switch (type) {
            case FIRE -> 1;
            case LIFE -> 3;
            case PUFF -> 5;
            case SPEED -> 7;
            case STRENGTH -> 9;
            case WEALTH -> 11;
            case ASTRA -> 13;
            default -> -1;
        };
        if (familyBase < 0) return -1;
        int highEnergyCmd = familyBase + (tier - 1);
        return highEnergyCmd + ((5 - state) * 20);
    }

    public static String modelPathFor(GemType type, int tier, int energy) {
        int cmd = modelDataFor(type, tier, energy);
        if (cmd < 0) return null;
        String family = tier >= 2 ? "prismarine_shard" : "amethyst_shard";
        int modelFile = cmd;
        // Flux's legacy model filenames are 97, 99, 101, 103, 105 while
        // the actual CustomModelData overrides are 97,117,137,157,177.
        if (type == GemType.FLUX) {
            modelFile = switch (cmd) {
                case 117 -> 99;
                case 137 -> 101;
                case 157 -> 103;
                case 177 -> 105;
                case 118 -> 118;
                case 138 -> 138;
                case 158 -> 158;
                case 178 -> 178;
                default -> cmd;
            };
        }
        return "item/" + family + "/" + modelFile;
    }

    public static int specialModelData(String kind) {
        if (kind == null) return -1;
        return switch (kind) {
            case "repairkit" -> 200;
            case "reviveitem_closed" -> 210;
            case "revivalitem_open" -> 220;
            case "spiritdagger" -> 230;
            case "fragment" -> 240;
            case "totemtoken" -> 250;
            case "energybottle" -> 300;
            case "remote" -> 310;
            default -> -1;
        };
    }

    public static String tier1Glyph() { return "\uD83D\uDD3A"; } // 🔺
    public static String tier2Glyph() { return "\uD83D\uDD2E"; } // 🔮
}
