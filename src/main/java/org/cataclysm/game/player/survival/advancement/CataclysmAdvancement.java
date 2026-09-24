package org.cataclysm.game.player.survival.advancement;

import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.advancement.Advancement;
import org.bukkit.entity.Player;

/**
 * Wrapper de un advancement del namespace "cataclysm".
 *
 * Los advancements se registran mediante el datapack "Cataclysm Advancements"
 * (generado por la tarea de Gradle 'cataclysmDatapack', que empaqueta los JSON
 * de src/main/resources/advancements/cataclysm/). Paper 1.21.5 no ofrece API
 * para crear advancements desde codigo, por lo que el datapack DEBE estar en
 * <world>/datapacks/ para que grant() funcione.
 */
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
                        + ". Instala el datapack 'Cataclysm-Advancements.zip' en <mundo>/datapacks/"
                        + " y ejecuta /reload o reinicia el servidor.");
                return;
            }

            var progress = player.getAdvancementProgress(advancement);
            if (progress.isDone()) return;

            for (var criterion : progress.getRemainingCriteria()) progress.awardCriteria(criterion);
        } catch (IllegalArgumentException ignored) {}
    }

    public boolean isDone(Player player) {
        var advancementKey = new NamespacedKey("cataclysm", this.key);
        Advancement advancement = Bukkit.getAdvancement(advancementKey);
        if (advancement == null) return false;
        return player.getAdvancementProgress(advancement).isDone();
    }
}
