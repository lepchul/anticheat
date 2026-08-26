package ru.lepchul.bastion.util;

import org.bukkit.ChatColor;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.Vector;

import java.util.List;

public final class Util {

    private Util() {}

    private static final double[] OFF = {-0.31, 0.0, 0.31};

    /** Серверная проверка "на земле" — не доверяем флагу клиента. */
    public static boolean onGround(Location loc) {
        for (double dx : OFF) {
            for (double dz : OFF) {
                Block b = loc.clone().add(dx, -0.06, dz).getBlock();
                if (!b.isPassable()) return true;
            }
        }
        return false;
    }

    public static boolean inLiquid(Player p) {
        Location l = p.getLocation();
        Material feet = l.getBlock().getType();
        Material legs = l.clone().add(0, 0.6, 0).getBlock().getType();
        return isLiquid(feet) || isLiquid(legs);
    }

    public static boolean isLiquid(Material m) {
        return m == Material.WATER || m == Material.LAVA
                || m == Material.BUBBLE_COLUMN
                || m == Material.KELP || m == Material.KELP_PLANT
                || m == Material.SEAGRASS || m == Material.TALL_SEAGRASS;
    }

    public static boolean onClimbable(Player p) {
        Material m = p.getLocation().getBlock().getType();
        return m == Material.LADDER || m == Material.VINE || m == Material.SCAFFOLDING
                || m == Material.TWISTING_VINES || m == Material.TWISTING_VINES_PLANT
                || m == Material.WEEPING_VINES || m == Material.WEEPING_VINES_PLANT
                || m == Material.CAVE_VINES || m == Material.CAVE_VINES_PLANT;
    }

    public static boolean inWeb(Player p) {
        Location l = p.getLocation();
        return l.getBlock().getType() == Material.COBWEB
                || l.clone().add(0, 1, 0).getBlock().getType() == Material.COBWEB;
    }

    /** Блок, стоя в котором игрок физически не должен быть (для Phase / Burrow). */
    public static boolean insideSolid(Location loc) {
        Block feet = loc.getBlock();
        Block head = loc.clone().add(0, 1.0, 0).getBlock();
        return isFullSolid(feet) || isFullSolid(head);
    }

    public static boolean isFullSolid(Block b) {
        if (b.isPassable()) return false;
        Material m = b.getType();
        // двери, люки, калитки, ступени и т.п. пропускаем — там куча ложных
        String n = m.name();
        if (n.contains("DOOR") || n.contains("TRAPDOOR") || n.contains("FENCE")
                || n.contains("SLAB") || n.contains("STAIRS") || n.contains("WALL")
                || n.contains("CARPET") || n.contains("SHULKER") || n.contains("CHEST")
                || n.contains("BED") || n.contains("PANE") || n.contains("SIGN")
                || n.contains("SCAFFOLDING") || n.contains("SNOW") || n.contains("PISTON")) return false;
        return m.isOccluding();
    }

    public static boolean nearLiquidSurface(Player p) {
        Location l = p.getLocation();
        Material below = l.clone().add(0, -0.2, 0).getBlock().getType();
        Material feet = l.getBlock().getType();
        return (below == Material.WATER || below == Material.LAVA)
                && (feet == Material.AIR || feet == Material.CAVE_AIR || isLiquid(feet));
    }

    public static boolean onBoat(Player p) {
        return p.getVehicle() != null;
    }

    public static int effect(Player p, PotionEffectType type) {
        PotionEffect e = p.getPotionEffect(type);
        return e == null ? 0 : e.getAmplifier() + 1;
    }

    public static boolean hasEffect(Player p, PotionEffectType type) {
        return p.getPotionEffect(type) != null;
    }

    public static double horizontal(Location a, Location b) {
        double dx = a.getX() - b.getX();
        double dz = a.getZ() - b.getZ();
        return Math.sqrt(dx * dx + dz * dz);
    }

    /** Угол в градусах между взглядом и направлением на цель. */
    public static double lookAngle(Location eye, Location target) {
        Vector dir = eye.getDirection().normalize();
        Vector to = target.toVector().subtract(eye.toVector());
        if (to.lengthSquared() < 1.0E-6) return 0;
        to.normalize();
        double dot = Math.max(-1.0, Math.min(1.0, dir.dot(to)));
        return Math.toDegrees(Math.acos(dot));
    }

    public static float wrapAngle(float angle) {
        angle %= 360.0f;
        if (angle >= 180.0f) angle -= 360.0f;
        if (angle < -180.0f) angle += 360.0f;
        return angle;
    }

    public static boolean creativeOrSpectator(Player p) {
        return p.getGameMode() == GameMode.CREATIVE || p.getGameMode() == GameMode.SPECTATOR;
    }

