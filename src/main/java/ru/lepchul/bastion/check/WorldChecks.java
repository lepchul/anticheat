package ru.lepchul.bastion.check;

import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockDamageEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.block.SignChangeEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.player.*;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.RayTraceResult;
import ru.lepchul.bastion.Bastion;
import ru.lepchul.bastion.data.PlayerData;
import ru.lepchul.bastion.util.Util;

import java.util.Locale;

public class WorldChecks implements Listener {

    private final Bastion plugin;
    private final Violations v;

    public WorldChecks(Bastion plugin) {
        this.plugin = plugin;
        this.v = plugin.violations();
    }

    // ==================== УСТАНОВКА БЛОКОВ ====================
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent e) {
        Player p = e.getPlayer();
        if (plugin.isExempt(p) || p.getGameMode() == GameMode.CREATIVE) return;
        PlayerData d = plugin.data(p);
        if (d == null) return;

        long now = System.currentTimeMillis();
        Block placed = e.getBlockPlaced();
        Block against = e.getBlockAgainst();

        // ---------- частота ----------
        d.placements.addLast(now);
        PlayerData.trim(d.placements, 1000);
        int maxPlace = plugin.getConfig().getInt("blocks.max-place-per-second", 12);
        if (d.placements.size() > maxPlace) {
            if (v.flag(p, CheckType.SCAFFOLD, "блоков/сек=" + d.placements.size())) {
                e.setCancelled(true);
                return;
            }
        }

        // ---------- AirPlace / LiquidPlace ----------
        if (against == null || against.getType() == Material.AIR || against.getType() == Material.CAVE_AIR) {
            if (v.flag(p, CheckType.AIR_PLACE, "установка без опоры")) {
                e.setCancelled(true);
                return;
            }
        } else if (Util.isLiquid(against.getType())) {
            if (v.flag(p, CheckType.LIQUID_PLACE, "опора — жидкость " + against.getType())) {
                e.setCancelled(true);
                return;
            }
        }

        // ---------- дальность установки ----------
        double dist = p.getEyeLocation().distance(placed.getLocation().add(0.5, 0.5, 0.5));
        if (dist > 6.5) {
            if (v.flag(p, CheckType.REACH, String.format("установка блока на %.2f", dist))) {
                e.setCancelled(true);
                return;
            }
        }

        // ---------- Scaffold: установка «спиной» ----------
        double angle = Util.lookAngle(p.getEyeLocation(), placed.getLocation().add(0.5, 0.5, 0.5));
        if (angle > 100 && dist > 1.2) {
            if (v.flag(p, CheckType.SCAFFOLD, String.format("установка за спиной, угол=%.1f°", angle))) {
                e.setCancelled(true);
                return;
            }
        }

        // ---------- Tower ----------
        Location pl = p.getLocation();
        boolean underSelf = placed.getX() == pl.getBlockX() && placed.getZ() == pl.getBlockZ()
                && placed.getY() < pl.getBlockY();
        if (underSelf) {
            if (now - d.lastTowerPlace < 180) d.towerCount++;
            else d.towerCount = 0;
            d.lastTowerPlace = now;
            if (d.towerCount >= 4) {
                v.flag(p, CheckType.TOWER, "башня со скоростью " + d.towerCount + " блоков подряд");
                d.towerCount = 0;
            }
        }

        // ---------- Burrow: блок в собственной позиции ----------
        if (placed.getX() == pl.getBlockX() && placed.getZ() == pl.getBlockZ()
                && (placed.getY() == pl.getBlockY() || placed.getY() == pl.getBlockY() + 1)) {
            if (v.flag(p, CheckType.BURROW, "установка блока внутрь себя")) {
                e.setCancelled(true);
                return;
            }
        }

        // ---------- Surround / SelfTrap / SelfWeb ----------
        if (isAroundSelf(pl, placed)) {
            d.recentPlacedAroundSelf.addLast(new long[]{now, placed.getX(), placed.getY(), placed.getZ()});
            while (!d.recentPlacedAroundSelf.isEmpty() && d.recentPlacedAroundSelf.peekFirst()[0] < now - 700) {
                d.recentPlacedAroundSelf.pollFirst();
            }
            int need = plugin.getConfig().getInt("blocks.surround-blocks", 4);
            if (d.recentPlacedAroundSelf.size() >= need) {
                CheckType type = placed.getType() == Material.COBWEB ? CheckType.SELF_TRAP : CheckType.SURROUND;
                v.flag(p, type, "блоков вокруг себя за 700мс=" + d.recentPlacedAroundSelf.size());
                d.recentPlacedAroundSelf.clear();
            }
        }

        // ---------- AutoReplenish ----------
        if (now - d.lastHeldSwap < 30 && now - d.lastPlace < 120) {
            v.flag(p, CheckType.AUTO_REPLENISH, "мгновенная подмена стака", 0.5);
        }

        d.lastPlace = now;
    }

    private boolean isAroundSelf(Location player, Block placed) {
        int dx = Math.abs(placed.getX() - player.getBlockX());
        int dz = Math.abs(placed.getZ() - player.getBlockZ());
        int dy = placed.getY() - player.getBlockY();
        return dx <= 1 && dz <= 1 && dy >= -1 && dy <= 2;
    }

    // ==================== ЛОМАНИЕ БЛОКОВ ====================
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDamageBlock(BlockDamageEvent e) {
        PlayerData d = plugin.data(e.getPlayer());
        if (d == null) return;
        d.digStart = e.getBlock().getLocation();
        d.digStartTime = System.currentTimeMillis();
        d.digMaterial = e.getBlock().getType();
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent e) {
        Player p = e.getPlayer();
        if (plugin.isExempt(p) || p.getGameMode() == GameMode.CREATIVE) return;
        PlayerData d = plugin.data(p);
        if (d == null) return;

        long now = System.currentTimeMillis();
        Block b = e.getBlock();

        // ---------- частота (Nuker) ----------
        d.breaks.addLast(now);
        PlayerData.trim(d.breaks, 1000);
        int maxBreak = plugin.getConfig().getInt("blocks.max-break-per-second", 12);
        if (d.breaks.size() > maxBreak) {
            if (v.flag(p, CheckType.NUKER, "блоков/сек=" + d.breaks.size())) {
                e.setCancelled(true);
                return;
            }
        }

        // ---------- дальность ----------
        double dist = p.getEyeLocation().distance(b.getLocation().add(0.5, 0.5, 0.5));
        if (dist > 6.5) {
            if (v.flag(p, CheckType.REACH, String.format("ломание на %.2f", dist))) {
                e.setCancelled(true);
                return;
            }
        }

        // ---------- PacketMine: не было анимации копки ----------
        if (d.digStart == null || !sameBlock(d.digStart, b.getLocation())) {
            double expected = Util.breakTicks(p, b);
            if (expected > 2) {
                if (v.flag(p, CheckType.PACKET_MINE, "ломание без начала копки (" + b.getType() + ")")) {
                    e.setCancelled(true);
                    return;
                }
            }
        } else {
            // ---------- SpeedMine ----------
            double expectedTicks = Util.breakTicks(p, b);
            double expectedMs = expectedTicks * 50.0;
            double actualMs = now - d.digStartTime;
            double ratio = plugin.getConfig().getDouble("blocks.min-break-time-ratio", 0.72);
            if (expectedMs > 200 && actualMs < expectedMs * ratio) {
                if (v.flag(p, CheckType.SPEED_MINE,
                        String.format("сломал за %.0fмс вместо %.0fмс", actualMs, expectedMs))) {
                    e.setCancelled(true);
                    return;
                }
            }
        }

        // ---------- VeinMiner: разрозненные блоки ----------
        if (d.digStart != null && !sameBlock(d.digStart, b.getLocation())
                && d.digStart.getWorld() == b.getWorld()
                && d.digStart.distance(b.getLocation()) > 2.5 && now - d.digStartTime < 300) {
            v.flag(p, CheckType.VEIN_MINER, "ломание нескольких блоков подряд на расстоянии");
        }

        // ---------- XRay-эвристика ----------
        if (isOre(b.getType())) {
            plugin.xray().onOreMined(p, b);
        } else {
            plugin.xray().onStoneMined(p);
        }

        d.digStart = null;
    }

    private boolean sameBlock(Location a, Location b) {
        return a.getWorld() == b.getWorld() && a.getBlockX() == b.getBlockX()
                && a.getBlockY() == b.getBlockY() && a.getBlockZ() == b.getBlockZ();
    }

    private boolean isOre(Material m) {
        String n = m.name();
        return n.endsWith("_ORE") || n.equals("ANCIENT_DEBRIS");
    }

    // ==================== ПРЕДМЕТЫ ====================
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onConsume(PlayerItemConsumeEvent e) {
        Player p = e.getPlayer();
        PlayerData d = plugin.data(p);
        if (d == null) return;
        long now = System.currentTimeMillis();
        Material m = e.getItem().getType();
        long minimum = m.name().contains("POTION") ? 800 : 1400;
        if (now - d.lastConsume < minimum) {
            if (v.flag(p, CheckType.FAST_USE, m + " употреблён через " + (now - d.lastConsume) + "мс")) {
                e.setCancelled(true);
                return;
            }
        }
        d.lastConsume = now;
    }

    @EventHandler(ignoreCancelled = true)
    public void onProjectileUse(PlayerInteractEvent e) {
        ItemStack item = e.getItem();
        if (item == null) return;
        Material m = item.getType();
        if (m != Material.ENDER_PEARL && m != Material.SNOWBALL && m != Material.EGG
                && m != Material.SPLASH_POTION && m != Material.LINGERING_POTION
                && m != Material.EXPERIENCE_BOTTLE) return;
        Player p = e.getPlayer();
        PlayerData d = plugin.data(p);
        if (d == null) return;
        long now = System.currentTimeMillis();
        if (now - d.lastItemUse < 90) {
            if (v.flag(p, CheckType.FAST_USE, m + " через " + (now - d.lastItemUse) + "мс")) {
                e.setCancelled(true);
                return;
            }
        }
        d.lastItemUse = now;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onHeld(PlayerItemHeldEvent e) {
        PlayerData d = plugin.data(e.getPlayer());
        if (d != null) d.lastHeldSwap = System.currentTimeMillis();
    }

    @EventHandler(ignoreCancelled = true)
    public void onFirework(PlayerInteractEvent e) {
        ItemStack item = e.getItem();
        if (item == null || item.getType() != Material.FIREWORK_ROCKET) return;
        Player p = e.getPlayer();
        if (!p.isGliding()) return;
        PlayerData d = plugin.data(p);
        if (d == null) return;
        d.fireworks.addLast(System.currentTimeMillis());
        PlayerData.trim(d.fireworks, 3000);
        if (d.fireworks.size() > 5) {
            if (v.flag(p, CheckType.ELYTRA_BOOST, "фейерверков за 3с=" + d.fireworks.size())) {
                e.setCancelled(true);
            }
        }
    }

    // ==================== КОНТЕЙНЕРЫ ====================
    @EventHandler(priority = EventPriority.MONITOR)
    public void onInvOpen(InventoryOpenEvent e) {
        if (!(e.getPlayer() instanceof Player p)) return;
        PlayerData d = plugin.data(p);
        if (d == null) return;
        d.inventoryOpen = true;
        d.inventoryOpenedAt = System.currentTimeMillis();

        Location loc = e.getInventory().getLocation();
        if (loc == null) return;
        double dist = p.getEyeLocation().distance(loc.clone().add(0.5, 0.5, 0.5));
        if (dist > 7.0) {
            v.flag(p, CheckType.CHEST_BYPASS, String.format("открытие контейнера с %.2f блоков", dist));
            e.setCancelled(true);
            return;
        }
        // проверка прямой видимости — открытие «сквозь блоки»
        try {
            RayTraceResult ray = p.getWorld().rayTraceBlocks(p.getEyeLocation(),
                    loc.clone().add(0.5, 0.5, 0.5).toVector().subtract(p.getEyeLocation().toVector()).normalize(),
                    dist + 0.5, org.bukkit.FluidCollisionMode.NEVER, true);
            if (ray != null && ray.getHitBlock() != null) {
                Block hit = ray.getHitBlock();
                if (Math.abs(hit.getX() - loc.getBlockX()) > 1
                        || Math.abs(hit.getY() - loc.getBlockY()) > 1
                        || Math.abs(hit.getZ() - loc.getBlockZ()) > 1) {
                    v.flag(p, CheckType.CHEST_BYPASS, "контейнер открыт сквозь " + hit.getType());
                    e.setCancelled(true);
                }
            }
        } catch (Throwable ignored) {
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onInvClose(InventoryCloseEvent e) {
        PlayerData d = plugin.data((Player) e.getPlayer());
        if (d != null) {
            d.inventoryOpen = false;
            d.containerTakes.clear();
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onContainerClick(InventoryClickEvent e) {
        if (!(e.getWhoClicked() instanceof Player p)) return;
        if (e.getInventory().getType() == InventoryType.PLAYER
                || e.getInventory().getType() == InventoryType.CRAFTING) return;
        if (e.getClickedInventory() == null || e.getClickedInventory().equals(p.getInventory())) return;

        PlayerData d = plugin.data(p);
        if (d == null) return;
        long now = System.currentTimeMillis();
        d.containerTakes.addLast(now);
        PlayerData.trim(d.containerTakes, 1000);
        if (d.containerTakes.size() > 14) {
            if (v.flag(p, CheckType.CHEST_STEALER, "взятий/сек=" + d.containerTakes.size())) {
                e.setCancelled(true);
                p.closeInventory();
            }
        }
    }

    // ==================== ЧАТ / КОМАНДЫ ====================
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onChat(AsyncPlayerChatEvent e) {
        Player p = e.getPlayer();
        PlayerData d = plugin.data(p);
        if (d == null) return;
        long now = System.currentTimeMillis();
        String msg = e.getMessage();

        int maxChars = plugin.getConfig().getInt("chat.max-message-chars", 256);
        if (msg.length() > maxChars) {
            v.flag(p, CheckType.CHAT_SPAM, "длина сообщения=" + msg.length());
            e.setCancelled(true);
            return;
        }
        long interval = plugin.getConfig().getLong("chat.min-message-interval-ms", 700);
        if (now - d.lastChat < interval) {
            v.flag(p, CheckType.CHAT_SPAM, "интервал " + (now - d.lastChat) + "мс");
            e.setCancelled(true);
            return;
        }
        long repeatWindow = plugin.getConfig().getLong("chat.block-repeat-within-ms", 5000);
        if (msg.equalsIgnoreCase(d.lastChatMessage) && now - d.lastChat < repeatWindow) {
            v.flag(p, CheckType.CHAT_SPAM, "повтор сообщения");
            e.setCancelled(true);
            return;
        }
        d.lastChat = now;
        d.lastChatMessage = msg;
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onCommand(PlayerCommandPreprocessEvent e) {
        Player p = e.getPlayer();
        PlayerData d = plugin.data(p);
        if (d == null) return;
        long now = System.currentTimeMillis();
        if (now - d.lastCommand < plugin.getConfig().getLong("chat.min-command-interval-ms", 400)) {
            v.flag(p, CheckType.COMMAND_SPAM, "интервал команд " + (now - d.lastCommand) + "мс");
            e.setCancelled(true);
            return;
        }
        d.lastCommand = now;

        if (e.getMessage().length() > 256) {
            v.flag(p, CheckType.INVALID_PACKET, "длина команды=" + e.getMessage().length());
            e.setCancelled(true);
            return;
        }
        plugin.pvpTag().onCommand(e);
    }

    // ==================== КРАШ-ВЕКТОРЫ ====================
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onBookEdit(PlayerEditBookEvent e) {
        Player p = e.getPlayer();
        var meta = e.getNewBookMeta();
        int pages = meta.getPageCount();
        int maxPages = plugin.getConfig().getInt("packets.max-book-pages", 50);
        int maxPage = plugin.getConfig().getInt("packets.max-book-page-chars", 1000);
        int maxTotal = plugin.getConfig().getInt("packets.max-book-total-chars", 12000);

        int total = 0;
        for (int i = 1; i <= pages; i++) {
            String page = meta.getPage(i);
            total += page.length();
            if (page.length() > maxPage) {
                v.flag(p, CheckType.BOOK_CRASH, "страница " + i + " длиной " + page.length());
                e.setCancelled(true);
                return;
            }
        }
        if (pages > maxPages || total > maxTotal) {
            v.flag(p, CheckType.BOOK_CRASH, "страниц=" + pages + " символов=" + total);
            e.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onSign(SignChangeEvent e) {
        int max = plugin.getConfig().getInt("packets.max-sign-line-chars", 100);
        for (String line : e.getLines()) {
            if (line != null && line.length() > max) {
                v.flag(e.getPlayer(), CheckType.SIGN_CRASH, "строка таблички длиной " + line.length());
                e.setCancelled(true);
                return;
            }
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onSwapHand(PlayerSwapHandItemsEvent e) {
        Player p = e.getPlayer();
        PlayerData d = plugin.data(p);
        if (d == null) return;
        long now = System.currentTimeMillis();
        if (now - d.lastHeldSwap < 25) {
            if (v.flag(p, CheckType.OFFHAND_CRASH, "свап рук через " + (now - d.lastHeldSwap) + "мс")) {
                e.setCancelled(true);
            }
        }
        d.lastHeldSwap = now;
    }

    /** Проверка предметов на «крашащий» NBT. */
    public boolean isCrashItem(Player p, ItemStack item) {
        if (item == null || item.getType() == Material.AIR) return false;
        if (item.getAmount() > item.getType().getMaxStackSize() && item.getAmount() > 64) {
            v.flag(p, CheckType.ITEM_CRASH, "стак=" + item.getAmount() + " " + item.getType());
            return true;
        }
        int maxLevel = plugin.getConfig().getInt("packets.max-enchant-level", 255);
        for (var entry : item.getEnchantments().entrySet()) {
            if (entry.getValue() > maxLevel || entry.getValue() < 0) {
                v.flag(p, CheckType.ITEM_CRASH, "зачарование " + entry.getKey().getKey() + " ур." + entry.getValue());
                return true;
            }
        }
        try {
            if (item.hasItemMeta()) {
                String s = item.getItemMeta().toString();
                int maxBytes = plugin.getConfig().getInt("packets.max-item-nbt-bytes", 20000);
                if (s.length() > maxBytes) {
                    v.flag(p, CheckType.ITEM_CRASH, "NBT предмета " + s.length() + " байт");
                    return true;
                }
                if (item.getItemMeta().hasDisplayName()
                        && item.getItemMeta().getDisplayName().length()
                        > plugin.getConfig().getInt("packets.max-anvil-name-chars", 48) * 4) {
                    v.flag(p, CheckType.ITEM_CRASH, "слишком длинное имя предмета");
                    return true;
                }
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPotion(org.bukkit.event.entity.EntityPotionEffectEvent e) {
        if (!(e.getEntity() instanceof Player p)) return;
        if (e.getCause() != org.bukkit.event.entity.EntityPotionEffectEvent.Cause.PLUGIN) return;
        // серверная сторона авторитетна; логируем несоответствие для PotionSpoof
        PlayerData d = plugin.data(p);
        if (d == null) return;
        if (e.getNewEffect() != null && e.getNewEffect().getAmplifier() > 10) {
            v.flag(p, CheckType.POTION_SPOOF,
                    "эффект " + e.getNewEffect().getType().getName() + " ур." + e.getNewEffect().getAmplifier());
        }
    }

    public static String lower(String s) {
        return s == null ? "" : s.toLowerCase(Locale.ROOT);
    }
}
