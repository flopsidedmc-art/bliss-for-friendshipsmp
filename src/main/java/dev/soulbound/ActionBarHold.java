package dev.soulbound;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** One-off action-bar messages held over the HUDs for a while (SoulboundGemsPlugin.holdActionBar; HoldTest). */
final class ActionBarHold {
    private final Map<UUID, Long> until = new HashMap<>();

    /** Held for {@code ms} from {@code now}; a longer hold already running is kept. */
    void hold(UUID id, long ms, long now) {
        if (id == null || ms <= 0) return;
        until.merge(id, now + ms, Math::max);
    }

    /** Still held at {@code now} (an ended hold is forgotten). */
    boolean held(UUID id, long now) {
        Long t = until.get(id);
        if (t == null) return false;
        if (t > now) return true;
        until.remove(id);
        return false;
    }

    void forget(UUID id) { until.remove(id); }
}