    /** Кастомное зачарование "Перо" на ботинках (работает как NoFall). */
    public static boolean hasFeatherBoots(Player p, NamespacedKey key, String loreKeyword) {
        ItemStack boots = p.getInventory().getBoots();
        if (boots == null || boots.getType() == Material.AIR || !boots.hasItemMeta()) return false;
        ItemMeta meta = boots.getItemMeta();
        if (meta == null) return false;
        if (key != null && meta.getPersistentDataContainer().has(key, PersistentDataType.INTEGER)) return true;
        if (loreKeyword != null && meta.hasLore()) {
            List<String> lore = meta.getLore();
            if (lore != null) {
                for (String line : lore) {
                    if (ChatColor.stripColor(line).toLowerCase().contains(loreKeyword.toLowerCase())) return true;
                }
            }
        }
        if (loreKeyword != null && meta.hasDisplayName()
                && ChatColor.stripColor(meta.getDisplayName()).toLowerCase().contains(loreKeyword.toLowerCase())) {
            return true;
        }
        return false;
    }

    /** Ванильное время ломания блока в тиках (упрощённая формула). */
    public static double breakTicks(Player p, Block block) {
        double hardness = blockHardness(block.getType());
        if (hardness <= 0) return 0;

        ItemStack tool = p.getInventory().getItemInMainHand();
        double speed = toolSpeed(tool, block.getType());
        boolean canHarvest = speed > 1.0;

        int eff = tool.getEnchantmentLevel(org.bukkit.enchantments.Enchantment.DIG_SPEED);
        if (eff > 0 && speed > 1.0) speed += eff * eff + 1;

        int haste = effect(p, PotionEffectType.FAST_DIGGING);
        if (haste > 0) speed *= 1.0 + 0.2 * haste;
        int fatigue = effect(p, PotionEffectType.SLOW_DIGGING);
        if (fatigue > 0) speed *= Math.pow(0.3, Math.min(fatigue, 4));

        if (!p.isOnGround()) speed /= 5.0;
        if (p.isInWater() && p.getInventory().getHelmet() == null) speed /= 5.0;

        double damage = speed / hardness / (canHarvest ? 30.0 : 100.0);
        if (damage >= 1.0) return 0;
        return Math.ceil(1.0 / damage);
    }

    private static double blockHardness(Material m) {
        try {
            // Bukkit не отдаёт hardness напрямую — таблица по самым частым группам
            String n = m.name();
            if (n.contains("OBSIDIAN")) return 50.0;
            if (n.contains("ANCIENT_DEBRIS")) return 30.0;
            if (n.contains("ENDER_CHEST")) return 22.5;
            if (n.contains("DEEPSLATE")) return 3.5;
            if (n.endsWith("_ORE")) return 3.0;
            if (n.contains("STONE") || n.contains("BRICK") || n.contains("COBBLE")) return 1.5;
            if (n.contains("LOG") || n.contains("PLANKS") || n.contains("WOOD")) return 2.0;
            if (n.contains("DIRT") || n.contains("GRASS_BLOCK") || n.contains("SAND") || n.contains("GRAVEL")) return 0.6;
            if (n.contains("WOOL") || n.contains("LEAVES")) return 0.3;
            if (n.contains("GLASS")) return 0.3;
            if (n.contains("NETHERRACK")) return 0.4;
            if (m.isOccluding()) return 1.5;
            return 0.5;
        } catch (Throwable t) {
            return 1.5;
        }
    }

    private static double toolSpeed(ItemStack tool, Material block) {
        if (tool == null) return 1.0;
        String t = tool.getType().name();
        String b = block.name();
        boolean pickTarget = b.contains("STONE") || b.contains("ORE") || b.contains("BRICK")
                || b.contains("DEEPSLATE") || b.contains("COBBLE") || b.contains("OBSIDIAN")
                || b.contains("METAL") || b.contains("IRON_BLOCK") || b.contains("CONCRETE");
        boolean axeTarget = b.contains("LOG") || b.contains("PLANKS") || b.contains("WOOD");
        boolean shovelTarget = b.contains("DIRT") || b.contains("SAND") || b.contains("GRAVEL")
                || b.contains("GRASS_BLOCK") || b.contains("SNOW") || b.contains("CLAY");

        boolean right = (t.endsWith("_PICKAXE") && pickTarget)
                || (t.endsWith("_AXE") && !t.endsWith("_PICKAXE") && axeTarget)
                || (t.endsWith("_SHOVEL") && shovelTarget)
                || (t.endsWith("_HOE") && b.contains("LEAVES"))
                || (t.equals("SHEARS") && (b.contains("WOOL") || b.contains("LEAVES")));
        if (!right) return 1.0;

        if (t.startsWith("WOODEN")) return 2.0;
        if (t.startsWith("STONE")) return 4.0;
        if (t.startsWith("IRON")) return 6.0;
        if (t.startsWith("DIAMOND")) return 8.0;
        if (t.startsWith("NETHERITE")) return 9.0;
        if (t.startsWith("GOLDEN")) return 12.0;
        if (t.equals("SHEARS")) return 5.0;
        return 1.0;
    }
}
