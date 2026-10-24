package com.hfr.entity;

import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import com.hfr.builder.BuilderPlacement;
import com.hfr.clowder.Clowder;
import com.hfr.main.MainRegistry;
import com.hfr.util.XFLog;
import com.hfr.worker.WorkerAccess;
import com.mojang.authlib.GameProfile;
import cpw.mods.fml.common.network.internal.FMLNetworkHandler;
import net.minecraft.block.Block;
import net.minecraft.entity.EntityLiving;
import net.minecraft.entity.SharedMonsterAttributes;
import net.minecraft.entity.item.EntityItem;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.inventory.IInventory;
import net.minecraft.item.ItemPickaxe;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.network.NetworkManager;
import net.minecraft.network.NetHandlerPlayServer;
import net.minecraft.network.Packet;
import net.minecraft.server.MinecraftServer;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.AxisAlignedBB;
import net.minecraft.util.ChatComponentTranslation;
import net.minecraft.world.World;
import net.minecraft.world.WorldServer;
import net.minecraft.world.WorldSettings;
import net.minecraftforge.common.util.FakePlayer;

/** Native persistent worker. Mining uses Forge's survival harvesting path, not generated loot. */
public final class EntityFactionMiner extends EntityLiving {
    public static final int GUI_ID = 42, PLAN_SIZE = 9;
    private UUID factionId, employerId;
    private String employerName = "", status = "idle";
    // chest X/Y/Z, min X/Z, max X/Z, top Y, bottom Y
    private int[] plan = new int[PLAN_SIZE];
    private int cursor, pathTicks, blockedTicks, jobDimension;
    private double pathDistance = Double.MAX_VALUE;
    private boolean configured, running, returning, toolBroken;
    private int[] pendingDrops;
    private final ItemStack[] cargo = new ItemStack[27];
    private float miningProgress;
    private Block miningBlock;
    private int miningMeta;
    private FakePlayer actor;

