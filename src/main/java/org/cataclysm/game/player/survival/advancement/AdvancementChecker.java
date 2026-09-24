package org.cataclysm.game.player.survival.advancement;

import org.bukkit.entity.Player;
import org.bukkit.persistence.PersistentDataType;
import org.cataclysm.api.data.PersistentData;
import org.cataclysm.api.item.ItemBuilder;
import org.cataclysm.api.structure.CataclysmStructure;
import org.cataclysm.game.items.ItemFamily;
import org.cataclysm.game.world.dungeons.Monolith;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.HashSet;

public class AdvancementChecker {
    private final Player player;

    public AdvancementChecker(Player player) {
        this.player = player;
    }

    public void checkChallengeCompletion(@NotNull CataclysmStructure structure) {
        var advancement = "";

        var id = structure.getConfig().getId();
        switch (id) {
            case "monolith" -> {
                var progress = getChallengeProgress(structure);
                if (progress >= Monolith.CHALLENGE_GOAL) advancement = "the_twisted/the_wise_men_fear";
            }
        }

        if (!advancement.isEmpty()) {
            new CataclysmAdvancement(advancement).grant(this.player);
        }
    }

    public void addChallengeProgress(@NotNull CataclysmStructure structure) {
        var data = PersistentData.get(this.player, structure.getUuid().toString(), PersistentDataType.INTEGER);
        if (data == null) data = 0;

        data++;
        PersistentData.set(this.player, structure.getUuid().toString(), PersistentDataType.INTEGER, data);
        this.checkChallengeCompletion(structure);
    }

    public int getChallengeProgress(@NotNull CataclysmStructure structure) {
        var data = PersistentData.get(this.player, structure.getUuid().toString(), PersistentDataType.INTEGER);
        if (data == null) return 0;
        return data;
    }

    public void checkMobAdvancements(@NotNull String key) {
        var advancements = new ArrayList<String>();

        switch (key) {
            case "twisted_terror" -> {
                var twistedMobs = new String[]{"Zombie", "Skeleton", "Creeper", "Spider", "Blaze", "Brute", "Enderman"};
                var completed = true;
                for (var mob : twistedMobs) {
                    var id = "Twisted" + mob;
                    var data = PersistentData.get(player, id, PersistentDataType.BOOLEAN);
                    if (data == null || !data) completed = false;
                }
                if (completed) advancements.add("the_twisted/twisted_terror");
            }
            case "ur_hunter" -> {
                var data = PersistentData.get(player, "Ur-Ghast", PersistentDataType.INTEGER);
                if (data == null) return;
                if (data >= 5) advancements.add("the_nether/ur_hunter");
            }
            case "cataclysm_unnerfed_edition" -> {
                var data = PersistentData.get(player, "CalamityBlaze", PersistentDataType.INTEGER);
                if (data == null) return;
                if (data >= 30) advancements.add("the_nether/cataclysm_unnerfed_edition");
            }
            case "cacaclysm" -> {
                var boggedTypes = new String[]{"Arbalist", "Arcane", "Warlock", "Cataclyst"};
                var completed = true;
                for (var type : boggedTypes) {
                    var id = type + "Bogged";
                    var data = PersistentData.get(player, id, PersistentDataType.BOOLEAN);
                    if (data == null || !data) completed = false;
                }
                if (completed) advancements.add("the_nether/cacaclysm");
            }
        }

        for (var advancement : advancements) {
            new CataclysmAdvancement(advancement).grant(this.player);
        }
    }

    public void checkEachFamilyAdvancements() {
        checkFamilyAdvancements(ItemFamily.values());
    }

    public void checkFamilyAdvancements(ItemFamily @NotNull ... itemFamilies) {
        var inventory = this.player.getInventory();
        var advancements = new ArrayList<String>();

        for (var family : itemFamilies) {
            if (family.getAdvancement() == null) continue;
            var advancementManager = new CataclysmAdvancement(family.getAdvancement());
            if (advancementManager.isDone(this.player)) continue;

            var set = new HashSet<String>();
            for (var item : inventory.getContents()) {
                if (item == null) continue;

                var builder = new ItemBuilder(item);
                var id = builder.getID();
                if (id == null) continue;

                var key = family.getKey();
                if (id.contains(key)) {
                    if (family == ItemFamily.CALAMITY_ARMOR) {
                        var name = item.getType().name();
                        if (!name.contains("NETHERITE") || name.contains("INGOT")) continue; //? Rushed code, basically just avoid non calamity armor items
                    }
                    set.add(id);
                }
            }

            var advancement = family.getAdvancement();
            if (advancement == null) continue;

            var members = family.getMembers();
            if (set.size() >= members.size()) advancements.add(advancement);
        }

        for (var advancement : advancements) {
            new CataclysmAdvancement(advancement).grant(this.player);
        }
    }

    public void checkUpgradeAdvancements(int upgrades) {
        var advancements = new ArrayList<String>();
        if (upgrades >= 2) advancements.add("wise_wise_very_wise");
        if (upgrades >= 4) advancements.add("wisdom_of_the_turtle");
        if (upgrades >= 6) advancements.add("kame_guy");

        for (var advancement : advancements) {
            new CataclysmAdvancement("the_beginning/" + advancement).grant(this.player);
        }
    }



}
