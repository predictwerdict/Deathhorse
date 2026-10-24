package dev.deathhorse;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.*;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Horse;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.inventory.HorseInventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.*;
import java.util.stream.Collectors;

public class DeathHorse extends JavaPlugin implements Listener {

    private final Set<UUID> selected = new HashSet<>();

    @Override
    public void onEnable() {
        saveDefaultConfig();
        getConfig().getStringList("players").forEach(s -> {
            try { selected.add(UUID.fromString(s)); } catch (IllegalArgumentException ignored) {}
        });
        Bukkit.getPluginManager().registerEvents(this, this);
    }

    private void save() {
        getConfig().set("players", selected.stream().map(UUID::toString).collect(Collectors.toList()));
        saveConfig();
    }

    @EventHandler
    public void onRespawn(PlayerRespawnEvent event) {
        Player player = event.getPlayer();
        if (!selected.contains(player.getUniqueId())) return;

        // Wait one tick so the player is fully placed in the world
        Bukkit.getScheduler().runTaskLater(this, () -> {
            if (!player.isOnline()) return;
            Horse horse = (Horse) player.getWorld().spawnEntity(player.getLocation(), EntityType.HORSE);
            horse.setTamed(true);
            horse.setOwner(player);
            horse.setAdult();
            horse.setStyle(Horse.Style.NONE);
            horse.setColor(Horse.Color.BROWN);

            HorseInventory inv = horse.getInventory();
            inv.setSaddle(new ItemStack(Material.SADDLE));
            inv.setArmor(new ItemStack(Material.DIAMOND_HORSE_ARMOR));

            horse.addPassenger(player);
        }, 1L);
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
            case "add", "remove" -> {
                if (args.length < 2) return false;
                OfflinePlayer target = Bukkit.getOfflinePlayer(args[1]);
                if (!target.hasPlayedBefore() && !target.isOnline()) {
                    sender.sendMessage("Player not found: " + args[1]);
                    return true;
                }
                if (args[0].equalsIgnoreCase("add")) {
                    selected.add(target.getUniqueId());
                    sender.sendMessage(args[1] + " will now respawn on a horse.");
                } else {
                    selected.remove(target.getUniqueId());
                    sender.sendMessage(args[1] + " will no longer respawn on a horse.");
                }
                save();
            }
            default -> { return false; }
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command cmd, String alias, String[] args) {
        if (args.length == 1) return List.of("add", "remove", "list");
        if (args.length == 2 && !args[0].equalsIgnoreCase("list"))
            return Bukkit.getOnlinePlayers().stream().map(Player::getName).collect(Collectors.toList());
        return List.of();
    }
}
