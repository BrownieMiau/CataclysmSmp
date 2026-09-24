package org.cataclysm.game.player.survival.advancement;

import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.entity.Arrow;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityBreedEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.inventory.CraftItemEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.persistence.PersistentDataType;
import org.cataclysm.Cataclysm;
import org.cataclysm.api.data.PersistentData;
import org.cataclysm.api.item.ItemBuilder;
import org.cataclysm.api.listener.registrable.Registrable;
import org.cataclysm.api.mob.CataclysmMob;
import org.cataclysm.game.block.arcane.table.events.PlayerUseArcaneTableEvent;
import org.cataclysm.game.effect.ImmunityEffect;
import org.cataclysm.game.items.CataclysmItems;
import org.cataclysm.game.player.CataclysmPlayer;
import org.cataclysm.game.player.mechanics.upgrade.UpgradeManager;
import org.cataclysm.game.player.mechanics.upgrade.event.PlayerUpgradeLemegetonEvent;

@Registrable
public class AdvancementListener implements Listener {

    @EventHandler
    private void onPlayerItemConsume(PlayerItemConsumeEvent event) {
        var itemStack = event.getItem();
        var builder = new ItemBuilder(itemStack);
        var player = event.getPlayer();

        var id = builder.getID();
        if (id == null) return;

        var advancement = "";

        switch (id) {
            case "twisted_flesh" -> advancement = "the_twisted/twister";
        }

        // Solo otorgar si se consumo un item con advancement asociado
        if (advancement.isEmpty()) return;
        new CataclysmAdvancement(advancement).grant(player);
    }

    @EventHandler
    public void onEntityDeath(EntityDeathEvent event) {
        var entity = event.getEntity();
        var killer = entity.getKiller();

        if (killer == null) return;

        var id = CataclysmMob.getID(entity);
        if (id == null) return;

        var player = killer.getPlayer();
        if (player == null) return;
        if (id.contains("Twisted")) {
            new CataclysmAdvancement("the_twisted/twisted_reality").grant(player);

            PersistentData.set(killer, id, PersistentDataType.BOOLEAN, true);
            new AdvancementChecker(player).checkMobAdvancements("twisted_terror");
            return;
        }
        if (id.equalsIgnoreCase("Ur-Ghast")) {
            var data = PersistentData.get(killer, id, PersistentDataType.INTEGER);
            if (data == null) data = 0;
            data++;
            PersistentData.set(killer, id, PersistentDataType.INTEGER, data);
            new AdvancementChecker(player).checkMobAdvancements("ur_hunter");
            return;
        }
        if (id.equalsIgnoreCase("CalamityBlaze")) {
            if (killer.getInventory().getItemInMainHand().getType().name().contains("BOW")) return;
            var data = PersistentData.get(killer, id, PersistentDataType.INTEGER);
            if (data == null) data = 0;
            data++;
            PersistentData.set(killer, id, PersistentDataType.INTEGER, data);
            new AdvancementChecker(player).checkMobAdvancements("cataclysm_unnerfed_edition");
        }
    }

    @EventHandler
    public void onCraftItem(CraftItemEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;

        var result = event.getRecipe().getResult();
        var builder = new ItemBuilder(result);

        var id = builder.getID();
        if (id == null) return;

        var advancement = "";

        switch (id) {
            case "paragon_blessing" -> advancement = "the_beginning/a_gods_power";
            case "twisted_relic" -> advancement = "the_twisted/from_the_abyss";
            case "midway_relic" -> advancement = "the_nether/halfway_through";
        }

        if (id.isEmpty()) return;
        if (advancement.isEmpty()) return;

        new CataclysmAdvancement(advancement).grant(player);
    }

    @EventHandler(priority =  EventPriority.LOWEST)
    private void onPlayerInteractEvent(PlayerInteractEvent event) {
        if (event.getAction().isLeftClick()) return;

        var itemStack = event.getItem();
        if (itemStack == null) return;

        var builder = new ItemBuilder(itemStack);
        var id = builder.getID();
        if (id == null) return;

        var player = event.getPlayer();
        if (player.hasCooldown(itemStack)) return;

        if (id.equalsIgnoreCase("paragons_blessing")) new CataclysmAdvancement("the_beginning/feeling_inmortal").grant(player);
    }

