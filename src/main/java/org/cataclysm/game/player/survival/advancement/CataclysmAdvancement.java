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

    private org.bukkit.advancement.Advancement createFallback(NamespacedKey key) {
        try {
            var display = net.kyori.adventure.text.Component.text(this.key);
            var icon = new org.bukkit.inventory.ItemStack(org.bukkit.Material.BOOK);
            boolean root = key.getKey().endsWith("/root") || !key.getKey().contains("/");

            // Advancement.builder() y Bukkit.addAdvancement() solo existen en Paper, no en
            // la API de Spigot/Bukkit. Se usan via reflection para que el proyecto compile
            // con cualquier API y funcione en servidores Paper.
            Class<?> builderClass = Class.forName("io.papermc.paper.advancement.AdvancementBuilder");
            Object builder = org.bukkit.advancement.Advancement.class
                    .getMethod("builder", NamespacedKey.class, String.class)
                    .invoke(null, key, "minecraft:book");

            Object frame = resolveFrame(root ? "GOAL" : "TASK");

            // display(...) cambia de firma entre versiones de Paper; se busca el overload
            // cuyo primer parametro sea ItemStack y se rellenan los extra con valores neutros.
            boolean displayed = false;
            for (var m : builderClass.getMethods()) {
                if (!m.getName().equals("display")) continue;
                var p = m.getParameterTypes();
                if (p.length < 7 || !p[0].equals(icon.getClass())) continue;
                Object[] args = new Object[p.length];
                args[0] = icon; args[1] = display; args[2] = display; args[3] = frame;
                args[4] = true; args[5] = true; args[6] = !root;
                for (int i = 7; i < p.length; i++) {
                    if (p[i] == NamespacedKey.class) {
                        args[i] = root
                                ? NamespacedKey.minecraft("textures/block/deepslate_tiles.png")
                                : null;
                    } else if (p[i] == boolean.class) {
                        args[i] = false;
                    }
                }
                m.invoke(builder, args);
                displayed = true;
                break;
            }
            if (!displayed) return null;

            NamespacedKey parentKey = parentKeyOf(key);
            var parent = parentKey != null ? Bukkit.getAdvancement(parentKey) : null;
            if (parent != null) {
                builderClass.getMethod("parent", org.bukkit.advancement.Advancement.class)
                        .invoke(builder, parent);
            }
            builderClass.getMethod("addCriterion", String.class).invoke(builder, "impossible");
            Object built = builderClass.getMethod("build").invoke(builder);
            var created = (org.bukkit.advancement.Advancement) invokeAddAdvancement(built);
            if (created != null) {
                Bukkit.getLogger().info("[Cataclysm] Advancement registrado en caliente: " + key);
            }
            return created;
        } catch (Exception e) {
            return null;
        }
    }

    private static Object resolveFrame(String name) throws Exception {
        for (String cls : new String[]{
                "io.papermc.paper.advancement.AdvancementDisplay$Frame",
                "org.bukkit.advancement.AdvancementDisplay$Frame",
                "org.bukkit.advancement.AdvancementFrame"}) {
            try {
                Class<?> frameClass = Class.forName(cls);
                return Enum.valueOf((Class<? extends Enum>) frameClass, name);
            } catch (ClassNotFoundException ignored) {}
        }
        throw new ClassNotFoundException("AdvancementFrame");
    }

    private static Object invokeAddAdvancement(Object built) throws Exception {
        for (var m : Bukkit.class.getMethods()) {
            if (m.getName().equals("addAdvancement") && m.getParameterCount() == 1) {
                return m.invoke(null, built);
            }
        }
        throw new NoSuchMethodException("Bukkit.addAdvancement");
    }

    private static NamespacedKey parentKeyOf(NamespacedKey key) {
        String path = key.getKey();
        int idx = path.lastIndexOf('/');
        if (idx < 0) return null; // raiz global sin parent
        String tab = path.substring(0, path.indexOf('/'));
        boolean directChild = path.indexOf('/') == idx; // solo un '/' -> hijo directo de pestana
        String parentPath = directChild ? tab + "/root" : path.substring(0, idx);
        return new NamespacedKey(key.getNamespace(), parentPath);
    }

    public boolean isDone(Player player) {
        var advancementKey = new NamespacedKey("cataclysm", this.key);
        var advancement = Bukkit.getAdvancement(advancementKey);

        if (advancement == null) return false;

        var progress = player.getAdvancementProgress(advancement);
        return progress.isDone();
    }
}