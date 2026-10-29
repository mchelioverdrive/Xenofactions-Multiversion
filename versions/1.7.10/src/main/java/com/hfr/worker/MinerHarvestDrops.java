package com.hfr.worker;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Predicate;
import com.hfr.builder.BuilderPlacement;
import com.hfr.compat.HbmMiningToolEnergy;
import net.minecraft.block.Block;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.ChunkCoordinates;
import net.minecraftforge.common.util.FakePlayer;
import net.minecraftforge.event.world.BlockEvent.BreakEvent;
import com.hfr.main.CommonEventHandler;

/** One server-thread tool action, including secondary and nested harvest callbacks. */
public final class MinerHarvestDrops {
    private static final ThreadLocal<MinerHarvestDrops> ACTIVE = new ThreadLocal<MinerHarvestDrops>();
    private final FakePlayer player;
    private final UUID factionId;
    private final MinerHarvestDrops previous;
    private final Consumer<List<BreakEvent>> completed;
    private final Predicate<BreakEvent> allowed;
    // Keep all attempts and their actual events: later cancellation must remain visible.
    private final Map<ChunkCoordinates,List<BreakEvent>> events = new LinkedHashMap<ChunkCoordinates,List<BreakEvent>>();

    private MinerHarvestDrops(FakePlayer player,UUID factionId,Predicate<BreakEvent> allowed,Consumer<List<BreakEvent>> completed) {
        this.player = player; this.factionId = factionId; this.allowed = allowed; this.completed = completed; previous = ACTIVE.get();
    }
    private static MinerHarvestDrops begin(FakePlayer player,UUID factionId,Predicate<BreakEvent> allowed,Consumer<List<BreakEvent>> completed) {
        MinerHarvestDrops scope = new MinerHarvestDrops(player,factionId,allowed,completed);
        ACTIVE.set(scope);
        return scope;
    }
    private static MinerHarvestDrops find(FakePlayer player) {
        for (MinerHarvestDrops scope = ACTIVE.get(); scope != null; scope = scope.previous)
            if (scope.player == player) return scope;
        return null;
    }
    public static boolean canContinueHarvest(EntityPlayer player) {
        return !(player instanceof FakePlayer) || find((FakePlayer)player) == null
                || HbmMiningToolEnergy.problem(player.getHeldItem()).isEmpty();
    }
    public static boolean defer(BreakEvent event) {
        if (!(event.getPlayer() instanceof FakePlayer)) return false;
        MinerHarvestDrops scope = find((FakePlayer)event.getPlayer());
        if (scope == null || scope.player.worldObj != event.world) return false;
        // Apply worker protections to every affected block, without restricting tool geometry.
        if (!canContinueHarvest(scope.player) || !scope.allowed.test(event) || !WorkerAccess.ownsLand(event.world,event.x,event.z,scope.factionId)
                || BuilderPlacement.protectedBlock(event.world,event.x,event.y,event.z)
                || event.block.getBlockHardness(event.world,event.x,event.y,event.z) < 0
                || event.world.getTileEntity(event.x,event.y,event.z) != null || event.block.getMaterial().isLiquid())
            event.setCanceled(true);
        ChunkCoordinates key = new ChunkCoordinates(event.x,event.y,event.z);
        List<BreakEvent> attempts = scope.events.get(key);
        if (attempts == null) { attempts = new ArrayList<BreakEvent>(); scope.events.put(key,attempts); }
        attempts.add(event);
        return true;
    }
    public static boolean harvest(FakePlayer player,int x,int y,int z,UUID factionId,Consumer<List<BreakEvent>> completed) {
        return harvest(player,x,y,z,factionId,event -> true,completed);
    }
    public static boolean harvest(FakePlayer player,int x,int y,int z,UUID factionId,Predicate<BreakEvent> allowed,Consumer<List<BreakEvent>> completed) {
        // A reentrant call for this actor belongs to the existing action and is finalized once.
        MinerHarvestDrops scope = find(player);
        boolean outermost = scope == null;
        if (outermost) scope = begin(player,factionId,allowed,completed);
        try {
            if (!canContinueHarvest(player)) return false;
            player.theItemInWorldManager.tryHarvestBlock(x,y,z);
            // A hook may handle removal itself and return false to stop vanilla harvesting.
            // Conversely, a true manager result must never skip a still-solid work cell.
            return player.worldObj.isAirBlock(x,y,z);
        } finally { if (outermost) scope.finish(); }
    }
    private void finish() {
        if (previous == null) ACTIVE.remove(); else ACTIVE.set(previous);
        List<BreakEvent> changed = new ArrayList<BreakEvent>();
        try {
            for (List<BreakEvent> attempts : events.values()) {
                BreakEvent first = attempts.get(0);
                if (!first.world.blockExists(first.x,first.y,first.z)) continue;
                Block remaining = first.world.getBlock(first.x,first.y,first.z);
                if (remaining == first.block && first.world.getBlockMetadata(first.x,first.y,first.z) == first.blockMetadata) continue;
                changed.add(first);
            }
            for (BreakEvent first : changed) {
                // Check this cell's removal, independently of the primary manager result.
                // Metadata-only changes are not successful breaks. Duplicate callbacks/retries
                // at a coordinate cannot award the same stone reward twice.
                if (first.world.getBlock(first.x,first.y,first.z) == first.block) continue;
                for (BreakEvent event : events.get(new ChunkCoordinates(first.x,first.y,first.z))) {
                    if (!event.isCanceled() && event.block == first.block && event.blockMetadata == first.blockMetadata) {
                        CommonEventHandler.dropStoneRewards(event);
                        break;
                    }
                }
            }
        } finally {
            try { completed.accept(changed); }
            finally { events.clear(); }
        }
    }
}
