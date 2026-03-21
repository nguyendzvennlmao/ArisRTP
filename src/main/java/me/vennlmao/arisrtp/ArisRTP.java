package me.vennlmao.arisrtp;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.*;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class ArisRTP extends JavaPlugin implements Listener, CommandExecutor {

    private final HashMap<UUID, Long> cooldowns = new HashMap<>();
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
        String soundName = getConfig().getString("settings.sounds." + path);
        if (soundName != null) {
            try {
                p.playSound(p.getLocation(), Sound.valueOf(soundName), 1f, 1f);
            } catch (IllegalArgumentException ignored) {}
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
            long timeLeft = (cooldowns.get(player.getUniqueId()) - System.currentTimeMillis()) / 1000;
            if (timeLeft > 0) {
                sendCustomMessage(player, "cooldown");
                playConfigSound(player, "cooldown-error");
                return;
            }
        }
        Inventory gui = Bukkit.createInventory(null, 27, "§8§lRANDOM TELEPORT");
        setGuiItem(gui, 11, "world");
        setGuiItem(gui, 13, "world_nether");
        setGuiItem(gui, 15, "world_the_end");
        player.openInventory(gui);
    }

    private void setGuiItem(Inventory gui, int slot, String key) {
        String p = "settings.worlds." + key;
        String matName = getConfig().getString(p + ".item");
        if (matName == null) return;
        Material m = Material.valueOf(matName);
        ItemStack item = new ItemStack(m);
        ItemMeta meta = item.getItemMeta();
        meta.setDisplayName(translate(getConfig().getString(p + ".name")));
        List<String> lore = new ArrayList<>();
        for (String s : getConfig().getStringList(p + ".lore")) lore.add(translate(s));
        meta.setLore(lore);
        item.setItemMeta(meta);
        gui.setItem(slot, item);
    }

    @EventHandler
    public void onClick(InventoryClickEvent e) {
        if (!e.getView().getTitle().equals("§8§lRANDOM TELEPORT")) return;
        e.setCancelled(true);
        Player p = (Player) e.getWhoClicked();
        World w = switch (e.getRawSlot()) {
            case 11 -> Bukkit.getWorld("world");
            case 13 -> Bukkit.getWorld("world_nether");
            case 15 -> Bukkit.getWorld("world_the_end");
            default -> null;
        };
        if (w != null) {
            playConfigSound(p, "gui-click");
            p.closeInventory();
            handleRTP(p, w);
        }
    }

    private void handleRTP(Player p, World w) {
        for (int i = 5; i > 0; i--) {
            int time = i;
            p.getScheduler().execute(this, () -> {
                sendCustomMessage(p, "countdown", "%time%", String.valueOf(time));
                playConfigSound(p, "countdown-tick");
            }, null, (5 - i) * 20L);
        }
        p.getScheduler().execute(this, () -> findSafe(p, w, 0), null, 100L);
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
