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
import org.bukkit.event.entity.EntityToggleGlideEvent;
import org.bukkit.event.player.*;
import org.bukkit.event.vehicle.VehicleMoveEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.Vector;
import ru.lepchul.bastion.Bastion;
import ru.lepchul.bastion.data.PlayerData;
import ru.lepchul.bastion.util.Util;

/**
 * Движковая часть античита.
 *
 * Вертикаль проверяется не «дельтой», а предсказанием ванильной физики:
 * прыжок задаёт motionY = 0.42 (+0.1 за уровень прыгучести), дальше каждый тик
 * motionY = (motionY - гравитация) * 0.98. Любое превышение предсказания —
 * это Fly, InfinityJump, HighJump или Step в воздухе, и игрока откатывает назад.
 */
public class MovementChecks implements Listener {

    private final Bastion plugin;
    private final Violations v;

    public MovementChecks(Bastion plugin) {
        this.plugin = plugin;
        this.v = plugin.violations();
    }

    private double cfg(String path, double def) {
        return plugin.getConfig().getDouble("movement." + path, def);
    }

    // ===================================================================
    //  ОТКАТ
    // ===================================================================
    private void setback(Player p, PlayerData d, PlayerMoveEvent e) {
        if (!plugin.getConfig().getBoolean("movement.setback", true)) return;

        Location safe = d.safeLocation != null ? d.safeLocation.clone() : e.getFrom().clone();
        if (safe.getWorld() != p.getWorld()) safe = e.getFrom().clone();
        Location to = e.getTo();
        if (to != null) {
            safe.setYaw(to.getYaw());
            safe.setPitch(to.getPitch());
        }
        e.setTo(safe);
        p.setVelocity(new Vector(0, 0, 0));
        p.setFallDistance(0f);

        d.graceTicks = 8;
        d.airTicks = 0;
        d.predictedY = 0;
        d.hoverTicks = 0;
        d.jumpLaunch = false;
        d.stepAccum = 0;
        d.jesusTicks = 0;
        d.speedSamples.clear();
        d.fallDistance = 0;
        d.setbacks++;
        d.lastSetback = System.currentTimeMillis();
    }

