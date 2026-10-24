package com.hfr.handler;

import com.hfr.inventory.container.*;
import net.minecraft.client.gui.inventory.GuiChest;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.inventory.ContainerChest;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.World;

import com.hfr.blocks.ModBlocks;
import com.hfr.inventory.gui.GUIFactory;
import com.hfr.inventory.gui.GUIGrainmill;
import com.hfr.inventory.gui.GUIMachineNet;
import com.hfr.inventory.gui.GUIMachineUni;
import com.hfr.inventory.gui.GUITemple;
import com.hfr.inventory.gui.GuiFlagBig;
import com.hfr.tileentity.clowder.*;
import com.hfr.tileentity.machine.*;

import cpw.mods.fml.common.network.IGuiHandler;

public class GUIHandler implements IGuiHandler {

	@Override
	public Object getServerGuiElement(int ID, EntityPlayer player, World world, int x, int y, int z) {
        if (ID == com.hfr.entity.EntityFactionMiner.GUI_ID) {
            net.minecraft.entity.Entity worker = world.getEntityByID(x);
            return worker instanceof com.hfr.entity.EntityFactionMiner && ((com.hfr.entity.EntityFactionMiner)worker).mayManage(player)
                    ? new com.hfr.inventory.container.ContainerMinerNPC((com.hfr.entity.EntityFactionMiner)worker) : null;
        }
		TileEntity entity = world.getTileEntity(x, y, z);
        if (ID == ModBlocks.guiID_builder || ID == ModBlocks.guiID_builder_npc) {
            com.hfr.tileentity.machine.TileEntityMachineBuilder depot = com.hfr.builder.BuilderGuiResolver.getDepot(world, x, y, z);
            if (depot == null || !(player instanceof net.minecraft.entity.player.EntityPlayerMP)
                    || !com.hfr.builder.BuilderDepotService.mayView((net.minecraft.entity.player.EntityPlayerMP)player, depot)) return null;
            if (ID == ModBlocks.guiID_builder) return depot.isUseableByPlayer(player) ? new com.hfr.inventory.container.ContainerMachineBuilder(player.inventory, depot) : null;
            com.hfr.entity.EntityFactionBuilder builder = com.hfr.builder.BuilderGuiResolver.getAssignedBuilder(depot);
            return builder != null && builder.isUseableByPlayer(player)
                    ? new com.hfr.inventory.container.ContainerBuilderNPC(player.inventory, depot, builder) : null;
        }
        if (ID == com.hfr.ender.EnderChestInteraction.GUI_ID) {
            if (world.getBlock(x, y, z) != net.minecraft.init.Blocks.ender_chest || !(entity instanceof net.minecraft.tileentity.TileEntityEnderChest)) return null;
            player.getInventoryEnderChest().func_146031_a((net.minecraft.tileentity.TileEntityEnderChest) entity);
            return world.isRemote ? new GuiChest(player.inventory, player.getInventoryEnderChest())
                : new com.hfr.ender.RestrictedEnderChestContainer(player.inventory, player.getInventoryEnderChest());
        }
		switch(ID)
		{

			case ModBlocks.guiID_net:
			{
				if(entity instanceof TileEntityMachineNet)
				{
					return new ContainerMachineNet(player.inventory, (TileEntityMachineNet) entity);
				}
				return null;
			}

			case ModBlocks.guiID_uni:
			{
				if(entity instanceof TileEntityMachineUni)
				{
					return new ContainerMachineUni(player.inventory, (TileEntityMachineUni) entity);
				}
				return null;
			}

			case ModBlocks.guiID_grainmill:
			{
				if(entity instanceof TileEntityMachineGrainmill)
				{
					return new ContainerGrainMill(player.inventory, (TileEntityMachineGrainmill) entity);
				}
				return null;
			}

			case ModBlocks.guiID_factory:
			{
				if(entity instanceof TileEntityMachineFactory)
				{
					return new ContainerFactory(player.inventory, (TileEntityMachineFactory) entity);
				}
				return null;
			}

			case ModBlocks.guiID_temple:
			{
				if(entity instanceof TileEntityMachineTemple)
				{
					return new ContainerTemple(player.inventory, (TileEntityMachineTemple) entity);
				}
				return null;
			}

			case ModBlocks.guiID_chest:
			{
				if(entity instanceof TileEntityOfficerChest)
				{
					return new ContainerChest(player.inventory, (TileEntityOfficerChest) entity);
				}
				return null;
			}

			case ModBlocks.guiID_flag_big:
			{
				if(entity instanceof TileEntityFlagBig)
				{
					return new ContainerFlagBig(player.inventory, (TileEntityFlagBig) entity);
				}
				return null;
			}

		}
		return null;
	}

