package com.hfr.items;

import java.util.Arrays;
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
                && stack.getTagCompound().hasKey("Miner", 8) && stack.getTagCompound().hasKey("Dimension", 3)
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
    public static final class Selection {
        public final int[] corners;
        public final String error;
        private Selection(int[] corners, String error) { this.corners = corners; this.error = error; }
    }
    /** Never turn an incomplete, invalid or foreign selection into an implicit resume. */
    public static Selection selection(EntityPlayer player, EntityFactionMiner miner) {
        int[] selected = new int[0];
        long revision = -1;
        boolean foreign = false;
        for (int slot = -1; slot < player.inventory.mainInventory.length; slot++) {
            ItemStack stack = slot < 0 ? player.inventory.getItemStack() : player.inventory.mainInventory[slot];
            if (bound(stack,miner) && stack.getItem() == ModItems.miner_area_wand
                    && (stack.getTagCompound().getBoolean("A") || stack.getTagCompound().getBoolean("B")))
                revision = Math.max(revision,stack.getTagCompound().getLong("SelectionRevision"));
        }
        for (int slot = -1; slot < player.inventory.mainInventory.length; slot++) {
            ItemStack stack = slot < 0 ? player.inventory.getItemStack() : player.inventory.mainInventory[slot];
            if (stack == null || stack.getItem() != ModItems.miner_area_wand || !stack.hasTagCompound()) continue;
            NBTTagCompound tag = stack.getTagCompound();
            if (!miner.getUniqueID().toString().equals(tag.getString("Miner"))) {
                if (tag.getBoolean("A") || tag.getBoolean("B")) foreign = true;
                continue;
            }
            if (!tag.getBoolean("A") && !tag.getBoolean("B")) continue;
            long candidateRevision = tag.getLong("SelectionRevision");
            if (bound(stack,miner) && candidateRevision < revision) continue;
            if (!bound(stack, miner)) return new Selection(new int[0], "xf.miner.selection_dimension");
            if (!tag.getBoolean("A") || !tag.getBoolean("B")) return new Selection(new int[0], "xf.miner.select_area");
            for (String prefix : new String[] {"A", "B"}) {
                if (!tag.hasKey(prefix, 1) || !tag.hasKey(prefix+"X", 3) || !tag.hasKey(prefix+"Y", 3) || !tag.hasKey(prefix+"Z", 3)
                        || Math.abs((long)tag.getInteger(prefix+"X")) > 30000000 || Math.abs((long)tag.getInteger(prefix+"Z")) > 30000000
                        || tag.getInteger(prefix+"Y") < 0 || tag.getInteger(prefix+"Y") >= miner.worldObj.getHeight())
                    return new Selection(new int[0], "xf.miner.invalid_area");
            }
            int[] next = corners(tag);
            if (selected.length != 0 && !Arrays.equals(plan(selected), plan(next)))
                return new Selection(new int[0], "xf.miner.selection_ambiguous");
            selected = next;
        }
        return new Selection(selected, selected.length == 0 && foreign ? "xf.miner.selection_other" : "");
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
        event.setCanceled(true); // Both logical sides: no chest open, placement or client breaking animation.
        // Native packets may follow a custom click. They must never resample Sneak or overwrite it.
    }
    public static void select(EntityPlayerMP player, int x, int y, int z, boolean a, boolean down) {
        ItemStack held = player.getHeldItem();
        if (player.worldObj.isRemote || held == null || !(held.getItem() instanceof ItemMinerWand)) return;
        if (!held.hasTagCompound()) return;
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
        if (y < 0 || y >= player.worldObj.getHeight() || Math.abs((long)x) > 30000000 || Math.abs((long)z) > 30000000
                || !player.worldObj.blockExists(x,y,z) || player.getDistanceSq(x+.5,y+.5,z+.5) > 64) return;
        tag.setInteger("Entity",miner.getEntityId());
        ItemMinerWand wand = (ItemMinerWand)held.getItem();
        if (!wand.area) {
            if (!a) player.addChatMessage(new ChatComponentTranslation(miner.selectStation(player,x,y,z), x,y,z, player.dimension));
            return;
        }
        if (!com.hfr.worker.WorkerAccess.ownsLand(player.worldObj,x,z,miner.getFactionId())) {
            player.addChatMessage(new ChatComponentTranslation("xf.worker.denied")); return;
        }
        String prefix = a ? "A" : "B";
        tag.setBoolean(prefix,true); tag.setInteger(prefix+"X",x); tag.setInteger(prefix+"Y",y); tag.setInteger(prefix+"Z",z);
        if (!a) tag.setBoolean("Down",down);
        long observed = tag.getLong("SelectionRevision");
        ItemStack cursor = player.inventory.getItemStack();
        if (bound(cursor,miner)) observed = Math.max(observed,cursor.getTagCompound().getLong("SelectionRevision"));
        for (ItemStack stack : player.inventory.mainInventory)
            if (bound(stack,miner)) observed = Math.max(observed,stack.getTagCompound().getLong("SelectionRevision"));
        tag.setLong("SelectionRevision",miner.nextSelectionRevision(observed));
        player.inventory.markDirty(); player.inventoryContainer.detectAndSendChanges();
        if (player.openContainer != player.inventoryContainer) player.openContainer.detectAndSendChanges();
        player.addChatMessage(new ChatComponentTranslation("xf.miner.corner",prefix,x,y,z));
        if (tag.getBoolean("A") && tag.getBoolean("B")) {
            int[] c = corners(tag), p = plan(c);
            player.addChatMessage(new ChatComponentTranslation("xf.miner.selection",p[3],p[8],p[4],p[5],p[7],p[6],volume(c)));
            player.addChatMessage(new ChatComponentTranslation(c[6] == 1 ? "xf.miner.depth_down" : "xf.miner.depth_corners"));
            player.addChatMessage(new ChatComponentTranslation("xf.miner.depth_bounds",p[7],p[8],volume(c)));
            miner.logSelection("accepted",c[6] == 1,p);
        }
        if (player.openContainer instanceof com.hfr.inventory.container.ContainerMinerNPC
                && ((com.hfr.inventory.container.ContainerMinerNPC)player.openContainer).worker == miner)
            com.hfr.packet.PacketDispatcher.wrapper.sendTo(new com.hfr.packet.builder.MinerSnapshotPacket(miner,player,"",false),player);
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
            if (tag.getBoolean("A") && tag.getBoolean("B")) {
                int[] c = corners(tag), p = plan(c);
                lines.add(StatCollector.translateToLocal(c[6] == 1 ? "xf.miner.depth_down" : "xf.miner.depth_corners"));
                lines.add(StatCollector.translateToLocalFormatted("xf.miner.depth_bounds",p[7],p[8],volume(c)));
            }
        }
    }
}
