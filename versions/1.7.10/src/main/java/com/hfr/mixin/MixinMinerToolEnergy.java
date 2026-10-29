package com.hfr.mixin;

import com.hfr.worker.MinerHarvestDrops;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.server.management.ItemInWorldManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(ItemInWorldManager.class)
public abstract class MixinMinerToolEnergy {
    @Redirect(method = "tryHarvestBlock(III)Z", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/item/Item;onBlockStartBreak(Lnet/minecraft/item/ItemStack;IIILnet/minecraft/entity/player/EntityPlayer;)Z",
            remap = false), require = 1, expect = 1, allow = 1)
    private boolean xf$stopDepletedMinerFallback(Item item, ItemStack stack, int x, int y, int z, EntityPlayer player) {
        boolean handled = item.onBlockStartBreak(stack,x,y,z,player);
        // Native AOE may spend the last charge before Forge continues the primary
        // block. Only an active miner scope suppresses that survival fallback.
        return handled || !MinerHarvestDrops.canContinueHarvest(player);
    }
}