	@Override
	public Object getClientGuiElement(int ID, EntityPlayer player, World world, int x, int y, int z) {
        if (ID == com.hfr.entity.EntityFactionMiner.GUI_ID) {
            net.minecraft.entity.Entity worker = world.getEntityByID(x);
            return worker instanceof com.hfr.entity.EntityFactionMiner ? new com.hfr.inventory.gui.GUIMinerNPC((com.hfr.entity.EntityFactionMiner)worker) : null;
        }
		TileEntity entity = world.getTileEntity(x, y, z);
        if (ID == ModBlocks.guiID_builder || ID == ModBlocks.guiID_builder_npc) {
            com.hfr.tileentity.machine.TileEntityMachineBuilder depot = com.hfr.builder.BuilderGuiResolver.getDepot(world, x, y, z);
            if (ID == ModBlocks.guiID_builder) return depot == null ? null : new com.hfr.inventory.gui.GUIMachineBuilder(player.inventory, depot);
            return new com.hfr.inventory.gui.GUIBuilderNPC(player.inventory, world.provider.dimensionId, x, y, z, depot,
                    com.hfr.builder.BuilderGuiResolver.getAssignedBuilder(depot));
        }
        if (ID == com.hfr.ender.EnderChestInteraction.GUI_ID) {
            if (world.getBlock(x, y, z) != net.minecraft.init.Blocks.ender_chest || !(entity instanceof net.minecraft.tileentity.TileEntityEnderChest)) return null;
            player.getInventoryEnderChest().func_146031_a((net.minecraft.tileentity.TileEntityEnderChest) entity);
            return world.isRemote ? new GuiChest(player.inventory, player.getInventoryEnderChest())
                : new com.hfr.ender.RestrictedEnderChestContainer(player.inventory, player.getInventoryEnderChest());
        }
		if(entity != null)
		{
			switch(ID)
			{
				
				case ModBlocks.guiID_net:
				{
					if(entity instanceof TileEntityMachineNet)
					{
						return new GUIMachineNet(player.inventory, (TileEntityMachineNet) entity);
					}
					return null;
				}
				
				case ModBlocks.guiID_uni:
				{
					if(entity instanceof TileEntityMachineUni)
					{
						return new GUIMachineUni(player.inventory, (TileEntityMachineUni) entity);
					}
					return null;
				}
				
				case ModBlocks.guiID_grainmill:
				{
					if(entity instanceof TileEntityMachineGrainmill)
					{
						return new GUIGrainmill(player.inventory, (TileEntityMachineGrainmill) entity);
					}
					return null;
				}
				
				case ModBlocks.guiID_factory:
				{
					if(entity instanceof TileEntityMachineFactory)
					{
						return new GUIFactory(player.inventory, (TileEntityMachineFactory) entity);
					}
					return null;
				}
				
				case ModBlocks.guiID_temple:
				{
					if(entity instanceof TileEntityMachineTemple)
					{
						return new GUITemple(player.inventory, (TileEntityMachineTemple) entity);
					}
					return null;
				}

				case ModBlocks.guiID_chest:
				{
					if(entity instanceof TileEntityOfficerChest)
					{
						return new GuiChest(player.inventory, (TileEntityOfficerChest) entity);
					}
					return null;
				}

				case ModBlocks.guiID_flag_big:
				{
					if(entity instanceof TileEntityFlagBig)
					{
						return new GuiFlagBig(player.inventory, (TileEntityFlagBig) entity);
					}
					return null;
				}
				
				case ModBlocks.guiID_market:
				{
					// Shop screens are opened exclusively by validated server snapshots.
					return null;
				}
				
			}
		}
		return null;
	}

}
