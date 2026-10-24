package com.hfr.inventory.container;

import com.hfr.entity.EntityFactionMiner;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.inventory.Container;
import net.minecraft.inventory.Slot;
import net.minecraft.entity.player.InventoryPlayer;
import net.minecraft.item.ItemStack;

/** Normal synchronized slots, with the equipment slot viewing the entity's held stack. */
public final class ContainerMinerNPC extends Container {
    public final EntityFactionMiner worker;
    // Server-owned, per-window approval; closing the container discards it.
    public EntityFactionMiner.Activation replacement;
    public ContainerMinerNPC(InventoryPlayer player, EntityFactionMiner worker) {
        this.worker = worker;
        for (int i = 0; i < 27; i++) addSlotToContainer(new Slot(worker,i,8+i%9*18,81+i/9*18));
        addSlotToContainer(new Slot(worker,27,8,43) {
            public boolean isItemValid(ItemStack stack) { return EntityFactionMiner.pickaxe(stack); }
            public int getSlotStackLimit() { return 1; }
        });
        addSlotToContainer(new Slot(worker,28,116,43) {
            public boolean isItemValid(ItemStack stack) { return EntityFactionMiner.isTorch(stack); }
        });
        for (int i = 0; i < 27; i++) addSlotToContainer(new Slot(player,i+9,8+i%9*18,149+i/9*18));
        for (int i = 0; i < 9; i++) addSlotToContainer(new Slot(player,i,8+i*18,207));
    }
    public boolean canInteractWith(EntityPlayer player) { return player.worldObj.isRemote || worker != null && worker.mayManage(player); }
    public ItemStack transferStackInSlot(EntityPlayer player, int index) {
        if (!canInteractWith(player) || index < 0 || index >= inventorySlots.size()) return null;
        Slot slot = (Slot)inventorySlots.get(index); if (!slot.getHasStack()) return null;
        ItemStack moving = slot.getStack(), original = moving.copy();
        if (index < 29) { if (!mergeItemStack(moving,29,65,true)) return null; }
        else {
            // Vanilla's empty-slot merge ignores Slot.getSlotStackLimit; split equipment explicitly.
            if (EntityFactionMiner.pickaxe(moving) && !((Slot)inventorySlots.get(27)).getHasStack())
                ((Slot)inventorySlots.get(27)).putStack(moving.splitStack(1));
            else if (EntityFactionMiner.isTorch(moving)) mergeItemStack(moving,28,29,false);
            if (moving.stackSize > 0) mergeItemStack(moving,0,27,false);
        }
        if (moving.stackSize == original.stackSize) return null;
        if (moving.stackSize == 0) slot.putStack(null); else slot.onSlotChanged();
        slot.onPickupFromSlot(player,moving); return original;
    }
}
