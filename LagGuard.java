package ru.lepchul.bastion.check;

import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.*;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockPhysicsEvent;
import org.bukkit.event.block.BlockRedstoneEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.entity.EntitySpawnEvent;
import org.bukkit.event.entity.ExplosionPrimeEvent;
import org.bukkit.event.inventory.InventoryMoveItemEvent;
import org.bukkit.event.player.PlayerPortalEvent;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.event.world.PortalCreateEvent;
import ru.lepchul.bastion.Bastion;
import ru.lepchul.bastion.data.PlayerData;

import java.util.HashMap;
import java.util.Map;

/**
 * Защита от лаг-машин и краша сервера через мир:
 * дроп-бомбы, TNT-цепи, падающие блоки, редстоун-часы, хопперы,
 * взрывные цепи, спам порталов и прогрузки чанков.
 */
public class LagGuard implements Listener {

    private final Bastion plugin;

    private final Map<Long, int[]> redstone = new HashMap<>();   // chunkKey -> [count, secondStart]
    private final Map<Long, Long> frozenChunks = new HashMap<>();
    private final Map<Long, int[]> hoppers = new HashMap<>();
    private final Map<Long, int[]> explosions = new HashMap<>();

    public LagGuard(Bastion plugin) {
        this.plugin = plugin;
    }

    private boolean enabled() {
        return plugin.getConfig().getBoolean("lag.enabled", true);
    }

    private static long key(Chunk c) {
        return ((long) c.getX() << 32) ^ (c.getZ() & 0xffffffffL) ^ ((long) c.getWorld().getName().hashCode() << 16);
    }

    private static long key(Location l) {
        return ((long) (l.getBlockX() >> 4) << 32) ^ ((l.getBlockZ() >> 4) & 0xffffffffL)
                ^ ((long) l.getWorld().getName().hashCode() << 16);
    }

    // ==================== ЛИМИТЫ СУЩНОСТЕЙ ====================
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onSpawn(EntitySpawnEvent e) {
        if (!enabled()) return;
        Entity ent = e.getEntity();
        Chunk chunk = ent.getLocation().getChunk();
        if (!chunk.isLoaded()) return;

        Entity[] entities = chunk.getEntities();
        int total = entities.length;
        if (total > plugin.getConfig().getInt("lag.max-entities-per-chunk", 150)) {
            e.setCancelled(true);
            return;
        }

        int items = 0, tnt = 0, falling = 0, carts = 0, stands = 0;
        for (Entity x : entities) {
            if (x instanceof Item) items++;
            else if (x instanceof TNTPrimed) tnt++;
            else if (x instanceof FallingBlock) falling++;
            else if (x instanceof Minecart) carts++;
            else if (x instanceof ArmorStand) stands++;
        }

        if (ent instanceof Item && items > plugin.getConfig().getInt("lag.max-items-per-chunk", 96)) {
            e.setCancelled(true);
        } else if (ent instanceof TNTPrimed && tnt > plugin.getConfig().getInt("lag.max-tnt-per-chunk", 32)) {
            e.setCancelled(true);
            warn(ent.getLocation(), "TNT-лимит в чанке превышен");
        } else if (ent instanceof FallingBlock && falling > plugin.getConfig().getInt("lag.max-falling-blocks-per-chunk", 64)) {
            e.setCancelled(true);
            warn(ent.getLocation(), "лимит падающих блоков превышен");
        } else if (ent instanceof Minecart && carts > plugin.getConfig().getInt("lag.max-minecarts-per-chunk", 24)) {
            e.setCancelled(true);
        } else if (ent instanceof ArmorStand && stands > plugin.getConfig().getInt("lag.max-armorstands-per-chunk", 40)) {
            e.setCancelled(true);
        }
    }

    // ==================== РЕДСТОУН-ЧАСЫ ====================
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onRedstone(BlockRedstoneEvent e) {
        if (!enabled()) return;
        long k = key(e.getBlock().getLocation());
        long now = System.currentTimeMillis();

        Long frozenUntil = frozenChunks.get(k);
        if (frozenUntil != null) {
            if (now < frozenUntil) {
                e.setNewCurrent(e.getOldCurrent());
                return;
            }
            frozenChunks.remove(k);
        }

        int[] state = redstone.computeIfAbsent(k, x -> new int[]{0, (int) (now / 1000)});
        int second = (int) (now / 1000);
        if (state[1] != second) {
            state[0] = 0;
            state[1] = second;
        }
        state[0]++;

        int max = plugin.getConfig().getInt("lag.max-redstone-per-chunk", 900);
        if (state[0] > max) {
            int freeze = plugin.getConfig().getInt("lag.redstone-freeze-seconds", 20);
            frozenChunks.put(k, now + freeze * 1000L);
            e.setNewCurrent(e.getOldCurrent());
            warn(e.getBlock().getLocation(), "редстоун-машина: " + state[0]
                    + " срабатываний/сек, чанк заморожен на " + freeze + "с");
        }
    }

    // ==================== ХОППЕРЫ ====================
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onHopper(InventoryMoveItemEvent e) {
        if (!enabled()) return;
        Location loc = e.getDestination().getLocation();
        if (loc == null) return;
        long k = key(loc);
        long now = System.currentTimeMillis();
        int second = (int) (now / 1000);

        int[] state = hoppers.computeIfAbsent(k, x -> new int[]{0, second});
        if (state[1] != second) {
            state[0] = 0;
            state[1] = second;
        }
        state[0]++;
        if (state[0] > plugin.getConfig().getInt("lag.max-hopper-moves-per-chunk", 400)) {
            e.setCancelled(true);
        }
    }

