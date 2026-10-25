package dev.deathhorse;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
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
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.*;
import java.util.stream.Collectors;

public class DeathHorse extends JavaPlugin implements Listener {

    enum Mode { HORSE, KIT }

    // Players who get a horse every time they respawn
    private final Set<UUID> selected = new HashSet<>();
    // HORSE = just the horse, KIT = horse + diamond armor + diamond sword for the player
    private final Map<UUID, Mode> modes = new HashMap<>();
    // Player UUID -> UUID of the horse currently belonging to them
    private final Map<UUID, UUID> horses = new HashMap<>();
    // Horses that should die but were in an unloaded chunk at the time
    private final Set<UUID> pendingKill = new HashSet<>();

    private NamespacedKey kitKey;

    @Override
    public void onEnable() {
        kitKey = new NamespacedKey(this, "kit");

        getConfig().getStringList("players").forEach(s -> {
            try { selected.add(UUID.fromString(s)); } catch (IllegalArgumentException ignored) {}
        });
        getConfig().getStringList("pending").forEach(s -> {
            try { pendingKill.add(UUID.fromString(s)); } catch (IllegalArgumentException ignored) {}
        });
        ConfigurationSection horseSec = getConfig().getConfigurationSection("horses");
        if (horseSec != null) {
            for (String key : horseSec.getKeys(false)) {
                try {
                    horses.put(UUID.fromString(key), UUID.fromString(horseSec.getString(key, "")));
                } catch (IllegalArgumentException ignored) {}
            }
        }
        ConfigurationSection modeSec = getConfig().getConfigurationSection("modes");
        if (modeSec != null) {
            for (String key : modeSec.getKeys(false)) {
                try {
                    modes.put(UUID.fromString(key), Mode.valueOf(modeSec.getString(key, "HORSE").toUpperCase()));
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
        getConfig().set("modes", null);
        modes.forEach((p, m) -> getConfig().set("modes." + p, m.name()));
        saveConfig();
    }

    private Mode modeOf(UUID id) {
        return modes.getOrDefault(id, Mode.HORSE);
    }

    // ---------------------------------------------------------------- horse

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

    // ------------------------------------------------------------------ kit

    private ItemStack kitItem(Material material) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        meta.getPersistentDataContainer().set(kitKey, PersistentDataType.BYTE, (byte) 1);
        item.setItemMeta(meta);
        return item;
    }

    private boolean isKitItem(ItemStack item) {
        if (item == null || !item.hasItemMeta()) return false;
        return item.getItemMeta().getPersistentDataContainer().has(kitKey, PersistentDataType.BYTE);
    }

    /** Removes any kit items the player currently has (so we never duplicate them). */
    private void clearKit(Player player) {
        PlayerInventory inv = player.getInventory();

        ItemStack[] storage = inv.getStorageContents();
        for (int i = 0; i < storage.length; i++) if (isKitItem(storage[i])) storage[i] = null;
        inv.setStorageContents(storage);

        ItemStack[] armor = inv.getArmorContents();
        for (int i = 0; i < armor.length; i++) if (isKitItem(armor[i])) armor[i] = null;
        inv.setArmorContents(armor);

        if (isKitItem(inv.getItemInOffHand())) inv.setItemInOffHand(null);
    }

    /** Gives the player diamond armor and a diamond sword. */
    private void giveKit(Player player) {
        clearKit(player);
        PlayerInventory inv = player.getInventory();
        inv.setHelmet(kitItem(Material.DIAMOND_HELMET));
        inv.setChestplate(kitItem(Material.DIAMOND_CHESTPLATE));
        inv.setLeggings(kitItem(Material.DIAMOND_LEGGINGS));
        inv.setBoots(kitItem(Material.DIAMOND_BOOTS));
        inv.addItem(kitItem(Material.DIAMOND_SWORD));
    }

    // --------------------------------------------------------------- events

    @EventHandler
    public void onDeath(PlayerDeathEvent event) {
        Player player = event.getPlayer();
        killHorse(player.getUniqueId());
        // Kit armor and sword never drop (even for players who are no longer selected)
        event.getDrops().removeIf(this::isKitItem);
    }

    @EventHandler
    public void onRespawn(PlayerRespawnEvent event) {
        Player player = event.getPlayer();
        if (!selected.contains(player.getUniqueId())) return;

        // Wait one tick so the player is fully placed in the world
        Bukkit.getScheduler().runTaskLater(this, () -> {
            if (!player.isOnline()) return;
            if (modeOf(player.getUniqueId()) == Mode.KIT) giveKit(player);
            spawnHorse(player);
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

    // ------------------------------------------------------------- commands

    private Mode parseMode(String s) {
        if (s == null) return Mode.HORSE;
        return s.equalsIgnoreCase("kit") ? Mode.KIT : s.equalsIgnoreCase("horse") ? Mode.HORSE : null;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (args.length == 0) return false;

        switch (args[0].toLowerCase()) {
            case "list" -> {
                String names = selected.stream()
                        .map(u -> Optional.ofNullable(Bukkit.getOfflinePlayer(u).getName()).orElse(u.toString())
                                + " (" + modeOf(u).name().toLowerCase() + ")")
                        .collect(Collectors.joining(", "));
                sender.sendMessage("Selected players: " + (names.isEmpty() ? "none" : names));
            }
            case "add" -> {
                if (args.length < 2) return false;
                Mode mode = parseMode(args.length >= 3 ? args[2] : null);
                if (mode == null) {
                    sender.sendMessage("Mode must be 'horse' or 'kit'.");
                    return true;
                }
                OfflinePlayer target = Bukkit.getOfflinePlayer(args[1]);
                if (!target.hasPlayedBefore() && !target.isOnline()) {
                    sender.sendMessage("Player not found: " + args[1]);
                    return true;
                }
                selected.add(target.getUniqueId());
                modes.put(target.getUniqueId(), mode);
                save();
                sender.sendMessage(args[1] + " will now respawn with: " + mode.name().toLowerCase() + ".");
                Player online = target.getPlayer();
                if (online != null) { // instant horse (and kit) too
                    if (mode == Mode.KIT) giveKit(online);
                    spawnHorse(online);
                }
            }
            case "mode" -> {
                if (args.length < 3) return false;
                Mode mode = parseMode(args[2]);
                if (mode == null) {
                    sender.sendMessage("Mode must be 'horse' or 'kit'.");
                    return true;
                }
                OfflinePlayer target = Bukkit.getOfflinePlayer(args[1]);
                if (!selected.contains(target.getUniqueId())) {
                    sender.sendMessage(args[1] + " isn't selected. Use /deathhorse add first.");
                    return true;
                }
                modes.put(target.getUniqueId(), mode);
                save();
                sender.sendMessage(args[1] + " is now in mode: " + mode.name().toLowerCase() + ".");
                Player online = target.getPlayer();
                if (online != null) {
                    if (mode == Mode.KIT) giveKit(online); else clearKit(online);
                }
            }
            case "remove" -> {
                if (args.length < 2) return false;
                OfflinePlayer target = Bukkit.getOfflinePlayer(args[1]);
                selected.remove(target.getUniqueId());
                modes.remove(target.getUniqueId());
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
        if (args.length == 1) return List.of("add", "remove", "mode", "summon", "list");
        if (args.length == 2 && !args[0].equalsIgnoreCase("list"))
            return Bukkit.getOnlinePlayers().stream().map(Player::getName).collect(Collectors.toList());
        if (args.length == 3 && (args[0].equalsIgnoreCase("add") || args[0].equalsIgnoreCase("mode")))
            return List.of("horse", "kit");
        return List.of();
    }
}
