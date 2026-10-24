package com.hfr.items;

import java.util.List;
import com.hfr.entity.EntityFactionMiner;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.ChatComponentTranslation;
import net.minecraft.util.StatCollector;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;

/** Server-owned selection lives on the bound item and follows ordinary inventory synchronization. */
public final class ItemMinerWand extends Item {
    private final boolean area;
    public ItemMinerWand(boolean area) { this.area = area; setMaxStackSize(1); }
    @Override public boolean onBlockStartBreak(ItemStack stack,int x,int y,int z,EntityPlayer player) { return true; }
    public static boolean bound(ItemStack stack, EntityFactionMiner miner) {
        return stack != null && stack.getItem() instanceof ItemMinerWand && stack.hasTagCompound()
                && miner.getUniqueID().toString().equals(stack.getTagCompound().getString("Miner"))
                && miner.dimension == stack.getTagCompound().getInteger("Dimension");
    }
    private static ItemStack find(EntityPlayer player, Item item) {
        ItemStack cursor = player.inventory.getItemStack();
        if (cursor != null && cursor.getItem() == item) return cursor;
        for (ItemStack stack : player.inventory.mainInventory) if (stack != null && stack.getItem() == item) return stack;
        return null;
    }
    public static String give(EntityPlayer player, EntityFactionMiner miner, boolean recover) {
        if (player.worldObj.isRemote || !miner.mayManage(player)) return "xf.worker.denied";
        NBTTagCompound data = player.getEntityData();
        if (!data.hasKey(EntityPlayer.PERSISTED_NBT_TAG)) data.setTag(EntityPlayer.PERSISTED_NBT_TAG, new NBTTagCompound());
        NBTTagCompound persisted = data.getCompoundTag(EntityPlayer.PERSISTED_NBT_TAG);
        NBTTagCompound issued = persisted.getCompoundTag("MinerWands");
        String key = miner.getUniqueID().toString();
        if (!recover && issued.getBoolean(key)) return "";
        Item[] types = {ModItems.miner_station_wand, ModItems.miner_area_wand};
        int needed = 0, free = 0;
        for (Item item : types) if (find(player, item) == null) needed++;
        for (ItemStack stack : player.inventory.mainInventory) if (stack == null) free++;
        if (needed > free) { player.addChatMessage(new ChatComponentTranslation("xf.miner.wands_full", needed)); return "xf.miner.wands_full_gui"; }
        for (Item item : types) {
            ItemStack stack = find(player, item);
            if (stack == null) {
                stack = new ItemStack(item);
                // Space was checked for both items before any mutation; never drop overflow.
                int slot = player.inventory.getFirstEmptyStack();
                player.inventory.setInventorySlotContents(slot, stack);
            }
            if (!bound(stack, miner)) {
                NBTTagCompound tag = new NBTTagCompound(); tag.setString("Miner", key); tag.setInteger("Dimension", miner.dimension);
                tag.setString("MinerName", miner.getCommandSenderName()); stack.setTagCompound(tag);
            }
            stack.getTagCompound().setInteger("Entity", miner.getEntityId());
        }
        issued.setBoolean(key, true); persisted.setTag("MinerWands", issued);
        player.inventory.markDirty(); player.inventoryContainer.detectAndSendChanges();
        player.addChatMessage(new ChatComponentTranslation("xf.miner.wands_ready"));
        return "xf.miner.wands_ready";
    }
    public static int[] selection(EntityPlayer player, EntityFactionMiner miner) {
        ItemStack stack = player.inventory.getItemStack();
        if (!bound(stack, miner) || stack.getItem() != ModItems.miner_area_wand) {
            stack = null;
            for (ItemStack candidate : player.inventory.mainInventory)
                if (bound(candidate, miner) && candidate.getItem() == ModItems.miner_area_wand) { stack = candidate; break; }
        }
        if (stack == null) return new int[0];
        NBTTagCompound tag = stack.getTagCompound();
        if (!tag.getBoolean("A") || !tag.getBoolean("B")) return new int[0];
        return corners(tag);
    }
    public static int[] corners(NBTTagCompound tag) {
        return new int[] {tag.getInteger("AX"), tag.getInteger("AY"), tag.getInteger("AZ"),
                tag.getInteger("BX"), tag.getInteger("BY"), tag.getInteger("BZ"), tag.getBoolean("Down") ? 1 : 0};
    }
    public static int[] plan(int[] c) {
        return new int[] {0,0,0,Math.min(c[0],c[3]),Math.min(c[2],c[5]),Math.max(c[0],c[3]),Math.max(c[2],c[5]),Math.max(c[1],c[4]),c[6] == 1 ? 0 : Math.min(c[1],c[4])};
    }
    public static long volume(int[] c) {
        int[] p = plan(c);
        return ((long)p[5]-p[3]+1)*((long)p[6]-p[4]+1)*((long)p[7]-p[8]+1);
    }
    public static void interact(PlayerInteractEvent event) {
        EntityPlayer player = event.entityPlayer; ItemStack held = player.getHeldItem();
        if (held == null || !(held.getItem() instanceof ItemMinerWand)) return;
        if (event.action != PlayerInteractEvent.Action.LEFT_CLICK_BLOCK && event.action != PlayerInteractEvent.Action.RIGHT_CLICK_BLOCK) return;
        boolean alreadyCanceled = event.isCanceled();
        event.setCanceled(true); // Both logical sides: no chest open, placement or client breaking animation.
        if (player.worldObj.isRemote || alreadyCanceled) return;
        if (!(player instanceof EntityPlayerMP) || !held.hasTagCompound()) return;
        NBTTagCompound tag = held.getTagCompound();
        EntityFactionMiner miner = null;
        if (player.dimension == tag.getInteger("Dimension")) {
            Entity entity = player.worldObj.getEntityByID(tag.getInteger("Entity"));
            if (entity instanceof EntityFactionMiner && bound(held,(EntityFactionMiner)entity)) miner = (EntityFactionMiner)entity;
            // Entity IDs change on load; use a bounded fallback without loading chunks.
            for (int i = 0; miner == null && i < Math.min(4096,player.worldObj.loadedEntityList.size()); i++) {
                Object value = player.worldObj.loadedEntityList.get(i);
                if (value instanceof EntityFactionMiner && bound(held,(EntityFactionMiner)value)) miner = (EntityFactionMiner)value;
            }
        }
        if (miner == null || !miner.maySelect(player)) { player.addChatMessage(new ChatComponentTranslation("xf.miner.wand_denied")); return; }
        if (!player.worldObj.blockExists(event.x,event.y,event.z) || player.getDistanceSq(event.x+.5,event.y+.5,event.z+.5) > 64) return;
        tag.setInteger("Entity",miner.getEntityId());
        ItemMinerWand wand = (ItemMinerWand)held.getItem();
        if (!wand.area) {
            if (event.action == PlayerInteractEvent.Action.RIGHT_CLICK_BLOCK)
                player.addChatMessage(new ChatComponentTranslation(miner.selectStation(player,event.x,event.y,event.z), event.x,event.y,event.z, player.dimension));
            return;
        }
        if (!com.hfr.worker.WorkerAccess.ownsLand(player.worldObj,event.x,event.z,miner.getFactionId())) {
            player.addChatMessage(new ChatComponentTranslation("xf.worker.denied")); return;
        }
        boolean a = event.action == PlayerInteractEvent.Action.LEFT_CLICK_BLOCK;
        String prefix = a ? "A" : "B";
        tag.setBoolean(prefix,true); tag.setInteger(prefix+"X",event.x); tag.setInteger(prefix+"Y",event.y); tag.setInteger(prefix+"Z",event.z);
        if (!a) tag.setBoolean("Down",player.isSneaking());
        player.inventory.markDirty(); player.inventoryContainer.detectAndSendChanges();
        player.addChatMessage(new ChatComponentTranslation("xf.miner.corner",prefix,event.x,event.y,event.z));
        if (tag.getBoolean("A") && tag.getBoolean("B")) {
            int[] c = corners(tag), p = plan(c);
            player.addChatMessage(new ChatComponentTranslation("xf.miner.selection",p[3],p[8],p[4],p[5],p[7],p[6],volume(c)));
        }
    }
    @SideOnly(Side.CLIENT)
    @Override public void addInformation(ItemStack stack, EntityPlayer player, List lines, boolean advanced) {
        if (stack.hasTagCompound()) {
            NBTTagCompound tag = stack.getTagCompound();
            lines.add(StatCollector.translateToLocalFormatted("xf.miner.bound",tag.getString("MinerName"),tag.getInteger("Dimension")));
            lines.add(tag.getString("Miner"));
        }
        lines.add(StatCollector.translateToLocal(area ? "xf.miner.area_controls" : "xf.miner.station_controls"));
        if (area && stack.hasTagCompound()) {
            NBTTagCompound tag = stack.getTagCompound();
            for (String p : new String[]{"A","B"}) if (tag.getBoolean(p))
                lines.add(p+": "+tag.getInteger(p+"X")+", "+tag.getInteger(p+"Y")+", "+tag.getInteger(p+"Z"));
            if (tag.getBoolean("A") && tag.getBoolean("B")) lines.add(StatCollector.translateToLocalFormatted("xf.miner.volume",volume(corners(tag))));
        }
    }
}
