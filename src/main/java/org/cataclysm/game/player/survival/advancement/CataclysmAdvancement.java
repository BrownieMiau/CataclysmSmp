package org.cataclysm.game.player.survival.advancement;

import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.advancement.Advancement;
import org.bukkit.entity.Player;

/**
 * Wrapper de un advancement del namespace "cataclysm".
 *
 * El registro real de los advancements lo hace AdvancementLoader.loadAll() al arrancar
 * (lee los JSON empaquetados en el jar y los crea via la API de Paper por reflection).
 * grant() simplemente busca el advancement ya registrado y le otorga los criterios
 * pendientes al jugador. Si por algun motivo no existe, intenta crearlo en caliente
 * reutilizando exactamente el mismo mecanismo del loader (una sola implementacion).
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
                // No se cargo (JSON ausente o world previo): reintento en caliente.
                advancement = AdvancementLoader.ensureRegistered(advancementKey);
            }

            if (advancement == null) {
                Bukkit.getLogger().warning("[Cataclysm] Advancement no encontrado: " + advancementKey
                        + " (falta el JSON en advancements/cataclysm/ del .jar o el plugin no lo registro)");
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
