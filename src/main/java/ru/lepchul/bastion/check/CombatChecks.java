package ru.lepchul.bastion.check;

import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.*;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityShootBowEvent;
import org.bukkit.event.entity.ProjectileLaunchEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;
import ru.lepchul.bastion.Bastion;
import ru.lepchul.bastion.data.PlayerData;
import ru.lepchul.bastion.util.Util;

public class CombatChecks implements Listener {

    private final Bastion plugin;
    private final Violations v;

    public CombatChecks(Bastion plugin) {
        this.plugin = plugin;
        this.v = plugin.violations();
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onAttack(EntityDamageByEntityEvent e) {
        if (!(e.getDamager() instanceof Player p)) return;
        if (plugin.isExempt(p) || p.getGameMode() == GameMode.CREATIVE) return;

        PlayerData d = plugin.data(p);
        if (d == null) return;

        Entity target = e.getEntity();
        long now = System.currentTimeMillis();
        Location eye = p.getEyeLocation();

        // ---------- Reach ----------
        BoundingBox box = target.getBoundingBox();
        double reach = distanceToBox(eye.toVector(), box);
        double maxReach = plugin.getConfig().getDouble("combat.max-reach-survival", 3.05)
                + plugin.getConfig().getDouble("combat.reach-lag-compensation", 0.6)
                + Math.min(safePing(p), 300) / 1000.0 * 1.5;
        if (reach > maxReach) {
            if (v.flag(p, CheckType.REACH, String.format("дистанция=%.2f лимит=%.2f", reach, maxReach))) {
                e.setCancelled(true);
                return;
            }
        }

        // ---------- KillAura: угол ----------
        double angle = Util.lookAngle(eye, target.getLocation().add(0, target.getHeight() / 2, 0));
        double maxAngle = plugin.getConfig().getDouble("combat.max-attack-angle", 85.0);
        if (angle > maxAngle) {
            if (v.flag(p, CheckType.KILLAURA, String.format("угол=%.1f° (атака вне поля зрения)", angle))) {
                e.setCancelled(true);
                return;
            }
        }

        // ---------- HitBox: луч должен попадать в цель ----------
        RayTraceResult ray = box.clone().expand(0.12).rayTrace(eye.toVector(), eye.getDirection(), maxReach + 1.0);
        if (ray == null && angle < maxAngle) {
            if (v.flag(p, CheckType.HITBOX, "луч взгляда не пересекает хитбокс цели")) {
                e.setCancelled(true);
                return;
            }
        }

        // ---------- Aimbot: мгновенный доворот перед ударом ----------
        if (d.lastYawDelta > 42.0f && now - d.lastAttack < 120) {
            v.flag(p, CheckType.AIMBOT, String.format("рывок камеры %.1f° перед ударом", d.lastYawDelta));
        }
        if (d.lastYawDelta > 0.01f && d.lastPitchDelta > 0.01f) {
            // GCD-эвристика: слишком «ровные» дельты = синтетический поворот
            float gcdY = d.lastYawDelta % 0.015f;
            if (gcdY < 0.0002f && d.lastYawDelta > 5.0f) {
                v.flag(p, CheckType.AIMBOT, "синтетический поворот камеры", 0.5);
            }
        }

        // ---------- MultiAura ----------
        if (now - d.recentTargetsWindow > 250) {
            d.recentTargets.clear();
            d.recentTargetsWindow = now;
        }
        d.recentTargets.add(target.getUniqueId());
        if (d.recentTargets.size() >= 3) {
            v.flag(p, CheckType.MULTIAURA, "целей за 250мс=" + d.recentTargets.size());
            d.recentTargets.clear();
        }

        // ---------- AutoClicker ----------
        d.clicks.addLast(now);
        PlayerData.trim(d.clicks, 1000);
        int cps = d.clicks.size();
        int maxCps = plugin.getConfig().getInt("combat.max-cps", 20);
        if (cps > maxCps) {
            v.flag(p, CheckType.AUTOCLICKER, "CPS=" + cps);
        }
        if (d.clicks.size() >= 8 && constantInterval(d)) {
            v.flag(p, CheckType.AUTOCLICKER, "идеально ровный интервал кликов", 0.5);
        }

        // ---------- Criticals ----------
        if (!Util.onGroundBlocks(p.getLocation()) && p.getFallDistance() <= 0.0f && !p.isInsideVehicle()
                && !Util.inLiquid(p) && !Util.onClimbable(p)
                && !Util.hasEffect(p, org.bukkit.potion.PotionEffectType.BLINDNESS)) {
            v.flag(p, CheckType.CRITICALS, "крит без реального падения", 0.5);
        }

        d.lastAttack = now;
        d.lastTarget = target.getUniqueId();

        if (target instanceof Player victim) {
            plugin.pvpTag().tag(victim, p);
            plugin.pvpTag().tag(p, victim);
        }
    }

    /** AutoTotem: скорость подстановки тотема во вторую руку. */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onTotemUse(org.bukkit.event.entity.EntityResurrectEvent e) {
        if (!(e.getEntity() instanceof Player p)) return;
        PlayerData d = plugin.data(p);
        if (d != null) d.lastTotemLoss = System.currentTimeMillis();
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onInventoryClick(InventoryClickEvent e) {
        if (!(e.getWhoClicked() instanceof Player p)) return;
        PlayerData d = plugin.data(p);
        if (d == null) return;

        ItemStack cur = e.getCurrentItem();
        boolean totem = (cur != null && cur.getType() == Material.TOTEM_OF_UNDYING)
                || (e.getCursor() != null && e.getCursor().getType() == Material.TOTEM_OF_UNDYING);
        if (totem && d.lastTotemLoss > 0) {
            long delta = System.currentTimeMillis() - d.lastTotemLoss;
            long min = plugin.getConfig().getLong("combat.min-totem-swap-ms", 120);
            if (delta < min) {
                v.flag(p, CheckType.AUTO_TOTEM, "подстановка тотема за " + delta + "мс");
            }
            d.lastTotemLoss = 0;
        }

        // InventoryMove: действия в инвентаре во время активного боя + движения
        if (d.inPvp() && d.inventoryOpen && System.currentTimeMillis() - d.inventoryOpenedAt < 60) {
            v.flag(p, CheckType.INVENTORY_MOVE, "клик через 0 тиков после открытия", 0.5);
        }
    }

    // ---------- лук ----------
    @EventHandler(ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent e) {
        if (e.getAction() != Action.RIGHT_CLICK_AIR && e.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        ItemStack item = e.getItem();
        if (item == null) return;
        PlayerData d = plugin.data(e.getPlayer());
        if (d == null) return;
        if (item.getType() == Material.BOW || item.getType() == Material.CROSSBOW) {
            d.bowDrawStart = System.currentTimeMillis();
        }
        if (item.getType() == Material.FLINT_AND_STEEL || item.getType() == Material.FIRE_CHARGE) {
            d.igniteUses.addLast(System.currentTimeMillis());
            PlayerData.trim(d.igniteUses, 1000);
            if (d.igniteUses.size() > 6) {
                v.flag(e.getPlayer(), CheckType.FLAMETHROWER, "поджогов/сек=" + d.igniteUses.size());
                e.setCancelled(true);
            }
        }
        if (item.getType() == Material.RESPAWN_ANCHOR) {
            long now = System.currentTimeMillis();
            if (now - d.lastAnchorUse < 250) {
                v.flag(e.getPlayer(), CheckType.ANCHOR, "интервал использования якоря " + (now - d.lastAnchorUse) + "мс");
                e.setCancelled(true);
            }
            d.lastAnchorUse = now;
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onShoot(EntityShootBowEvent e) {
        if (!(e.getEntity() instanceof Player p)) return;
        PlayerData d = plugin.data(p);
        if (d == null) return;
        long now = System.currentTimeMillis();

        long draw = d.bowDrawStart == 0 ? 9999 : now - d.bowDrawStart;
        long minDraw = plugin.getConfig().getLong("combat.min-bow-draw-ms", 120);
        if (draw < minDraw) {
            if (v.flag(p, CheckType.ARROW_SPAM, "натяжение " + draw + "мс")) e.setCancelled(true);
        }
        if (now - d.lastBowShot < 90) {
            if (v.flag(p, CheckType.ARROW_SPAM, "интервал выстрелов " + (now - d.lastBowShot) + "мс")) e.setCancelled(true);
        }
        d.lastBowShot = now;
        d.bowDrawStart = 0;
    }

    /** ArrowDodge: нечеловечески быстрая реакция на летящий снаряд. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onProjectile(ProjectileLaunchEvent e) {
        Projectile proj = e.getEntity();
        if (!(proj.getShooter() instanceof Player shooter)) return;
        Location origin = proj.getLocation();
        Vector dir = proj.getVelocity().clone().normalize();

        for (Player other : origin.getWorld().getPlayers()) {
            if (other.equals(shooter)) continue;
            if (other.getLocation().distanceSquared(origin) > 40 * 40) continue;
            PlayerData d = plugin.data(other);
            if (d == null) continue;
            Vector to = other.getLocation().toVector().subtract(origin.toVector());
            if (to.lengthSquared() < 1) continue;
            if (dir.dot(to.clone().normalize()) > 0.985) {
                d.lastProjectileNear = System.currentTimeMillis();
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onCrystalOrAnvil(org.bukkit.event.block.BlockPlaceEvent e) {
        Material m = e.getBlockPlaced().getType();
        Player p = e.getPlayer();
        PlayerData d = plugin.data(p);
        if (d == null) return;
        String n = m.name();
        if (n.contains("ANVIL")) {
            Location above = e.getBlock().getLocation();
            for (Player other : p.getWorld().getPlayers()) {
                if (other.equals(p)) continue;
                Location ol = other.getLocation();
                if (Math.abs(ol.getBlockX() - above.getBlockX()) <= 1
                        && Math.abs(ol.getBlockZ() - above.getBlockZ()) <= 1
                        && above.getBlockY() - ol.getBlockY() >= 2 && above.getBlockY() - ol.getBlockY() <= 12) {
                    v.flag(p, CheckType.AUTO_ANVIL, "наковальня над игроком " + other.getName());
                }
            }
        }
        if (n.contains("BED") && (p.getWorld().getEnvironment() != org.bukkit.World.Environment.NORMAL)) {
            long now = System.currentTimeMillis();
            if (now - d.lastPlace < 200) {
                v.flag(p, CheckType.AUTO_BED, "спам кроватями в " + p.getWorld().getEnvironment());
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onCrystalSpawn(org.bukkit.event.player.PlayerInteractEvent e) {
        ItemStack item = e.getItem();
        if (item == null || item.getType() != Material.END_CRYSTAL) return;
        Player p = e.getPlayer();
        PlayerData d = plugin.data(p);
        if (d == null) return;
        d.placements.addLast(System.currentTimeMillis());
        PlayerData.trim(d.placements, 1000);
        if (d.placements.size() > 8) {
            v.flag(p, CheckType.AUTO_CRYSTAL, "кристаллов/сек=" + d.placements.size());
            e.setCancelled(true);
        }
        if (e.getClickedBlock() != null) {
            double angle = Util.lookAngle(p.getEyeLocation(), e.getClickedBlock().getLocation().add(0.5, 0.5, 0.5));
            if (angle > 90) {
                v.flag(p, CheckType.AUTO_CRYSTAL, String.format("установка за спиной, угол=%.1f°", angle));
                e.setCancelled(true);
            }
        }
    }

    private boolean constantInterval(PlayerData d) {
        Long[] arr = d.clicks.toArray(new Long[0]);
        if (arr.length < 8) return false;
        double sum = 0, sumSq = 0;
        int n = 0;
        for (int i = 1; i < arr.length; i++) {
            double delta = arr[i] - arr[i - 1];
            sum += delta;
            sumSq += delta * delta;
            n++;
        }
        if (n < 6) return false;
        double mean = sum / n;
        double variance = sumSq / n - mean * mean;
        return Math.sqrt(Math.max(variance, 0)) < 3.0 && mean < 120;
    }

    private double distanceToBox(Vector eye, BoundingBox box) {
        double dx = Math.max(Math.max(box.getMinX() - eye.getX(), 0), eye.getX() - box.getMaxX());
        double dy = Math.max(Math.max(box.getMinY() - eye.getY(), 0), eye.getY() - box.getMaxY());
        double dz = Math.max(Math.max(box.getMinZ() - eye.getZ(), 0), eye.getZ() - box.getMaxZ());
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    private int safePing(Player p) {
        try {
            return p.getPing();
        } catch (Throwable t) {
            return 100;
        }
    }
}