    public EntityFactionMiner(World world) {
        super(world); setSize(.6F, 1.8F); stepHeight = 1F;
        getNavigator().setCanSwim(true);
    }
    protected void applyEntityAttributes() {
        super.applyEntityAttributes();
        getEntityAttribute(SharedMonsterAttributes.maxHealth).setBaseValue(20);
        getEntityAttribute(SharedMonsterAttributes.movementSpeed).setBaseValue(.28);
    }
    protected boolean canDespawn() { return false; }
    protected boolean isAIEnabled() { return true; }
    public void assign(UUID faction, UUID employer, String name) { factionId = faction; employerId = employer; employerName = name; }
    public boolean mayManage(EntityPlayer player) {
        return !isDead && player.worldObj == worldObj && player.getDistanceSqToEntity(this) <= 64
                && WorkerAccess.member(player, factionId);
    }
    public boolean interact(EntityPlayer player) {
        if (worldObj.isRemote) return true;
        if (!mayManage(player)) { player.addChatMessage(new ChatComponentTranslation("xf.worker.denied")); return true; }
        FMLNetworkHandler.openGui(player, MainRegistry.instance, GUI_ID, worldObj, getEntityId(), 0, 0);
        return true;
    }
    public void pause() { running = false; status = "paused"; resetPath(); }
    private void stop(String reason) { running = false; status = reason; resetPath(); }
    private void resetPath() { getNavigator().clearPathEntity(); pathTicks = blockedTicks = 0; pathDistance = Double.MAX_VALUE; miningProgress = 0; miningBlock = null; }
    public String configure(EntityPlayerMP player, int[] proposed) {
        if (!mayManage(player) || proposed.length != PLAN_SIZE) return "xf.worker.denied";
        int[] next = proposed.clone();
        long width = (long)next[5] - next[3] + 1, length = (long)next[6] - next[4] + 1, depth = (long)next[7] - next[8] + 1;
        if (width < 1 || width > 64 || length < 1 || length > 64 || depth < 1 || depth > 128
                || width * length * depth > 262144 || next[8] < 1 || next[7] >= worldObj.getHeight()
                || next[1] < 0 || next[1] >= worldObj.getHeight()) return "xf.miner.invalid_area";
        for (int i : new int[] {0, 2, 3, 4, 5, 6}) if (Math.abs((long)next[i]) > 30000000) return "xf.miner.invalid_area";
        if (Math.abs((long)next[3] - next[0]) > 128 || Math.abs((long)next[5] - next[0]) > 128
                || Math.abs((long)next[4] - next[2]) > 128 || Math.abs((long)next[6] - next[2]) > 128) return "xf.miner.invalid_area";
        if (!WorkerAccess.ownsLand(worldObj, next[0], next[2], factionId)) return "xf.worker.denied";
        for (int x = next[3]; x <= next[5]; x++) for (int z = next[4]; z <= next[6]; z++)
            if (!WorkerAccess.ownsLand(worldObj, x, z, factionId)) return "xf.worker.denied";
        if (!worldObj.blockExists(next[0], next[1], next[2]) || !station(worldObj.getTileEntity(next[0], next[1], next[2]))) return "xf.miner.no_chest";
        if (!configured || jobDimension != dimension || !Arrays.equals(plan, next)) {
            if (pendingDrops != null) return "xf.miner.pending_drops";
            cursor = 0;
        }
        plan = next; jobDimension = dimension; configured = true; running = true; returning = hasCargo(); status = "working";
        employerId = player.getUniqueID(); employerName = player.getCommandSenderName(); actor = null; toolBroken = false; resetPath();
        return "";
    }
    public void onLivingUpdate() {
        super.onLivingUpdate();
        if (worldObj.isRemote || !running || ticksExisted % 10 != 0) return;
        try { work(); }
        catch (RuntimeException error) { stop("error"); XFLog.error("Miner stopped: " + getUniqueID(), error); }
    }
    private boolean authorized() {
        Clowder faction = factionId == null ? null : Clowder.getClowderFromUUID(factionId.toString());
        return faction != null && employerId != null && faction.getRole(employerId) != null && !faction.isInfrastructureDisabled();
    }
    private void work() {
        if (!configured || !authorized()) { stop("denied"); return; }
        if (jobDimension != dimension) { stop("dimension"); return; }
        if (!WorkerAccess.ownsLand(worldObj, plan[0], plan[2], factionId)) { stop("denied"); return; }
        if (returning || getHeldItem() == null || cargoFull()) { visitChest(); return; }
        if (!pickaxe(getHeldItem())) { stop("no_tool"); return; }
        if (pendingDrops != null) {
            int x = pendingDrops[0], y = pendingDrops[1], z = pendingDrops[2];
            if (!worldObj.blockExists(x, y, z)) { status = "chunk"; return; }
            if (!WorkerAccess.ownsLand(worldObj, x, z, factionId)) { stop("denied"); return; }
            if (!moveNear(x, y + 1, z)) return;
            if (collectDrops(x, y, z)) pendingDrops = null;
            returning = hasCargo(); return;
        }
        int width = plan[5] - plan[3] + 1, length = plan[6] - plan[4] + 1, layer = width * length;
        int total = layer * (plan[7] - plan[8] + 1);
        for (int scans = 0; scans < 64 && cursor < total; scans++) {
            int x = plan[3] + cursor % width, z = plan[4] + cursor / width % length, y = plan[7] - cursor / layer;
            if (!worldObj.blockExists(x, y, z)) { status = "chunk"; return; }
            Block block = worldObj.getBlock(x, y, z);
            if (worldObj.isAirBlock(x, y, z)) { cursor++; miningProgress = 0; miningBlock = null; continue; }
            if (!WorkerAccess.ownsLand(worldObj, x, z, factionId) || BuilderPlacement.protectedBlock(worldObj, x, y, z)
                    || worldObj.getTileEntity(x, y, z) != null) { stop("protected"); return; }
            if (block.getMaterial().isLiquid()) { stop("liquid"); return; }
            if (block.getBlockHardness(worldObj, x, y, z) < 0) { stop("protected"); return; }
            if (!moveNear(x, y + 1, z)) return;
            net.minecraft.util.MovingObjectPosition hit = worldObj.func_147447_a(
                    net.minecraft.util.Vec3.createVectorHelper(posX, posY + getEyeHeight(), posZ),
                    net.minecraft.util.Vec3.createVectorHelper(x + .5, y + .5, z + .5), false, true, false);
            if (hit != null && (hit.blockX != x || hit.blockY != y || hit.blockZ != z)) {
                miningProgress = 0; blockedTicks += 10;
                if (blockedTicks >= 100) stop("unreachable");
                else { status = "travel"; getNavigator().tryMoveToXYZ(x + .5, y + 1, z + .5, 1); }
                return;
            }
            blockedTicks = 0;
            FakePlayer player = actor();
            player.setPosition(posX, posY, posZ); player.onGround = onGround;
            player.inventory.mainInventory[0] = getHeldItem(); player.inventory.currentItem = 0;
            int meta = worldObj.getBlockMetadata(x, y, z);
            if (!block.canHarvestBlock(player, meta)) { stop("tool_level"); return; }
            if (miningBlock != block || miningMeta != meta) { miningBlock = block; miningMeta = meta; miningProgress = 0; }
            status = "mining"; swingItem();
            miningProgress += block.getPlayerRelativeBlockHardness(player, worldObj, x, y, z) * 10;
            if (miningProgress < 1) return;
            // Forge posts BreakEvent here, including XF stone drops and protection listeners.
            boolean harvested = player.theItemInWorldManager.tryHarvestBlock(x, y, z);
            setCurrentItemOrArmor(0, player.inventory.mainInventory[0]); player.inventory.mainInventory[0] = null;
            miningProgress = 0; miningBlock = null;
            if (!harvested) { stop("protected"); return; }
            if (!collectDrops(x, y, z)) { pendingDrops = new int[] {x, y, z}; returning = true; }
            cursor++; resetPath();
            if (getHeldItem() == null) { returning = toolBroken = true; status = "tool_broken"; }
            if (cargoFull()) returning = true;
            return;
        }
        if (cursor >= total) { returning = true; visitChest(); }
    }
    private FakePlayer actor() {
        if (actor == null) {
            actor = new FakePlayer((WorldServer)worldObj, new GameProfile(employerId, employerName));
            // Forge 1.7.10's harvesting hooks send block updates even for fake players.
            new NetHandlerPlayServer(MinecraftServer.getServer(), new NetworkManager(false), actor) {
                public void sendPacket(Packet packet) { }
            };
            actor.theItemInWorldManager.setGameType(WorldSettings.GameType.SURVIVAL);
        }
        return actor;
    }
    private boolean moveNear(int x, int y, int z) {
        double distance = getDistanceSq(x + .5, y, z + .5);
        if (distance <= 9) { getNavigator().clearPathEntity(); pathTicks = 0; pathDistance = Double.MAX_VALUE; return true; }
        miningProgress = 0;
        if (distance < pathDistance - .1) { pathTicks = 0; pathDistance = distance; } else pathTicks += 10;
        if (pathTicks >= 100) { stop("unreachable"); return false; }
        if (getNavigator().noPath()) getNavigator().tryMoveToXYZ(x + .5, y, z + .5, 1);
        status = "travel"; return false;
    }
    private void visitChest() {
        if (!worldObj.blockExists(plan[0], plan[1], plan[2])) { status = "chunk"; return; }
        TileEntity tile = worldObj.getTileEntity(plan[0], plan[1], plan[2]);
        if (!station(tile)) { stop("no_chest"); return; }
        if (!moveNear(plan[0], plan[1], plan[2])) return;
        IInventory chest = (IInventory)tile;
        for (ItemStack stack : cargo) if (stack != null) insert(chest, stack);
        for (int i = 0; i < cargo.length; i++) if (cargo[i] != null && cargo[i].stackSize <= 0) cargo[i] = null;
        chest.markDirty();
        if (hasCargo()) { stop("storage_full"); return; }
        if (toolBroken) { toolBroken = false; stop("tool_broken"); return; }
        int total = (plan[5] - plan[3] + 1) * (plan[6] - plan[4] + 1) * (plan[7] - plan[8] + 1);
        if (cursor >= total && pendingDrops == null) { stop("complete"); return; }
        if (getHeldItem() == null) {
            for (int i = firstSlot(chest); i < chest.getSizeInventory(); i++) {
                ItemStack stack = chest.getStackInSlot(i);
                if (pickaxe(stack)) { setCurrentItemOrArmor(0, chest.decrStackSize(i, 1)); chest.markDirty(); break; }
            }
            if (getHeldItem() == null) { stop("no_tool"); return; }
        }
        returning = false; status = "working"; resetPath();
    }
    private static boolean pickaxe(ItemStack stack) {
        return stack != null && stack.stackSize > 0 && (stack.getItem() instanceof ItemPickaxe
                || stack.getItem().getToolClasses(stack).contains("pickaxe"));
    }
    private boolean station(TileEntity tile) {
        return tile != null && (tile.getClass() == net.minecraft.tileentity.TileEntityChest.class
                || tile instanceof com.hfr.tileentity.machine.TileEntityMachineBuilder
                && factionId != null && factionId.equals(((com.hfr.tileentity.machine.TileEntityMachineBuilder)tile).getFactionId()));
    }
    private static int firstSlot(IInventory inventory) { return inventory instanceof com.hfr.tileentity.machine.TileEntityMachineBuilder ? 1 : 0; }
    private static void insert(IInventory inventory, ItemStack moving) {
        for (int pass = 0; pass < 2 && moving.stackSize > 0; pass++) for (int i = firstSlot(inventory); i < inventory.getSizeInventory() && moving.stackSize > 0; i++) {
            if (!inventory.isItemValidForSlot(i, moving)) continue;
            ItemStack stored = inventory.getStackInSlot(i);
            if (pass == 0 && (stored == null || !stored.isItemEqual(moving) || !ItemStack.areItemStackTagsEqual(stored, moving))) continue;
            if (pass == 1 && stored != null) continue;
            int capacity = Math.min(inventory.getInventoryStackLimit(), moving.getMaxStackSize()) - (stored == null ? 0 : stored.stackSize);
            int amount = Math.min(moving.stackSize, Math.max(0, capacity));
            if (amount == 0) continue;
            if (stored == null) { stored = moving.copy(); stored.stackSize = amount; inventory.setInventorySlotContents(i, stored); }
            else { stored.stackSize += amount; inventory.setInventorySlotContents(i, stored); }
            moving.stackSize -= amount;
        }
    }
    private boolean collectDrops(int x, int y, int z) {
        boolean all = true;
        List<EntityItem> drops = worldObj.getEntitiesWithinAABB(EntityItem.class, AxisAlignedBB.getBoundingBox(x - 1, y - 1, z - 1, x + 2, y + 2, z + 2));
        for (EntityItem entity : drops) {
            if (entity.isDead) continue;
            ItemStack moving = entity.getEntityItem();
            for (int pass = 0; pass < 2 && moving.stackSize > 0; pass++) for (int i = 0; i < cargo.length && moving.stackSize > 0; i++) {
                ItemStack stored = cargo[i];
                if (pass == 0 && (stored == null || !stored.isItemEqual(moving) || !ItemStack.areItemStackTagsEqual(stored, moving))) continue;
                if (pass == 1 && stored != null) continue;
                int amount = Math.min(moving.stackSize, moving.getMaxStackSize() - (stored == null ? 0 : stored.stackSize));
                if (amount <= 0) continue;
                if (stored == null) { stored = moving.copy(); stored.stackSize = amount; cargo[i] = stored; } else stored.stackSize += amount;
                moving.stackSize -= amount;
            }
            if (moving.stackSize <= 0) entity.setDead(); else { entity.setEntityItemStack(moving); all = false; }
        }
        return all;
    }
    private boolean hasCargo() { for (ItemStack stack : cargo) if (stack != null && stack.stackSize > 0) return true; return false; }
    private boolean cargoFull() { for (ItemStack stack : cargo) if (stack == null) return false; return true; }
    public NBTTagCompound snapshot() {
        NBTTagCompound tag = new NBTTagCompound(); tag.setIntArray("Plan", plan); tag.setBoolean("Configured", configured); tag.setInteger("Dimension", jobDimension);
        tag.setBoolean("Running", running); tag.setString("Status", status); tag.setInteger("Cursor", cursor);
        int count = 0; for (ItemStack stack : cargo) if (stack != null) count += stack.stackSize; tag.setInteger("Cargo", count);
        if (getHeldItem() != null) tag.setTag("Tool", getHeldItem().writeToNBT(new NBTTagCompound()));
        return tag;
    }
    public void writeEntityToNBT(NBTTagCompound tag) {
        super.writeEntityToNBT(tag);
        if (factionId != null) tag.setString("FactionUUID", factionId.toString());
        if (employerId != null) tag.setString("EmployerUUID", employerId.toString());
        tag.setString("EmployerName", employerName); tag.setTag("MiningJob", snapshot()); tag.setBoolean("Returning", returning); tag.setBoolean("ToolBroken", toolBroken);
        if (pendingDrops != null) tag.setIntArray("PendingDrops", pendingDrops);
        NBTTagList list = new NBTTagList();
        for (int i = 0; i < cargo.length; i++) if (cargo[i] != null) { NBTTagCompound entry = cargo[i].writeToNBT(new NBTTagCompound()); entry.setByte("Slot", (byte)i); list.appendTag(entry); }
        tag.setTag("Cargo", list);
    }
    public void readEntityFromNBT(NBTTagCompound tag) {
        super.readEntityFromNBT(tag); actor = null;
        try { factionId = UUID.fromString(tag.getString("FactionUUID")); employerId = UUID.fromString(tag.getString("EmployerUUID")); }
        catch (IllegalArgumentException absent) { factionId = employerId = null; }
        employerName = tag.getString("EmployerName"); NBTTagCompound job = tag.getCompoundTag("MiningJob");
        jobDimension = job.hasKey("Dimension") ? job.getInteger("Dimension") : dimension;
        int[] saved = job.getIntArray("Plan"); configured = saved.length == PLAN_SIZE && job.getBoolean("Configured");
        if (saved.length == PLAN_SIZE) plan = saved;
        cursor = Math.max(0, job.getInteger("Cursor")); running = false; status = "paused"; returning = tag.getBoolean("Returning"); toolBroken = tag.getBoolean("ToolBroken");
        int[] site = tag.getIntArray("PendingDrops"); pendingDrops = site.length == 3 ? site : null;
        Arrays.fill(cargo, null); NBTTagList list = tag.getTagList("Cargo", 10);
        for (int i = 0; i < list.tagCount(); i++) { NBTTagCompound entry = list.getCompoundTagAt(i); int slot = entry.getByte("Slot") & 255; if (slot < cargo.length) cargo[slot] = ItemStack.loadItemStackFromNBT(entry); }
    }
    protected void dropFewItems(boolean recentlyHit, int looting) {
        super.dropFewItems(recentlyHit, looting);
        ItemStack tool = getHeldItem();
        if (tool != null) { setCurrentItemOrArmor(0, null); entityDropItem(tool, 0); }
        for (int i = 0; i < cargo.length; i++) if (cargo[i] != null) { entityDropItem(cargo[i], 0); cargo[i] = null; }
    }
}
