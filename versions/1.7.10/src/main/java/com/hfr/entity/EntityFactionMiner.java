package com.hfr.entity;

import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import com.hfr.builder.BuilderPlacement;
import com.hfr.clowder.Clowder;
import com.hfr.items.ItemMinerWand;
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
import net.minecraft.pathfinding.PathEntity;
import net.minecraft.pathfinding.PathPoint;
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
    private int cursor, pathTicks, jobDimension;
    private boolean configured, running, toolBroken;
    private enum Task {
        MINING("excavate"), TO_STORAGE("to_storage"), DEPOSITING("deposit"), RESTOCKING("restock"), TO_WORK("to_work"), DROPS("drops"), COMPLETE("complete");
        final String key;
        Task(String key) { this.key = key; }
    }
    private enum StorageReason {
        NONE("none"), CAPACITY("capacity"), OVERFLOW("overflow"), TOOL("tool"), TORCHES("torches"), COMPLETE("complete");
        final String key;
        StorageReason(String key) { this.key = key; }
    }
    private Task task = Task.TO_WORK;
    private StorageReason storageReason = StorageReason.NONE;
    private final long[] taskLogs = new long[Task.values().length];
    private long lastStopLog;
    private int[] pendingDrops;
    private boolean pendingDropsCaptured;
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
    private long selectionRevision, lastSelectionLog, lastInstalledLog, lastNavigationLog;
    private boolean installedDown;
    private int[] destination, waypoint;
    private int navigationFailures;
    private double bestRouteProgress;
    private String navigationReason = "";
    private String navigationDetail = "";
    // A helix of original terrain remains until the final bottom-up pass. Never place free blocks.
    private boolean accessRamp;
    private int rampOffset, rampCleanup = -1;
    private final java.util.BitSet rejectedTargets = new java.util.BitSet();
    // Storage -> work, never appended to by ordinary movement. Revalidated before reuse.
    private final java.util.List<int[]> returnRoute = new java.util.ArrayList<int[]>();
    private final java.util.List<int[]> travelRoute = new java.util.ArrayList<int[]>();
    private int routeIndex, segmentEnd, routeRetries;
    // A temporary trip, never a job/corridor change or part of the storage return cache.
    private static final int RECOVERY_RADIUS = 64, RECOVERY_HEIGHT = 16, RECOVERY_COST = 2560;
    private static final int RECOVERY_ATTEMPTS = 3, RECOVERY_TICKS = 2400;
    private final java.util.List<int[]> recoveryRoute = new java.util.ArrayList<int[]>();
    private boolean recoveringCorridor;
    private int[] recoveryOrigin, recoveryDestination;
    private int recoveryAttempts, recoveryTicks;
    private final java.util.List<AxisAlignedBB> recoveryCollisions = new java.util.ArrayList<AxisAlignedBB>();
    private final java.util.List<ItemStack> overflowDrops = new java.util.ArrayList<ItemStack>();
    private String capacityDetail = "";
    private int returnFailureTarget = -1, returnFailures;

    public EntityFactionMiner(World world) {
        super(world); setSize(.6F, 1.8F); stepHeight = 1F;
        getNavigator().setCanSwim(false); getNavigator().setAvoidsWater(true);
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
        long now = System.nanoTime();
        if (!"paused".equals(reason) && !"complete".equals(reason) && !"navigation_failed".equals(reason)
                && (lastStopLog == 0 || now-lastStopLog >= 60000000000L)) {
            lastStopLog = now; XFLog.warn(taskDiagnostic("stopped: " + reason));
        }
        running = false; status = reason; resetPath(); clearRequests();
        if (!"navigation_failed".equals(reason)) navigationReason = navigationDetail = "";
    }
    private void resetPath() {
        getNavigator().clearPathEntity(); pathTicks = 0;
        destination = waypoint = null; navigationFailures = 0; resetMining();
        travelRoute.clear(); routeIndex = segmentEnd = routeRetries = 0; bestRouteProgress = -Double.MAX_VALUE;
        recoveringCorridor = false; recoveryOrigin = recoveryDestination = null; recoveryRoute.clear();
        recoveryAttempts = recoveryTicks = 0;
    }
    private String taskDiagnostic(String reason) {
        return "Miner " + getUniqueID() + " task=" + task.key + " reason=" + reason + " storage=" + storageReason.key
                + " pos=" + posX+","+posY+","+posZ + " destination=" + Arrays.toString(destination)
                + " recoveryOrigin=" + Arrays.toString(recoveryOrigin) + " recoveryDestination=" + Arrays.toString(recoveryDestination)
                + " waypoint=" + Arrays.toString(waypoint) + " cursor=" + cursor + " target=" + targetCursor + " cleanup=" + rampCleanup;
    }
    private void transition(Task next, String reason, int[] goal) {
        if (task == next && Arrays.equals(destination,goal)) return;
        String previous = task.key;
        resetPath(); task = next; destination = goal == null ? null : goal.clone();
        navigationReason = navigationDetail = "";
        status = next.key;
        long now = System.nanoTime();
        if (taskLogs[next.ordinal()] == 0 || now-taskLogs[next.ordinal()] >= 1000000000L) {
            taskLogs[next.ordinal()] = now;
            XFLog.info(taskDiagnostic(previous + " -> " + reason) + (next == Task.TO_STORAGE ? " " + cargoCapacityDetail() + " " + capacityDetail : ""));
        }
    }
    private boolean storageTask() { return task == Task.TO_STORAGE || task == Task.DEPOSITING || task == Task.RESTOCKING; }
    private int totalWork() { return (plan[5]-plan[3]+1)*(plan[6]-plan[4]+1)*(plan[7]-plan[8]+1); }
    private boolean unfinished() { return cursor < totalWork() || rampCleanup >= 0 || pendingDrops != null || !overflowDrops.isEmpty(); }
    private int[] workDestination() {
        if (pendingDrops != null) return new int[] {pendingDrops[0],pendingDrops[1]+1,pendingDrops[2]};
        int width = plan[5]-plan[3]+1, layer = width*(plan[6]-plan[4]+1);
        int current = cursor >= totalWork() && rampCleanup >= 0 ? rampCursor(rampCleanup)
                : targetCursor >= cursor && targetCursor/layer == cursor/layer ? targetCursor : cursor;
        return current < totalWork() ? new int[] {plan[3]+current%width,plan[7]-current/layer+1,plan[4]+current/width%(plan[6]-plan[4]+1)} : null;
    }
    private void returnToWork(String reason) { transition(Task.TO_WORK,reason,workDestination()); }
    private void beginStorage(StorageReason reason) {
        storageReason = reason;
        if (reason != StorageReason.OVERFLOW) capacityDetail = "";
        transition(Task.TO_STORAGE,reason.key,new int[] {plan[0],plan[1],plan[2]});
    }
    public long nextSelectionRevision(long observed) {
        selectionRevision = Math.max(selectionRevision,observed);
        if (selectionRevision < Long.MAX_VALUE) selectionRevision++;
        return selectionRevision;
    }
    public void logSelection(String action, boolean down, int[] bounds) {
        long now = System.nanoTime();
        boolean installed = "installed".equals(action);
        long last = installed ? lastInstalledLog : lastSelectionLog;
        if (last != 0 && now-last < 1000000000L) return;
        if (installed) lastInstalledLog = now; else lastSelectionLog = now;
        XFLog.info("Miner " + getUniqueID() + " selection " + action + " down=" + down + " bounds=" + Arrays.toString(bounds)
                + " installed=" + Arrays.toString(plan));
    }
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
        pause(); plan = next; stationAssigned = true; jobDimension = dimension; returnRoute.clear();
        task = Task.TO_WORK; storageReason = StorageReason.NONE;
        return "xf.miner.station_set";
    }
    // Only Start can install a validated, confirmed replacement. Real overflow drops remain collectible.
    private void configure(int[] next) {
        pause(); plan = next; cursor = 0; configured = true; task = Task.TO_WORK; storageReason = StorageReason.NONE;
        targetCursor = -1; searchOffset = 0;
        rejectedTargets.clear(); returnRoute.clear();
        requiredHarvest = -1; needsLight = false; toolBroken = false;
        accessRamp = plan[5] > plan[3] && plan[6] > plan[4] && plan[7] > plan[8];
        rampCleanup = accessRamp ? plan[7]-plan[8] : -1;
        rampOffset = 0;
        if (accessRamp) {
            double best = Double.MAX_VALUE;
            for (int i = 0; i < rampPerimeter(); i++) {
                int[] r = rampColumn(i);
                double d = (double)(r[0]-plan[0])*(r[0]-plan[0])+(double)(r[1]-plan[2])*(r[1]-plan[2]);
                if (d < best) { best = d; rampOffset = i; }
            }
        }
    }
    public static final class Activation {
        public final String feedback;
        private final int[] previous, replacement;
        public final boolean down;
        private Activation(String feedback) { this(feedback, null, null); }
        private Activation(String feedback, int[] previous, int[] replacement) {
            this(feedback,previous,replacement,false);
        }
        private Activation(String feedback, int[] previous, int[] replacement, boolean down) {
            this.feedback = feedback; this.previous = previous; this.replacement = replacement;
            this.down = down;
        }
        public boolean needsConfirmation() { return replacement != null; }
        public int[] proposedPlan() { return replacement == null ? new int[0] : replacement.clone(); }
    }
    public Activation start(EntityPlayerMP player, Activation confirmation) {
        if (worldObj.isRemote || !mayManage(player)) return new Activation("xf.worker.denied");
        int[] next = plan;
        boolean down = installedDown;
        if (confirmation != null) {
            if (confirmation.replacement == null || !Arrays.equals(confirmation.previous,plan)) return new Activation("xf.miner.selection_changed");
            next = confirmation.replacement.clone(); down = confirmation.down;
        } else {
            ItemMinerWand.Selection selected = ItemMinerWand.selection(player, this);
            if (!selected.error.isEmpty()) return new Activation(selected.error);
            if (selected.corners.length == 7) {
                next = ItemMinerWand.plan(selected.corners); down = selected.corners[6] == 1;
                for (int i = 0; i < 3; i++) next[i] = plan[i];
            } else if (!configured) return new Activation("xf.miner.select_area");
        }
        String error = validate(next, true);
        if (!error.isEmpty()) return new Activation(error);
        if (!configured || !Arrays.equals(next, plan)) {
            int total = (plan[5]-plan[3]+1) * (plan[6]-plan[4]+1) * (plan[7]-plan[8]+1);
            boolean unfinished = configured && (cursor < total || rampCleanup >= 0 || pendingDrops != null || hasCargo());
            if (unfinished && confirmation == null)
                return new Activation("xf.miner.confirm_replace", plan.clone(), next.clone(),down);
            configure(next);
        }
        installedDown = down;
        logSelection("installed",down,plan);
        employerId = player.getUniqueID(); employerName = player.getCommandSenderName(); actor = null;
        if (!running) {
            running = true; status = "working"; resetPath();
            navigationReason = navigationDetail = "";
            rejectedTargets.clear(); returnFailures = 0; returnFailureTarget = -1;
            searchOffset = 0;
            // Resume an explicit saved trip, never infer a trip from partial cargo.
            destination = storageTask() ? new int[] {plan[0],plan[1],plan[2]} : workDestination();
        }
        if (!recoveringCorridor && !inCorridor((int)Math.floor(posX),(int)Math.floor(posZ))) beginCorridorRecovery();
        return new Activation("");
    }
    public void onLivingUpdate() {
        if (!worldObj.isRemote && running && configured && !recoveringCorridor
                && !inCorridor((int)Math.floor(posX),(int)Math.floor(posZ))) beginCorridorRecovery();
        // Native following checks neighboring cells while shortcutting. Do not let those checks
        // discover unloaded chunks even though our planned route itself only uses loaded cells.
        if (!worldObj.isRemote && !getNavigator().noPath() && waypoint != null && !segmentChunksLoaded(waypoint)) {
            getNavigator().clearPathEntity(); status = recoveringCorridor ? "corridor_recovery" : "chunk";
            navigationReason = "chunk"; pathTicks = 0;
        }
        if (!worldObj.isRemote && !recoveringCorridor && !getNavigator().noPath() && destination != null
                && atDestination(destination[0],destination[1],destination[2],task == Task.TO_WORK && pendingDrops == null))
            arrived();
        super.onLivingUpdate();
        if (worldObj.isRemote || !running || ticksExisted % 10 != 0) return;
        try {
            if (supplyCheckTicks > 0 && !recoveringCorridor && inCorridor((int)Math.floor(posX),(int)Math.floor(posZ))) supplyCheckTicks -= 10;
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
        // Recover before inventory-driven trips, work scans or interaction can change the saved task.
        if (recoverCorridor()) return;
        equipCarriedTool();
        stockTorchesFromCargo();
        if (suitable(getHeldItem())) requestedTools = 0;
        if (torchCount() > 0) { requestedTorches = 0; needsLight = false; }
        if ((task == Task.TO_STORAGE || task == Task.RESTOCKING && !hasCargo())
                && (storageReason == StorageReason.TOOL || storageReason == StorageReason.TORCHES
                    || task == Task.TO_STORAGE && storageReason == StorageReason.CAPACITY)
                && suitable(getHeldItem()) && (!needsLight || torchCount() > 0) && !cargoFull() && overflowDrops.isEmpty())
            returnToWork("storage need resolved by inventory change");
        if (!storageTask()) {
            if (!unfinished()) {
                if (hasCargo()) beginStorage(StorageReason.COMPLETE);
                else { transition(Task.COMPLETE,"work and deposits finished",null); stop("complete"); return; }
            } else if (!overflowDrops.isEmpty()) beginStorage(StorageReason.OVERFLOW);
            else if (cargoFull()) beginStorage(StorageReason.CAPACITY);
            else if ((cursor < totalWork() || rampCleanup >= 0) && !suitable(getHeldItem())) beginStorage(StorageReason.TOOL);
            else if ((cursor < totalWork() || rampCleanup >= 0) && needsLight && torchCount() == 0) beginStorage(StorageReason.TORCHES);
        }
        if (storageTask()) { visitChest(); return; }
        if (pendingDrops != null) {
            int x = pendingDrops[0], y = pendingDrops[1], z = pendingDrops[2];
            if (!worldObj.blockExists(x, y, z)) { status = "chunk"; return; }
            if (!WorkerAccess.ownsLand(worldObj, x, z, factionId)) { stop("denied"); return; }
            if (task != Task.DROPS) transition(Task.TO_WORK,"recover pending drops",new int[] {x,y+1,z});
            if (!moveNear(x, y + 1, z)) { if ("route_failed".equals(status)) terminalNavigation(); return; }
            transition(Task.DROPS,"at pending drop site",new int[] {x,y+1,z});
            if (collectDrops(x, y, z)) { pendingDrops = null; pendingDropsCaptured = false; returnToWork("pending drops recovered"); }
            else { pendingDropsCaptured = true; beginStorage(StorageReason.OVERFLOW); }
            return;
        }
        int width = plan[5] - plan[3] + 1, length = plan[6] - plan[4] + 1, layer = width * length;
        int total = layer * (plan[7] - plan[8] + 1);
        for (int scans = 0; scans < 64 && (cursor < total || rampCleanup >= 0); scans++) {
            boolean cleanup = cursor >= total;
            int workCursor = cleanup ? rampCursor(rampCleanup) : targetCursor >= cursor && targetCursor / layer == cursor / layer ? targetCursor : cursor;
            int x = plan[3] + workCursor % width, z = plan[4] + workCursor / width % length, y = plan[7] - workCursor / layer;
            if (!worldObj.blockExists(x, y, z)) { status = "chunk"; return; }
            Block block = worldObj.getBlock(x, y, z);
            if (worldObj.isAirBlock(x, y, z) || block == net.minecraft.init.Blocks.torch) { advance(workCursor); continue; }
            if (!cleanup && accessRamp && workCursor == rampCursor(workCursor / layer)) { advance(workCursor); continue; }
            if (!WorkerAccess.ownsLand(worldObj, x, z, factionId)) { stop("denied"); return; }
            if (block == net.minecraft.init.Blocks.bedrock || block.getBlockHardness(worldObj, x, y, z) < 0) {
                advance(workCursor); continue;
            }
            if (BuilderPlacement.protectedBlock(worldObj, x, y, z)) { stop("protected"); return; }
            if (worldObj.getTileEntity(x, y, z) != null) { stop("container"); return; }
            if (block.getMaterial().isLiquid()) { stop("liquid"); return; }
            int[] workGoal = new int[] {x,y+1,z};
            if (task != Task.MINING || !Arrays.equals(destination,workGoal)) transition(Task.TO_WORK,"approach unfinished target",workGoal);
            if (!moveNear(x, y + 1, z, true)) {
                if ("route_failed".equals(status)) {
                    if (cleanup) terminalNavigation();
                    else { rejectedTargets.set(workCursor); findAccessibleTarget(layer); }
                }
                return;
            }
            transition(Task.MINING,"at work position",workGoal);
            if (!visible(x, y, z)) {
                if ("chunk".equals(status)) return;
                resetMining();
                // A row-order target can be hidden behind another block in the same layer.
                // Search that layer without skipping any remaining work.
                if (cleanup) { navigationReason = "sight"; terminalNavigation(); }
                else findAccessibleTarget(layer);
                return;
            }
            if (!ensureReturnRoute(x,y,z)) {
                if ("chunk".equals(status)) return;
                if (returnFailureTarget != workCursor) { returnFailureTarget = workCursor; returnFailures = 0; }
                if (++returnFailures < 3) { status = "recovering"; return; }
                if (cleanup) terminalNavigation();
                else { rejectedTargets.set(workCursor); findAccessibleTarget(layer); }
                return;
            }
            returnFailures = 0; returnFailureTarget = -1;
            FakePlayer player = actor();
            player.setPosition(posX, posY, posZ); player.onGround = onGround;
            player.inventory.mainInventory[0] = getHeldItem(); player.inventory.currentItem = 0;
            int meta = worldObj.getBlockMetadata(x, y, z);
            if (!block.canHarvestBlock(player, meta)) {
                requiredHarvest = Math.max(0, block.getHarvestLevel(meta));
                player.inventory.mainInventory[0] = null; beginStorage(StorageReason.TOOL); resetMining(); return;
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
            if (!harvested) { stop("harvest_denied"); return; }
            rejectedTargets.clear();
            boolean collected = collectDrops(x, y, z);
            if (!collected) { pendingDrops = new int[] {x, y, z}; pendingDropsCaptured = true; }
            advance(workCursor);
            // The safety route was checked with precisely this block absent; do not append travel to it.
            if (!collected) beginStorage(StorageReason.OVERFLOW);
            else if (!unfinished()) {
                if (hasCargo()) beginStorage(StorageReason.COMPLETE);
                else { transition(Task.COMPLETE,"last block had no carried output",null); stop("complete"); }
            }
            else if (cargoFull()) beginStorage(StorageReason.CAPACITY);
            else if (getHeldItem() == null) { toolBroken = true; beginStorage(StorageReason.TOOL); }
            return;
        }
        if (cursor >= total && rampCleanup < 0) {
            if (hasCargo()) { beginStorage(StorageReason.COMPLETE); visitChest(); }
            else { transition(Task.COMPLETE,"no work or cargo remains",null); stop("complete"); }
        }
        else status = "scanning";
    }
    private void advance(int minedCursor) {
        int total = (plan[5]-plan[3]+1)*(plan[6]-plan[4]+1)*(plan[7]-plan[8]+1);
        if (cursor >= total && rampCleanup >= 0) rampCleanup--;
        else if (minedCursor == cursor) cursor++;
        targetCursor = -1; searchOffset = 0; resetMining();
        // A scan over air must not repeatedly cancel a useful movement path.
    }
    private int rampPerimeter() { return 2*(plan[5]-plan[3]+plan[6]-plan[4]); }
    private int[] rampColumn(int index) {
        int w = plan[5]-plan[3], l = plan[6]-plan[4];
        index = index % (2*(w+l));
        if (index < w) return new int[] {plan[3]+index,plan[4]};
        index -= w;
        if (index < l) return new int[] {plan[5],plan[4]+index};
        index -= l;
        if (index < w) return new int[] {plan[5]-index,plan[6]};
        return new int[] {plan[3],plan[6]-(index-w)};
    }
    private int rampCursor(int depth) {
        int[] r = rampColumn(rampOffset+depth);
        int width = plan[5]-plan[3]+1;
        return depth*width*(plan[6]-plan[4]+1)+(r[1]-plan[4])*width+r[0]-plan[3];
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
            if (rejectedTargets.get(candidate) || accessRamp && candidate == rampCursor(candidate/layer)) continue;
            if (!worldObj.blockExists(x, y, z)) { searchUnloaded = true; continue; }
            Block block = worldObj.getBlock(x, y, z);
            if (worldObj.isAirBlock(x, y, z) || block == net.minecraft.init.Blocks.torch || block.getMaterial().isLiquid()
                    || block.getBlockHardness(worldObj, x, y, z) < 0 || worldObj.getTileEntity(x, y, z) != null
                    || !WorkerAccess.ownsLand(worldObj, x, z, factionId) || BuilderPlacement.protectedBlock(worldObj, x, y, z)) continue;
            status = "searching";
            // A block may be approachable without already being within harvesting reach.
            if (standingCandidates(x,y+1,z,true).isEmpty()) continue;
            targetCursor = candidate; searchOffset = 0; resetPath(); status = "working"; return true;
        }
        if (cursor + searchOffset >= end) {
            searchOffset = 0;
            if (searchUnloaded) status = "chunk";
            else { if (navigationReason.isEmpty()) navigationReason = "sight"; terminalNavigation(); }
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
    private void beginCorridorRecovery() {
        // Retain task, storage reason, interaction destination and all job/item state.
        getNavigator().clearPathEntity(); travelRoute.clear(); recoveryRoute.clear(); waypoint = null;
        pathTicks = navigationFailures = routeRetries = 0; bestRouteProgress = -Double.MAX_VALUE;
        recoveryOrigin = new int[] {(int)Math.floor(posX),(int)Math.floor(boundingBox.minY+.1),(int)Math.floor(posZ)};
        recoveryDestination = null; recoveryAttempts = recoveryTicks = 0; recoveringCorridor = true;
        resetMining(); status = "corridor_recovery";
        navigationReason = "outside"; navigationDetail = "Recovery origin=" + Arrays.toString(recoveryOrigin);
    }
    /** Returns true while recovery owns movement, including the tick that finishes or stops it. */
    private boolean recoverCorridor() {
        int x = (int)Math.floor(posX), y = (int)Math.floor(boundingBox.minY+.1), z = (int)Math.floor(posZ);
        if (!recoveringCorridor) {
            if (inCorridor(x,z)) return false;
            beginCorridorRecovery();
        }
        recoveryTicks += 10;
        if (!inRecoveryBounds(x,y,z) || recoveryTicks > RECOVERY_TICKS) {
            if (!inRecoveryBounds(x,y,z) || !"chunk".equals(navigationReason)) navigationReason = "recovery_limit";
            navigationDetail = "Fixed recovery boundary/time limit; origin=" + Arrays.toString(recoveryOrigin);
            terminalNavigation(); return true;
        }
        if (onGround && inCorridor(x,z) && recoveryStanding(x,y,z)) {
            // Clear only temporary movement. The next work tick resumes the interrupted task.
            getNavigator().clearPathEntity(); recoveryRoute.clear(); travelRoute.clear(); waypoint = null;
            recoveringCorridor = false; recoveryOrigin = recoveryDestination = null;
            pathTicks = navigationFailures = routeRetries = 0; bestRouteProgress = -Double.MAX_VALUE;
            navigationReason = navigationDetail = ""; status = task.key; supplyCheckTicks = 0;
            return true;
        }
        moveNear(0,0,0,false,true);
        if ("route_failed".equals(status)) terminalNavigation();
        return true;
    }
    private boolean inRecoveryBounds(int x,int y,int z) {
        if (recoveryOrigin == null) return false;
        long dx = (long)x-recoveryOrigin[0], dz = (long)z-recoveryOrigin[2];
        return dx*dx+dz*dz <= RECOVERY_RADIUS*RECOVERY_RADIUS && Math.abs(y-recoveryOrigin[1]) <= RECOVERY_HEIGHT;
    }
    private boolean moveNear(int x, int y, int z) {
        return moveNear(x,y,z,false);
    }
    private boolean atDestination(int x,int y,int z,boolean mining) {
        if (!onGround || getDistanceSq(x+.5,y,z+.5) > 9 || mining && cursor >= totalWork() && posY <= y+.5) return false;
        int px = (int)Math.floor(posX), py = (int)Math.floor(boundingBox.minY+.1), pz = (int)Math.floor(posZ);
        return inCorridor(px,pz) && standing(px,py,pz,mining ? x : -30000001,mining ? y-1 : -1,z) && (!mining || visible(x,y-1,z));
    }
    private void arrived() {
        getNavigator().clearPathEntity(); travelRoute.clear(); waypoint = null;
        pathTicks = navigationFailures = routeRetries = 0; navigationReason = navigationDetail = "";
    }
    private boolean moveNear(int x, int y, int z, boolean mining) {
        return moveNear(x,y,z,mining,false);
    }
    private boolean moveNear(int x, int y, int z, boolean mining, boolean recovery) {
        List<int[]> route = recovery ? recoveryRoute : travelRoute;
        status = recovery ? "corridor_recovery" : task.key;
        int[] goal = new int[] {x,y,z};
        if (!recovery) {
            if (!Arrays.equals(destination,goal)) { resetPath(); destination = goal; }
            if (atDestination(x,y,z,mining)) { arrived(); return true; }
            if (task == Task.MINING || task == Task.DROPS) transition(Task.TO_WORK,"work position changed",goal);
            else if (storageTask() && task != Task.TO_STORAGE) transition(Task.TO_STORAGE,"station interaction position changed",goal);
        }
        String movingStatus = recovery ? "corridor_recovery" : task.key;
        resetMining();
        if (!onGround) {
            getNavigator().clearPathEntity(); pathTicks += 10; status = recovery ? movingStatus : "recovering";
            if (pathTicks >= 100) { navigationReason = "footing"; navigationDetail = "Start has no supported footing"; status = "route_failed"; }
            return false;
        }
        if (waypoint != null && !segmentChunksLoaded(waypoint)) {
            getNavigator().clearPathEntity(); status = recovery ? movingStatus : "chunk";
            navigationReason = "chunk"; pathTicks = 0;
            if (!recovery) return false;
            // A different loaded entry may remain reachable. Replan inside the original bounds.
            route.clear(); waypoint = null; routeRetries++;
        }
        if (!route.isEmpty()) {
            int previous = routeIndex;
            for (int i = routeIndex+1; i <= segmentEnd && i < route.size(); i++) {
                int[] next = route.get(i);
                if (getDistanceSq(next[0]+.5,next[1],next[2]+.5) < .36) routeIndex = i;
            }
            int[] next = route.get(Math.min(routeIndex+1,route.size()-1));
            double progress = routeIndex-Math.min(1,Math.sqrt(getDistanceSq(next[0]+.5,next[1],next[2]+.5)));
            if (progress > bestRouteProgress+.1) { bestRouteProgress = progress; pathTicks = 0; }
            else pathTicks += 10;
            if (routeIndex > previous) navigationFailures = 0;
            boolean intact = routeIntact(route,routeIndex,Math.min(route.size()-1,routeIndex+8),
                    mining ? x : -30000001,mining ? y-1 : -1,z,recovery);
            if (!intact || getDistanceSq(next[0]+.5,next[1],next[2]+.5) > 16 || pathTicks >= 100) {
                navigationReason = intact ? "stalled" : "path";
                if (!intact) for (int i = routeIndex; i <= Math.min(route.size()-1,routeIndex+8); i++) {
                    int[] p = route.get(i);
                    if (!worldObj.blockExists(p[0],p[1]-1,p[2]) || !worldObj.blockExists(p[0],p[1]+1,p[2])) {
                        navigationReason = "chunk"; break;
                    }
                }
                navigationDetail = "Route stopped at step " + routeIndex + "/" + (route.size()-1)
                        + "; next=" + Arrays.toString(next) + (intact ? "; no forward route progress" : "; footing/headroom changed");
                getNavigator().clearPathEntity(); route.clear(); routeRetries++; pathTicks = 0;
            } else if (!getNavigator().noPath()) { status = movingStatus; return false; }
            else if (routeIndex+1 >= route.size()) {
                // A valid path endpoint is still not arrival unless the interaction check above succeeds.
                navigationReason = "standing"; navigationDetail = "Route endpoint no longer permits interaction";
                route.clear(); routeRetries++;
            }
        }
        if (routeRetries >= 4 || navigationFailures >= 3) { status = "route_failed"; return false; }
        if (route.isEmpty()) {
            if (recovery && recoveryAttempts >= RECOVERY_ATTEMPTS) { status = "route_failed"; return false; }
            List<int[]> goals = recovery ? java.util.Collections.<int[]>emptyList() : standingCandidates(x,y,z,mining);
            if (!recovery && goals.isEmpty()) { navigationReason = "standing"; navigationDetail = standingObstruction(x,y,z); }
            else {
                // Search complete routes first, including direct approaches; a verified cached
                // route remains a safe fallback when the bounded search cannot rediscover it.
                if (recovery) recoveryAttempts++;
                List<int[]> found = searchRoute(goals,mining ? x : -30000001,mining ? y-1 : -1,z,recovery);
                if (found == null && !recovery) found = cachedTravel(goals,mining ? x : -30000001,mining ? y-1 : -1,z);
                if (found != null && !found.isEmpty()) {
                    route.addAll(found); routeIndex = segmentEnd = 0;
                    if (recovery) recoveryDestination = found.get(found.size()-1).clone();
                    bestRouteProgress = -Double.MAX_VALUE; pathTicks = 0;
                }
            }
            if (route.isEmpty()) {
                if (recovery) {
                    status = recoveryAttempts >= RECOVERY_ATTEMPTS || "footing".equals(navigationReason) ? "route_failed" : movingStatus;
                    return false;
                }
                if (!"chunk".equals(status)) { navigationFailures++; status = navigationFailures >= 3 ? "route_failed" : "recovering"; }
                return false;
            }
        }
        // Route candidates are ranked by complete safe route cost, not an angular rotation.
        // Give vanilla only a straight, verified run (at most six edges). Its shortcutting cannot
        // cut a corner across water/a pit or bypass a height change on these segments.
        segmentEnd = Math.min(routeIndex+1,route.size()-1);
        int[] from = route.get(routeIndex), first = route.get(segmentEnd);
        int dx = first[0]-from[0], dz = first[2]-from[2];
        if (segmentEnd > routeIndex && first[1] == from[1]) while (segmentEnd+1 < route.size() && segmentEnd-routeIndex < 6) {
            int[] a = route.get(segmentEnd), b = route.get(segmentEnd+1);
            if (b[1] != a[1] || b[0]-a[0] != dx || b[2]-a[2] != dz) break;
            segmentEnd++;
        }
        PathPoint[] points = new PathPoint[segmentEnd-routeIndex+1];
        for (int i = 0; i < points.length; i++) {
            int[] node = route.get(routeIndex+i); points[i] = new PathPoint(node[0],node[1],node[2]);
        }
        waypoint = route.get(segmentEnd).clone();
        if (!segmentChunksLoaded(waypoint)) { status = recovery ? movingStatus : "chunk"; navigationReason = "chunk"; pathTicks = 0; return false; }
        // setPath compares only coordinates with the previous path, retaining its finished index.
        getNavigator().clearPathEntity();
        if (!getNavigator().setPath(new PathEntity(points),1)) {
            navigationReason = "path"; navigationDetail = "Navigator rejected verified route segment";
            route.clear(); navigationFailures++; status = recovery ? movingStatus : "recovering"; return false;
        }
        status = movingStatus; navigationReason = navigationDetail = ""; return false;
    }
    private boolean segmentChunksLoaded(int[] end) {
        int px = (int)Math.floor(posX), pz = (int)Math.floor(posZ);
        for (int cx = (Math.min(px,end[0])-2)>>4; cx <= (Math.max(px,end[0])+2)>>4; cx++)
            for (int cz = (Math.min(pz,end[2])-2)>>4; cz <= (Math.max(pz,end[2])+2)>>4; cz++)
                if (!worldObj.blockExists(cx<<4,Math.max(0,Math.min(worldObj.getHeight()-1,(int)posY)),cz<<4)) return false;
        return true;
    }
    private boolean recoveryCellLoaded(int x,int y,int z) {
        // Match the native follower's neighbor margin while choosing reachable entries.
        for (int cx = (x-2)>>4; cx <= (x+2)>>4; cx++)
            for (int cz = (z-2)>>4; cz <= (z+2)>>4; cz++)
                if (!worldObj.blockExists(cx<<4,y,cz<<4)) return false;
        return true;
    }
    private List<int[]> standingCandidates(int x,int y,int z,boolean mining) {
        List<int[]> result = new java.util.ArrayList<int[]>();
        for (int dx = -2; dx <= 2; dx++) for (int dz = -2; dz <= 2; dz++) for (int dy = -2; dy <= 2; dy++) {
            if (dx*dx+dy*dy+dz*dz > 9 || mining && dx == 0 && dz == 0 && dy == 0) continue;
            int sx = x+dx, sy = y+dy, sz = z+dz;
            if (!standing(sx,sy,sz,mining ? x : -30000001,mining ? y-1 : -1,z)) continue;
            // During ramp removal, back up onto the higher step before harvesting the lower one.
            if (mining && cursor >= (plan[5]-plan[3]+1)*(plan[6]-plan[4]+1)*(plan[7]-plan[8]+1) && sy <= y) continue;
            if (mining && !sight(sx+.5,sy+getEyeHeight(),sz+.5,x,y-1,z)) continue;
            result.add(new int[] {sx,sy,sz});
        }
        java.util.Collections.sort(result,(a,b) -> Double.compare(getDistanceSq(a[0]+.5,a[1],a[2]+.5),getDistanceSq(b[0]+.5,b[1],b[2]+.5)));
        return result;
    }
    private boolean standing(int x,int y,int z,int removedX,int removedY,int removedZ) {
        if (y < 1 || y+1 >= worldObj.getHeight() || !worldObj.blockExists(x,y-1,z)
                || !worldObj.blockExists(x,y+1,z) || x == removedX && y-1 == removedY && z == removedZ) return false;
        if (!worldObj.isSideSolid(x,y-1,z,net.minecraftforge.common.util.ForgeDirection.UP)) return false;
        for (int dy = 0; dy < 2; dy++) {
            Block block = worldObj.getBlock(x,y+dy,z);
            if (block.getMaterial().isLiquid() || !block.getBlocksMovement(worldObj,x,y+dy,z)) return false;
        }
        return true;
    }
    private boolean recoveryStanding(int x,int y,int z) {
        if (!standing(x,y,z,-30000001,-1,0)) return false;
        // Passable materials can still have collision shapes (for example thin snow).
        // This body fits inside the checked loaded column; do not use world entity/chunk scans.
        AxisAlignedBB body = AxisAlignedBB.getBoundingBox(x+.5-width/2,y,z+.5-width/2,x+.5+width/2,y+height,z+.5+width/2);
        recoveryCollisions.clear();
        for (int dy = -1; dy <= 1; dy++)
            worldObj.getBlock(x,y+dy,z).addCollisionBoxesToList(worldObj,x,y+dy,z,body,recoveryCollisions,this);
        return recoveryCollisions.isEmpty();
    }
    private boolean sight(double sx,double sy,double sz,int x,int y,int z) {
        for (int cx = Math.min((int)Math.floor(sx),x)>>4; cx <= (Math.max((int)Math.floor(sx),x)>>4); cx++)
            for (int cz = Math.min((int)Math.floor(sz),z)>>4; cz <= (Math.max((int)Math.floor(sz),z)>>4); cz++)
                if (!worldObj.blockExists(cx<<4,y,cz<<4)) return false;
        net.minecraft.util.MovingObjectPosition hit = worldObj.func_147447_a(net.minecraft.util.Vec3.createVectorHelper(sx,sy,sz),
                net.minecraft.util.Vec3.createVectorHelper(x+.5,y+.5,z+.5),false,true,false);
        return hit == null || hit.blockX == x && hit.blockY == y && hit.blockZ == z;
    }
    private static long positionKey(int x,int y,int z) {
        return ((long)x & 0x3ffffffL)<<38 | ((long)z & 0x3ffffffL)<<12 | y & 0xfffL;
    }
    private boolean inCorridor(int x,int z) {
        if (x >= plan[3]-8 && x <= plan[5]+8 && z >= plan[4]-8 && z <= plan[6]+8) return true;
        int wx = Math.max(plan[3],Math.min(plan[5],plan[0])), wz = Math.max(plan[4],Math.min(plan[6],plan[2]));
        return corridorLeg(x,z,wx,wz) || pendingDrops != null && corridorLeg(x,z,pendingDrops[0],pendingDrops[2]);
    }
    private boolean corridorLeg(int x,int z,int wx,int wz) {
        return corridorLegDistanceSq(x,z,wx,wz) <= 256;
    }
    private double corridorLegDistanceSq(int x,int z,int wx,int wz) {
        double dx = wx-plan[0], dz = wz-plan[2], length = dx*dx+dz*dz;
        double t = length == 0 ? 0 : Math.max(0,Math.min(1,((x-plan[0])*dx+(z-plan[2])*dz)/length));
        double ox = x-plan[0]-t*dx, oz = z-plan[2]-t*dz;
        return ox*ox+oz*oz;
    }
    /** Lower bound to any corridor entry, not a commitment to the nearest geometric entry.
     * Every supported entry reached by the search is a candidate, including detours. */
    private int recoveryHeuristic(int x,int z) {
        int dx = Math.max(0,Math.max(plan[3]-8-x,x-plan[5]-8));
        int dz = Math.max(0,Math.max(plan[4]-8-z,z-plan[6]-8));
        double distance = Math.sqrt((double)dx*dx+(double)dz*dz);
        int wx = Math.max(plan[3],Math.min(plan[5],plan[0])), wz = Math.max(plan[4],Math.min(plan[6],plan[2]));
        distance = Math.min(distance,Math.max(0,Math.sqrt(corridorLegDistanceSq(x,z,wx,wz))-16));
        if (pendingDrops != null) distance = Math.min(distance,Math.max(0,Math.sqrt(corridorLegDistanceSq(x,z,pendingDrops[0],pendingDrops[2]))-16));
        return (int)Math.floor(10*distance);
    }
    private boolean safeEdge(int[] a,int[] b) {
        if (Math.abs(a[0]-b[0])+Math.abs(a[2]-b[2]) != 1 || Math.abs(a[1]-b[1]) > 1) return false;
        if (a[1] == b[1]) return true;
        int[] low = a[1] < b[1] ? a : b;
        return worldObj.blockExists(low[0],low[1]+2,low[2])
                && !worldObj.getBlock(low[0],low[1]+2,low[2]).getMaterial().isLiquid()
                && worldObj.getBlock(low[0],low[1]+2,low[2]).getBlocksMovement(worldObj,low[0],low[1]+2,low[2]);
    }
    private boolean routeIntact(List<int[]> route,int from,int to,int removedX,int removedY,int removedZ) {
        return routeIntact(route,from,to,removedX,removedY,removedZ,false);
    }
    private boolean routeIntact(List<int[]> route,int from,int to,int removedX,int removedY,int removedZ,boolean recovery) {
        for (int i = from; i <= to; i++) {
            int[] p = route.get(i);
            if (!(recovery ? inRecoveryBounds(p[0],p[1],p[2]) && recoveryStanding(p[0],p[1],p[2])
                    : inCorridor(p[0],p[2]) && standing(p[0],p[1],p[2],removedX,removedY,removedZ))
                    || i > from && !safeEdge(route.get(i-1),p)) return false;
        }
        return true;
    }
    private int routePosition(List<int[]> route) {
        int px = (int)Math.floor(posX), py = (int)Math.floor(boundingBox.minY+.1), pz = (int)Math.floor(posZ);
        for (int i = 0; i < route.size(); i++) {
            int[] p = route.get(i); if (p[0] == px && p[1] == py && p[2] == pz) return i;
        }
        return -1;
    }
    private boolean stationEndpoint(int[] p) {
        return (double)(p[0]-plan[0])*(p[0]-plan[0])+(double)(p[1]-plan[1])*(p[1]-plan[1])
                +(double)(p[2]-plan[2])*(p[2]-plan[2]) <= 9;
    }
    private List<int[]> cachedTravel(List<int[]> goals,int removedX,int removedY,int removedZ) {
        if (returnRoute.isEmpty() || !stationEndpoint(returnRoute.get(0))) { returnRoute.clear(); return null; }
        int here = routePosition(returnRoute), there = -1, cost = Integer.MAX_VALUE;
        if (here < 0) return null;
        for (int i = 0; i < returnRoute.size(); i++) for (int[] goal : goals) {
            if (Arrays.equals(returnRoute.get(i),goal) && Math.abs(i-here) < cost) { there = i; cost = Math.abs(i-here); }
        }
        if (there < 0) return null;
        if (!routeIntact(returnRoute,Math.min(here,there),Math.max(here,there),removedX,removedY,removedZ)) {
            returnRoute.clear(); return null;
        }
        List<int[]> result = new java.util.ArrayList<int[]>();
        int step = there >= here ? 1 : -1;
        for (int i = here;; i += step) { result.add(returnRoute.get(i).clone()); if (i == there) break; }
        return result;
    }
    private static int routeHeuristic(int x,int y,int z,List<int[]> goals) {
        int best = Integer.MAX_VALUE;
        for (int[] goal : goals) best = Math.min(best,10*(Math.abs(x-goal[0])+Math.abs(z-goal[2]))+4*Math.abs(y-goal[1]));
        return best;
    }
    /** Bounded A*: rank every interaction endpoint by actual route cost plus admissible progress.
     * Cardinal edges may climb/descend one block and must be safe in both directions. */
    private List<int[]> searchRoute(List<int[]> goals,int removedX,int removedY,int removedZ) {
        return searchRoute(goals,removedX,removedY,removedZ,false);
    }
    private List<int[]> searchRoute(List<int[]> goals,int removedX,int removedY,int removedZ,boolean recovery) {
        int sx = (int)Math.floor(posX), sy = (int)Math.floor(boundingBox.minY+.1), sz = (int)Math.floor(posZ);
        if (sy < 1 || sy+1 >= worldObj.getHeight()) {
            navigationReason = "footing"; navigationDetail = "Start outside supported world height: " + sy; return null;
        }
        if (!worldObj.blockExists(sx,sy-1,sz) || !worldObj.blockExists(sx,sy+1,sz)
                || recovery && !recoveryCellLoaded(sx,sy,sz)) {
            navigationReason = "chunk"; navigationDetail = "Start chunk unavailable: " + sx+","+sy+","+sz;
            status = recovery ? "corridor_recovery" : "chunk"; return null;
        }
        if (!(recovery ? recoveryStanding(sx,sy,sz) : standing(sx,sy,sz,removedX,removedY,removedZ))) {
            navigationReason = !worldObj.isSideSolid(sx,sy-1,sz,net.minecraftforge.common.util.ForgeDirection.UP)
                    || sx == removedX && sy-1 == removedY && sz == removedZ ? "footing" : "clearance";
            navigationDetail = "Unsupported or obstructed start: " + sx+","+sy+","+sz; return null;
        }
        if (!recovery && !inCorridor(sx,sz)) {
            navigationReason = "outside"; navigationDetail = "Supported start outside job corridor: " + sx+","+sy+","+sz; return null;
        }
        java.util.Set<Long> endpoints = new java.util.HashSet<Long>();
        for (int[] goal : goals) if (inCorridor(goal[0],goal[2])) endpoints.add(positionKey(goal[0],goal[1],goal[2]));
        if (!recovery && endpoints.isEmpty()) { navigationReason = "standing"; navigationDetail = "No valid endpoint inside the station/work corridor"; return null; }
        java.util.Map<Long,int[]> visited = new java.util.HashMap<Long,int[]>();
        java.util.Map<Long,Long> parents = new java.util.HashMap<Long,Long>();
        java.util.PriorityQueue<int[]> open = new java.util.PriorityQueue<int[]>(64,(a,b) -> {
            int order = Integer.compare(a[4],b[4]); return order != 0 ? order : Integer.compare(b[3],a[3]);
        });
        long start = positionKey(sx,sy,sz);
        int[] first = new int[] {sx,sy,sz,0,recovery ? recoveryHeuristic(sx,sz) : routeHeuristic(sx,sy,sz,goals)};
        visited.put(start,first); open.add(first);
        int expanded = 0; boolean unloaded = false, distanceLimited = false;
        while (!open.isEmpty() && expanded < 8192 && visited.size() < 16384) {
            int[] p = open.poll(); long key = positionKey(p[0],p[1],p[2]);
            if (visited.get(key) != p) continue;
            expanded++;
            if (recovery ? inCorridor(p[0],p[2]) : endpoints.contains(key)) {
                List<int[]> result = new java.util.ArrayList<int[]>();
                while (true) {
                    int[] node = visited.get(key); result.add(new int[] {node[0],node[1],node[2]});
                    if (key == start) break;
                    if (result.size() >= 4096) { navigationReason = "detour_limit"; navigationDetail = "Route exceeds 4096 positions"; return null; }
                    key = parents.get(key);
                }
                java.util.Collections.reverse(result); return result;
            }
            for (int direction = 0; direction < 4; direction++) for (int stepY = -1; stepY <= 1; stepY++) {
                int nx = p[0]+(direction == 0 ? 1 : direction == 1 ? -1 : 0);
                int nz = p[2]+(direction == 2 ? 1 : direction == 3 ? -1 : 0), ny = p[1]+stepY;
                if (ny < 1 || ny+1 >= worldObj.getHeight()) continue;
                if (recovery ? !inRecoveryBounds(nx,ny,nz) : !inCorridor(nx,nz)) {
                    if (recovery) distanceLimited = true;
                    continue;
                }
                if (!worldObj.blockExists(nx,ny,nz) || recovery && !recoveryCellLoaded(nx,ny,nz)) { unloaded = true; continue; }
                long next = positionKey(nx,ny,nz); int cost = p[3]+10+4*Math.abs(stepY);
                if (recovery && cost > RECOVERY_COST) { distanceLimited = true; continue; }
                int[] old = visited.get(next);
                if (old != null && old[3] <= cost || !(recovery ? recoveryStanding(nx,ny,nz) : standing(nx,ny,nz,removedX,removedY,removedZ))) continue;
                int[] node = new int[] {nx,ny,nz,cost,cost+(recovery ? recoveryHeuristic(nx,nz) : routeHeuristic(nx,ny,nz,goals))};
                if (!safeEdge(p,node)) continue;
                if (old == null && visited.size() >= 16384) continue;
                visited.put(next,node); parents.put(next,key); open.add(node);
            }
        }
        navigationReason = !open.isEmpty() ? "search_limit" : unloaded ? "chunk" : distanceLimited ? "recovery_limit" : "path";
        navigationDetail = "No complete safe route; expanded=" + expanded + " discovered=" + visited.size()
                + " endpoints=" + (recovery ? "any supported corridor entry" : endpoints.size())
                + (recovery ? "; fixed recovery origin=" + Arrays.toString(recoveryOrigin) : "; bounded station/work corridor");
        if ("chunk".equals(navigationReason)) status = recovery ? "corridor_recovery" : "chunk";
        return null;
    }
    /** Canonical cache order is storage -> current work position. Check only the prefix needed
     * to leave now; discard stale suffixes rather than joining old trips in opposite directions. */
    private boolean ensureReturnRoute(int removedX,int removedY,int removedZ) {
        int here = routePosition(returnRoute);
        if (here >= 0 && stationEndpoint(returnRoute.get(0))
                && routeIntact(returnRoute,0,here,removedX,removedY,removedZ)) {
            while (returnRoute.size() > here+1) returnRoute.remove(returnRoute.size()-1);
            return true;
        }
        returnRoute.clear();
        status = task.key;
        List<int[]> goals = standingCandidates(plan[0],plan[1],plan[2],false);
        List<int[]> route = goals.isEmpty() ? null : searchRoute(goals,removedX,removedY,removedZ);
        if (route != null) {
            java.util.Collections.reverse(route); returnRoute.addAll(route); navigationReason = navigationDetail = ""; return true;
        }
        if (!"chunk".equals(status)) {
            navigationReason = "search_limit".equals(navigationReason) ? "return_limit" : "return_blocked";
            navigationDetail = "Cannot leave after harvesting " + removedX+","+removedY+","+removedZ + ": " + navigationDetail;
        }
        return false;
    }
    private String standingObstruction(int x,int y,int z) {
        for (int dy = -1; dy <= 1; dy++) {
            if (!worldObj.blockExists(x,y+dy,z)) return "Unloaded position " + x+","+(y+dy)+","+z;
            Block b = worldObj.getBlock(x,y+dy,z);
            if (dy == -1 ? !worldObj.isSideSolid(x,y-1,z,net.minecraftforge.common.util.ForgeDirection.UP)
                    : b.getMaterial().isLiquid() || !b.getBlocksMovement(worldObj,x,y+dy,z))
                return (dy == -1 ? "Unsupported floor: " : "Blocked headroom/feet: ") + Block.blockRegistry.getNameForObject(b) + " at " + x+","+(y+dy)+","+z;
        }
        return "No visible supported approach near " + x+","+y+","+z;
    }
    private void terminalNavigation() {
        // Preserve diagnostics before stop/reset clears the live movement state.
        int[] dest = destination == null ? new int[0] : destination.clone();
        int[] step = waypoint == null ? new int[0] : waypoint.clone();
        String reason = navigationReason.isEmpty() ? "path" : navigationReason;
        int[] recoveryStart = recoveryOrigin, recoveryEnd = recoveryDestination;
        int attempts = recoveryAttempts;
        long now = System.nanoTime();
        if (lastNavigationLog == 0 || now-lastNavigationLog >= 60000000000L) {
            lastNavigationLog = now;
            XFLog.warn("Miner " + getUniqueID() + " navigation=" + reason + " pos=" + posX+","+posY+","+posZ
                    + " task=" + task.key + " storageReason=" + storageReason.key
                    + " cursor=" + cursor + " target=" + targetCursor + " cleanup=" + rampCleanup
                    + " destination=" + Arrays.toString(dest) + " waypoint=" + Arrays.toString(step)
                    + " recoveryOrigin=" + Arrays.toString(recoveryStart) + " recoveryDestination=" + Arrays.toString(recoveryEnd)
                    + " recoveryAttempts=" + attempts
                    + " range=" + getNavigator().getPathSearchRange() + " bounds=" + Arrays.toString(plan) + " detail=" + navigationDetail);
        }
        stop("navigation_failed"); navigationReason = reason; destination = dest.length == 3 ? dest : null; waypoint = step.length == 3 ? step : null;
        recoveryOrigin = recoveryStart; recoveryDestination = recoveryEnd; recoveryAttempts = attempts;
    }
    private void visitChest() {
        if (!worldObj.blockExists(plan[0], plan[1], plan[2])) { status = "chunk"; return; }
        IInventory chest = stationInventory(plan[0], plan[1], plan[2]);
        if (chest == null) { status = stationUnavailable(plan[0], plan[1], plan[2]); return; }
        if (!moveNear(plan[0], plan[1], plan[2])) { if ("route_failed".equals(status)) terminalNavigation(); return; }
        if (task != Task.RESTOCKING || hasCargo() || !overflowDrops.isEmpty())
            transition(Task.DEPOSITING,"at storage: " + storageReason.key,new int[] {plan[0],plan[1],plan[2]});
        equipCarriedTool();
        stockTorchesFromCargo();
        for (ItemStack stack : cargo) if (stack != null) insert(chest, stack);
        for (int i = 0; i < cargo.length; i++) if (cargo[i] != null && cargo[i].stackSize <= 0) cargo[i] = null;
        for (java.util.Iterator<ItemStack> items = overflowDrops.iterator(); items.hasNext();) {
            ItemStack stack = items.next(); insert(chest,stack); if (stack.stackSize <= 0) items.remove();
        }
        chest.markDirty();
        if (hasCargo() || !overflowDrops.isEmpty()) { status = "storage_full"; supplyCheckTicks = 40; return; }
        // New overflow was entirely captured from actual entities. Once it is deposited there
        // is nothing left at that site; legacy coordinate-only saves still need a collection visit.
        if (pendingDropsCaptured) { pendingDrops = null; pendingDropsCaptured = false; }
        toolBroken = false;
        int total = (plan[5] - plan[3] + 1) * (plan[6] - plan[4] + 1) * (plan[7] - plan[8] + 1);
        if (cursor >= total && rampCleanup < 0 && pendingDrops == null) {
            transition(Task.COMPLETE,"final deposit finished",null); stop("complete"); return;
        }
        transition(Task.RESTOCKING,"deposit finished; check required supplies",new int[] {plan[0],plan[1],plan[2]});
        boolean miningRemains = cursor < total || rampCleanup >= 0;
        if (miningRemains && !suitable(getHeldItem())) {
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
        if (miningRemains && needsLight && torchCount() == 0) {
            requestedTorches = 1; status = "no_torches"; supplyCheckTicks = 40; return;
        }
        requestedTorches = 0;
        returnToWork("deposit and required restocking finished");
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
        int current = cursor >= width*length*(plan[7]-plan[8]+1) && rampCleanup >= 0 ? rampCursor(rampCleanup)
                : targetCursor >= cursor && targetCursor/(width*length) == cursor/(width*length) ? targetCursor : cursor;
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
        if (torchCount() == 0) { beginStorage(StorageReason.TORCHES); return false; }
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
            if (moving.stackSize > 0) {
                // Keep the actual harvest remainder, not another drop roll or a despawning world item.
                capacityDetail = "output=" + net.minecraft.item.Item.itemRegistry.getNameForObject(moving.getItem())
                        + ":" + moving.getItemDamage() + " remaining=" + moving.stackSize + " compatibleCapacity=" + remainingCapacity(moving);
                overflowDrops.add(moving.copy()); all = false;
            }
            entity.setDead();
        }
        return all;
    }
    private boolean hasCargo() { for (ItemStack stack : cargo) if (stack != null && stack.stackSize > 0) return true; return false; }
    private boolean cargoFull() {
        for (ItemStack stack : cargo) if (stack == null || stack.stackSize < Math.min(getInventoryStackLimit(),stack.getMaxStackSize())) return false;
        return true;
    }
    private int remainingCapacity(ItemStack output) {
        int capacity = 0, limit = Math.min(getInventoryStackLimit(),output.getMaxStackSize());
        for (ItemStack stack : cargo) {
            if (stack == null) capacity += limit;
            else if (stack.isItemEqual(output) && ItemStack.areItemStackTagsEqual(stack,output)) capacity += Math.max(0,limit-stack.stackSize);
        }
        return capacity;
    }
    private String cargoCapacityDetail() {
        int occupied = 0, partialCapacity = 0;
        for (ItemStack stack : cargo) if (stack != null) {
            occupied++; partialCapacity += Math.max(0,Math.min(getInventoryStackLimit(),stack.getMaxStackSize())-stack.stackSize);
        }
        return "occupied=" + occupied + "/" + cargo.length + " partialStackCapacity=" + partialCapacity;
    }
    private void updateRequests() {
        requestedTools = requestedTorches = 0;
        if (worldObj.isRemote || !configured || !authorized() || jobDimension != dimension) return;
        int total = (plan[5] - plan[3] + 1) * (plan[6] - plan[4] + 1) * (plan[7] - plan[8] + 1);
        if (cursor >= total && rampCleanup < 0) return;
        boolean tool = suitable(getHeldItem());
        int lights = torchCount();
        for (ItemStack stack : cargo) {
            if (!tool && suitable(stack)) tool = true;
            if (isTorch(stack)) lights += stack.stackSize;
        }
        for (ItemStack stack : overflowDrops) {
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
        tag.setBoolean("Down",installedDown);
        tag.setBoolean("AccessRamp",accessRamp); tag.setInteger("RampOffset",rampOffset); tag.setInteger("RampCleanup",rampCleanup);
        tag.setString("NavigationReason",navigationReason);
        tag.setString("NavigationDetail",navigationReason.isEmpty() ? "" : navigationDetail);
        if (recoveryOrigin != null) tag.setIntArray("RecoveryOrigin",recoveryOrigin);
        if (recoveryDestination != null) tag.setIntArray("RecoveryDestination",recoveryDestination);
        tag.setInteger("RecoveryAttempts",recoveryAttempts);
        if (destination != null) tag.setIntArray("Destination",destination);
        if (waypoint != null) tag.setIntArray("Waypoint",waypoint);
        tag.setInteger("PickaxesRequested", requestedTools);
        tag.setInteger("HarvestLevel", requiredHarvest);
        tag.setInteger("TorchesRequested", requestedTorches);
        tag.setInteger("TorchRestock", Math.max(0, 32 - torchCount()));
        tag.setInteger("WorkCursor", targetCursor);
        tag.setInteger("TaskVersion",1);
        tag.setString("Task", !configured ? "unassigned" : task.key);
        tag.setString("StorageReason",storageReason.key);
        int overflow = 0; for (ItemStack stack : overflowDrops) overflow += stack.stackSize;
        tag.setInteger("Overflow",overflow);
        int width = plan[5] - plan[3] + 1, length = plan[6] - plan[4] + 1;
        int current = cursor >= width*length*(plan[7]-plan[8]+1) && rampCleanup >= 0 ? rampCursor(rampCleanup) : targetCursor >= cursor ? targetCursor : cursor;
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
        tag.setString("EmployerName", employerName); tag.setTag("MiningJob", snapshot()); tag.setBoolean("ToolBroken", toolBroken);
        if (pendingDrops != null) { tag.setIntArray("PendingDrops", pendingDrops); tag.setBoolean("PendingDropsCaptured",pendingDropsCaptured); }
        NBTTagList list = new NBTTagList();
        for (int i = 0; i < cargo.length; i++) if (cargo[i] != null) { NBTTagCompound entry = cargo[i].writeToNBT(new NBTTagCompound()); entry.setByte("Slot", (byte)i); list.appendTag(entry); }
        tag.setTag("Cargo", list);
        NBTTagList overflow = new NBTTagList();
        for (ItemStack stack : overflowDrops) overflow.appendTag(stack.writeToNBT(new NBTTagCompound()));
        tag.setTag("OverflowDrops",overflow);
        if (torches != null) tag.setTag("Torches", torches.writeToNBT(new NBTTagCompound()));
        tag.setInteger("RequiredHarvest", requiredHarvest);
        tag.setBoolean("NeedsLight",needsLight);
        tag.setLong("SelectionRevision",selectionRevision);
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
        installedDown = job.hasKey("Down") ? job.getBoolean("Down") : configured && plan[8] == 0;
        accessRamp = configured && job.getBoolean("AccessRamp") && plan[5] > plan[3] && plan[6] > plan[4];
        rampOffset = accessRamp ? Math.max(0,job.getInteger("RampOffset")) % rampPerimeter() : 0;
        rampCleanup = accessRamp ? Math.max(-1,Math.min(plan[7]-plan[8],job.getInteger("RampCleanup"))) : -1;
        selectionRevision = Math.max(0,tag.getLong("SelectionRevision"));
        returnRoute.clear(); rejectedTargets.clear(); resetPath(); navigationReason = "";
        torches = tag.hasKey("Torches") ? ItemStack.loadItemStackFromNBT(tag.getCompoundTag("Torches")) : null;
        requiredHarvest = tag.hasKey("RequiredHarvest") ? tag.getInteger("RequiredHarvest") : -1;
        cursor = Math.max(0, job.getInteger("Cursor")); running = false; status = "paused"; toolBroken = tag.getBoolean("ToolBroken");
        task = Task.TO_WORK; storageReason = StorageReason.NONE;
        if (job.getInteger("TaskVersion") == 1) {
            for (Task value : Task.values()) if (value.key.equals(job.getString("Task"))) task = value;
            for (StorageReason value : StorageReason.values()) if (value.key.equals(job.getString("StorageReason"))) storageReason = value;
            if (storageTask() && storageReason == StorageReason.NONE) task = Task.TO_WORK;
        }
        needsLight = tag.hasKey("NeedsLight") ? tag.getBoolean("NeedsLight") : storageReason == StorageReason.TORCHES;
        // Legacy Returning encoded both partial cargo and supply trips. Reevaluate actual needs
        // on Start rather than translating that ambiguous boolean into a mandatory deposit.
        int layer = (plan[5] - plan[3] + 1) * (plan[6] - plan[4] + 1);
        targetCursor = job.hasKey("WorkCursor") ? job.getInteger("WorkCursor") : -1;
        if (layer <= 0 || targetCursor < cursor || targetCursor / layer != cursor / layer) targetCursor = -1;
        int[] site = tag.getIntArray("PendingDrops"); pendingDrops = site.length == 3 ? site : null;
        pendingDropsCaptured = pendingDrops != null && tag.getBoolean("PendingDropsCaptured");
        Arrays.fill(cargo, null); NBTTagList list = tag.getTagList("Cargo", 10);
        for (int i = 0; i < list.tagCount(); i++) { NBTTagCompound entry = list.getCompoundTagAt(i); int slot = entry.getByte("Slot") & 255; if (slot < cargo.length) cargo[slot] = ItemStack.loadItemStackFromNBT(entry); }
        overflowDrops.clear(); NBTTagList overflow = tag.getTagList("OverflowDrops",10);
        for (int i = 0; i < overflow.tagCount(); i++) {
            ItemStack stack = ItemStack.loadItemStackFromNBT(overflow.getCompoundTagAt(i));
            if (stack != null && stack.stackSize > 0) overflowDrops.add(stack);
        }
    }
    protected void dropFewItems(boolean recentlyHit, int looting) {
        super.dropFewItems(recentlyHit, looting);
        ItemStack tool = getHeldItem();
        if (tool != null) { setCurrentItemOrArmor(0, null); entityDropItem(tool, 0); }
        for (int i = 0; i < cargo.length; i++) if (cargo[i] != null) { entityDropItem(cargo[i], 0); cargo[i] = null; }
        for (ItemStack stack : overflowDrops) entityDropItem(stack,0);
        overflowDrops.clear();
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