    // ==================== ВЗРЫВЫ ====================
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onExplode(EntityExplodeEvent e) {
        if (!enabled()) return;
        if (countExplosion(e.getLocation())) {
            e.setCancelled(true);
            warn(e.getLocation(), "цепная реакция взрывов остановлена");
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPrime(ExplosionPrimeEvent e) {
        if (!enabled()) return;
        if (e.getRadius() > 12.0f) {
            e.setRadius(4.0f);
            warn(e.getEntity().getLocation(), "аномальный радиус взрыва обрезан");
        }
    }

    private boolean countExplosion(Location loc) {
        long k = key(loc);
        int second = (int) (System.currentTimeMillis() / 1000);
        int[] state = explosions.computeIfAbsent(k, x -> new int[]{0, second});
        if (state[1] != second) {
            state[0] = 0;
            state[1] = second;
        }
        state[0]++;
        return state[0] > plugin.getConfig().getInt("lag.max-explosions-per-second-per-chunk", 12);
    }

    // ==================== ФИЗИКА (обновление-бомбы) ====================
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPhysics(BlockPhysicsEvent e) {
        if (!enabled()) return;
        Long frozenUntil = frozenChunks.get(key(e.getBlock().getLocation()));
        if (frozenUntil != null && System.currentTimeMillis() < frozenUntil) {
            e.setCancelled(true);
        }
    }

    // ==================== ПОРТАЛЫ / ЧАНКИ ====================
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPortalCreate(PortalCreateEvent e) {
        if (!enabled()) return;
        if (e.getBlocks().size() > 400) {
            e.setCancelled(true);
            warn(e.getWorld().getSpawnLocation(), "попытка создать гигантский портал");
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPlayerPortal(PlayerPortalEvent e) {
        if (!enabled()) return;
        PlayerData d = plugin.data(e.getPlayer());
        if (d == null) return;
        d.portals.addLast(System.currentTimeMillis());
        PlayerData.trim(d.portals, 60_000);
        if (d.portals.size() > plugin.getConfig().getInt("lag.max-portals-per-minute", 6)) {
            e.setCancelled(true);
            plugin.violations().flag(e.getPlayer(), CheckType.LAG_MACHINE,
                    "порталов за минуту=" + d.portals.size());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onChunkLoad(ChunkLoadEvent e) {
        if (!enabled() || !e.isNewChunk()) return;
        for (Player p : e.getWorld().getPlayers()) {
            if (p.getLocation().getChunk().getWorld() != e.getWorld()) continue;
            int dx = Math.abs(p.getLocation().getChunk().getX() - e.getChunk().getX());
            int dz = Math.abs(p.getLocation().getChunk().getZ() - e.getChunk().getZ());
            if (dx > 3 || dz > 3) continue;
            PlayerData d = plugin.data(p);
            if (d == null) continue;
            d.chunkLoads.addLast(System.currentTimeMillis());
            PlayerData.trim(d.chunkLoads, 1000);
            if (d.chunkLoads.size() > plugin.getConfig().getInt("lag.max-chunk-loads-per-second", 25)) {
                plugin.violations().flag(p, CheckType.LAG_MACHINE,
                        "генерация чанков/сек=" + d.chunkLoads.size());
                d.chunkLoads.clear();
            }
        }
    }

    // ==================== ПЕРИОДИЧЕСКАЯ ЧИСТКА ====================
    public void cleanupTask() {
        int seconds = plugin.getConfig().getInt("lag.item-cleanup-seconds", 300);
        if (seconds <= 0) return;
        Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            if (!enabled()) return;
            int removed = 0;
            for (org.bukkit.World w : Bukkit.getWorlds()) {
                for (Chunk c : w.getLoadedChunks()) {
                    int items = 0;
                    for (Entity ent : c.getEntities()) {
                        if (ent instanceof Item item) {
                            items++;
                            if (items > plugin.getConfig().getInt("lag.max-items-per-chunk", 96)
                                    || item.getTicksLived() > seconds * 20) {
                                Material m = item.getItemStack().getType();
                                if (m != Material.NETHERITE_INGOT && m != Material.ELYTRA
                                        && m != Material.TOTEM_OF_UNDYING && m != Material.NETHERITE_SCRAP) {
                                    item.remove();
                                    removed++;
                                }
                            }
                        }
                    }
                }
            }
            if (removed > 0) {
                plugin.getLogger().info("[AntiLag] Очищено предметов: " + removed);
            }
            redstone.clear();
            hoppers.clear();
            explosions.clear();
        }, 20L * 60, 20L * 60);
    }

    private void warn(Location loc, String msg) {
        plugin.getLogger().warning("[AntiLag] " + msg + " @ " + loc.getWorld().getName()
                + " " + loc.getBlockX() + " " + loc.getBlockY() + " " + loc.getBlockZ());
        for (Player staff : Bukkit.getOnlinePlayers()) {
            if (staff.hasPermission("bastion.alerts") && plugin.alertsEnabled(staff)) {
                staff.sendMessage("§8[§cBastion§8] §e" + msg);
            }
        }
    }
}
