package ru.lepchul.bastion;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.NamespacedKey;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRegisterChannelEvent;
import org.bukkit.plugin.java.JavaPlugin;
import ru.lepchul.bastion.check.*;
import ru.lepchul.bastion.data.PlayerData;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class Bastion extends JavaPlugin implements Listener {

    private final Map<UUID, PlayerData> players = new ConcurrentHashMap<>();
    private final Set<UUID> alertsOff = new HashSet<>();
    private final Set<UUID> manualExempt = new HashSet<>();

    private Violations violations;
    private PvpTag pvpTag;
    private PacketGuard packetGuard;
    private XRayHeuristic xray;
    private LagGuard lagGuard;
    private NamespacedKey featherKey;

    @Override
    public void onEnable() {
        saveDefaultConfig();

        featherKey = new NamespacedKey(this,
                getConfig().getString("custom-enchants.feather.pdc-key", "feather"));

        violations = new Violations(this);
        pvpTag = new PvpTag(this);
        packetGuard = new PacketGuard(this);
        xray = new XRayHeuristic(this);
        lagGuard = new LagGuard(this);

        Bukkit.getPluginManager().registerEvents(this, this);
        Bukkit.getPluginManager().registerEvents(new MovementChecks(this), this);
        Bukkit.getPluginManager().registerEvents(new CombatChecks(this), this);
        Bukkit.getPluginManager().registerEvents(new WorldChecks(this), this);
        Bukkit.getPluginManager().registerEvents(pvpTag, this);
        Bukkit.getPluginManager().registerEvents(lagGuard, this);

        // определение клиента
        Bukkit.getMessenger().registerIncomingPluginChannel(this, "minecraft:brand",
                (channel, player, message) -> {
                    PlayerData d = data(player);
                    if (d != null) d.brand = readBrand(message);
                });

        // уже онлайн (перезагрузка плагина)
        for (Player p : Bukkit.getOnlinePlayers()) {
            register(p);
        }

        startTicker();
        lagGuard.cleanupTask();

        getLogger().info("Bastion включён. Активных проверок: " + CheckType.values().length);
    }

    @Override
    public void onDisable() {
        for (Player p : Bukkit.getOnlinePlayers()) {
            packetGuard.eject(p);
            PlayerData d = players.get(p.getUniqueId());
            if (d != null && d.bossBar != null) d.bossBar.removeAll();
        }
        players.clear();
    }

    // ==================== ТИКЕР ====================
    private void startTicker() {
        Bukkit.getScheduler().runTaskTimer(this, () -> {
            long now = System.currentTimeMillis();
            int decay = getConfig().getInt("general.vl-decay-seconds", 25);

            for (Player p : Bukkit.getOnlinePlayers()) {
                PlayerData d = players.get(p.getUniqueId());
                if (d == null) continue;

                if (d.exemptTicks > 0) d.exemptTicks--;

                if (decay > 0 && now - d.lastDecay > decay * 1000L) {
                    d.lastDecay = now;
                    d.vl.replaceAll((k, val) -> Math.max(0.0, val - 1.0));
                }

                // ArrowDodge: реакция быстрее человеческой
                if (d.lastProjectileNear > 0 && now - d.lastProjectileNear < 55) {
                    double speed = p.getVelocity().clone().setY(0).length();
                    if (speed > 0.34) {
                        violations.flag(p, CheckType.ARROW_DODGE,
                                String.format("уход от снаряда за %dмс", now - d.lastProjectileNear));
                        d.lastProjectileNear = 0;
                    }
                }
                if (d.lastProjectileNear > 0 && now - d.lastProjectileNear > 500) d.lastProjectileNear = 0;
            }
            pvpTag.tick();
        }, 1L, 1L);
    }

    // ==================== ПОДКЛЮЧЕНИЕ ====================
    @EventHandler(priority = EventPriority.LOWEST)
    public void onJoin(PlayerJoinEvent e) {
        register(e.getPlayer());
    }

    private void register(Player p) {
        PlayerData d = new PlayerData(p);
        d.protocolInfo = detectVersion(p);
        d.brand = safeBrand(p);
        d.channels.addAll(p.getListeningPluginChannels());
        players.put(p.getUniqueId(), d);
        d.exemptTicks = 60;
        packetGuard.inject(p);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent e) {
        packetGuard.eject(e.getPlayer());
        players.remove(e.getPlayer().getUniqueId());
        xray.remove(e.getPlayer().getUniqueId());
        alertsOff.remove(e.getPlayer().getUniqueId());
    }

    @EventHandler
    public void onChannel(PlayerRegisterChannelEvent e) {
        PlayerData d = data(e.getPlayer());
        if (d == null) return;
        d.channels.add(e.getChannel());
        String c = e.getChannel().toLowerCase();
        if (c.startsWith("fml") || c.contains("forge")) d.brand = "forge (" + d.brand + ")";
        else if (c.contains("fabric")) d.brand = "fabric (" + d.brand + ")";
    }

    private String safeBrand(Player p) {
        try {
            String brand = p.getClientBrandName();
            return brand == null || brand.isEmpty() ? "unknown" : brand;
        } catch (Throwable t) {
            return "unknown";
        }
    }

    /** Версия протокола: сначала ViaVersion, потом рефлексия, потом версия сервера. */
    private String detectVersion(Player p) {
        try {
            Class<?> via = Class.forName("com.viaversion.viaversion.api.Via");
            Object api = via.getMethod("getAPI").invoke(null);
            Object ver = api.getClass().getMethod("getPlayerVersion", UUID.class).invoke(api, p.getUniqueId());
            return "protocol " + ver;
        } catch (Throwable ignored) {
        }
        try {
            Object handle = p.getClass().getMethod("getHandle").invoke(p);
            for (java.lang.reflect.Field f : handle.getClass().getFields()) {
                if (f.getType() == int.class && f.getName().toLowerCase().contains("protocol")) {
                    f.setAccessible(true);
                    return "protocol " + f.getInt(handle);
                }
            }
        } catch (Throwable ignored) {
        }
        return Bukkit.getBukkitVersion();
    }

    private String readBrand(byte[] message) {
        try {
            int offset = 0;
            int length = 0, shift = 0;
            while (offset < message.length) {
                byte b = message[offset++];
                length |= (b & 0x7F) << shift;
                if ((b & 0x80) == 0) break;
                shift += 7;
            }
            if (length <= 0 || offset + length > message.length) return "unknown";
            return new String(message, offset, length, java.nio.charset.StandardCharsets.UTF_8);
        } catch (Throwable t) {
            return "unknown";
        }
    }

    // ==================== ДОСТУП ====================
    public PlayerData data(Player p) {
        return p == null ? null : players.get(p.getUniqueId());
    }

    public Violations violations() { return violations; }
    public PvpTag pvpTag() { return pvpTag; }
    public XRayHeuristic xray() { return xray; }
    public NamespacedKey featherKey() { return featherKey; }

    public String featherKeyword() {
        return getConfig().getString("custom-enchants.feather.lore-keyword", "Перо");
    }

    public boolean isExempt(Player p) {
        if (p == null) return true;
        if (manualExempt.contains(p.getUniqueId())) return true;
        return getConfig().getBoolean("general.respect-bypass-permission", true)
                && p.hasPermission("bastion.bypass");
    }

    public boolean alertsEnabled(Player p) {
        return !alertsOff.contains(p.getUniqueId());
    }

    // ==================== КОМАНДА ====================
    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            sender.sendMessage(ChatColor.RED + "/bastion <reload|alerts|info|vl|exempt>");
            return true;
        }
        switch (args[0].toLowerCase()) {
            case "reload" -> {
                reloadConfig();
                sender.sendMessage(ChatColor.GREEN + "Конфиг перезагружен.");
            }
            case "alerts" -> {
                if (!(sender instanceof Player p)) {
                    sender.sendMessage(ChatColor.RED + "Только для игроков.");
                    return true;
                }
                if (alertsOff.remove(p.getUniqueId())) sender.sendMessage(ChatColor.GREEN + "Алерты включены.");
                else {
                    alertsOff.add(p.getUniqueId());
                    sender.sendMessage(ChatColor.YELLOW + "Алерты выключены.");
                }
            }
            case "info" -> {
                if (args.length < 2) {
                    sender.sendMessage(ChatColor.RED + "/bastion info <ник>");
                    return true;
                }
                Player t = Bukkit.getPlayerExact(args[1]);
                PlayerData d = data(t);
                if (d == null) {
                    sender.sendMessage(ChatColor.RED + "Игрок не найден.");
                    return true;
                }
                sender.sendMessage(ChatColor.GOLD + "=== " + t.getName() + " ===");
                sender.sendMessage(ChatColor.GRAY + "Клиент: " + ChatColor.WHITE + d.brand);
                sender.sendMessage(ChatColor.GRAY + "Версия: " + ChatColor.WHITE + d.protocolInfo);
                sender.sendMessage(ChatColor.GRAY + "Каналы: " + ChatColor.WHITE
                        + (d.channels.isEmpty() ? "нет" : String.join(", ", d.channels)));
                sender.sendMessage(ChatColor.GRAY + "ПВП-метка: " + ChatColor.WHITE + (d.inPvp() ? "да" : "нет"));
            }
            case "vl" -> {
                if (args.length < 2) {
                    sender.sendMessage(ChatColor.RED + "/bastion vl <ник>");
                    return true;
                }
                Player t = Bukkit.getPlayerExact(args[1]);
                PlayerData d = data(t);
                if (d == null) {
                    sender.sendMessage(ChatColor.RED + "Игрок не найден.");
                    return true;
                }
                sender.sendMessage(ChatColor.GOLD + "VL " + t.getName() + ":");
                boolean any = false;
                for (Map.Entry<CheckType, Double> en : d.vl.entrySet()) {
                    if (en.getValue() >= 1) {
                        any = true;
                        sender.sendMessage(ChatColor.GRAY + " - " + en.getKey().id() + ": "
                                + ChatColor.RED + en.getValue().intValue());
                    }
                }
                if (!any) sender.sendMessage(ChatColor.GREEN + " чисто");
            }
            case "exempt" -> {
                if (args.length < 2) {
                    sender.sendMessage(ChatColor.RED + "/bastion exempt <ник>");
                    return true;
                }
                Player t = Bukkit.getPlayerExact(args[1]);
                if (t == null) {
                    sender.sendMessage(ChatColor.RED + "Игрок не найден.");
                    return true;
                }
                if (manualExempt.remove(t.getUniqueId())) {
                    sender.sendMessage(ChatColor.GREEN + t.getName() + " снова проверяется.");
                } else {
                    manualExempt.add(t.getUniqueId());
                    sender.sendMessage(ChatColor.YELLOW + t.getName() + " исключён из проверок.");
                }
            }
            default -> sender.sendMessage(ChatColor.RED + "Неизвестная подкоманда.");
        }
        return true;
    }
}
