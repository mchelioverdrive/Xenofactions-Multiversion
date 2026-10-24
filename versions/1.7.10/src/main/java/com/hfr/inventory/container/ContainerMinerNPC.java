package com.hfr.inventory.container;

import com.hfr.entity.EntityFactionMiner;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.inventory.Container;

/** Management only. Supplies are transferred through the assigned chest, never GUI intents. */
public final class ContainerMinerNPC extends Container {
    public final EntityFactionMiner worker;
    public ContainerMinerNPC(EntityFactionMiner worker) { this.worker = worker; }
    public boolean canInteractWith(EntityPlayer player) { return player.worldObj.isRemote || worker != null && worker.mayManage(player); }
}
