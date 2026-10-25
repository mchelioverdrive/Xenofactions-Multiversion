package com.hfr.worker;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTBase;
import net.minecraft.nbt.NBTTagCompound;
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
        if (scope == null || scope.player != event.getPlayer()) return false;
        // An NPC is authorized for exactly one cell, including custom stone rewards.
        if (scope.x!=event.x || scope.y!=event.y || scope.z!=event.z) { event.setCanceled(true); return true; }
        if (scope.event == null) scope.event = event;
        return true;
    }
    private static boolean bismuth(ItemStack stack) {
        return stack != null && "hbm:item.bismuth_pickaxe".equals(Item.itemRegistry.getNameForObject(stack.getItem()))
                && "com.hbm.items.tool.ItemToolAbility".equals(stack.getItem().getClass().getName());
    }
    public static boolean singleBlockTool(ItemStack stack) {
        // Exact inspected implementations only. Avoid loading optional mod classes
        // (or their client-only method signatures) on a dedicated server.
        return stack != null && (stack.getItem().getClass() == net.minecraft.item.ItemPickaxe.class || bismuth(stack));
    }
    public static boolean harvest(net.minecraftforge.common.util.FakePlayer player,int x,int y,int z) {
        ItemStack tool = player.getHeldItem();
        if (!singleBlockTool(tool)) return false;
        boolean adapted = bismuth(tool), hadTag = tool.hasTagCompound();
        NBTBase ability = hadTag && tool.getTagCompound().hasKey("ability") ? tool.getTagCompound().getTag("ability").copy() : null;
        // RX-RNTM 2.3: index zero is the null ability; index one is Hammer(1).
        // Apply to the real tool for this harvest, preserving durability and other tags.
        if (adapted) {
            if (!hadTag) tool.setTagCompound(new NBTTagCompound());
            tool.getTagCompound().setInteger("ability",0);
        }
        boolean success = false;
        begin(player,x,y,z);
        try { success = player.theItemInWorldManager.tryHarvestBlock(x,y,z); return success; }
        finally {
            if (adapted && tool.hasTagCompound()) {
                if (ability == null) tool.getTagCompound().removeTag("ability");
                else tool.getTagCompound().setTag("ability",ability);
                if (!hadTag && tool.getTagCompound().hasNoTags()) tool.setTagCompound(null);
            }
            finish(success);
        }
    }
    public static void finish(boolean success) {
        MinerHarvestDrops scope = ACTIVE.get(); ACTIVE.remove();
        if (success && scope != null && scope.event != null && !scope.event.isCanceled())
            CommonEventHandler.dropStoneRewards(scope.event);
    }
}
