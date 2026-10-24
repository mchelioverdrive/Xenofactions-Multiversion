package com.hfr.items;

import java.util.List;
import java.util.UUID;
import com.hfr.clowder.Clowder;
import com.hfr.entity.EntityFarmer;
import com.hfr.entity.EntityFactionMiner;
import com.hfr.worker.WorkerAccess;
import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;
import net.minecraft.creativetab.CreativeTabs;
import net.minecraft.entity.EntityLiving;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.util.ChatComponentTranslation;
import net.minecraft.util.Facing;
import net.minecraft.world.World;
import net.minecraftforge.common.util.FakePlayer;

/** Native creative/admin-distributed contracts, like the recovered Builder Contract. */
public final class ItemWorkerContract extends Item {
    private final boolean miner;
    public ItemWorkerContract(boolean miner) { this.miner = miner; setHasSubtypes(!miner); }
    @SideOnly(Side.CLIENT)
    public void getSubItems(Item item, CreativeTabs tab, List stacks) {
        for (int i = 0; i < (miner ? 1 : 3); i++) stacks.add(new ItemStack(item, 1, i));
    }
    @SideOnly(Side.CLIENT)
    public void addInformation(ItemStack stack, EntityPlayer player, List lines, boolean advanced) {
        lines.add(net.minecraft.client.resources.I18n.format("xf.worker.contract"));
        lines.add(net.minecraft.client.resources.I18n.format(miner ? "xf.worker.miner" : "xf.worker.farmer"));
        if (!miner) lines.add(net.minecraft.client.resources.I18n.format("xf.worker.farmer." + Math.max(0, Math.min(2, stack.getItemDamage()))));
    }
    public boolean onItemUse(ItemStack stack, EntityPlayer player, World world, int x, int y, int z,
            int side, float hitX, float hitY, float hitZ) {
        if (world.isRemote) return true;
        if (!(player instanceof EntityPlayerMP) || player instanceof FakePlayer || side < 0 || side > 5) return false;
        Clowder faction = Clowder.getClowderFromPlayer(player);
        x += Facing.offsetsXForSide[side]; y += Facing.offsetsYForSide[side]; z += Facing.offsetsZForSide[side];
        if (faction == null || faction.getPermLevel(player) < 2
                || !WorkerAccess.ownsLand(world, x, z, UUID.fromString(faction.uuid))
                || !player.canPlayerEdit(x, y, z, side, stack)) {
            player.addChatMessage(new ChatComponentTranslation("xf.worker.denied")); return true;
        }
        if (y < 1 || y + 1 >= world.getHeight() || !world.blockExists(x, y + 1, z)
                || !world.isAirBlock(x, y, z) || !world.isAirBlock(x, y + 1, z)) return false;
        EntityLiving worker;
        if (miner) {
            EntityFactionMiner next = new EntityFactionMiner(world);
            next.assign(UUID.fromString(faction.uuid), player.getUniqueID(), player.getCommandSenderName()); worker = next;
        } else {
            EntityFarmer next = new EntityFarmer(world);
            next.assign(UUID.fromString(faction.uuid)); next.setType(stack.getItemDamage()); worker = next;
        }
        worker.setLocationAndAngles(x + .5, y, z + .5, player.rotationYaw, 0);
        if (stack.hasDisplayName()) worker.setCustomNameTag(stack.getDisplayName());
        if (world.spawnEntityInWorld(worker) && !player.capabilities.isCreativeMode) --stack.stackSize;
        return true;
    }
}
