package org.cataclysm.game.player.survival.advancement;

import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;

public class CataclysmAdvancement {
    private final String key;

    public CataclysmAdvancement(String key) {
        this.key = key;
    }

    public void grant(Player player) {
        try {
            var advancementKey = new NamespacedKey("cataclysm", this.key);
            var advancement = Bukkit.getAdvancement(advancementKey);

            if (advancement == null) {
                Bukkit.getLogger().warning("[Cataclysm] Advancement no encontrado: " + advancementKey
                        + " (falta el JSON en data/advancements/cataclysm o el plugin no lo registro)");
                return;
            }

            var progress = player.getAdvancementProgress(advancement);
            if (progress.isDone()) return;

            for (var criterion : progress.getRemainingCriteria()) progress.awardCriteria(criterion);
        } catch (IllegalArgumentException ignored) {}
    }

    public boolean isDone(Player player) {
        var advancementKey = new NamespacedKey("cataclysm", this.key);
        var advancement = Bukkit.getAdvancement(advancementKey);

        if (advancement == null) return false;

        var progress = player.getAdvancementProgress(advancement);
        return progress.isDone();
    }
}