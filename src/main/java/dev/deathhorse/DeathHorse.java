package dev.deathhorse;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.*;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Horse;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.world.EntitiesLoadEvent;
import org.bukkit.inventory.HorseInventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.*;
import java.util.stream.Collectors;

public class DeathHorse extends JavaPlugin implements Listener {

    // Players who get a horse every time they respawn
    private final Set<UUID> selected = new HashSet<>();
    // Player UUID -> UUID of the horse currently belonging to them
    private final Map<UUID, UUID> horses = new HashMap<>();
    // Horses that should die but were in an unloaded chunk at the time
    private final Set<UUID> pendingKill = new HashSet<>();

    @Override
    public void onEnable() {
        getConfig().getStringList("players").forEach(s -> {
            try { selected.add(UUID.fromString(s)); } catch (IllegalArgumentException ignored) {}
        });
        getConfig().getStringList("pending").forEach(s -> {
            try { pendingKill.add(UUID.fromString(s)); } catch (IllegalArgumentException ignored) {}
        });
        ConfigurationSection sec = getConfig().getConfigurationSection("horses");
        if (sec != null) {
            for (String key : sec.getKeys(false)) {
                try {
                    horses.put(UUID.fromString(key), UUID.fromString(sec.getString(key, "")));
                } catch (IllegalArgumentException ignored) {}
            }
        }
        Bukkit.getPluginManager().registerEvents(this, this);
    }

    private void save() {
        getConfig().set("players", selected.stream().map(UUID::toString).collect(Collectors.toList()));
        getConfig().set("pending", pendingKill.stream().map(UUID::toString).collect(Collectors.toList()));
        getConfig().set("horses", null);
        horses.forEach((p, h) -> getConfig().set("horses." + p, h.toString()));
        saveConfig();
    }

    /** Spawns a saddled, diamond-armored horse under the player and puts them on it. */
    private void spawnHorse(Player player) {
        killHorse(player.getUniqueId()); // one horse per player

        Horse horse = player.getWorld().spawn(player.getLocation(), Horse.class, h -> {
            h.setTamed(true);
            h.setOwner(player);
            h.setAdult();
            h.setStyle(Horse.Style.NONE);
            h.setColor(Horse.Color.BROWN);
            HorseInventory inv = h.getInventory();
            inv.setSaddle(new ItemStack(Material.SADDLE));
            inv.setArmor(new ItemStack(Material.DIAMOND_HORSE_ARMOR));
        });
        horse.addPassenger(player);

        horses.put(player.getUniqueId(), horse.getUniqueId());
        save();
    }

    /** Instantly kills the player's horse (no item drops). */
    private void killHorse(UUID playerId) {
        UUID horseId = horses.remove(playerId);
        if (horseId == null) return;

        Entity e = Bukkit.getEntity(horseId);
        if (e instanceof Horse h && h.isValid()) {
            killNoDrops(h);
        } else {
            // Chunk isn't loaded right now - kill it as soon as it loads
            pendingKill.add(horseId);
        }
        save();
    }

    private void killNoDrops(Horse horse) {
        horse.getInventory().clear(); // so the saddle and diamond armor don't drop
        horse.setHealth(0);
    }

    @EventHandler
    public void onDeath(PlayerDeathEvent event) {
        killHorse(event.getPlayer().getUniqueId());
    }

    @EventHandler
    public void onRespawn(PlayerRespawnEvent event) {
        Player player = event.getPlayer();
        if (!selected.contains(player.getUniqueId())) return;

        // Wait one tick so the player is fully placed in the world
        Bukkit.getScheduler().runTaskLater(this, () -> {
            if (player.isOnline()) spawnHorse(player);
        }, 1L);
    }

    @EventHandler
    public void onEntitiesLoad(EntitiesLoadEvent event) {
        if (pendingKill.isEmpty()) return;
        boolean changed = false;
        for (Entity e : event.getEntities()) {
            if (e instanceof Horse h && pendingKill.remove(h.getUniqueId())) {
                killNoDrops(h);
                changed = true;
            }
        }
        if (changed) save();
    }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (args.length == 0) return false;

        switch (args[0].toLowerCase()) {
            case "list" -> {
                String names = selected.stream()
                        .map(u -> Optional.ofNullable(Bukkit.getOfflinePlayer(u).getName()).orElse(u.toString()))
                        .collect(Collectors.joining(", "));
                sender.sendMessage("Selected players: " + (names.isEmpty() ? "none" : names));
            }
            case "add" -> {
                if (args.length < 2) return false;
                OfflinePlayer target = Bukkit.getOfflinePlayer(args[1]);
                if (!target.hasPlayedBefore() && !target.isOnline()) {
                    sender.sendMessage("Player not found: " + args[1]);
                    return true;
                }
                selected.add(target.getUniqueId());
                save();
                sender.sendMessage(args[1] + " will now respawn on a horse.");
                Player online = target.getPlayer();
                if (online != null) spawnHorse(online); // instant horse too
            }
            case "remove" -> {
                if (args.length < 2) return false;
                OfflinePlayer target = Bukkit.getOfflinePlayer(args[1]);
                selected.remove(target.getUniqueId());
                save();
                sender.sendMessage(args[1] + " will no longer respawn on a horse.");
            }
            case "summon" -> {
                // Spawns a horse right now without adding the player to the respawn list
                Player target;
                if (args.length >= 2) {
                    target = Bukkit.getPlayerExact(args[1]);
                } else if (sender instanceof Player p) {
                    target = p;
                } else {
                    sender.sendMessage("Usage: /deathhorse summon <player>");
                    return true;
                }
                if (target == null) {
                    sender.sendMessage("That player isn't online.");
                    return true;
                }
                spawnHorse(target);
                sender.sendMessage("Spawned a horse for " + target.getName() + ".");
            }
            default -> { return false; }
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command cmd, String alias, String[] args) {
        if (args.length == 1) return List.of("add", "remove", "summon", "list");
        if (args.length == 2 && !args[0].equalsIgnoreCase("list"))
            return Bukkit.getOnlinePlayers().stream().map(Player::getName).collect(Collectors.toList());
        return List.of();
    }
}