    @EventHandler
    private void onPlayerJoinEvent(PlayerJoinEvent event) {
        var player = event.getPlayer();
        new CataclysmAdvancement("root").grant(player);

        var day = Cataclysm.getDay();
        if (day >= 0) new CataclysmAdvancement("the_beginning/root").grant(player);
        if (day >= 7) new CataclysmAdvancement("the_twisted/root").grant(player);
        if (day >= 14) new CataclysmAdvancement("the_nether/root").grant(player);
        if (day >= 21) new CataclysmAdvancement("the_end/root").grant(player);

        if (day >= 28) {
            new CataclysmAdvancement("the_pale_void/root").grant(player);
            new CataclysmAdvancement("the_pale_void/esne_paratus").grant(player);
        }

        new CataclysmAdvancement("the_beginning/are_you_ready").grant(player);

        var um = CataclysmPlayer.getCataclysmPlayer(player);
        if (um != null) {
            var upgrades = new UpgradeManager(player).getUpgrades();
            var advancementChecker = new AdvancementChecker(player);
            advancementChecker.checkUpgradeAdvancements(upgrades);
            advancementChecker.checkEachFamilyAdvancements();
        }

    }

    @EventHandler
    private void onPlayerUpgradeLemegeton(PlayerUpgradeLemegetonEvent event) {
        var player = event.getPlayer();
        var upgrades = event.getUpgrades();

        new AdvancementChecker(player).checkUpgradeAdvancements(upgrades);
    }

    @EventHandler
    private void onPlayerUseArcaneTableEvent(PlayerUseArcaneTableEvent event) {
        var player = event.getPlayer();
        var result = event.getResult();

        var builder = new ItemBuilder(result);
        var family = builder.getFamily();

        if (family == null) return;

        new CataclysmAdvancement("the_twisted/archanter").grant(player);
        new AdvancementChecker(player).checkFamilyAdvancements(family);

        if (builder.getID() != null && builder.getID().equals(CataclysmItems.MIRAGE_ELYTRA.getBuilder().getID())) {
            new CataclysmAdvancement("the_end/to_infinity_and_beyond").grant(player);
        }
    }

    @EventHandler
    private void onPlayerBreedLlama(EntityBreedEvent event) {
        if (!(event.getBreeder() instanceof Player player)) return;
        var breeded = event.getEntity();

        var day = Cataclysm.getDay();
        if (day >= 14 && breeded.getType() == EntityType.LLAMA) new CataclysmAdvancement("the_nether/viracocha").grant(player);
    }

    @EventHandler
    private void onPlayerInteractAtEntity(PlayerInteractEntityEvent event) {
        var player = event.getPlayer();
        if (!(event.getRightClicked() instanceof LivingEntity entity)) return;
        if (player.getEquipment() == null) return;

        var id = CataclysmMob.getID(entity);
        if (id == null) return;

        var itemInHand = player.getEquipment().getItem(event.getHand());
        if (id.contains("Bogged") && itemInHand.getType() == Material.SHEARS) {
            PersistentData.set(player, id, PersistentDataType.BOOLEAN, true);
            new AdvancementChecker(player).checkMobAdvancements("cacaclysm");
        }
    }

    @EventHandler
    private void onEntityDamagePlayer(EntityDamageByEntityEvent event) {
        if (!(event.getEntity() instanceof Player player)) return;
        if (!(event.getDamager() instanceof Arrow arrow && arrow.getShooter() instanceof LivingEntity entity)) return;

        var id = CataclysmMob.getID(entity);
        if (id == null) return;

        if (event.isCancelled() || player.isBlocking() || player.hasPotionEffect(ImmunityEffect.EFFECT_TYPE)) return;
        if (id.equalsIgnoreCase("NetherNightmare")) {
            int totems = CataclysmPlayer.getCataclysmPlayer(player).getTotemManager().getPoppedTotems();
            Bukkit.getScheduler().runTaskLater(Cataclysm.getInstance(), () -> {
                int difference = CataclysmPlayer.getCataclysmPlayer(player).getTotemManager().getPoppedTotems() - totems;
                if (difference == 0 && player.getGameMode() != GameMode.SPECTATOR) new CataclysmAdvancement("the_nether/one_shot_nightmare").grant(player);
            }, 2L);
        }
    }

}
