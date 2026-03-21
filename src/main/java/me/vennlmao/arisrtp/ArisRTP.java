package me.vennlmao.arisrtp;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.*;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class ArisRTP extends JavaPlugin implements Listener, CommandExecutor {

    private final HashMap<UUID, Long> cooldowns = new HashMap<>();
    private final Set<UUID> teleporting = new HashSet<>();
    private FileConfiguration msgConfig;
    private final Pattern hexPattern = Pattern.compile("&#([A-Fa-f0-9]{6})");

    @Override
    public void onEnable() {
        saveDefaultConfig();
        loadMessages();
        getCommand("rtp").setExecutor(this);
        getServer().getPluginManager().registerEvents(this, this);
    }

    private void loadMessages() {
        File file = new File(getDataFolder(), "message.yml");
        if (!file.exists()) saveResource("message.yml", false);
        msgConfig = YamlConfiguration.loadConfiguration(file);
    }

    private void playConfigSound(Player p, String path) {
        String s = getConfig().getString("settings.sounds." + path);
        if (s != null) {
            try { p.playSound(p.getLocation(), Sound.valueOf(s), 1f, 1f); } catch (Exception ignored) {}
        }
    }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (args.length > 0 && args[0].equalsIgnoreCase("reload")) {
            if (!sender.hasPermission("arisrtp.admin")) return true;
            reloadConfig();
            loadMessages();
            if (sender instanceof Player p) sendCustomMessage(p, "reload");
            return true;
        }
        if (sender instanceof Player p) openRTPGui(p);
        return true;
    }

    public void openRTPGui(Player player) {
        if (cooldowns.containsKey(player.getUniqueId())) {
            long left = (cooldowns.get(player.getUniqueId()) - System.currentTimeMillis()) / 1000;
            if (left > 0) {
                sendCustomMessage(player, "cooldown");
                playConfigSound(player, "cooldown-error");
                return;
            }
        }

        String title = translate(getConfig().getString("settings.gui.title", "&8Random Teleport"));
        int rows = getConfig().getInt("settings.gui.rows", 3) * 9;
        Inventory gui = Bukkit.createInventory(null, rows, title);

        ConfigurationSection worlds = getConfig().getConfigurationSection("settings.worlds");
        if (worlds != null) {
            for (String key : worlds.getKeys(false)) {
                setGuiItem(gui, key);
            }
        }
        player.openInventory(gui);
    }

    private void setGuiItem(Inventory gui, String key) {
        String path = "settings.worlds." + key;
        int slot = getConfig().getInt(path + ".slot");
        String matName = getConfig().getString(path + ".item");
        if (matName == null || slot >= gui.getSize()) return;

        ItemStack item = new ItemStack(Material.valueOf(matName));
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(translate(getConfig().getString(path + ".name")));
            List<String> lore = new ArrayList<>();
            for (String s : getConfig().getStringList(path + ".lore")) lore.add(translate(s));
            meta.setLore(lore);
            item.setItemMeta(meta);
        }
        gui.setItem(slot, item);
    }

    @EventHandler
    public void onClick(InventoryClickEvent e) {
        String title = translate(getConfig().getString("settings.gui.title", "&8Random Teleport"));
        if (!e.getView().getTitle().equals(title)) return;
        e.setCancelled(true);
        
        Player p = (Player) e.getWhoClicked();
        int clickedSlot = e.getRawSlot();

        ConfigurationSection worlds = getConfig().getConfigurationSection("settings.worlds");
        if (worlds != null) {
            for (String key : worlds.getKeys(false)) {
                if (getConfig().getInt("settings.worlds." + key + ".slot") == clickedSlot) {
                    World w = Bukkit.getWorld(key);
                    if (w != null) {
                        playConfigSound(p, "gui-click");
                        p.closeInventory();
                        handleRTP(p, w);
                    }
                    break;
                }
            }
        }
    }

    @EventHandler
    public void onDamage(EntityDamageEvent e) {
        if (e.getEntity() instanceof Player p && teleporting.contains(p.getUniqueId())) {
            teleporting.remove(p.getUniqueId());
            sendCustomMessage(p, "cancelled");
            playConfigSound(p, "cooldown-error");
        }
    }

    private void handleRTP(Player p, World w) {
        Location startLoc = p.getLocation();
        UUID id = p.getUniqueId();
        teleporting.add(id);

        for (int i = 5; i >= 0; i--) {
            int time = i;
            p.getScheduler().execute(this, () -> {
                if (!teleporting.contains(id)) return;
                if (p.getLocation().distanceSquared(startLoc) > 0.01) {
                    teleporting.remove(id);
                    sendCustomMessage(p, "cancelled");
                    playConfigSound(p, "cooldown-error");
                    return;
                }
                if (time > 0) {
                    sendCustomMessage(p, "countdown", "%time%", String.valueOf(time));
                    playConfigSound(p, "countdown-tick");
                } else {
                    teleporting.remove(id);
                    findSafe(p, w, 0);
                }
            }, null, (5 - i) * 20L);
        }
    }

    private void findSafe(Player p, World w, int t) {
        if (t > getConfig().getInt("settings.max-retries")) return;
        sendCustomMessage(p, "searching");
        int r = getConfig().getInt("settings.worlds." + w.getName() + ".max-radius");
        int x = ThreadLocalRandom.current().nextInt(-r, r);
        int z = ThreadLocalRandom.current().nextInt(-r, r);
        int y = w.getHighestBlockYAt(x, z);
        Location loc = new Location(w, x + 0.5, y + 1, z + 0.5);
        Material block = loc.clone().add(0, -1, 0).getBlock().getType();
        if (block == Material.LAVA || block == Material.WATER || block == Material.AIR) {
            findSafe(p, w, t + 1);
            return;
        }
        p.teleportAsync(loc).thenAccept(s -> {
            if (s) {
                sendCustomMessage(p, "success");
                playConfigSound(p, "teleport-success");
                cooldowns.put(p.getUniqueId(), System.currentTimeMillis() + (getConfig().getLong("settings.cooldown-seconds") * 1000));
            }
        });
    }

    private void sendCustomMessage(Player p, String path, String... r) {
        String m = msgConfig.getString("messages." + path + ".text");
        if (m == null) return;
        for (int i = 0; i < r.length; i += 2) m = m.replace(r[i], r[i + 1]);
        Component c = LegacyComponentSerializer.legacySection().deserialize(translate(m));
        if (msgConfig.getBoolean("messages." + path + ".chat")) p.sendMessage(c);
        if (msgConfig.getBoolean("messages." + path + ".actionbar")) p.sendActionBar(c);
    }

    private String translate(String m) {
        if (m == null) return "";
        m = m.replace("&#fb0000", "§x§F§B§0§0§0§0");
        Matcher matcher = hexPattern.matcher(m);
        StringBuilder sb = new StringBuilder();
        while (matcher.find()) {
            String color = matcher.group(1);
            StringBuilder replace = new StringBuilder("§x");
            for (char c : color.toCharArray()) replace.append('§').append(c);
            matcher.appendReplacement(sb, replace.toString());
        }
        matcher.appendTail(sb);
        return ChatColor.translateAlternateColorCodes('&', sb.toString());
    }
        }
