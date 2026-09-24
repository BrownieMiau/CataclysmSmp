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

            // Si falta, registramos el advancement en caliente con un criterio
            // 'impossible' (solo otorgable por codigo). Esto cubre tanto un JSON
            // ausente/malformado como un world generado antes del plugin.
            if (advancement == null) {
                advancement = createFallback(advancementKey);
            }

            if (advancement == null) {
                Bukkit.getLogger().warning("[Cataclysm] Advancement no registrado y no se pudo crear: "
                        + advancementKey + " (revisa src/main/resources/advancements/cataclysm en el .jar)");
                return;
            }

            var progress = player.getAdvancementProgress(advancement);
            if (progress.isDone()) return;

            for (var criterion : progress.getRemainingCriteria()) progress.awardCriteria(criterion);
        } catch (IllegalArgumentException ignored) {}
    }

    @SuppressWarnings("deprecation")
    private org.bukkit.advancement.Advancement createFallback(NamespacedKey key) {
        try {
            var display = net.kyori.adventure.text.Component.text(this.key);
            var icon = new org.bukkit.inventory.ItemStack(org.bukkit.Material.BOOK);
            var root = key.getKey().endsWith("/root");
            var build = org.bukkit.advancement.Advancement.builder(key, "minecraft:book")
                    .display(icon, display, display,
                            root ? org.bukkit.advancement.AdvancementFrame.GOAL
                                 : org.bukkit.advancement.AdvancementFrame.TASK,
                            true, true, !root, false);
            if (root) {
                build.background(org.bukkit.NamespacedKey.minecraft("textures/block/deepslate_tiles.png"));
            }
            var parentKey = NamespacedKey.fromString("cataclysm:" + parentOf(key));
            var parent = parentKey != null ? Bukkit.getAdvancement(parentKey) : null;
            if (parent != null) build.parent(parent);
            build.addCriterion("impossible");
            var created = Bukkit.addAdvancement(build.build());
            if (created != null) {
                Bukkit.getLogger().info("[Cataclysm] Advancement registrado en caliente: " + key);
            }
            return created;
        } catch (Exception e) {
            return null;
        }
    }

    private static String parentOf(NamespacedKey key) {
        String path = key.getKey();
        int idx = path.lastIndexOf('/');
        if (idx < 0) return null; // raiz global sin parent
        String tab = path.substring(0, path.indexOf('/'));
        // hijos directas de la pestana -> parent = <pestana>/root ; demas -> carpeta padre
        boolean directChild = path.substring(idx + 1).chars().noneMatch(c -> c == '/');
        return directChild ? tab + "/root" : path.substring(0, idx);
    }

    public boolean isDone(Player player) {
        var advancementKey = new NamespacedKey("cataclysm", this.key);
        var advancement = Bukkit.getAdvancement(advancementKey);

        if (advancement == null) return false;

        var progress = player.getAdvancementProgress(advancement);
        return progress.isDone();
    }
}