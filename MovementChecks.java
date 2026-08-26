package ru.lepchul.bastion.check;

import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Boat;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.*;
import org.bukkit.event.vehicle.VehicleMoveEvent;
import org.bukkit.potion.PotionEffectType;
import ru.lepchul.bastion.Bastion;
import ru.lepchul.bastion.data.PlayerData;
import ru.lepchul.bastion.util.Util;

public class MovementChecks implements Listener {

    private final Bastion plugin;
    private final Violations v;

    public MovementChecks(Bastion plugin) {
        this.plugin = plugin;
        this.v = plugin.violations();
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent e) {
        PlayerData d = plugin.data(e.getPlayer());
        if (d == null) return;
        d.exemptTicks = plugin.getConfig().getInt("general.exempt-ticks-after-teleport", 20);
        d.lastTeleport = System.currentTimeMillis();
        d.lastLocation = e.getTo() == null ? e.getFrom().clone() : e.getTo().clone();
        d.airTicks = 0;
        d.noDescendTicks = 0;
        d.speedSamples.clear();
    }

    @EventHandler
    public void onRespawn(PlayerRespawnEvent e) {
        PlayerData d = plugin.data(e.getPlayer());
        if (d != null) {
            d.exemptTicks = 40;
            d.lastLocation = e.getRespawnLocation().clone();
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent e) {
        Player p = e.getPlayer();
        if (plugin.isExempt(p)) return;
        Location from = e.getFrom();
        Location to = e.getTo();
        if (to == null) return;

        PlayerData d = plugin.data(p);
        if (d == null) return;

        // счётчик move-пакетов -> Timer
        long now = System.currentTimeMillis();
        if (now - d.moveSecondStart >= 1000L) {
            int limit = plugin.getConfig().getInt("movement.max-move-packets", 25);
            if (d.movePacketsThisSecond > limit && d.exemptTicks <= 0) {
                v.flag(p, CheckType.TIMER, "пакетов/сек=" + d.movePacketsThisSecond + " лимит=" + limit);
            }
            d.movePacketsThisSecond = 0;
            d.moveSecondStart = now;
        }
        if (from.getX() != to.getX() || from.getY() != to.getY() || from.getZ() != to.getZ()) {
            d.movePacketsThisSecond++;
        }

        // повороты — для Aimbot
        float yawDelta = Math.abs(Util.wrapAngle(to.getYaw() - from.getYaw()));
        float pitchDelta = Math.abs(to.getPitch() - from.getPitch());
        d.lastYawDelta = yawDelta;
        d.lastPitchDelta = pitchDelta;
        d.lastYaw = to.getYaw();
        d.lastPitch = to.getPitch();

        if (Math.abs(to.getPitch()) > 90.5f) {
            v.flag(p, CheckType.INVALID_PACKET, "pitch=" + to.getPitch());
        }
        if (!Double.isFinite(to.getX()) || !Double.isFinite(to.getY()) || !Double.isFinite(to.getZ())) {
            v.flag(p, CheckType.INVALID_PACKET, "NaN/Infinity в координатах");
            e.setCancelled(true);
            return;
        }

        if (d.exemptTicks > 0 || Util.creativeOrSpectator(p) || p.getAllowFlight()
                || p.isInsideVehicle() || p.isGliding() || p.isRiptiding()) {
            d.lastLocation = to.clone();
            d.wasOnGround = true;
            d.airTicks = 0;
            return;
        }

        double dx = to.getX() - from.getX();
        double dy = to.getY() - from.getY();
        double dz = to.getZ() - from.getZ();
        double horiz = Math.sqrt(dx * dx + dz * dz);

        boolean ground = Util.onGround(to);
        boolean liquid = Util.inLiquid(p);
        boolean climb = Util.onClimbable(p);
        boolean web = Util.inWeb(p);

        // ---------- ClickTP / Blink ----------
        double maxSingle = plugin.getConfig().getDouble("movement.max-single-move", 8.0);
        if (horiz > maxSingle && System.currentTimeMillis() - d.lastTeleport > 1000) {
            v.flag(p, CheckType.CLICK_TP, String.format("рывок=%.2f блоков за пакет", horiz));
            e.setTo(from.clone());
            return;
        }

        // ---------- Phase / NoClip ----------
        if (Util.insideSolid(to) && !Util.insideSolid(from)) {
            v.flag(p, CheckType.PHASE, "вошёл в блок " + to.getBlock().getType());
            e.setTo(from.clone());
            return;
        }

        // ---------- Speed (по среднему за окно) ----------
        checkSpeed(p, d, horiz, ground, liquid, climb, web);

        // ---------- воздух / полёт ----------
        if (ground) {
            d.airTicks = 0;
            d.noDescendTicks = 0;
            d.jumpsSinceGround = 0;
            d.groundTicks++;
            d.lastGroundY = to.getY();
            d.jumpStartX = to.getX();
            d.jumpStartZ = to.getZ();
        } else if (!liquid && !climb && !web) {
            d.airTicks++;
            d.groundTicks = 0;

            if (dy >= -0.0001) d.noDescendTicks++;
            else d.noDescendTicks = 0;

            int flightTicks = plugin.getConfig().getInt("movement.flight-air-ticks", 22);
            if (d.noDescendTicks > flightTicks && !Util.hasEffect(p, PotionEffectType.LEVITATION)
                    && !Util.hasEffect(p, PotionEffectType.SLOW_FALLING)) {
                v.flag(p, CheckType.FLIGHT, "тиков без снижения=" + d.noDescendTicks + " dy=" + fmt(dy));
                d.noDescendTicks = 0;
            }

            // зависание на месте в воздухе
            if (d.airTicks > 10 && Math.abs(dy) < 1.0E-5 && Math.abs(d.lastDeltaY) < 1.0E-5
                    && !Util.hasEffect(p, PotionEffectType.LEVITATION)) {
                v.flag(p, CheckType.FLIGHT, "зависание, dy=0 " + d.airTicks + " тиков");
            }

            // ---------- InfinityJump ----------
            if (dy > 0.40 && d.lastDeltaY <= 0.0 && d.airTicks > 3
                    && !Util.hasEffect(p, PotionEffectType.JUMP)) {
                d.jumpsSinceGround++;
                if (d.jumpsSinceGround >= 1) {
                    v.flag(p, CheckType.INFINITY_JUMP, "прыжок #" + (d.jumpsSinceGround + 1) + " без земли");
                }
            }

            // ---------- HighJump ----------
            double maxJump = plugin.getConfig().getDouble("movement.max-jump-height", 1.30);
            int jumpBoost = Util.effect(p, PotionEffectType.JUMP);
            double allowed = maxJump + jumpBoost * 0.75;
            double height = to.getY() - d.lastGroundY;
            if (height > allowed && dy > 0 && !climb) {
                v.flag(p, CheckType.HIGH_JUMP, String.format("высота=%.2f лимит=%.2f", height, allowed));
            }

            // ---------- LongJump ----------
            double maxDist = plugin.getConfig().getDouble("movement.max-jump-distance", 5.2);
            double jumped = Math.hypot(to.getX() - d.jumpStartX, to.getZ() - d.jumpStartZ);
            if (jumped > maxDist + Util.effect(p, PotionEffectType.SPEED) * 1.2 && d.airTicks > 6) {
                v.flag(p, CheckType.LONG_JUMP, String.format("дальность=%.2f", jumped));
            }

            // ---------- Spider ----------
            if (dy > 0.05 && d.airTicks > 2 && againstWall(to) && !climb
                    && !Util.hasEffect(p, PotionEffectType.LEVITATION)) {
                v.flag(p, CheckType.SPIDER, "подъём вдоль стены dy=" + fmt(dy));
            }
        }

        // ---------- Step ----------
        if (ground && d.wasOnGround && dy > 0.63 && !climb && !liquid) {
            v.flag(p, CheckType.STEP, String.format("шаг вверх=%.2f", dy));
        }

        // ---------- Jesus ----------
        if (Util.nearLiquidSurface(p) && !p.isSwimming() && Math.abs(dy) < 0.005 && horiz > 0.10) {
            Material below = to.clone().add(0, -0.2, 0).getBlock().getType();
            if (below == Material.WATER || below == Material.LAVA) {
                v.flag(p, CheckType.JESUS, "стоит на поверхности " + below);
            }
        }

        // ---------- AntiVoid ----------
        double minY = p.getWorld().getMinHeight();
        if (to.getY() < minY - 2 && dy >= 0) {
            v.flag(p, CheckType.ANTI_VOID, "y=" + fmt(to.getY()) + " dy=" + fmt(dy));
        }

        // ---------- GuiMove ----------
        if (d.inventoryOpen && (horiz > 0.22 || p.isSprinting())) {
            v.flag(p, CheckType.GUI_MOVE, String.format("движение %.2f с открытым GUI", horiz));
        }

        // ---------- NoSlow ----------
        if (isUsingItem(p) && horiz > 0.135 && ground && !p.isInsideVehicle()
                && Util.effect(p, PotionEffectType.SPEED) == 0) {
            v.flag(p, CheckType.NO_SLOW, String.format("скорость %.3f при использовании предмета", horiz));
        }

        // ---------- Velocity / ReverseStep ----------
        if (d.velocityPending) {
            d.velocityTicks++;
            if (d.velocityTicks == 2) {
                double expected = Math.hypot(d.expectedVelX, d.expectedVelZ);
                if (expected > 0.08) {
                    double ratio = horiz / expected;
                    if (ratio < 0.25) {
                        v.flag(p, CheckType.VELOCITY, String.format("принято %.0f%% отбрасывания", ratio * 100));
                    }
                    double dot = dx * d.expectedVelX + dz * d.expectedVelZ;
                    if (dot < -0.02) {
                        v.flag(p, CheckType.REVERSE_STEP, "движение против отбрасывания");
                    }
                }
                d.velocityPending = false;
                d.velocityTicks = 0;
            }
        }

        // ---------- NoFall ----------
        if (dy < 0) d.fallDistance += -dy;
        if (ground && !d.wasOnGround) {
            if (d.fallDistance > 3.5 && p.getFallDistance() < 0.5 && !liquid && !web && !climb) {
                boolean feather = Util.hasFeatherBoots(p, plugin.featherKey(), plugin.featherKeyword());
                if (!feather && !Util.hasEffect(p, PotionEffectType.SLOW_FALLING)
                        && !isSoftLanding(to)) {
                    v.flag(p, CheckType.NOFALL, String.format("падение %.1f, клиент прислал 0", d.fallDistance));
                }
            }
            d.fallDistance = 0;
        }
        if (liquid || web || climb) d.fallDistance = 0;

        d.lastDeltaY = dy;
        d.wasOnGround = ground;
        d.lastLocation = to.clone();
    }

    private void checkSpeed(Player p, PlayerData d, double horiz, boolean ground,
                            boolean liquid, boolean climb, boolean web) {
        int window = plugin.getConfig().getInt("movement.speed-average-ticks", 20);
        d.speedSamples.addLast(horiz);
        while (d.speedSamples.size() > window) d.speedSamples.pollFirst();
        if (d.speedSamples.size() < window) return;
        if (liquid || climb || web) return;

        double sum = 0;
        for (double s : d.speedSamples) sum += s;
        double avg = sum / d.speedSamples.size();

        double limit = plugin.getConfig().getDouble("movement.max-sprint-speed", 0.45);
        limit += Util.effect(p, PotionEffectType.SPEED) * 0.062;
        limit += Util.effect(p, PotionEffectType.DOLPHINS_GRACE) * 0.10;
        if (Util.effect(p, PotionEffectType.SLOW) > 0) limit *= 0.7;
        if (onIce(p.getLocation())) limit += 0.28;
        if (p.getLocation().getBlock().getType() == Material.SOUL_SAND) limit += 0.15;
        if (!ground) limit += 0.05;

        if (avg > limit) {
            v.flag(p, CheckType.SPEED, String.format("средняя=%.3f лимит=%.3f", avg, limit));
            d.speedSamples.clear();
        }
    }

    private boolean onIce(Location l) {
        Material m = l.clone().add(0, -0.2, 0).getBlock().getType();
        return m == Material.ICE || m == Material.PACKED_ICE || m == Material.BLUE_ICE || m == Material.FROSTED_ICE;
    }

    private boolean isSoftLanding(Location l) {
        Material m = l.clone().add(0, -0.2, 0).getBlock().getType();
        return m == Material.HAY_BLOCK || m == Material.SLIME_BLOCK || m == Material.HONEY_BLOCK
                || m == Material.COBWEB || m == Material.SCAFFOLDING || m == Material.POWDER_SNOW
                || m == Material.WATER || m == Material.BUBBLE_COLUMN || m.name().contains("BED");
    }

    private boolean againstWall(Location l) {
        double[][] dirs = {{0.35, 0}, {-0.35, 0}, {0, 0.35}, {0, -0.35}};
        for (double[] dd : dirs) {
            if (!l.clone().add(dd[0], 0.9, dd[1]).getBlock().isPassable()) return true;
        }
        return false;
    }

    private boolean isUsingItem(Player p) {
        try {
            return p.isHandRaised() || p.isBlocking();
        } catch (Throwable t) {
            return p.isBlocking();
        }
    }

    // ---------- отбрасывание ----------
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDamage(EntityDamageEvent e) {
        if (!(e.getEntity() instanceof Player p)) return;
        PlayerData d = plugin.data(p);
        if (d == null) return;
        org.bukkit.util.Vector vel = p.getVelocity();
        d.expectedVelX = vel.getX();
        d.expectedVelZ = vel.getZ();
        d.velocityPending = true;
        d.velocityTicks = 0;
        d.velocityAppliedAt = System.currentTimeMillis();
    }

    // ---------- элитра ----------
    @EventHandler(ignoreCancelled = true)
    public void onToggleGlide(org.bukkit.event.entity.EntityToggleGlideEvent e) {
        if (!(e.getEntity() instanceof Player p)) return;
        if (!e.isGliding()) return;
        org.bukkit.inventory.ItemStack chest = p.getInventory().getChestplate();
        boolean hasElytra = chest != null && chest.getType() == Material.ELYTRA;
        if (!hasElytra && p.getGameMode() != GameMode.CREATIVE && p.getGameMode() != GameMode.SPECTATOR) {
            v.flag(p, CheckType.ELYTRA_FLY, "планирование без элитр");
            e.setCancelled(true);
        }
    }

    // ---------- транспорт ----------
    @EventHandler(ignoreCancelled = true)
    public void onVehicleMove(VehicleMoveEvent e) {
        Entity passenger = e.getVehicle().getPassengers().isEmpty() ? null : e.getVehicle().getPassengers().get(0);
        if (!(passenger instanceof Player p)) return;
        if (plugin.isExempt(p)) return;

        double dy = e.getTo().getY() - e.getFrom().getY();
        double dxz = Util.horizontal(e.getFrom(), e.getTo());

        if (e.getVehicle() instanceof Boat) {
            Material below = e.getTo().clone().add(0, -0.3, 0).getBlock().getType();
            boolean support = below != Material.AIR && below != Material.CAVE_AIR;
            if (dy > 0.12 && !support) {
                v.flag(p, CheckType.BOAT_FLY, String.format("лодка вверх dy=%.3f без опоры", dy));
            }
            if (dxz > 1.4) {
                v.flag(p, CheckType.ENTITY_SPEED, String.format("лодка %.2f блоков/тик", dxz));
            }
        } else if (dxz > 1.6) {
            v.flag(p, CheckType.ENTITY_SPEED, String.format("транспорт %.2f блоков/тик", dxz));
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onSteer(PlayerInteractEntityEvent e) {
        Player p = e.getPlayer();
        Entity target = e.getRightClicked();
        if (target.getWorld() != p.getWorld()) return;
        double dist = p.getLocation().distance(target.getLocation());
        if (p.isInsideVehicle() && p.getVehicle() != null && !p.getVehicle().equals(target) && dist > 6) {
            v.flag(p, CheckType.ENTITY_CONTROL, "взаимодействие с чужим мобом на " + fmt(dist));
            e.setCancelled(true);
        } else if (dist > 6.5) {
            v.flag(p, CheckType.MOUNT_BYPASS, String.format("взаимодействие с %.2f блоков", dist));
            e.setCancelled(true);
        }
    }

    private static String fmt(double d) {
        return String.format("%.3f", d);
    }
}
