package com.hfr.items;

import com.hfr.clowder.CityCenterRelocationManager;
import com.hfr.blocks.clowder.Flag;
import com.hfr.tileentity.clowder.TileEntityFlag;

import net.minecraft.block.Block;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemBlock;
import net.minecraft.item.ItemStack;
import net.minecraft.world.World;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.ChatComponentText;
import net.minecraftforge.common.util.BlockSnapshot;
import net.minecraftforge.common.util.ForgeDirection;
import net.minecraftforge.event.ForgeEventFactory;

/** Preserves the existing block item ID while intercepting relocation tokens before normal founding. */
public class ItemBlockCityCenter extends ItemBlock {
    private static final ThreadLocal<BlockSnapshot> foundingPlacement=new ThreadLocal<BlockSnapshot>();
    public static boolean isFoundingPlacement(BlockSnapshot snapshot){return snapshot==foundingPlacement.get();}
    public ItemBlockCityCenter(Block block) { super(block); setMaxStackSize(1); }

    @Override
    public boolean placeBlockAt(ItemStack stack,EntityPlayer player,World world,int x,int y,int z,int side,float hitX,float hitY,float hitZ,int metadata){
        // Leave client inventory/block prediction to the authoritative placement response.
        if(world.isRemote)return false;
        if(foundingPlacement.get()!=null)return false;
        if(!world.getChunkProvider().chunkExists(x>>4,z>>4))return reject(player,"Destination chunk is not loaded; stay nearby and retry.");
        String error=Flag.foundingError(world,x,y,z,player,stack);
        if(error!=null)return reject(player,error);
        if(!world.canMineBlock(player,x,y,z)||!player.canPlayerEdit(x,y,z,side,stack))return reject(player,"You do not have permission to place a City Center here.");

        BlockSnapshot snapshot=BlockSnapshot.getBlockSnapshot(world,x,y,z);
        boolean capturing=world.captureBlockSnapshots,restoring=world.restoringBlockSnapshots,success=false;
        // Handle this single block's Forge approval here, before founding. Do not leave a second
        // snapshot for ForgeHooks to cancel after prestige and claims have already been committed.
        world.captureBlockSnapshots=false;foundingPlacement.set(snapshot);
        try {
            if(!super.placeBlockAt(stack,player,world,x,y,z,side,hitX,hitY,hitZ,metadata))return reject(player,"The City Center block could not be placed.");
            if(ForgeEventFactory.onPlayerBlockPlace(player,snapshot,ForgeDirection.getOrientation(side)).isCanceled())
                return reject(player,"City Center placement was denied by block protection.");
            TileEntity tile=world.getTileEntity(x,y,z);
            if(world.getBlock(x,y,z)!=field_150939_a || !(tile instanceof TileEntityFlag))return reject(player,"The City Center block changed during placement; retry.");
            if(stack.stackSize<1 || stack.getItem()!=this || player.getCurrentEquippedItem()!=stack
                || !world.canMineBlock(player,x,y,z)||!player.canPlayerEdit(x,y,z,side,stack))
                return reject(player,"You do not have permission or an available City Center to complete placement.");
            error=Flag.foundingError(world,x,y,z,player,stack);
            if(error!=null)return reject(player,error);
            Flag.foundCity(world,x,y,z,player,stack,(TileEntityFlag)tile);
            com.hfr.main.XFAchievements.cityFounded(player);
            success=true;return true; // ItemBlock consumes the item only for a successful commit.
        } catch(RuntimeException failure) {
            com.hfr.main.MainRegistry.logger.error("City Center founding failed; rolling back placement",failure);
            return reject(player,"City Center founding failed; placement was restored. See the server log.");
        } finally {
            try {
                if(!success){world.restoringBlockSnapshots=true;snapshot.restore(true,false);}
            } finally {
                foundingPlacement.remove();world.captureBlockSnapshots=capturing;world.restoringBlockSnapshots=restoring;
            }
        }
    }

    private static boolean reject(EntityPlayer player,String reason){player.addChatMessage(new ChatComponentText(reason));return false;}

    @Override
    public boolean onItemUse(ItemStack stack, EntityPlayer player, World world, int x, int y, int z, int side, float hitX, float hitY, float hitZ) {
        if(!CityCenterRelocationManager.isToken(stack)) {
            int before = stack.stackSize;
            boolean placed = super.onItemUse(stack, player, world, x, y, z, side, hitX, hitY, hitZ);
            // Queue confirmation after consumption, outside block/faction rollback. Saving waits
            // until Forge finalizes inventory bookkeeping, including creative-mode restoration.
            // ItemBlock.onItemUse also returns true when placeBlockAt rejects; consumption proves acceptance.
            if (!world.isRemote && stack.stackSize < before) com.hfr.clowder.FactionSetupGuide.cityFounded(player);
            return placed;
        }
        Block clicked=world.getBlock(x,y,z);
        if(!clicked.isReplaceable(world,x,y,z)) {
            if(side==0)y--; else if(side==1)y++; else if(side==2)z--; else if(side==3)z++; else if(side==4)x--; else if(side==5)x++;
        }
        return CityCenterRelocationManager.relocate(player, stack, world, x, y, z);
    }
}
