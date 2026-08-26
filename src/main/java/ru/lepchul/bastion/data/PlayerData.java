package ru.lepchul.bastion.data;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.boss.BossBar;
import org.bukkit.entity.Player;
import ru.lepchul.bastion.check.CheckType;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

public class PlayerData {

    public final UUID uuid;
    public final String name;

    public PlayerData(Player p) {
        this.uuid = p.getUniqueId();
        this.name = p.getName();
        this.lastLocation = p.getLocation().clone();
        this.lastGroundY = p.getLocation().getY();
    }

    // ---------- клиент ----------
    public String brand = "unknown";
    public String protocolInfo = "unknown";
    public final Set<String> channels = new HashSet<>();

    // ---------- нарушения ----------
    public final Map<CheckType, Double> vl = new EnumMap<>(CheckType.class);
    public final Map<CheckType, Long> lastFlag = new EnumMap<>(CheckType.class);
    public long lastDecay = System.currentTimeMillis();

    // ---------- движение ----------
    public Location lastLocation;
    public double lastDeltaY;
    public double lastGroundY;
    public int airTicks;
    public int groundTicks;
    public int jumpsSinceGround;
    public double jumpStartX, jumpStartZ;
    public boolean wasOnGround = true;
    public final Deque<Double> speedSamples = new ArrayDeque<>();
    public int movePacketsThisSecond;
    public long moveSecondStart = System.currentTimeMillis();
    public int exemptTicks;
    public long lastTeleport;
    public int noDescendTicks;
    public double fallDistance;

    // ---------- бой ----------
    public long lastAttack;
    public long lastHurt;
    public UUID lastTarget;
    public final Deque<Long> clicks = new ArrayDeque<>();
    public final Set<UUID> recentTargets = new HashSet<>();
    public long recentTargetsWindow;
    public long velocityAppliedAt;
    public double expectedVelX, expectedVelZ;
    public boolean velocityPending;
    public int velocityTicks;
    public float lastYaw, lastPitch;
    public float lastYawDelta, lastPitchDelta;
    public long lastBowShot;
    public long bowDrawStart;
    public long lastTotemLoss;
    public long lastProjectileNear;

    // ---------- блоки ----------
    public final Deque<Long> placements = new ArrayDeque<>();
    public final Deque<Long> breaks = new ArrayDeque<>();
    public final Deque<long[]> recentPlacedAroundSelf = new ArrayDeque<>();
    public Location digStart;
    public long digStartTime;
    public Material digMaterial;
    public long lastTowerPlace;
    public int towerCount;
    public long lastPlace;

    // ---------- предметы ----------
    public long lastConsume;
    public long lastItemUse;
    public long lastHeldSwap;
    public final Deque<Long> containerTakes = new ArrayDeque<>();
    public boolean inventoryOpen;
    public long inventoryOpenedAt;
    public long lastAnchorUse;
    public final Deque<Long> fireworks = new ArrayDeque<>();
    public final Deque<Long> igniteUses = new ArrayDeque<>();

    // ---------- чат ----------
    public long lastChat;
    public String lastChatMessage = "";
    public long lastCommand;

    // ---------- пакеты (пишутся из netty-потока) ----------
    public final Map<String, AtomicInteger> packetCounts = new ConcurrentHashMap<>();
    public final AtomicInteger totalPackets = new AtomicInteger();
    public volatile long packetWindowStart = System.currentTimeMillis();
    public volatile boolean kicking;

    // ---------- ПВП ----------
    public long pvpTagUntil;
    public BossBar bossBar;
    public UUID lastAttacker;

    // ---------- чанки ----------
    public final Deque<Long> chunkLoads = new ArrayDeque<>();
    public final Deque<Long> portals = new ArrayDeque<>();

    public double vl(CheckType t) {
        return vl.getOrDefault(t, 0.0);
    }

    public AtomicInteger packet(String key) {
        return packetCounts.computeIfAbsent(key, k -> new AtomicInteger());
    }

    public boolean inPvp() {
        return System.currentTimeMillis() < pvpTagUntil;
    }

    public static void trim(Deque<Long> deque, long windowMs) {
        long cutoff = System.currentTimeMillis() - windowMs;
        while (!deque.isEmpty() && deque.peekFirst() < cutoff) deque.pollFirst();
    }
}
