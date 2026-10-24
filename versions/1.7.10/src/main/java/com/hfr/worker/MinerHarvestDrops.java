package com.hfr.worker;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraftforge.event.world.BlockEvent.BreakEvent;
import com.hfr.main.CommonEventHandler;

/** A server-thread harvest scope. Retain the actual event so later cancellation is visible. */
public final class MinerHarvestDrops {
    private static final ThreadLocal<MinerHarvestDrops> ACTIVE = new ThreadLocal<MinerHarvestDrops>();
    private final EntityPlayer player;
    private final int x,y,z;
    private BreakEvent event;
    private MinerHarvestDrops(EntityPlayer player,int x,int y,int z) { this.player = player; this.x=x; this.y=y; this.z=z; }
    public static void begin(EntityPlayer player,int x,int y,int z) {
        if (ACTIVE.get() != null) throw new IllegalStateException("Nested miner harvest");
        ACTIVE.set(new MinerHarvestDrops(player,x,y,z));
    }
    public static boolean defer(BreakEvent event) {
        MinerHarvestDrops scope = ACTIVE.get();
        if (scope == null || scope.player != event.getPlayer() || scope.x!=event.x || scope.y!=event.y || scope.z!=event.z) return false;
        if (scope.event == null) scope.event = event;
        return true;
    }
    public static void finish(boolean success) {
        MinerHarvestDrops scope = ACTIVE.get(); ACTIVE.remove();
        if (success && scope != null && scope.event != null && !scope.event.isCanceled())
            CommonEventHandler.dropStoneRewards(scope.event);
    }
}
