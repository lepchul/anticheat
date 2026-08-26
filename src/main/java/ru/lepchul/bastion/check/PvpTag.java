package ru.lepchul.bastion.check;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.boss.BossBar;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import ru.lepchul.bastion.Bastion;
import ru.lepchul.bastion.data.PlayerData;

import java.util.List;
import java.util.Locale;

public class PvpTag implements Listener {

    private final Bastion plugin;

    public PvpTag(Bastion plugin) {
        this.plugin = plugin;
    }

    private boolean enabled() {
        return plugin.getConfig().getBoolean("pvp-tag.enabled", true);
    }

    public void tag(Player player, Player source) {
        if (!enabled() || player == null) return;
        PlayerData d = plugin.data(player);
        if (d == null) return;

        int seconds = plugin.getConfig().getInt("pvp-tag.seconds", 30);
        boolean fresh = !d.inPvp();
        d.pvpTagUntil = System.currentTimeMillis() + seconds * 1000L;
        if (source != null) d.lastAttacker = source.getUniqueId();

        if (d.bossBar == null) {
            String title = plugin.getConfig().getString("pvp-tag.bossbar-title", "§cРежим ПВП §7— §f%seconds%с");
            d.bossBar = Bukkit.createBossBar(
                    ChatColor.translateAlternateColorCodes('&', title.replace("%seconds%", String.valueOf(seconds))),
                    BarColor.RED, BarStyle.SEGMENTED_10);
        }
        if (!d.bossBar.getPlayers().contains(player)) d.bossBar.addPlayer(player);
        d.bossBar.setVisible(true);
        d.bossBar.setProgress(1.0);

        if (fresh) {
            player.sendMessage(ChatColor.RED + "Вы вступили в бой. Выход = смерть, телепорт заблокирован.");
        }
    }

    /** Вызывается каждый тик из основного таймера. */
    public void tick() {
        if (!enabled()) return;
        int seconds = plugin.getConfig().getInt("pvp-tag.seconds", 30);
        String title = plugin.getConfig().getString("pvp-tag.bossbar-title", "§cРежим ПВП §7— §f%seconds%с");

        for (Player p : Bukkit.getOnlinePlayers()) {
            PlayerData d = plugin.data(p);
            if (d == null || d.bossBar == null) continue;

            long left = d.pvpTagUntil - System.currentTimeMillis();
            if (left <= 0) {
                if (d.bossBar.isVisible()) {
                    d.bossBar.removeAll();
                    d.bossBar.setVisible(false);
                    p.sendMessage(ChatColor.GREEN + "Режим ПВП закончился.");
                }
                continue;
            }
            double progress = Math.max(0.0, Math.min(1.0, left / (seconds * 1000.0)));
            d.bossBar.setProgress(progress);
            d.bossBar.setTitle(ChatColor.translateAlternateColorCodes('&',
                    title.replace("%seconds%", String.valueOf((int) Math.ceil(left / 1000.0)))));
            d.bossBar.setColor(left < 5000 ? BarColor.YELLOW : BarColor.RED);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent e) {
        if (!enabled() || !plugin.getConfig().getBoolean("pvp-tag.block-teleport", true)) return;
        PlayerData d = plugin.data(e.getPlayer());
        if (d == null || !d.inPvp()) return;

        switch (e.getCause()) {
            case ENDER_PEARL:
            case COMMAND:
            case PLUGIN:
            case CHORUS_FRUIT:
            case SPECTATE:
                e.setCancelled(true);
                e.getPlayer().sendMessage(ChatColor.RED + "Телепорт заблокирован — вы в бою.");
                break;
            default:
                break;
        }
    }

    public void onCommand(PlayerCommandPreprocessEvent e) {
        if (!enabled()) return;
        PlayerData d = plugin.data(e.getPlayer());
        if (d == null || !d.inPvp()) return;

        String cmd = e.getMessage().substring(1).split(" ")[0].toLowerCase(Locale.ROOT);
        if (cmd.contains(":")) cmd = cmd.substring(cmd.indexOf(':') + 1);
        List<String> blocked = plugin.getConfig().getStringList("pvp-tag.blocked-commands");
        if (blocked.contains(cmd)) {
            e.setCancelled(true);
            e.getPlayer().sendMessage(ChatColor.RED + "Эта команда заблокирована во время боя.");
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent e) {
        Player p = e.getPlayer();
        PlayerData d = plugin.data(p);
        if (d == null) return;
        if (d.bossBar != null) {
            d.bossBar.removeAll();
            d.bossBar = null;
        }
        if (!enabled() || !plugin.getConfig().getBoolean("pvp-tag.kill-on-logout", true)) return;
        if (!d.inPvp()) return;

        // combat log — убиваем на месте, вещи выпадают
        try {
            p.setHealth(0.0);
            plugin.getLogger().warning("[PvP] " + p.getName() + " вышел в бою и был убит (combat log).");
            String attacker = d.lastAttacker == null ? "неизвестно" : String.valueOf(Bukkit.getOfflinePlayer(d.lastAttacker).getName());
            Bukkit.broadcastMessage(ChatColor.RED + p.getName() + " вышел из игры во время боя с " + attacker + " и умер.");
        } catch (Throwable t) {
            plugin.getLogger().warning("[PvP] Не удалось убить " + p.getName() + " при выходе: " + t.getMessage());
        }
    }
}
