package ru.lepchul.bastion.check;

import io.netty.channel.Channel;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPipeline;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import ru.lepchul.bastion.Bastion;
import ru.lepchul.bastion.data.PlayerData;

import java.lang.reflect.Field;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.Set;

/**
 * Перехват входящих пакетов через netty-пайплайн.
 * Имена NMS-классов различаются между маппингами, поэтому пакеты классифицируются
 * по простому имени класса, а канал ищется рефлексией по типу поля.
 */
public class PacketGuard {

    public static final String HANDLER_NAME = "bastion_packet_guard";

    private final Bastion plugin;

    public PacketGuard(Bastion plugin) {
        this.plugin = plugin;
    }

    public boolean enabled() {
        return plugin.getConfig().getBoolean("packets.enabled", true);
    }

    // ---------------------------------------------------------------
    public void inject(Player player) {
        if (!enabled()) return;
        try {
            Channel channel = findChannel(player);
            if (channel == null) {
                plugin.getLogger().warning("Не удалось найти netty-канал для " + player.getName()
                        + " — защита от пакетного флуда для него отключена.");
                return;
            }
            ChannelPipeline pipeline = channel.pipeline();
            if (pipeline.get(HANDLER_NAME) != null) pipeline.remove(HANDLER_NAME);

            ChannelDuplexHandler handler = new ChannelDuplexHandler() {
                @Override
                public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
                    try {
                        if (!handleInbound(player, msg)) {
                            return; // пакет отброшен
                        }
                    } catch (Throwable ignored) {
                    }
                    super.channelRead(ctx, msg);
                }
            };

            if (pipeline.get("packet_handler") != null) {
                pipeline.addBefore("packet_handler", HANDLER_NAME, handler);
            } else {
                pipeline.addLast(HANDLER_NAME, handler);
            }
        } catch (Throwable t) {
            plugin.getLogger().warning("Инъекция пакетов не удалась: " + t);
        }
    }

    public void eject(Player player) {
        try {
            Channel channel = findChannel(player);
            if (channel == null) return;
            channel.eventLoop().submit(() -> {
                try {
                    if (channel.pipeline().get(HANDLER_NAME) != null) {
                        channel.pipeline().remove(HANDLER_NAME);
                    }
                } catch (Throwable ignored) {
                }
                return null;
            });
        } catch (Throwable ignored) {
        }
    }

    // ---------------------------------------------------------------
    /** @return false — пакет нужно отбросить. */
    private boolean handleInbound(Player player, Object packet) {
        PlayerData d = plugin.data(player);
        if (d == null || d.kicking) return true;

        long now = System.currentTimeMillis();
        if (now - d.packetWindowStart >= 1000L) {
            d.packetWindowStart = now;
            d.totalPackets.set(0);
            d.packetCounts.values().forEach(a -> a.set(0));
        }

        int total = d.totalPackets.incrementAndGet();
        int maxTotal = plugin.getConfig().getInt("packets.max-total-per-second", 500);
        if (total > maxTotal) {
            kick(player, CheckType.PACKET_SPAM, "всего пакетов/сек=" + total + " лимит=" + maxTotal);
            return false;
        }

        String key = classify(packet.getClass().getSimpleName());
        if (key == null) return true;

        int count = d.packet(key).incrementAndGet();
        int limit = plugin.getConfig().getInt("packets.limits." + key, 60);
        if (count > limit) {
            kick(player, CheckType.PACKET_SPAM, key + "/сек=" + count + " лимит=" + limit);
            return false;
        }
        if ("swap-hand".equals(key) && count > plugin.getConfig().getInt("packets.limits.swap-hand", 20)) {
            kick(player, CheckType.OFFHAND_CRASH, "свапов рук/сек=" + count);
            return false;
        }
        if ("keepalive".equals(key) && count > plugin.getConfig().getInt("packets.limits.keepalive", 8)) {
            kick(player, CheckType.KEEPALIVE_SPOOF, "keep-alive/сек=" + count);
            return false;
        }
        return true;
    }

    /** Классификация пакета по простому имени класса (работает и с obf, и с mojang-маппингом). */
    private String classify(String simpleName) {
        String n = simpleName.toLowerCase();
        if (n.contains("flying") || n.contains("moveplayer") || n.contains("position") || n.contains("look")) return "move";
        if (n.contains("armanimation") || n.contains("swing")) return "swing";
        if (n.contains("blockdig") || n.contains("playeraction")) return "dig";
        if (n.contains("useentity") || n.contains("interact")) return "use-entity";
        if (n.contains("windowclick") || n.contains("containerclick")) return "window-click";
        if (n.contains("setcreativeslot") || n.contains("creativemode")) return "creative";
        if (n.contains("helditemslot") || n.contains("carrieditem")) return "held-slot";
        if (n.contains("custompayload")) return "payload";
        if (n.contains("keepalive")) return "keepalive";
        if (n.contains("updatesign") || n.contains("signupdate")) return "sign";
        if (n.contains("bedit") || n.contains("editbook")) return "book";
        if (n.contains("blockplace") || n.contains("useitem")) return "place";
        if (n.contains("swaphand") || n.contains("swapitem") || n.contains("pickitem")) return "swap-hand";
        return null;
    }

    private void kick(Player player, CheckType type, String details) {
        PlayerData d = plugin.data(player);
        if (d == null || d.kicking) return;
        d.kicking = true;
        Bukkit.getScheduler().runTask(plugin, () -> {
            plugin.violations().report(player, type, details, plugin.violations().maxVl(type));
            if (player.isOnline()) {
                player.kickPlayer(org.bukkit.ChatColor.translateAlternateColorCodes('&',
                        plugin.getConfig().getString("messages.kick",
                                "§cВы были замечены в использовании читов.\n§7Лог о вашем поведении был отправлен администратором")));
            }
        });
    }

    // ---------------------------------------------------------------
    /** Ищет io.netty.channel.Channel рефлексией внутри handle игрока. */
    public static Channel findChannel(Player player) throws Exception {
        Object handle = player.getClass().getMethod("getHandle").invoke(player);
        return search(handle, 0, new HashSet<>());
    }

    private static Channel search(Object root, int depth, Set<Object> seen) {
        if (root == null || depth > 3) return null;
        if (root instanceof Channel c) return c;
        if (!seen.add(root)) return null;

        Deque<Object> queue = new ArrayDeque<>();
        Class<?> cls = root.getClass();
        while (cls != null && cls != Object.class) {
            for (Field f : cls.getDeclaredFields()) {
                if (java.lang.reflect.Modifier.isStatic(f.getModifiers())) continue;
                try {
                    f.setAccessible(true);
                    Object value = f.get(root);
                    if (value == null) continue;
                    if (value instanceof Channel c) return c;
                    String pkg = value.getClass().getName();
                    if (pkg.startsWith("net.minecraft") || pkg.startsWith("org.bukkit")
                            || pkg.startsWith("io.netty")) {
                        queue.add(value);
                    }
                } catch (Throwable ignored) {
                }
            }
            cls = cls.getSuperclass();
        }
        for (Object next : queue) {
            Channel c = search(next, depth + 1, seen);
            if (c != null) return c;
        }
        return null;
    }
}
