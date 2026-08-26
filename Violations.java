package ru.lepchul.bastion.check;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;
import ru.lepchul.bastion.Bastion;
import ru.lepchul.bastion.data.PlayerData;

import java.util.Map;

public class Violations {

    private final Bastion plugin;

    public Violations(Bastion plugin) {
        this.plugin = plugin;
    }

    public boolean enabled(CheckType type) {
        return plugin.getConfig().getBoolean("checks." + type.name() + ".enabled", true);
    }

    public int maxVl(CheckType type) {
        return plugin.getConfig().getInt("checks." + type.name() + ".max-vl", 10);
    }

    /**
     * Регистрирует нарушение. Возвращает true, если действие нужно отменить.
     */
    public boolean flag(Player player, CheckType type, String details) {
        return flag(player, type, details, 1.0);
    }

    public boolean flag(Player player, CheckType type, String details, double weight) {
        if (player == null || !enabled(type)) return false;
        if (plugin.isExempt(player)) return false;

        PlayerData data = plugin.data(player);
        if (data == null || data.kicking) return false;

        double now = data.vl(type) + weight;
        data.vl.put(type, now);
        data.lastFlag.put(type, System.currentTimeMillis());

        alert(player, type, details, now);

        int max = maxVl(type);
        if (max > 0 && now >= max) {
            punish(player, type, details, now);
            return true;
        }
        return true;
    }

    private void alert(Player player, CheckType type, String details, double vl) {
        String msg = plugin.getConfig().getString("messages.alert",
                        "§8[§cBastion§8] §f%player% §7провалил §c%check% §7(VL %vl%) §8%details%")
                .replace("%player%", player.getName())
                .replace("%check%", type.id())
                .replace("%vl%", String.valueOf((int) vl))
                .replace("%details%", details == null ? "" : details);
        msg = ChatColor.translateAlternateColorCodes('&', msg);

        for (Player staff : Bukkit.getOnlinePlayers()) {
            if (staff.hasPermission("bastion.alerts") && plugin.alertsEnabled(staff)) {
                staff.sendMessage(msg);
            }
        }
        if (plugin.getConfig().getBoolean("general.console-alerts", true)) {
            plugin.getLogger().info(ChatColor.stripColor(msg));
        }
    }

    private void punish(Player player, CheckType type, String details, double vl) {
        PlayerData data = plugin.data(player);
        data.kicking = true;
        report(player, type, details, vl);

        String kickMsg = ChatColor.translateAlternateColorCodes('&',
                plugin.getConfig().getString("messages.kick",
                        "§cВы были замечены в использовании читов.\n§7Лог о вашем поведении был отправлен администратором"));

        Bukkit.getScheduler().runTask(plugin, () -> {
            if (player.isOnline()) player.kickPlayer(kickMsg);
        });
    }

    /** Подробный отчёт в консоль. */
    public void report(Player player, CheckType type, String details, double vl) {
        PlayerData d = plugin.data(player);
        StringBuilder sb = new StringBuilder("\n");
        sb.append("§r==================== BASTION ====================\n");
        sb.append(" Игрок      : ").append(player.getName()).append(" (").append(player.getUniqueId()).append(")\n");
        sb.append(" IP         : ").append(player.getAddress() == null ? "?" : player.getAddress().getAddress().getHostAddress()).append('\n');
        sb.append(" Клиент     : ").append(d == null ? "unknown" : d.brand).append('\n');
        sb.append(" Версия     : ").append(d == null ? "unknown" : d.protocolInfo).append('\n');
        sb.append(" Моды/каналы: ").append(d == null || d.channels.isEmpty() ? "нет" : String.join(", ", d.channels)).append('\n');
        sb.append(" Пинг       : ").append(safePing(player)).append(" мс\n");
        sb.append(" Мир        : ").append(player.getWorld().getName())
                .append(" @ ").append(player.getLocation().getBlockX()).append(' ')
                .append(player.getLocation().getBlockY()).append(' ')
                .append(player.getLocation().getBlockZ()).append('\n');
        sb.append(" Нарушение  : ").append(type.id()).append(" — ").append(type.ru())
                .append("  (VL ").append((int) vl).append(")\n");
        sb.append(" Детали     : ").append(details == null ? "-" : details).append('\n');
        if (d != null && !d.vl.isEmpty()) {
            sb.append(" История VL : ");
            for (Map.Entry<CheckType, Double> e : d.vl.entrySet()) {
                if (e.getValue() >= 1) sb.append(e.getKey().id()).append('=').append((int) (double) e.getValue()).append(' ');
            }
            sb.append('\n');
        }
        sb.append("=================================================");
        plugin.getLogger().warning(ChatColor.stripColor(sb.toString()));
    }

    private String safePing(Player p) {
        try {
            return String.valueOf(p.getPing());
        } catch (Throwable t) {
            return "?";
        }
    }
}
