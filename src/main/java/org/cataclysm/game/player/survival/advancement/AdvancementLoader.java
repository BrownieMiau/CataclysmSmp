package org.cataclysm.game.player.survival.advancement;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
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

        // La API de advancements programatica (Advancement.builder / builder.display(...,Frame,...)
        // / Bukkit.addAdvancement) NO es estable entre versiones de Paper y su paquete/enum
        // Frame cambia (io.papermc.paper.advancement.* vs org.bukkit.advancement.*).
        // Para que el proyecto SIEMPRE compile, todo el registro se hace por reflection
        // resolviendo los nombres correctos en tiempo de ejecucion segun el server.
        try {
            Object built = buildViaReflection(key, parentKey, displayObj, json, keyPath);
            Object created = addAdvancement(built);
            if (created != null) {
                Bukkit.getLogger().fine("[Cataclysm] Advancement registrado: " + key);
                return true;
            }
        } catch (Exception e) {
            Bukkit.getLogger().warning("[Cataclysm] Fallo al registrar " + key + ": " + e.getMessage());
        }
        return false;
    }

    private static Object buildViaReflection(NamespacedKey key, NamespacedKey parentKey,
                                             JsonObject displayObj, JsonObject json, String keyPath)
            throws Exception {
        Class<?> advClass = org.bukkit.advancement.Advancement.class;
        java.lang.reflect.Method builderMethod = findBuilderMethod(advClass);
        Class<?>[] bp = builderMethod.getParameterTypes();
        boolean takesParentKey = bp.length >= 2 && bp[1] == NamespacedKey.class;
        Object builder = takesParentKey
                ? builderMethod.invoke(null, key, parentKey)
                : builderMethod.invoke(null, key);
        Class<?> bc = builder.getClass();

        if (displayObj != null) {
            Component title = textOf(displayObj, "title");
            Component desc = textOf(displayObj, "description");
            String frameName = switch (displayObj.has("frame")
                    ? displayObj.get("frame").getAsString().toLowerCase() : "task") {
                case "goal" -> "GOAL";
                case "challenge" -> "CHALLENGE";
                default -> "TASK";
            };

            ItemStack icon = null;
            if (displayObj.has("icon") && displayObj.getAsJsonObject("icon").isJsonObject()) {
                var iconObj = displayObj.getAsJsonObject("icon");
                String itemName = iconObj.has("item") ? iconObj.get("item").getAsString()
                        : iconObj.has("id") ? iconObj.get("id").getAsString() : null;
                if (itemName != null) {
                    Material mat = Material.matchMaterial(
                            itemName.contains(":") ? itemName : "minecraft:" + itemName);
                    // Item inexistente (p.ej. de otro plugin) -> icono generico.
                    icon = new ItemStack(mat != null ? mat : Material.BOOK);
                }
            }
            if (icon == null) icon = new ItemStack(Material.BOOK);

            NamespacedKey background = displayObj.has("background")
                    ? keyFromPath(displayObj.get("background").getAsString()) : null;
            boolean showToast = displayObj.has("show_toast") && displayObj.get("show_toast").getAsBoolean();
            boolean announce = !displayObj.has("announce_to_chat")
                    || displayObj.get("announce_to_chat").getAsBoolean();
            boolean hidden = displayObj.has("hidden") && displayObj.get("hidden").getAsBoolean();

            java.lang.reflect.Method dm = findDisplayMethod(bc);
            Class<?>[] p = dm.getParameterTypes();
            Object[] args = new Object[p.length];
            int idx = 0;
            if (p[0].equals(ItemStack.class)) {
                args[idx++] = icon;
            }
            args[idx++] = title != null ? title : Component.text(keyPath);   // Component title
            args[idx++] = desc != null ? desc : Component.empty();           // Component description
            args[idx++] = resolveFrame(frameName);                           // Frame enum
            // Rellena los flags restantes (toast / chat / hidden) segun orden de tipos.
            for (; idx < p.length; idx++) {
                if (p[idx] == boolean.class) {
                    if (!consumedToast) { args[idx] = showToast; consumedToast = true; }
                    else if (!consumedAnnounce) { args[idx] = announce; consumedAnnounce = true; }
                    else { args[idx] = hidden; }
                } else if (p[idx] == NamespacedKey.class) {
                    args[idx] = background;
                } else if (Component.class.isAssignableFrom(p[idx])) {
                    args[idx] = Component.empty();
                } else {
                    args[idx] = defaultFor(p[idx]);
                }
            }
            consumedToast = false;
            consumedAnnounce = false;
            dm.invoke(builder, args);
        }

        if (parentKey != null) {
            Advancement parent = Bukkit.getAdvancement(parentKey);
            if (parent != null) {
                trySetParent(bc, builder, parent);
            }
        }

        var criteriaObj = json.has("criteria") && json.getAsJsonObject("criteria").isJsonObject()
                ? json.getAsJsonObject("criteria") : null;
        if (criteriaObj != null && criteriaObj.size() > 0) {
            for (var entry : criteriaObj.entrySet()) {
                bc.getMethod("addCriterion", String.class).invoke(builder, entry.getKey());
            }
        } else {
            bc.getMethod("addCriterion", String.class).invoke(builder, "impossible");
        }

        var requirementsJson = json.has("requirements") && json.get("requirements").isJsonArray()
                ? json.getAsJsonArray("requirements") : null;
        if (requirementsJson != null) {
            for (var group : requirementsJson) {
                if (group.isJsonArray()) {
                    List<String> orGroup = new ArrayList<>();
                    for (var c : group.getAsJsonArray()) orGroup.add(c.getAsString());
                    bc.getMethod("addRequirements", List.class).invoke(builder, orGroup);
                } else {
                    bc.getMethod("addRequirement", String.class)
                            .invoke(builder, group.getAsString());
                }
            }
        }

        return bc.getMethod("build").invoke(builder);
    }

    private static boolean consumedToast = false;
    private static boolean consumedAnnounce = false;

    /** Busca Advancement.builder(key[, parent]) sin importar la firma exacta de la version. */
    private static java.lang.reflect.Method findBuilderMethod(Class<?> advClass) throws Exception {
        for (var m : advClass.getMethods()) {
            if (!m.getName().equals("builder")) continue;
            var p = m.getParameterTypes();
            if (p.length >= 1 && p[0] == NamespacedKey.class) return m;
        }
        throw new NoSuchMethodException("Advancement.builder(NamespacedKey, ...)");
    }

    /** Busca builder.display(...) con >=3 params; prefiere el overload que empieza con ItemStack. */
    private static java.lang.reflect.Method findDisplayMethod(Class<?> bc) throws Exception {
        java.lang.reflect.Method fallback = null;
        for (var m : bc.getMethods()) {
            if (!m.getName().equals("display")) continue;
            var p = m.getParameterTypes();
            if (p.length < 3) continue;
            if (p[0] == ItemStack.class) return m;
            if (fallback == null) fallback = m;
        }
        if (fallback != null) return fallback;
        throw new NoSuchMethodException("AdvancementBuilder.display(...)");
    }

    /** Resuelve el enum Frame sea cual sea su paquete en esta version de Paper. */
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
        throw new ClassNotFoundException("Advancement Display Frame enum");
    }

    private static void trySetParent(Class<?> bc, Object builder, Advancement parent) {
        for (var m : bc.getMethods()) {
            if (!m.getName().equals("parent")) continue;
            var p = m.getParameterTypes();
            if (p.length == 1 && p[0].isInstance(parent)) {
                try { m.invoke(builder, parent); } catch (Exception ignored) {}
                return;
            }
        }
    }

    private static Object defaultFor(Class<?> type) {
        if (type == boolean.class) return false;
        if (type == int.class) return 0;
        if (type == float.class) return 0f;
        if (type == double.class) return 0d;
        if (type == long.class) return 0L;
        return null;
    }

    /** Llama Bukkit.addAdvancement(...) por reflection (solo existe en Paper). */
    private static Object addAdvancement(Object built) throws Exception {
        for (var m : Bukkit.class.getMethods()) {
            if (m.getName().equals("addAdvancement") && m.getParameterCount() == 1
                    && m.getParameterTypes()[0].isInstance(built)) {
                return m.invoke(null, built);
            }
        }
        throw new NoSuchMethodException("Bukkit.addAdvancement");
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
