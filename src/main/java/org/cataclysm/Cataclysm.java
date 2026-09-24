package org.cataclysm;

import co.aikar.commands.PaperCommandManager;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import lombok.Getter;
import lombok.Setter;
import me.libraryaddict.disguise.DisguiseConfig;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.Listener;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scoreboard.Team;
import org.cataclysm.api.boss.CataclysmBoss;
import org.cataclysm.api.item.crafting.CataclysmRecipes;
import org.cataclysm.api.listener.registrable.RegistrableUtils;
import org.cataclysm.api.mob.CataclysmMob;
import org.cataclysm.api.mob.MobUtils;
import org.cataclysm.api.mob.store.MobStore;
import org.cataclysm.api.structure.StructureManager;
import org.cataclysm.api.structure.data.StructureLoader;
import org.cataclysm.discord.DiscordChannels;
import org.cataclysm.discord.DiscordConnection;
import org.cataclysm.discord.DiscordListener;
import org.cataclysm.game.GameManager;
import org.cataclysm.game.data.GameDataManager;
import org.cataclysm.game.events.finale.CataclysmFinale;
import org.cataclysm.game.events.limited.EventManager;
import org.cataclysm.game.events.limited.data.EventLoader;
import org.cataclysm.game.events.pantheon.PantheonOfCataclysm;
import org.cataclysm.game.events.pantheon.cmd.PantheonCommand;
import org.cataclysm.game.events.pantheon.cmd.ProfileCommand;
import org.cataclysm.game.events.raids.structures.RaidStructures;
import org.cataclysm.game.mob.task.MobTask;
import org.cataclysm.game.player.CataclysmPlayer;
import org.cataclysm.game.player.PlayerTask;
import org.cataclysm.game.player.data.PlayerLoader;
import org.cataclysm.game.player.survival.advancement.AdvancementLoader;
import org.cataclysm.game.player.survival.death.DeathSequence;
import org.cataclysm.game.world.generator.CataclysmGenerator;
import org.cataclysm.game.world.ragnarok.Ragnarok;
import org.cataclysm.game.world.ragnarok.RagnarokLoader;
import org.cataclysm.game.world.time.TimeManager;
import org.cataclysm.game.world.time.data.TimeLoader;
import org.cataclysm.global.commands.CataclysmCommand;
import org.cataclysm.global.commands.PodiumCommand;
import org.cataclysm.global.commands.RaidCommand;
import org.cataclysm.global.commands.StaffCommand;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.HashSet;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

public final class Cataclysm extends JavaPlugin {
    private static final @Getter ScheduledExecutorService service = Executors.newSingleThreadScheduledExecutor();
    private static final @Getter HashMap<UUID, Integer> tasks = new HashMap<>();

    private static final @Getter Gson gson = new GsonBuilder().setPrettyPrinting().serializeNulls().create();
    private static final @Getter HashMap<String, CataclysmPlayer> cataclysmPlayers = new HashMap<>();

    private static @Getter @Setter PantheonOfCataclysm pantheon;
    private static @Getter @Setter CataclysmFinale finale;

    private static @Getter @Setter TimeManager timeManager;
    private static @Getter @Setter int day;

    private static @Getter Cataclysm instance;
    private static @Getter MobStore store;

    private static @Getter @Setter @Nullable CataclysmBoss boss;
    private static @Getter @Setter DiscordConnection discord;
    private static @Getter @Setter DeathSequence deathSequence;
    private static @Getter @Setter GameManager gameManager;
    private static @Getter @Setter EventManager eventManager;
    private static @Getter @Setter Ragnarok ragnarok;

