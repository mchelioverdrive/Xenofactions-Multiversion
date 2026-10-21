package com.hfr.leaderboard;

import com.hfr.clowder.Clowder;
import com.hfr.clowder.ClowderTerritory;
import com.hfr.clowder.FactionMemberRecord;
import com.hfr.clowder.FactionRole;
import com.hfr.data.ClowderData;
import com.hfr.util.ChatFormatting;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Locale;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.world.World;
import net.minecraftforge.common.DimensionManager;

/** Server-thread-only snapshots. No player, inventory, chunk or block discovery. */
public final class FactionLeaderboards {
    public static final int ENTRIES = 10;
    public static final int REFRESH_TICKS = 200;

    public enum Metric {
        TERRITORY("Territory", "claim nodes", ""),
        PRESTIGE("Prestige", "prestige", ""),
        MONEY("Money", "", "Unavailable: no faction money metric"),
        KILLS("Kills", "", "Unavailable: no faction kill metric");

        public final String title, unit, unavailable;
        Metric(String title, String unit, String unavailable) {
            this.title = title; this.unit = unit; this.unavailable = unavailable;
        }
        public String key() { return name().toLowerCase(Locale.ROOT); }
        public static Metric parse(String key) {
            for (Metric metric : values()) if (metric.key().equalsIgnoreCase(key)) return metric;
            return null;
        }
    }

    private static final Map<Metric, NBTTagList> cache = new EnumMap<Metric, NBTTagList>(Metric.class);
    private static long refreshedAt;
    private static boolean populated;

    private FactionLeaderboards() { }

    public static boolean canManage(EntityPlayer player) {
        return player != null && player.canCommandSenderUseCommand(3, "xclowder");
    }

    public static void clear() { cache.clear(); populated = false; }

    /** Returns a detached, bounded display payload, never the authoritative cache. */
    public static NBTTagList rows(World world, Metric metric) {
        if (world == null || world.isRemote) return new NBTTagList();
        ClowderData.getData(world); // Loads the canonical store and resets caches on save changes.
        World overworld = DimensionManager.getWorld(0);
        long tick = (overworld == null ? world : overworld).getTotalWorldTime();
        if (!populated || tick - refreshedAt >= REFRESH_TICKS || tick < refreshedAt) {
            rebuild(); refreshedAt = tick; populated = true;
        }
        return (NBTTagList) cache.get(metric).copy();
    }

    private static boolean eligible(Clowder faction) {
        if (faction == null || faction.uuid == null || faction.uuid.isEmpty()
                || faction.name == null || faction.name.isEmpty()) return false;
        // Same owner validity as Clowder.valid(), without its per-faction list scan.
        for (FactionMemberRecord member : faction.memberRecords.values())
            if (member.role == FactionRole.OWNER) return true;
        return faction.memberRecords.isEmpty() && faction.leader != null && !faction.leader.isEmpty();
    }

    private static void rebuild() {
        Map<Clowder, Long> territory = new IdentityHashMap<Clowder, Long>();
        List<Clowder> factions = new ArrayList<Clowder>();
        for (Clowder faction : Clowder.clowders) if (eligible(faction)) {
            factions.add(faction); territory.put(faction, 0L);
        }
        for (ClowderTerritory.TerritoryMeta claim : ClowderTerritory.territories.values()) {
            if (claim == null || claim.owner == null || claim.owner.zone != ClowderTerritory.Zone.FACTION) continue;
            Clowder faction = claim.owner.owner;
            Long count = territory.get(faction);
            if (count != null) territory.put(faction, count + 1L);
        }
        for (Metric metric : Metric.values()) {
            final Map<Clowder, Double> values = new HashMap<Clowder, Double>();
            if (metric == Metric.TERRITORY || metric == Metric.PRESTIGE) {
                for (Clowder faction : factions) {
                    double value = metric == Metric.TERRITORY ? territory.get(faction).doubleValue() : faction.getPrestige();
                    if (!Double.isNaN(value) && !Double.isInfinite(value)) values.put(faction, value);
                }
            }
            List<Clowder> ranked = new ArrayList<Clowder>(values.keySet());
            Collections.sort(ranked, new Comparator<Clowder>() {
                public int compare(Clowder a, Clowder b) {
                    int value = Double.compare(values.get(b), values.get(a));
                    return value != 0 ? value : a.uuid.compareTo(b.uuid);
                }
            });
            NBTTagList rows = new NBTTagList();
            for (int i = 0; i < Math.min(ENTRIES, ranked.size()); i++) {
                Clowder faction = ranked.get(i);
                NBTTagCompound row = new NBTTagCompound();
                row.setInteger("rank", i + 1);
                row.setString("name", displayName(faction.name));
                row.setString("value", metric == Metric.TERRITORY ? Long.toString(territory.get(faction))
                    : Clowder.round(faction.getPrestige()));
                rows.appendTag(row);
            }
            cache.put(metric, rows);
        }
    }

    private static String displayName(String name) {
        String clean = ChatFormatting.stripFormatting(name)
            .replaceAll("[\\p{Cntrl}]", "");
        return clean.substring(0, Math.min(64, clean.length()));
    }
}
