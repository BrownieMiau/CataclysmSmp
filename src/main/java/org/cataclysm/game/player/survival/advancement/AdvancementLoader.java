package org.cataclysm.game.player.survival.advancement;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.papermc.paper.advancement.AdvancementBuilder;
import io.papermc.paper.advancement.AdvancementDisplay;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.gson.GsonComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.advancement.Advancement;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.cataclysm.Cataclysm;

import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * Carga los advancements del plugin desde el interior del .jar.
 *
 * Nota: en un "paper-plugin" (paper-plugin.yml) Paper NO registra automaticamente
 * los JSON de /advancements; y si ademas el jar se empaqueta con Shadow, el
 * classloader del plugin no ve los recursos de otros plugins ni del server.
 * Por eso los leemos manualmente desde el propio jar y los registramos via
 * Bukkit.addAdvancement().
 */
public final class AdvancementLoader {
    private static final String RESOURCE_ROOT = "advancements/cataclysm";
    private static final String NAMESPACE = "cataclysm";

    private AdvancementLoader() {}

    public static void loadAll() {
        Plugin plugin = Cataclysm.getInstance();
        List<String> files = listJsonFiles(plugin);
        if (files.isEmpty()) {
            Bukkit.getLogger().severe("[Cataclysm] No se encontraron JSON de advancements dentro del jar ("
                    + RESOURCE_ROOT + "/). Revisa que 'processResources' los incluya.");
            return;
        }

        // Registro en dos pasadas para respetar dependencias parent -> hijo.
        List<JsonObject> pending = new ArrayList<>();
        int registered = 0;
        for (String path : files) {
            try (InputStream in = plugin.getResource(path)) {
                if (in == null) continue;
                JsonObject json = JsonParser.parseReader(
                        new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
                json.addProperty("$file", path);
                if (hasParentLoaded(json)) {
                    if (register(json)) registered++;
                } else {
                    pending.add(json);
                }
            } catch (Exception e) {
                Bukkit.getLogger().warning("[Cataclysm] JSON invalido en " + path + ": " + e.getMessage());
            }
        }

        // Cola con reintentos (por si el orden del listado separaba parent/hijo).
        Deque<JsonObject> queue = new ArrayDeque<>(pending);
        int stuckGuard = queue.size() * queue.size() + queue.size();
        while (!queue.isEmpty() && stuckGuard-- > 0) {
            JsonObject json = queue.poll();
            if (hasParentLoaded(json)) {
                if (register(json)) registered++;
            } else {
                queue.add(json);
            }
        }
        for (JsonObject leftover : queue) {
            Bukkit.getLogger().warning("[Cataclysm] Advancement sin parent disponible: "
                    + leftover.get("$file").getAsString()
                    + " (parent=" + (leftover.has("parent") ? leftover.get("parent").getAsString() : "ninguno") + ")");
        }

        Bukkit.getLogger().info("[Cataclysm] Advancements cargados desde el jar: " + registered
                + "/" + files.size());
    }

    private static boolean hasParentLoaded(JsonObject json) {
        if (!json.has("parent")) return true;
        NamespacedKey parent = keyFromPath(json.get("parent").getAsString());
        return parent == null || Bukkit.getAdvancement(parent) != null;
    }

    /**
     * Acepta rutas estilo vanilla ("the_twisted/root"), keys completas
     * ("cataclysm:the_twisted/root") o externas ("minecraft:story/root").
     */
    private static NamespacedKey keyFromPath(String raw) {
        String s = raw.trim();
        int colon = s.indexOf(':');
        if (colon >= 0) {
            String ns = s.substring(0, colon);
            String key = s.substring(colon + 1);
            if (key.isEmpty()) return null;
            return new NamespacedKey(ns, key);
        }
        return new NamespacedKey(NAMESPACE, s);
    }

    private static boolean register(JsonObject json) {
        String file = json.remove("$file").getAsString();
        String keyPath = file.substring(RESOURCE_ROOT.length() + 1)
                .replaceAll("\\.json$", "");
        NamespacedKey key = new NamespacedKey(NAMESPACE, keyPath);
        if (Bukkit.getAdvancement(key) != null) return false; // ya existia (u otro plugin lo registro)

        String parentRaw = json.has("parent") ? json.get("parent").getAsString() : null;
        NamespacedKey parentKey = parentRaw != null ? keyFromPath(parentRaw) : null;

        var displayObj = json.has("display") && json.getAsJsonObject("display").isJsonObject()
                ? json.getAsJsonObject("display") : null;

        AdvancementBuilder builder = Advancement.builder(key, parentKey);

        if (displayObj != null) {
            Component title = textOf(displayObj, "title");
            Component desc = textOf(displayObj, "description");
            AdvancementDisplay.Frame frame = switch (displayObj.has("frame")
                    ? displayObj.get("frame").getAsString().toLowerCase() : "task") {
                case "goal" -> AdvancementDisplay.Frame.GOAL;
                case "challenge" -> AdvancementDisplay.Frame.CHALLENGE;
                default -> AdvancementDisplay.Frame.TASK;
            };
            if (displayObj.has("icon") && displayObj.getAsJsonObject("icon").isJsonObject()) {
                var iconObj = displayObj.getAsJsonObject("icon");
                String itemName = iconObj.has("id") ? iconObj.get("id").getAsString()
                        : iconObj.has("item") ? iconObj.get("item").getAsString() : null;
                if (itemName != null) {
                    Material mat = Material.matchMaterial(
                            itemName.contains(":") ? itemName : "minecraft:" + itemName);
                    // Si el item no existe en este server (p.ej. bloque de otro plugin),
                    // caemos a un icono generico para no perder el advancement.
                    builder.icon(new ItemStack(mat != null ? mat : Material.BOOK));
                }
            }
            builder.display(
                    title != null ? title : Component.text(keyPath),
                    desc != null ? desc : Component.empty(),
                    frame,
                    displayObj.has("show_toast") && displayObj.get("show_toast").getAsBoolean(),
                    !displayObj.has("announce_to_chat") || displayObj.get("announce_to_chat").getAsBoolean(),
                    displayObj.has("hidden") && displayObj.get("hidden").getAsBoolean(),
                    displayObj.has("background")
                            ? keyFromPath(displayObj.get("background").getAsString())
                            : null
            );
        }

        var criteriaObj = json.has("criteria") && json.getAsJsonObject("criteria").isJsonObject()
                ? json.getAsJsonObject("criteria") : null;
        if (criteriaObj != null && criteriaObj.size() > 0) {
            for (var entry : criteriaObj.entrySet()) {
                builder.addCriterion(entry.getKey());
            }
        } else {
            builder.addCriterion("impossible");
        }

        var requirementsJson = json.has("requirements") && json.get("requirements").isJsonArray()
                ? json.getAsJsonArray("requirements") : null;
        if (requirementsJson != null) {
            for (var group : requirementsJson) {
                if (group.isJsonArray()) {
                    List<String> orGroup = new ArrayList<>();
                    for (var c : group.getAsJsonArray()) orGroup.add(c.getAsString());
                    builder.addRequirements(orGroup);
                } else {
                    builder.addRequirement(group.getAsString());
                }
            }
        }

        try {
            Advancement created = Bukkit.addAdvancement(builder.build());
            if (created != null) {
                Bukkit.getLogger().fine("[Cataclysm] Advancement registrado: " + key);
                return true;
            }
        } catch (Exception e) {
            Bukkit.getLogger().warning("[Cataclysm] Fallo al registrar " + key + ": " + e.getMessage());
        }
        return false;
    }

    /** Convierte title/description (objeto Component o string plano) a Adventure Component. */
    private static Component textOf(JsonObject displayObj, String field) {
        if (!displayObj.has(field)) return null;
        var el = displayObj.get(field);
        try {
            if (el.isJsonObject()) {
                return GsonComponentSerializer.gson().deserialize(el.toString());
            }
            return Component.text(el.getAsString());
        } catch (Exception e) {
            return null;
        }
    }

    private static List<String> listJsonFiles(Plugin plugin) {
        List<String> out = new ArrayList<>();
        try (var jarFile = new java.util.zip.ZipFile(pluginJar(plugin))) {
            var entries = jarFile.entries();
            while (entries.hasMoreElements()) {
                var entry = entries.nextElement();
                String name = entry.getName();
                if (!entry.isDirectory()
                        && name.startsWith(RESOURCE_ROOT + "/")
                        && name.endsWith(".json")) {
                    out.add(name);
                }
            }
        } catch (Exception e) {
            // Fallback: carpeta de datos (si alguien copio los JSON a mano).
            File dir = new File(plugin.getDataFolder(), RESOURCE_ROOT);
            if (dir.isDirectory()) {
                collectFromDir(dir, RESOURCE_ROOT, out);
            }
        }
        return out;
    }

    private static File pluginJar(Plugin plugin) throws Exception {
        // JavaPlugin#getFile es protected; usamos reflection una sola vez.
        var m = org.bukkit.plugin.java.JavaPlugin.class.getDeclaredMethod("getFile");
        m.setAccessible(true);
        return (File) m.invoke(plugin);
    }

    private static void collectFromDir(File dir, String prefix, List<String> out) {
        File[] files = dir.listFiles();
        if (files == null) return;
        for (File f : files) {
            if (f.isDirectory()) {
                collectFromDir(f, prefix + "/" + f.getName(), out);
            } else if (f.getName().endsWith(".json")) {
                out.add(prefix + "/" + f.getName());
            }
        }
    }
}