    @Override
    public void onEnable() {
        CataclysmMob.initializeMobConstructors();
        instance = this;
        store = new MobStore();
        saveDefaultConfig();
        String token = Cataclysm.getInstance().getConfig().getString("discord_token");
        discordIsNotEnabled = (token == null || token.isBlank());

        if (!discordIsNotEnabled) {
            discord = new DiscordConnection();
            DiscordChannels.reload();
        }

        try {
            StructureLoader.loadAll();
            PlayerLoader.loadAll();
            new TimeLoader().restore();
            new GameDataManager().restore();
            new RagnarokLoader().restore();
            new EventLoader().restore();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }

        for (var player : Bukkit.getOnlinePlayers()) {
            if (ragnarok != null) ragnarok.getBossBar().addViewer(player);
            if (eventManager != null) eventManager.barManager.bossBar.addViewer(player);
        }

        PaperCommandManager paperCommandManager = new PaperCommandManager(this);
        paperCommandManager.getCommandCompletions().registerAsyncCompletion("@CataclysmMobs", c -> MobUtils.getMobNames());
        paperCommandManager.registerCommand(new CataclysmCommand());
        paperCommandManager.registerCommand(new StaffCommand());
        paperCommandManager.registerCommand(new PodiumCommand());
        paperCommandManager.registerCommand(new RaidCommand());
        //Pantheon Commands
        paperCommandManager.registerCommand(new PantheonCommand());
        paperCommandManager.registerCommand(new ProfileCommand());
        paperCommandManager.registerCommand(new FinaleCommand());

        if (!discordIsNotEnabled) {
            Bukkit.getPluginManager().registerEvents(new DiscordListener(), this);
        }

        RegistrableUtils.registerListeners();
        // Paper no registra automaticamente los /advancements de un paper-plugin,
        // y Shadow puede ocultarlos al classloader: los cargamos desde el jar.
        AdvancementLoader.loadAll();
        CataclysmRecipes.updateRecipes();

        new PlayerTask().startTickTask(20);
        new MobTask().startTickTask(40);

        for (RaidStructures raidStructures : RaidStructures.values()) {
            Listener listener = raidStructures.getStructure().getListener();
            if (listener == null) continue;
            Bukkit.getPluginManager().registerEvents(listener, Cataclysm.getInstance());
        }

        CataclysmGenerator.setUp();
        DisguiseConfig.setPlayerNameType(DisguiseConfig.PlayerNameType.VANILLA);

        Bukkit.getConsoleSender().sendMessage("   ___   _ _____ _   ___ _ __   _____ __  __ ");
        Bukkit.getConsoleSender().sendMessage("  / __| /_\\_   _/_\\ / __| |\\ \\ / / __|  \\/  |");
        Bukkit.getConsoleSender().sendMessage(" | (__ / _ \\| |/ _ \\ (__| |_\\ V /\\__ \\ |\\/| |");
        Bukkit.getConsoleSender().sendMessage("  \\___/_/ \\_\\_/_/ \\_\\___|____|_| |___/_|  |_|");
        Bukkit.getConsoleSender().sendMessage("");
        Bukkit.getConsoleSender().sendMessage("Cataclysm has been succesfully enabled.");
    }

    @Override
    public void onDisable() {
        Team team = Bukkit.getScoreboardManager().getMainScoreboard().getTeam("nocollision");
        if (team != null) {
            for (String entry : new HashSet<>(team.getEntries())) {
                try {
                    UUID uuid = UUID.fromString(entry);
                    if (Bukkit.getEntity(uuid) == null) {
                        team.removeEntry(entry);
                    }
                } catch (IllegalArgumentException ignored) {
                    team.removeEntry(entry);
                }
            }
        }
        if (pantheon != null) pantheon.getConfigurator().save();

        if (ragnarok != null) {
            for (Player player : Bukkit.getOnlinePlayers()) {
                ragnarok.getBossBar().removeViewer(player);
            }
        }

        for (Player player : Bukkit.getOnlinePlayers()) {
            if (ragnarok != null) ragnarok.getBossBar().removeViewer(player);
            if (eventManager != null) eventManager.barManager.bossBar.removeViewer(player);
        }

        try {
            StructureManager.getInstance().shutdown();
            StructureLoader.saveAll();
            PlayerLoader.saveAll();

            new TimeLoader().save();
            new GameDataManager().save();
            new RagnarokLoader().save();
            new EventLoader().save();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }

        tasks.forEach((uuid, task) -> Bukkit.getScheduler().cancelTask(task));
        tasks.clear();
        Bukkit.getConsoleSender().sendMessage("Cataclysm has been succesfully disabled.");
    }

    public static void debug(String info) {
        Bukkit.getConsoleSender().sendMessage("[Cataclysm Debug] " + info);
    }

    /**
     * Verifies if the server is the main Cataclysm's host or the BETA.
     * @return If the server is Cataclysm's main host.
     */
    public static boolean isTesting() {
        return Cataclysm.getInstance().getConfig().getBoolean("testing");
    }

    public static boolean discordIsNotEnabled = true;
}