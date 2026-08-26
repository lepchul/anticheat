package ru.lepchul.bastion.check;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import ru.lepchul.bastion.Bastion;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * ESP/X-Ray нельзя обнаружить напрямую — это чтение уже отправленных клиенту данных.
 * Реальная защита: obfuscation в paper.yml (anti-xray engine-mode 2).
 * Здесь — поведенческая эвристика: соотношение руды к породе и «прямой ход к руде».
 */
public class XRayHeuristic {

    private static class Stats {
        int stone;
        int ores;
        int suspiciousApproaches;
        Location lastOre;
        long windowStart = System.currentTimeMillis();
    }

    private final Bastion plugin;
    private final Map<UUID, Stats> stats = new HashMap<>();

    public XRayHeuristic(Bastion plugin) {
        this.plugin = plugin;
    }

    public void onStoneMined(Player p) {
        get(p).stone++;
    }

    public void onOreMined(Player p, Block block) {
        Stats s = get(p);
        s.ores++;

        // руда, вскрытая «с первого удара» из сплошного камня = подозрительно
        if (fullyEnclosed(block.getLocation())) {
            s.suspiciousApproaches++;
        }
        s.lastOre = block.getLocation();

        evaluate(p, s);
    }

    private void evaluate(Player p, Stats s) {
        long now = System.currentTimeMillis();
        if (now - s.windowStart > 20 * 60 * 1000L) {
            s.stone = 0;
            s.ores = 0;
            s.suspiciousApproaches = 0;
            s.windowStart = now;
            return;
        }
        int total = s.stone + s.ores;
        if (total < 200) return;

        double ratio = (double) s.ores / total;
        // в ванильной генерации ниже y=16 доля ценной руды редко превышает ~8%
        if (ratio > 0.11 && s.ores > 25) {
            plugin.violations().flag(p, CheckType.XRAY,
                    String.format("руда/порода=%.1f%% (%d/%d), вскрытий вслепую=%d",
                            ratio * 100, s.ores, total, s.suspiciousApproaches));
            s.stone = 0;
            s.ores = 0;
            s.suspiciousApproaches = 0;
        }
    }

    private boolean fullyEnclosed(Location loc) {
        int[][] dirs = {{1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}};
        int solid = 0;
        for (int[] d : dirs) {
            Block b = loc.clone().add(d[0], d[1], d[2]).getBlock();
            Material m = b.getType();
            if (m != Material.AIR && m != Material.CAVE_AIR && m != Material.WATER && m != Material.LAVA) solid++;
        }
        return solid >= 5;
    }

    private Stats get(Player p) {
        return stats.computeIfAbsent(p.getUniqueId(), k -> new Stats());
    }

    public void remove(UUID uuid) {
        stats.remove(uuid);
    }
}
