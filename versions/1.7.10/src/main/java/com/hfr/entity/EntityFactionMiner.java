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
import cpw.mods.fml.common.registry.IEntityAdditionalSpawnData;
import io.netty.buffer.ByteBuf;
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
public final class EntityFactionMiner extends EntityLiving implements IInventory, IEntityAdditionalSpawnData {
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
    private ItemStack torches;
    private boolean stationAssigned, needsLight;
    private int requiredHarvest = -1;
    private int targetCursor = -1, searchOffset, supplyCheckTicks;
    private boolean searchUnloaded;
    private ItemStack miningTool;
    private int requestedTools, requestedTorches;
    private long lastSupplyReminder;
    private boolean sentSupplyReminder;

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
    // FML's 1.7.10 mob spawn packet otherwise leaves the client's random UUID intact.
    public void writeSpawnData(ByteBuf data) {
        data.writeLong(getUniqueID().getMostSignificantBits()); data.writeLong(getUniqueID().getLeastSignificantBits());
    }
    public void readSpawnData(ByteBuf data) { entityUniqueID = new UUID(data.readLong(), data.readLong()); }
    public void assign(UUID faction, UUID employer, String name) { factionId = faction; employerId = employer; employerName = name; }
    public UUID getFactionId() { return factionId; }
    public boolean mayManage(EntityPlayer player) {
        return maySelect(player) && player.getDistanceSqToEntity(this) <= 64;
    }
    public boolean maySelect(EntityPlayer player) {
        return !isDead && player.worldObj == worldObj && WorkerAccess.member(player, factionId);
    }
    public boolean interact(EntityPlayer player) {
        if (worldObj.isRemote) return true;
        if (!mayManage(player)) { player.addChatMessage(new ChatComponentTranslation("xf.worker.denied")); return true; }
        com.hfr.items.ItemMinerWand.give(player, this, false);
        FMLNetworkHandler.openGui(player, MainRegistry.instance, GUI_ID, worldObj, getEntityId(), 0, 0);
        return true;
    }
    public void pause() { stop("paused"); }
    private void stop(String reason) {
        running = false; status = reason; resetPath(); clearRequests();
    }
    private void resetPath() { getNavigator().clearPathEntity(); pathTicks = blockedTicks = 0; pathDistance = Double.MAX_VALUE; resetMining(); }
    private void resetMining() { miningProgress = 0; miningBlock = null; miningTool = null; }
    private void clearRequests() { requestedTools = requestedTorches = supplyCheckTicks = 0; }
    private String validate(int[] next, boolean area) {
        if (!stationAssigned) return "xf.miner.no_chest";
        if (jobDimension != dimension) return "xf.miner.state.dimension";
        if (!area) return "";
        return validateArea(next);
    }
    private String validateArea(int[] next) {
        long width = (long)next[5] - next[3] + 1, length = (long)next[6] - next[4] + 1, depth = (long)next[7] - next[8] + 1;
        if (width < 1 || width > 64 || length < 1 || length > 64 || depth < 1 || depth > worldObj.getHeight()
                || width * length * depth > 262144 || next[8] < 0 || next[7] >= worldObj.getHeight()
                || next[1] < 0 || next[1] >= worldObj.getHeight()) return "xf.miner.invalid_area";
        for (int i : new int[] {0, 2, 3, 4, 5, 6}) if (Math.abs((long)next[i]) > 30000000) return "xf.miner.invalid_area";
        if (Math.abs((long)next[3] - next[0]) > 128 || Math.abs((long)next[5] - next[0]) > 128
                || Math.abs((long)next[4] - next[2]) > 128 || Math.abs((long)next[6] - next[2]) > 128) return "xf.miner.invalid_area";
        if (!WorkerAccess.ownsLand(worldObj, next[0], next[2], factionId)) return "xf.worker.denied";
        for (int x = next[3]; x <= next[5]; x++) for (int z = next[4]; z <= next[6]; z++)
            if (!WorkerAccess.ownsLand(worldObj, x, z, factionId)) return "xf.worker.denied";
        if (stationInventory(next[0], next[1], next[2]) == null) return "xf.miner.state." + stationUnavailable(next[0], next[1], next[2]);
        return "";
    }
    public String selectStation(EntityPlayer player, int x, int y, int z) {
        if (!maySelect(player) || !WorkerAccess.ownsLand(worldObj, x, z, factionId)) return "xf.worker.denied";
        if (Math.abs((long)x) > 30000000 || Math.abs((long)z) > 30000000 || y < 0 || y >= worldObj.getHeight()) return "xf.miner.no_chest";
        if (stationInventory(x, y, z) == null) return "xf.miner.state." + stationUnavailable(x, y, z);
        int[] next = plan.clone(); next[0] = x; next[1] = y; next[2] = z;
        // An existing area must still fit the replacement station's distance limits.
        String error = configured ? validateArea(next) : "";
        if (!error.isEmpty()) return error;
        pause(); plan = next; stationAssigned = true; jobDimension = dimension;
        return "xf.miner.station_set";
    }
    public String configure(EntityPlayerMP player, int[] proposed) {
        if (!mayManage(player) || proposed == null || proposed.length != PLAN_SIZE) return "xf.worker.denied";
        int[] next = proposed.clone();
        for (int i = 0; i < 3; i++) next[i] = plan[i];
        String error = validate(next, true);
        if (!error.isEmpty()) return error;
        if (pendingDrops != null) return "xf.miner.pending_drops";
        pause(); plan = next; cursor = 0; configured = true; returning = hasCargo();
        targetCursor = -1; searchOffset = 0;
        requiredHarvest = -1; needsLight = false; toolBroken = false;
        employerId = player.getUniqueID(); employerName = player.getCommandSenderName(); actor = null;
        return "xf.miner.area_set";
    }
    public String start(EntityPlayerMP player) {
        if (!mayManage(player)) return "xf.worker.denied";
        if (!configured) return "xf.miner.select_area";
        String error = validate(plan, true); if (!error.isEmpty()) return error;
        employerId = player.getUniqueID(); employerName = player.getCommandSenderName(); actor = null;
        if (!running) {
            running = true; returning = hasCargo(); status = "working"; resetPath();
            searchOffset = 0;
        }
        return "";
    }
    public void onLivingUpdate() {
        super.onLivingUpdate();
        if (worldObj.isRemote || !running || ticksExisted % 10 != 0) return;
        try {
            if (supplyCheckTicks > 0) supplyCheckTicks -= 10;
            else work();
            updateRequests(); notifySupplies();
        }
        catch (RuntimeException error) { stop("error"); XFLog.error("Miner stopped: " + getUniqueID(), error); }
        finally { if (actor != null) actor.inventory.mainInventory[0] = null; }
    }
    private boolean authorized() {
        Clowder faction = factionId == null ? null : Clowder.getClowderFromUUID(factionId.toString());
        return faction != null && employerId != null && faction.getRole(employerId) != null && !faction.isInfrastructureDisabled();
    }
    private void work() {
        if (!configured || !authorized()) { stop("denied"); return; }
        if (jobDimension != dimension) { stop("dimension"); return; }
        if (!WorkerAccess.ownsLand(worldObj, plan[0], plan[2], factionId)) { stop("denied"); return; }
        equipCarriedTool();
        stockTorchesFromCargo();
        if (suitable(getHeldItem())) requestedTools = 0;
        if (torchCount() > 0) { requestedTorches = 0; needsLight = false; }
        int totalWork = (plan[5] - plan[3] + 1) * (plan[6] - plan[4] + 1) * (plan[7] - plan[8] + 1);
        if (returning && !hasCargo() && cursor < totalWork && suitable(getHeldItem()) && (!needsLight || torchCount() > 0))
            returning = false;
        if (returning || !suitable(getHeldItem()) || cargoFull() || needsLight && torchCount() == 0) { visitChest(); return; }
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
            int workCursor = targetCursor >= cursor && targetCursor / layer == cursor / layer ? targetCursor : cursor;
            int x = plan[3] + workCursor % width, z = plan[4] + workCursor / width % length, y = plan[7] - workCursor / layer;
            if (!worldObj.blockExists(x, y, z)) { status = "chunk"; return; }
            Block block = worldObj.getBlock(x, y, z);
            if (worldObj.isAirBlock(x, y, z) || block == net.minecraft.init.Blocks.torch) { advance(workCursor); continue; }
            if (!WorkerAccess.ownsLand(worldObj, x, z, factionId)) { stop("denied"); return; }
            if (block == net.minecraft.init.Blocks.bedrock || block.getBlockHardness(worldObj, x, y, z) < 0) {
                advance(workCursor); continue;
            }
            if (BuilderPlacement.protectedBlock(worldObj, x, y, z)
                    || worldObj.getTileEntity(x, y, z) != null) { stop("protected"); return; }
            if (block.getMaterial().isLiquid()) { stop("liquid"); return; }
            if (!moveNear(x, y + 1, z)) {
                if ("unreachable".equals(status)) findAccessibleTarget(layer);
                return;
            }
            status = "travel";
            if (!visible(x, y, z)) {
                if ("chunk".equals(status)) return;
                resetMining(); blockedTicks += 10;
                // A row-order target can be hidden behind another block in the same layer.
                // Search that layer without skipping any remaining work.
                if (!findAccessibleTarget(layer) && "travel".equals(status)) navigate(x, y + 1, z);
                return;
            }
            blockedTicks = 0;
            FakePlayer player = actor();
            player.setPosition(posX, posY, posZ); player.onGround = onGround;
            player.inventory.mainInventory[0] = getHeldItem(); player.inventory.currentItem = 0;
            int meta = worldObj.getBlockMetadata(x, y, z);
            if (!block.canHarvestBlock(player, meta)) {
                requiredHarvest = Math.max(0, block.getHarvestLevel(meta));
                player.inventory.mainInventory[0] = null; returning = true; status = "tool_level"; resetMining(); return;
            }
            requiredHarvest = -1;
            if (!lightWorkArea()) { player.inventory.mainInventory[0] = null; return; }
            if (miningBlock != block || miningMeta != meta || !ItemStack.areItemStacksEqual(miningTool, getHeldItem())) {
                miningBlock = block; miningMeta = meta; miningTool = getHeldItem().copy(); miningProgress = 0;
            }
            status = "mining"; swingItem();
            miningProgress += block.getPlayerRelativeBlockHardness(player, worldObj, x, y, z) * 10;
            if (miningProgress <= 0) { stop("unmineable"); return; }
            if (miningProgress < 1) return;
            boolean harvested = false;
            com.hfr.worker.MinerHarvestDrops.begin(player,x,y,z);
            try { harvested = player.theItemInWorldManager.tryHarvestBlock(x, y, z); }
            finally {
                setCurrentItemOrArmor(0, player.inventory.mainInventory[0]); player.inventory.mainInventory[0] = null;
                com.hfr.worker.MinerHarvestDrops.finish(harvested);
            }
            miningProgress = 0; miningBlock = null;
            if (!harvested) { stop("protected"); return; }
            if (!collectDrops(x, y, z)) { pendingDrops = new int[] {x, y, z}; returning = true; }
            advance(workCursor);
            if (getHeldItem() == null) { returning = toolBroken = true; status = "tool_broken"; }
            if (cargoFull()) returning = true;
            return;
        }
        if (cursor >= total) { returning = true; visitChest(); }
        else status = "scanning";
    }
    private void advance(int minedCursor) {
        if (minedCursor == cursor) cursor++;
        targetCursor = -1; searchOffset = 0; resetPath();
    }
    private boolean visible(int x, int y, int z) {
        int px = net.minecraft.util.MathHelper.floor_double(posX), pz = net.minecraft.util.MathHelper.floor_double(posZ);
        for (int cx = Math.min(px, x) >> 4; cx <= (Math.max(px, x) >> 4); cx++)
            for (int cz = Math.min(pz, z) >> 4; cz <= (Math.max(pz, z) >> 4); cz++)
                if (!worldObj.blockExists(cx << 4, y, cz << 4)) { status = "chunk"; return false; }
        net.minecraft.util.MovingObjectPosition hit = worldObj.func_147447_a(
                net.minecraft.util.Vec3.createVectorHelper(posX, posY + getEyeHeight(), posZ),
                net.minecraft.util.Vec3.createVectorHelper(x + .5, y + .5, z + .5), false, true, false);
        return hit == null || hit.blockX == x && hit.blockY == y && hit.blockZ == z;
    }
    private boolean findAccessibleTarget(int layer) {
        int width = plan[5] - plan[3] + 1, end = (cursor / layer + 1) * layer;
        if (searchOffset == 0) searchUnloaded = false;
        for (int scans = 0; scans < 64 && cursor + searchOffset < end; scans++, searchOffset++) {
            int candidate = cursor + searchOffset;
            int x = plan[3] + candidate % width, z = plan[4] + candidate / width % (plan[6] - plan[4] + 1), y = plan[7] - candidate / layer;
            if (getDistanceSq(x + .5, y + 1, z + .5) > 9) continue;
            if (!worldObj.blockExists(x, y, z)) { searchUnloaded = true; continue; }
            Block block = worldObj.getBlock(x, y, z);
            if (worldObj.isAirBlock(x, y, z) || block == net.minecraft.init.Blocks.torch || block.getMaterial().isLiquid()
                    || block.getBlockHardness(worldObj, x, y, z) < 0 || worldObj.getTileEntity(x, y, z) != null
                    || !WorkerAccess.ownsLand(worldObj, x, z, factionId) || BuilderPlacement.protectedBlock(worldObj, x, y, z)) continue;
            status = "searching";
            if (!visible(x, y, z)) { if ("chunk".equals(status)) searchUnloaded = true; continue; }
            targetCursor = candidate; searchOffset = 0; resetPath(); status = "working"; return true;
        }
        if (cursor + searchOffset >= end) {
            searchOffset = 0;
            if (searchUnloaded) status = "chunk";
            else if (blockedTicks >= 100 || pathTicks >= 100 || "unreachable".equals(status)) stop("unreachable");
            else status = "travel";
        }
        else status = "searching";
        return false;
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
        if (distance <= 9) {
            // Keep a visibility-recovery path alive until the selected block is exposed.
            if (blockedTicks == 0) getNavigator().clearPathEntity();
            pathTicks = 0; pathDistance = Double.MAX_VALUE; return true;
        }
        miningProgress = 0;
        if (distance < pathDistance - .1) { pathTicks = 0; pathDistance = distance; } else pathTicks += 10;
        if (pathTicks >= 100) { status = "unreachable"; getNavigator().clearPathEntity(); return false; }
        if (getNavigator().noPath() && !navigate(x,y,z)) return false;
        status = "travel"; return false;
    }
    private boolean navigate(int x,int y,int z) {
        // Vanilla constructs a ChunkCache around the worker and otherwise loads every missing chunk in it.
        int radius = (int)(getNavigator().getPathSearchRange()+8F);
        if (radius < 0 || radius > 72) { stop("unreachable"); return false; }
        int px = net.minecraft.util.MathHelper.floor_double(posX), pz = net.minecraft.util.MathHelper.floor_double(posZ);
        for (int cx = (px-radius)>>4; cx <= (px+radius)>>4; cx++)
            for (int cz = (pz-radius)>>4; cz <= (pz+radius)>>4; cz++)
                if (!worldObj.blockExists(cx<<4,Math.max(0,Math.min(worldObj.getHeight()-1,(int)posY)),cz<<4)) {
                    getNavigator().clearPathEntity(); pathTicks = 0; status = "chunk"; return false;
                }
        if (!getNavigator().tryMoveToXYZ(x+.5,y,z+.5,1)) { status = "unreachable"; return false; }
        return true;
    }
    private void visitChest() {
        if (!worldObj.blockExists(plan[0], plan[1], plan[2])) { status = "chunk"; return; }
        IInventory chest = stationInventory(plan[0], plan[1], plan[2]);
        if (chest == null) { status = stationUnavailable(plan[0], plan[1], plan[2]); return; }
        if (!moveNear(plan[0], plan[1], plan[2])) return;
        equipCarriedTool();
        stockTorchesFromCargo();
        for (ItemStack stack : cargo) if (stack != null) insert(chest, stack);
        for (int i = 0; i < cargo.length; i++) if (cargo[i] != null && cargo[i].stackSize <= 0) cargo[i] = null;
        chest.markDirty();
        if (hasCargo()) { status = "storage_full"; return; }
        toolBroken = false;
        int total = (plan[5] - plan[3] + 1) * (plan[6] - plan[4] + 1) * (plan[7] - plan[8] + 1);
        if (cursor >= total && pendingDrops == null) { stop("complete"); return; }
        if (!suitable(getHeldItem())) {
            if (getHeldItem() != null) {
                ItemStack old = getHeldItem(); insert(chest, old);
                if (old.stackSize > 0) { status = "storage_full"; return; }
                setCurrentItemOrArmor(0, null);
            }
            for (int i = firstSlot(chest); i < chest.getSizeInventory(); i++) {
                ItemStack stack = chest.getStackInSlot(i);
                if (suitable(stack)) { setCurrentItemOrArmor(0, chest.decrStackSize(i, 1)); chest.markDirty(); break; }
            }
            if (!suitable(getHeldItem())) {
                requestedTools = 1; status = requiredHarvest >= 0 ? "tool_level" : "no_tool";
                supplyCheckTicks = 40; return;
            }
        }
        requestedTools = 0;
        for (int i = firstSlot(chest); i < chest.getSizeInventory() && torchCount() < 32; i++) {
            ItemStack stack = chest.getStackInSlot(i);
            if (!isTorch(stack)) continue;
            // Keep differently tagged supplies separate, including custom torch tags.
            if (torches != null && !ItemStack.areItemStackTagsEqual(torches, stack)) continue;
            ItemStack taken = chest.decrStackSize(i, Math.min(32 - torchCount(), stack.stackSize));
            if (taken != null) { if (torches == null) torches = taken; else torches.stackSize += taken.stackSize; }
            chest.markDirty();
        }
        if (needsLight && torchCount() == 0) {
            requestedTorches = 1; status = "no_torches"; supplyCheckTicks = 40; return;
        }
        requestedTorches = 0;
        returning = false; status = "working"; resetPath();
    }
    public static boolean pickaxe(ItemStack stack) {
        return stack != null && stack.stackSize > 0 && (stack.getItem() instanceof ItemPickaxe
                || stack.getItem().getToolClasses(stack).contains("pickaxe"));
    }
    private boolean station(TileEntity tile) {
        return tile != null && (tile.getClass() == net.minecraft.tileentity.TileEntityChest.class
                || tile instanceof com.hfr.tileentity.machine.TileEntityMachineBuilder
                && factionId != null && factionId.equals(((com.hfr.tileentity.machine.TileEntityMachineBuilder)tile).getFactionId()));
    }
    private IInventory stationInventory(int x, int y, int z) {
        if (!worldObj.blockExists(x, y, z) || !station(worldObj.getTileEntity(x, y, z))
                || !WorkerAccess.ownsLand(worldObj, x, z, factionId)) return null;
        if (worldObj.getTileEntity(x, y, z) instanceof net.minecraft.tileentity.TileEntityChest) {
            Block block = worldObj.getBlock(x, y, z);
            if (!(block instanceof net.minecraft.block.BlockChest)) return null;
            // Native large-chest resolution checks lids; check both halves' claims and loadedness first.
            for (int[] d : new int[][] {{-1,0},{1,0},{0,-1},{0,1}}) {
                if (!worldObj.blockExists(x + d[0], y, z + d[1])) return null;
                if (worldObj.getBlock(x + d[0], y, z + d[1]) == block
                        && !WorkerAccess.ownsLand(worldObj, x + d[0], z + d[1], factionId)) return null;
            }
            return ((net.minecraft.block.BlockChest)block).func_149951_m(worldObj, x, y, z);
        }
        return (IInventory)worldObj.getTileEntity(x, y, z);
    }
    private String stationUnavailable(int x, int y, int z) {
        if (!worldObj.blockExists(x, y, z)) return "chunk";
        if (!WorkerAccess.ownsLand(worldObj, x, z, factionId)) return "denied";
        if (!station(worldObj.getTileEntity(x, y, z))) return "no_chest";
        if (worldObj.getTileEntity(x, y, z) instanceof net.minecraft.tileentity.TileEntityChest) {
            Block block = worldObj.getBlock(x, y, z);
            for (int[] d : new int[][] {{-1,0},{1,0},{0,-1},{0,1}}) {
                if (!worldObj.blockExists(x + d[0], y, z + d[1])) return "chunk";
                if (worldObj.getBlock(x + d[0], y, z + d[1]) == block
                        && !WorkerAccess.ownsLand(worldObj, x + d[0], z + d[1], factionId)) return "denied";
            }
            if (block instanceof net.minecraft.block.BlockChest) return "station_blocked";
        }
        return "no_chest";
    }
    private boolean suitable(ItemStack stack) {
        if (!pickaxe(stack) || stack.isItemStackDamageable() && stack.getItemDamage() >= stack.getMaxDamage()) return false;
        if (requiredHarvest < 0) return true;
        if (stack.getItem().getHarvestLevel(stack, "pickaxe") < requiredHarvest) return false;
        int width = plan[5]-plan[3]+1, length = plan[6]-plan[4]+1;
        if (!configured || employerId == null || width <= 0 || length <= 0) return false;
        int current = targetCursor >= cursor && targetCursor/(width*length) == cursor/(width*length) ? targetCursor : cursor;
        int x = plan[3]+current%width, z = plan[4]+current/width%length, y = plan[7]-current/(width*length);
        if (!worldObj.blockExists(x,y,z)) return false;
        FakePlayer player = actor(); ItemStack previous = player.inventory.mainInventory[0];
        player.inventory.mainInventory[0] = stack;
        try { return worldObj.getBlock(x,y,z).canHarvestBlock(player,worldObj.getBlockMetadata(x,y,z)); }
        finally { player.inventory.mainInventory[0] = previous; }
    }
    private void equipCarriedTool() {
        if (suitable(getHeldItem())) return;
        for (int i = 0; i < cargo.length; i++) if (suitable(cargo[i])) {
            if (cargo[i].stackSize == 1) {
                ItemStack replacement = cargo[i]; cargo[i] = getHeldItem(); setCurrentItemOrArmor(0, replacement); return;
            }
            ItemStack old = getHeldItem();
            int free = -1;
            if (old != null) {
                for (int j = 0; j < cargo.length; j++) if (cargo[j] == null) { free = j; break; }
                if (free < 0) continue;
                cargo[free] = old;
            }
            setCurrentItemOrArmor(0,cargo[i].splitStack(1)); return;
        }
    }
    public static boolean isTorch(ItemStack stack) { return stack != null && stack.stackSize > 0 && stack.getItem() == net.minecraft.item.Item.getItemFromBlock(net.minecraft.init.Blocks.torch); }
    private int torchCount() { return torches == null ? 0 : torches.stackSize; }
    private void stockTorchesFromCargo() {
        for (int i = 0; i < cargo.length; i++) if (isTorch(cargo[i])) {
            if (torches == null) { torches = cargo[i]; cargo[i] = null; }
            else if (ItemStack.areItemStackTagsEqual(torches, cargo[i])) {
                int amount = Math.min(cargo[i].stackSize, Math.max(0, torches.getMaxStackSize() - torchCount()));
                torches.stackSize += amount; cargo[i].stackSize -= amount;
                if (cargo[i].stackSize == 0) cargo[i] = null;
            }
        }
    }
    private boolean lightWorkArea() {
        int px = net.minecraft.util.MathHelper.floor_double(posX), py = net.minecraft.util.MathHelper.floor_double(posY), pz = net.minecraft.util.MathHelper.floor_double(posZ);
        // Brightness and torch support helpers inspect neighbors; never discover an unloaded chunk through them.
        for (int dx = -2; dx <= 2; dx++) for (int dz = -2; dz <= 2; dz++)
            if (!worldObj.blockExists(px+dx,py,pz+dz)) { status = "chunk"; return false; }
        needsLight = worldObj.getBlockLightValue(px, py, pz) < 8;
        if (!needsLight) return true;
        stockTorchesFromCargo();
        if (torchCount() == 0) { status = "no_torches"; returning = true; return false; }
        // A small local search; mining skips torches so a placed light is not immediately harvested.
        for (int dy = 0; dy <= 1; dy++) for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) {
            int x = px + dx, y = py + dy, z = pz + dz;
            if (y <= 0 || y >= worldObj.getHeight() || !worldObj.blockExists(x, y, z) || !worldObj.blockExists(x, y - 1, z)
                    || !WorkerAccess.ownsLand(worldObj, x, z, factionId) || !worldObj.isAirBlock(x, y, z)
                    || !worldObj.isSideSolid(x, y - 1, z, net.minecraftforge.common.util.ForgeDirection.UP)
                    || !net.minecraft.init.Blocks.torch.canPlaceBlockAt(worldObj, x, y, z)) continue;
            FakePlayer player = actor();
            if (!player.canPlayerEdit(x, y, z, 1, torches) || worldObj.getTileEntity(x, y - 1, z) != null
                    || worldObj.getBlock(x,y-1,z) != net.minecraft.init.Blocks.bedrock && BuilderPlacement.protectedBlock(worldObj, x, y - 1, z)) continue;
            net.minecraftforge.common.util.BlockSnapshot before = net.minecraftforge.common.util.BlockSnapshot.getBlockSnapshot(worldObj, x, y, z);
            if (!worldObj.setBlock(x, y, z, net.minecraft.init.Blocks.torch, 5, 3)) continue;
            boolean accepted = false;
            ItemStack previous = player.inventory.mainInventory[0]; player.inventory.mainInventory[0] = torches;
            try {
                net.minecraftforge.event.world.BlockEvent.PlaceEvent event = net.minecraftforge.event.ForgeEventFactory.onPlayerBlockPlace(player, before, net.minecraftforge.common.util.ForgeDirection.UP);
                accepted = !event.isCanceled() && worldObj.getBlock(x,y,z) == net.minecraft.init.Blocks.torch;
            } finally {
                player.inventory.mainInventory[0] = previous;
                if (!accepted) before.restore(true, false);
            }
            if (!accepted) continue;
            if (--torches.stackSize == 0) torches = null;
            needsLight = false; return true;
        }
        status = "light_blocked"; return false;
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
                int amount = Math.min(moving.stackSize, Math.min(getInventoryStackLimit(), moving.getMaxStackSize()) - (stored == null ? 0 : stored.stackSize));
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
    private void updateRequests() {
        requestedTools = requestedTorches = 0;
        if (worldObj.isRemote || !configured || !authorized() || jobDimension != dimension) return;
        int total = (plan[5] - plan[3] + 1) * (plan[6] - plan[4] + 1) * (plan[7] - plan[8] + 1);
        if (cursor >= total && pendingDrops == null) return;
        boolean tool = suitable(getHeldItem());
        int lights = torchCount();
        for (ItemStack stack : cargo) {
            if (!tool && suitable(stack)) tool = true;
            if (isTorch(stack)) lights += stack.stackSize;
        }
        if (tool && (!needsLight || lights > 0)) return;
        IInventory station = stationAssigned ? stationInventory(plan[0], plan[1], plan[2]) : null;
        // An inaccessible/unloaded station is an access problem, not proof of missing supplies.
        if (station == null) return;
        for (int i = firstSlot(station); i < station.getSizeInventory(); i++) {
            ItemStack stack = station.getStackInSlot(i);
            if (!tool && suitable(stack)) tool = true;
            if (isTorch(stack)) lights += stack.stackSize;
        }
        requestedTools = tool ? 0 : 1;
        requestedTorches = needsLight && lights == 0 ? 1 : 0;
    }
    private void notifySupplies() {
        long now = System.nanoTime();
        if (!running || requestedTools == 0 && requestedTorches == 0
                || sentSupplyReminder && now - lastSupplyReminder < 60000000000L) return;
        EntityPlayerMP recipient = null;
        for (Object value : MinecraftServer.getServer().getConfigurationManager().playerEntityList) {
            EntityPlayerMP player = (EntityPlayerMP)value;
            if (player.getUniqueID().equals(employerId) && WorkerAccess.member(player, factionId)
                    && player.playerNetServerHandler.netManager.isChannelOpen()) { recipient = player; break; }
        }
        if (recipient == null) return;
        // One private message per Miner per minute, including changing requests and reopen/Start spam.
        ChatComponentTranslation tool = new ChatComponentTranslation(requiredHarvest >= 0 ? "xf.miner.request_tier" : "xf.miner.request_pick", requiredHarvest);
        ChatComponentTranslation light = new ChatComponentTranslation("xf.miner.need_torches", requestedTorches);
        ChatComponentTranslation request = requestedTools > 0 ? tool : light;
        if (requestedTools > 0 && requestedTorches > 0) request = new ChatComponentTranslation("xf.miner.both_requests", tool, light);
        recipient.addChatMessage(new ChatComponentTranslation("xf.miner.supply_notice", getCommandSenderName(),
                getUniqueID().toString().substring(0, 8), request,
                new ChatComponentTranslation("xf.miner.supplies")));
        lastSupplyReminder = now; sentSupplyReminder = true;
    }
    public NBTTagCompound snapshot() {
        updateRequests();
        NBTTagCompound tag = new NBTTagCompound(); tag.setIntArray("Plan", plan); tag.setBoolean("Configured", configured); tag.setInteger("Dimension", jobDimension);
        tag.setBoolean("StationAssigned", stationAssigned);
        tag.setInteger("PickaxesRequested", requestedTools);
        tag.setInteger("HarvestLevel", requiredHarvest);
        tag.setInteger("TorchesRequested", requestedTorches);
        tag.setInteger("TorchRestock", Math.max(0, 32 - torchCount()));
        tag.setInteger("WorkCursor", targetCursor);
        tag.setString("Task", !configured ? "unassigned" : returning ? "storage" : pendingDrops != null ? "drops" : "excavate");
        int width = plan[5] - plan[3] + 1, length = plan[6] - plan[4] + 1;
        int current = targetCursor >= cursor ? targetCursor : cursor;
        if (configured && width > 0 && length > 0 && current < width * length * (plan[7] - plan[8] + 1))
            tag.setIntArray("Target", new int[] {plan[3] + current % width, plan[7] - current / (width * length), plan[4] + current / width % length});
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
        if (torches != null) tag.setTag("Torches", torches.writeToNBT(new NBTTagCompound()));
        tag.setInteger("RequiredHarvest", requiredHarvest);
    }
    public void readEntityFromNBT(NBTTagCompound tag) {
        super.readEntityFromNBT(tag); actor = null;
        try { factionId = UUID.fromString(tag.getString("FactionUUID")); employerId = UUID.fromString(tag.getString("EmployerUUID")); }
        catch (IllegalArgumentException absent) { factionId = employerId = null; }
        employerName = tag.getString("EmployerName"); NBTTagCompound job = tag.getCompoundTag("MiningJob");
        jobDimension = job.hasKey("Dimension") ? job.getInteger("Dimension") : dimension;
        int[] saved = job.getIntArray("Plan"); configured = saved.length == PLAN_SIZE && job.getBoolean("Configured");
        if (saved.length == PLAN_SIZE) plan = saved;
        stationAssigned = job.hasKey("StationAssigned") ? job.getBoolean("StationAssigned") : configured;
        torches = tag.hasKey("Torches") ? ItemStack.loadItemStackFromNBT(tag.getCompoundTag("Torches")) : null;
        requiredHarvest = tag.hasKey("RequiredHarvest") ? tag.getInteger("RequiredHarvest") : -1;
        cursor = Math.max(0, job.getInteger("Cursor")); running = false; status = "paused"; returning = tag.getBoolean("Returning"); toolBroken = tag.getBoolean("ToolBroken");
        int layer = (plan[5] - plan[3] + 1) * (plan[6] - plan[4] + 1);
        targetCursor = job.hasKey("WorkCursor") ? job.getInteger("WorkCursor") : -1;
        if (layer <= 0 || targetCursor < cursor || targetCursor / layer != cursor / layer) targetCursor = -1;
        int[] site = tag.getIntArray("PendingDrops"); pendingDrops = site.length == 3 ? site : null;
        Arrays.fill(cargo, null); NBTTagList list = tag.getTagList("Cargo", 10);
        for (int i = 0; i < list.tagCount(); i++) { NBTTagCompound entry = list.getCompoundTagAt(i); int slot = entry.getByte("Slot") & 255; if (slot < cargo.length) cargo[slot] = ItemStack.loadItemStackFromNBT(entry); }
    }
    protected void dropFewItems(boolean recentlyHit, int looting) {
        super.dropFewItems(recentlyHit, looting);
        ItemStack tool = getHeldItem();
        if (tool != null) { setCurrentItemOrArmor(0, null); entityDropItem(tool, 0); }
        for (int i = 0; i < cargo.length; i++) if (cargo[i] != null) { entityDropItem(cargo[i], 0); cargo[i] = null; }
        if (torches != null) { entityDropItem(torches, 0); torches = null; }
    }
    // The tool slot is a view of EntityLiving's equipment, never a second stored stack.
    public int getSizeInventory() { return 29; }
    public ItemStack getStackInSlot(int slot) { return slot >= 0 && slot < 27 ? cargo[slot] : slot == 27 ? getHeldItem() : slot == 28 ? torches : null; }
    public ItemStack decrStackSize(int slot, int amount) {
        ItemStack stack = getStackInSlot(slot); if (stack == null || amount <= 0) return null;
        if (stack.stackSize <= amount) { setInventorySlotContents(slot, null); return stack; }
        ItemStack split = stack.splitStack(amount); markDirty(); return split;
    }
    public ItemStack getStackInSlotOnClosing(int slot) { ItemStack stack = getStackInSlot(slot); setInventorySlotContents(slot, null); return stack; }
    public void setInventorySlotContents(int slot, ItemStack stack) {
        if (slot >= 0 && slot < 27) cargo[slot] = stack;
        else if (slot == 27) setCurrentItemOrArmor(0, stack);
        else if (slot == 28) torches = stack;
        markDirty();
    }
    public String getInventoryName() { return "xf.miner.title"; }
    public boolean hasCustomInventoryName() { return false; }
    public int getInventoryStackLimit() { return 64; }
    // Cargo/torch slot synchronization must not restart a partly mined block.
    // The work loop compares the actual tool, target, block and metadata instead.
    public void markDirty() { supplyCheckTicks = 0; }
    public boolean isUseableByPlayer(EntityPlayer player) { return mayManage(player); }
    public void openInventory() { }
    public void closeInventory() { }
    public boolean isItemValidForSlot(int slot, ItemStack stack) { return slot >= 0 && slot < 27 || slot == 27 && pickaxe(stack) || slot == 28 && isTorch(stack); }
}