    // ===================================================================
    //  ГРЕЙС
    // ===================================================================
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent e) {
        PlayerData d = plugin.data(e.getPlayer());
        if (d == null) return;
        Location to = e.getTo() == null ? e.getFrom() : e.getTo();
        d.graceTicks = Math.max(d.graceTicks, 25);
        d.exemptTicks = plugin.getConfig().getInt("general.exempt-ticks-after-teleport", 20);
        d.lastTeleport = System.currentTimeMillis();
        d.lastLocation = to.clone();
        d.safeLocation = to.clone();
        reset(d);
    }

    @EventHandler
    public void onRespawn(PlayerRespawnEvent e) {
        PlayerData d = plugin.data(e.getPlayer());
        if (d == null) return;
        d.graceTicks = 40;
        d.exemptTicks = 40;
        d.lastLocation = e.getRespawnLocation().clone();
        d.safeLocation = e.getRespawnLocation().clone();
        reset(d);
    }

    /** Любая серверная скорость (кнокбек, взрыв, удочка, поршень) — короткий грейс. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onVelocity(PlayerVelocityEvent e) {
        PlayerData d = plugin.data(e.getPlayer());
        if (d == null) return;
        d.graceTicks = Math.max(d.graceTicks, 20);
        d.predictedY = e.getVelocity().getY();
        d.expectedVelX = e.getVelocity().getX();
        d.expectedVelZ = e.getVelocity().getZ();
        d.velocityPending = true;
        d.velocityTicks = 0;
        d.velocityAppliedAt = System.currentTimeMillis();
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDamage(EntityDamageEvent e) {
        if (!(e.getEntity() instanceof Player p)) return;
        PlayerData d = plugin.data(p);
        if (d != null) d.graceTicks = Math.max(d.graceTicks, 20);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onRiptide(PlayerRiptideEvent e) {
        PlayerData d = plugin.data(e.getPlayer());
        if (d != null) d.graceTicks = Math.max(d.graceTicks, 45);
    }

    private void reset(PlayerData d) {
        d.airTicks = 0;
        d.predictedY = 0;
        d.hoverTicks = 0;
        d.jumpLaunch = false;
        d.stepAccum = 0;
        d.stepTicks = 0;
        d.jesusTicks = 0;
        d.voidTicks = 0;
        d.glideTicks = 0;
        d.glideAscend = 0;
        d.fallDistance = 0;
        d.speedSamples.clear();
    }

    // ===================================================================
    //  ОСНОВНАЯ ПРОВЕРКА
    // ===================================================================
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent e) {
        Player p = e.getPlayer();
        Location from = e.getFrom();
        Location to = e.getTo();
        if (to == null) return;

        PlayerData d = plugin.data(p);
        if (d == null) return;

        countMovePackets(p, d, from, to);

        // некорректные значения — сразу назад
        if (!Double.isFinite(to.getX()) || !Double.isFinite(to.getY()) || !Double.isFinite(to.getZ())
                || Math.abs(to.getX()) > 3.0E7 || Math.abs(to.getZ()) > 3.0E7) {
            v.flag(p, CheckType.INVALID_PACKET, "битые координаты");
            setback(p, d, e);
            return;
        }
        if (Math.abs(to.getPitch()) > 90.5f) {
            v.flag(p, CheckType.INVALID_PACKET, "pitch=" + to.getPitch());
        }

        trackRotation(d, from, to);

        if (plugin.isExempt(p)) {
            d.safeLocation = to.clone();
            return;
        }

        double dx = to.getX() - from.getX();
        double dy = to.getY() - from.getY();
        double dz = to.getZ() - from.getZ();
        double horiz = Math.sqrt(dx * dx + dz * dz);

        // ---------- полные исключения ----------
        if (Util.creativeOrSpectator(p) || p.getAllowFlight() || p.isInsideVehicle()
                || p.isDead() || p.isSleeping() || p.isRiptiding()) {
            d.safeLocation = to.clone();
            d.lastLocation = to.clone();
            reset(d);
            return;
        }

        // ---------- состояние окружения ----------
        boolean ground = Util.onGroundBlocks(to);
        if (!ground && d.airTicks >= 3) ground = Util.onGroundEntity(p);

        boolean liquid = Util.inLiquid(p) || p.isInWater() || p.isSwimming();
        boolean climb = Util.onClimbable(p);
        boolean web = Util.inWeb(p);
        boolean glide = p.isGliding();
        boolean levit = Util.hasEffect(p, PotionEffectType.LEVITATION);
        boolean bouncy = Util.bouncyNearby(to);

        if (Util.onIce(to)) d.iceTicks = 25;
        else if (d.iceTicks > 0) d.iceTicks--;

        if (levit || bouncy) d.graceTicks = Math.max(d.graceTicks, 12);

        // ---------- ClickTP / Blink ----------
        double maxSingle = cfg("max-single-move", 8.0);
        if (horiz > maxSingle && System.currentTimeMillis() - d.lastTeleport > 1500) {
            v.flag(p, CheckType.CLICK_TP, String.format("рывок %.2f блоков за пакет", horiz));
            setback(p, d, e);
            return;
        }

        // ---------- Phase ----------
        if (Util.insideSolid(to) && !Util.insideSolid(from)) {
            v.flag(p, CheckType.PHASE, "вход в блок " + to.getBlock().getType());
            setback(p, d, e);
            return;
        }

        // ---------- элитра ----------
        if (glide) {
            if (checkElytra(p, d, e, dy, horiz, ground)) return;
            finish(d, to, dy, ground, false);
            return;
        } else {
            d.glideTicks = 0;
            d.glideAscend = 0;
        }

        // ---------- грейс ----------
        if (d.graceTicks > 0 || d.exemptTicks > 0) {
            if (d.graceTicks > 0) d.graceTicks--;
            d.predictedY = dy;
            finish(d, to, dy, ground, ground);
            return;
        }

        // ======================= ВЕРТИКАЛЬ =======================
        boolean skipVertical = liquid || climb || web || levit || bouncy;

        if (ground) {
            d.airTicks = 0;
            d.hoverTicks = 0;
            d.predictedY = 0;
            d.jumpLaunch = false;
        } else if (!skipVertical) {
            double tolerance = cfg("vertical-tolerance", 0.06);
            double gravity = Util.hasEffect(p, PotionEffectType.SLOW_FALLING) ? 0.01 : 0.08;

            if (d.wasOnGround) {
                // первый тик в воздухе
                double jumpPower = 0.42 + Util.effect(p, PotionEffectType.JUMP) * 0.1;
                if (dy > 0.10) {
                    d.jumpLaunch = true;
                    d.launchX = from.getX();
                    d.launchY = from.getY();
                    d.launchZ = from.getZ();
                    d.predictedY = jumpPower;
                    if (dy > jumpPower + tolerance) {
                        v.flag(p, CheckType.HIGH_JUMP,
                                String.format("старт прыжка %.3f, максимум %.3f", dy, jumpPower));
                        setback(p, d, e);
                        return;
                    }
                } else {
                    d.jumpLaunch = false;
                    d.predictedY = dy;
                }
            } else {
                d.predictedY = (d.predictedY - gravity) * 0.98;
                if (dy > d.predictedY + tolerance) {
                    CheckType type = dy > 0.20 && d.predictedY < 0.05
                            ? CheckType.INFINITY_JUMP
                            : CheckType.FLIGHT;
                    v.flag(p, type, String.format("dy=%.3f, ожидалось %.3f, тиков в воздухе=%d",
                            dy, d.predictedY, d.airTicks));
                    setback(p, d, e);
                    return;
                }
                // ресинк вниз: столкновения замедляют падение легально
                d.predictedY = dy;
            }

            // зависание
            if (Math.abs(dy) < 1.0E-4) d.hoverTicks++;
            else d.hoverTicks = 0;
            if (d.hoverTicks > (int) cfg("max-hover-ticks", 6)) {
                v.flag(p, CheckType.FLIGHT, "зависание в воздухе " + d.hoverTicks + " тиков");
                setback(p, d, e);
                return;
            }

            d.airTicks++;
        } else {
            d.airTicks++;
            d.predictedY = dy;
            d.hoverTicks = 0;
        }

        // ---------- Step ----------
        if (checkStep(p, d, e, dy, ground, climb, liquid, web)) return;

        // ---------- LongJump ----------
        if (checkLongJump(p, d, e, to, liquid, climb, web)) return;

        // ---------- Speed ----------
        if (checkSpeed(p, d, e, horiz, ground, liquid, climb, web)) return;

        // ---------- Jesus ----------
        if (checkJesus(p, d, e, dy, horiz, ground)) return;

        // ---------- Spider ----------
        if (!ground && !climb && !liquid && !web && dy > 0.05 && d.airTicks > 2 && againstWall(to)) {
            v.flag(p, CheckType.SPIDER, String.format("подъём вдоль стены dy=%.3f", dy));
            setback(p, d, e);
            return;
        }

        // ---------- AntiVoid ----------
        double minY = p.getWorld().getMinHeight();
        if (to.getY() < minY - 2) {
            if (dy >= -0.05) d.voidTicks++;
            else d.voidTicks = 0;
            if (d.voidTicks > 5) {
                v.flag(p, CheckType.ANTI_VOID, String.format("y=%.1f без падения", to.getY()));
                d.voidTicks = 0;
            }
        } else {
            d.voidTicks = 0;
        }

        // ---------- GuiMove ----------
        if (d.inventoryOpen && (horiz > 0.22 || p.isSprinting())) {
            v.flag(p, CheckType.GUI_MOVE, String.format("движение %.2f с открытым GUI", horiz));
        }

        // ---------- NoSlow ----------
        if (isUsingItem(p) && horiz > 0.135 && ground && Util.effect(p, PotionEffectType.SPEED) == 0
                && d.iceTicks == 0) {
            v.flag(p, CheckType.NO_SLOW, String.format("скорость %.3f при использовании предмета", horiz));
        }

        // ---------- Velocity / ReverseStep ----------
        checkVelocity(p, d, dx, dz, horiz);

        // ---------- NoFall ----------
        checkNoFall(p, d, dy, ground, liquid, web, climb, to);

        finish(d, to, dy, ground, ground);
    }

    // ===================================================================
    private void finish(PlayerData d, Location to, double dy, boolean ground, boolean safe) {
        d.lastDeltaY = dy;
        d.wasOnGround = ground;
        d.lastLocation = to.clone();
        if (ground) {
            d.groundTicks++;
            d.lastGroundY = to.getY();
        } else {
            d.groundTicks = 0;
        }
        if (safe && !Util.insideSolid(to)) {
            d.safeLocation = to.clone();
        }
    }

    // ===================================================================
    //  STEP — подъём на блок без прыжка
    // ===================================================================
    private boolean checkStep(Player p, PlayerData d, PlayerMoveEvent e,
                              double dy, boolean ground, boolean climb, boolean liquid, boolean web) {
        double maxStep = cfg("max-step-height", 0.63);

        if (!ground || climb || liquid || web) {
            d.stepAccum = 0;
            d.stepTicks = 0;
            return false;
        }
        if (!d.wasOnGround) {
            d.stepAccum = 0;
            d.stepTicks = 0;
            return false;
        }

        // одиночный шаг больше ванильных 0.6
        if (dy > maxStep) {
            v.flag(p, CheckType.STEP, String.format("шаг вверх %.3f (максимум %.2f)", dy, maxStep));
            setback(p, d, e);
            return true;
        }

        // подъём по половинкам за несколько тиков (обход одиночной проверки)
        if (dy > 0.001) {
            d.stepAccum += dy;
            d.stepTicks++;
            if (d.stepAccum > maxStep && d.stepTicks <= 4) {
                v.flag(p, CheckType.STEP, String.format("подъём %.2f за %d тика без прыжка",
                        d.stepAccum, d.stepTicks));
                setback(p, d, e);
                return true;
            }
            if (d.stepTicks > 4) {
                d.stepAccum = 0;
                d.stepTicks = 0;
            }
        } else {
            d.stepAccum = 0;
            d.stepTicks = 0;
        }
        return false;
    }

    // ===================================================================
    //  LONG JUMP — только для настоящего прыжка, не для падения
    // ===================================================================
    private boolean checkLongJump(Player p, PlayerData d, PlayerMoveEvent e, Location to,
                                  boolean liquid, boolean climb, boolean web) {
        if (!d.jumpLaunch || liquid || climb || web) return false;

        // упал ниже точки старта — это уже падение, а не прыжок
        if (to.getY() < d.launchY - 0.8 || d.airTicks > 16) {
            d.jumpLaunch = false;
            return false;
        }

        double limit = cfg("max-jump-distance", 5.2)
                + Util.effect(p, PotionEffectType.SPEED) * 1.1
                + (d.iceTicks > 0 ? 3.0 : 0);
        double travelled = Math.hypot(to.getX() - d.launchX, to.getZ() - d.launchZ);
        if (travelled > limit) {
            v.flag(p, CheckType.LONG_JUMP, String.format("прыжок на %.2f (лимит %.2f)", travelled, limit));
            setback(p, d, e);
            return true;
        }
        return false;
    }

    // ===================================================================
    //  SPEED
    // ===================================================================
    private boolean checkSpeed(Player p, PlayerData d, PlayerMoveEvent e, double horiz,
                               boolean ground, boolean liquid, boolean climb, boolean web) {
        if (liquid || climb || web) {
            d.speedSamples.clear();
            return false;
        }

        double limit = cfg("max-average-speed", 0.40)
                + Util.effect(p, PotionEffectType.SPEED) * 0.062
                + (d.iceTicks > 0 ? 0.28 : 0)
                + (Util.effect(p, PotionEffectType.JUMP) > 0 ? 0.05 : 0);
        if (Util.effect(p, PotionEffectType.SLOW) > 0) limit *= 0.65;

        double hardCap = cfg("hard-speed-cap", 0.80) + (d.iceTicks > 0 ? 0.35 : 0)
                + Util.effect(p, PotionEffectType.SPEED) * 0.10;

        // мгновенный предел — ловит рывки
        if (horiz > hardCap) {
            v.flag(p, CheckType.SPEED, String.format("рывок %.3f блоков за тик (лимит %.2f)", horiz, hardCap));
            setback(p, d, e);
            return true;
        }

        int window = (int) cfg("speed-average-ticks", 8);
        d.speedSamples.addLast(horiz);
        while (d.speedSamples.size() > window) d.speedSamples.pollFirst();
        if (d.speedSamples.size() < window) return false;

        double sum = 0;
        for (double s : d.speedSamples) sum += s;
        double avg = sum / d.speedSamples.size();

        if (avg > limit) {
            v.flag(p, CheckType.SPEED, String.format("средняя %.3f (лимит %.3f)", avg, limit));
            setback(p, d, e);
            return true;
        }
        return false;
    }

    // ===================================================================
    //  JESUS — только реальная ходьба по поверхности
    // ===================================================================
    private boolean checkJesus(Player p, PlayerData d, PlayerMoveEvent e,
                               double dy, double horiz, boolean ground) {
        if (ground || !Util.standingOnLiquidSurface(p) || Math.abs(dy) > 0.02 || horiz < 0.08) {
            d.jesusTicks = 0;
            return false;
        }
        d.jesusTicks++;
        if (d.jesusTicks > (int) cfg("jesus-ticks", 4)) {
            v.flag(p, CheckType.JESUS, "ходьба по поверхности жидкости " + d.jesusTicks + " тиков");
            setback(p, d, e);
            return true;
        }
        return false;
    }

    // ===================================================================
    //  ЭЛИТРА
    // ===================================================================
    private boolean checkElytra(Player p, PlayerData d, PlayerMoveEvent e,
                                double dy, double horiz, boolean ground) {
        ItemStack chest = p.getInventory().getChestplate();
        if (chest == null || chest.getType() != Material.ELYTRA) {
            v.flag(p, CheckType.ELYTRA_FLY, "планирование без элитр");
            setback(p, d, e);
            return true;
        }

        d.glideTicks++;

        if (ground && d.glideTicks > 6) {
            v.flag(p, CheckType.ELYTRA_FLY, "планирование стоя на земле");
            setback(p, d, e);
            return true;
        }

        boolean boosted = !d.fireworks.isEmpty()
                && System.currentTimeMillis() - d.fireworks.peekLast() < 3500;

        if (dy > 0) d.glideAscend += dy;
        else d.glideAscend = Math.max(0, d.glideAscend + dy * 0.5);

        double maxAscend = cfg("elytra-max-ascend", 3.5);
        if (!boosted && d.glideAscend > maxAscend) {
            v.flag(p, CheckType.ELYTRA_FLY,
                    String.format("набор высоты %.1f без фейерверка", d.glideAscend));
            setback(p, d, e);
            return true;
        }

        double maxSpeed = cfg("elytra-max-speed", 3.5);
        if (horiz > maxSpeed) {
            v.flag(p, CheckType.ELYTRA_FLY, String.format("скорость планирования %.2f", horiz));
            setback(p, d, e);
            return true;
        }
        return false;
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onToggleGlide(EntityToggleGlideEvent e) {
        if (!(e.getEntity() instanceof Player p)) return;
        if (!e.isGliding()) return;
        if (Util.creativeOrSpectator(p)) return;
        ItemStack chest = p.getInventory().getChestplate();
        if (chest == null || chest.getType() != Material.ELYTRA) {
            v.flag(p, CheckType.ELYTRA_FLY, "включение планирования без элитр");
            e.setCancelled(true);
        }
    }

    // ===================================================================
    //  VELOCITY / NOFALL / ПРОЧЕЕ
    // ===================================================================
    private void checkVelocity(Player p, PlayerData d, double dx, double dz, double horiz) {
        if (!d.velocityPending) return;
        d.velocityTicks++;
        if (d.velocityTicks != 2) return;

        double expected = Math.hypot(d.expectedVelX, d.expectedVelZ);
        if (expected > 0.10) {
            double ratio = horiz / expected;
            if (ratio < 0.25) {
                v.flag(p, CheckType.VELOCITY, String.format("принято %.0f%% отбрасывания", ratio * 100));
            }
            if (dx * d.expectedVelX + dz * d.expectedVelZ < -0.02) {
                v.flag(p, CheckType.REVERSE_STEP, "движение против отбрасывания");
            }
        }
        d.velocityPending = false;
        d.velocityTicks = 0;
    }

    private void checkNoFall(Player p, PlayerData d, double dy, boolean ground,
                             boolean liquid, boolean web, boolean climb, Location to) {
        if (dy < 0) d.fallDistance += -dy;
        if (liquid || web || climb) {
            d.fallDistance = 0;
            return;
        }
        if (ground && !d.wasOnGround) {
            if (d.fallDistance > 3.5 && p.getFallDistance() < 0.5 && !isSoftLanding(to)
                    && !Util.hasEffect(p, PotionEffectType.SLOW_FALLING)
                    && !Util.hasFeatherBoots(p, plugin.featherKey(), plugin.featherKeyword())) {
                v.flag(p, CheckType.NOFALL, String.format("падение %.1f, клиент прислал 0", d.fallDistance));
            }
            d.fallDistance = 0;
        }
    }

    private void countMovePackets(Player p, PlayerData d, Location from, Location to) {
        long now = System.currentTimeMillis();
        if (now - d.moveSecondStart >= 1000L) {
            int limit = plugin.getConfig().getInt("movement.max-move-packets", 25);
            if (d.movePacketsThisSecond > limit && d.exemptTicks <= 0 && d.graceTicks <= 0) {
                v.flag(p, CheckType.TIMER, "move-пакетов/сек=" + d.movePacketsThisSecond + " лимит=" + limit);
            }
            d.movePacketsThisSecond = 0;
            d.moveSecondStart = now;
        }
        if (from.getX() != to.getX() || from.getY() != to.getY() || from.getZ() != to.getZ()) {
            d.movePacketsThisSecond++;
        }
    }

    private void trackRotation(PlayerData d, Location from, Location to) {
        d.lastYawDelta = Math.abs(Util.wrapAngle(to.getYaw() - from.getYaw()));
        d.lastPitchDelta = Math.abs(to.getPitch() - from.getPitch());
        d.lastYaw = to.getYaw();
        d.lastPitch = to.getPitch();
    }

    private boolean isSoftLanding(Location l) {
        Material m = l.clone().add(0, -0.2, 0).getBlock().getType();
        return m == Material.HAY_BLOCK || m == Material.SLIME_BLOCK || m == Material.HONEY_BLOCK
                || m == Material.COBWEB || m == Material.SCAFFOLDING || m == Material.POWDER_SNOW
                || m == Material.WATER || m == Material.BUBBLE_COLUMN || m.name().endsWith("_BED");
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

    // ===================================================================
    //  ТРАНСПОРТ
    // ===================================================================
    @EventHandler(ignoreCancelled = true)
    public void onVehicleMove(VehicleMoveEvent e) {
        if (e.getVehicle().getPassengers().isEmpty()) return;
        if (!(e.getVehicle().getPassengers().get(0) instanceof Player p)) return;
        if (plugin.isExempt(p)) return;

        double dy = e.getTo().getY() - e.getFrom().getY();
        double dxz = Util.horizontal(e.getFrom(), e.getTo());

        if (e.getVehicle() instanceof Boat) {
            Material below = e.getTo().clone().add(0, -0.35, 0).getBlock().getType();
            boolean support = below != Material.AIR && below != Material.CAVE_AIR;
            if (dy > 0.12 && !support) {
                v.flag(p, CheckType.BOAT_FLY, String.format("лодка вверх %.3f без опоры", dy));
                e.getVehicle().teleport(e.getFrom());
                return;
            }
            if (dxz > 1.4) {
                v.flag(p, CheckType.ENTITY_SPEED, String.format("лодка %.2f блоков/тик", dxz));
                e.getVehicle().teleport(e.getFrom());
            }
        } else if (dxz > 1.6) {
            v.flag(p, CheckType.ENTITY_SPEED, String.format("транспорт %.2f блоков/тик", dxz));
            e.getVehicle().teleport(e.getFrom());
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onInteractEntity(PlayerInteractEntityEvent e) {
        Player p = e.getPlayer();
        Entity target = e.getRightClicked();
        if (target.getWorld() != p.getWorld()) return;
        double dist = p.getLocation().distance(target.getLocation());
        if (p.isInsideVehicle() && p.getVehicle() != null && !p.getVehicle().equals(target) && dist > 6) {
            v.flag(p, CheckType.ENTITY_CONTROL, String.format("чужой моб на %.2f", dist));
            e.setCancelled(true);
        } else if (dist > 6.5) {
            v.flag(p, CheckType.MOUNT_BYPASS, String.format("взаимодействие с %.2f блоков", dist));
            e.setCancelled(true);
        }
    }
}
