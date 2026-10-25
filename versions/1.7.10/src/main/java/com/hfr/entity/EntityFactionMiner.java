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
        ESCAPE("escape"), MINING("excavate"), TO_STORAGE("to_storage"), DEPOSITING("deposit"), RESTOCKING("restock"), TO_WORK("to_work"), DROPS("drops"), COMPLETE("complete");
        final String key;
        Task(String key) { this.key = key; }
    }
    private enum StorageReason {
        NONE("none"), CAPACITY("capacity"), OVERFLOW("overflow"), TOOL("tool"), TORCHES("torches"), LADDERS("ladders"), ACCESS("access"), COMPLETE("complete");
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
    // A helix of original terrain remains until the final bottom-up pass.
    private boolean accessRamp;
    private int rampOffset, rampDirection = 1, rampCleanup = -1;
    // Cleanup still visits terrain deferred before supplied access chose an alignment.
    private int terrainRampOffset;
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
    private enum Movement { WALK, STEP, DESCEND, CLIMB }
    private enum EdgeFailure { NONE, FOOTING, RISE, DROP, SPAN, LADDER, CLEARANCE, LANDING }
    private int[] preciseGoal, interactionTarget, selectedEndpoint, plannedEntrance;
    private Block interactionBlock;
    private int interactionMeta;
    private boolean interactionMining;
    private final java.util.Set<Long> failedEndpoints = new java.util.HashSet<Long>();
    private String arrivalFailure = "";
    // Hypothetical access exists only during planning; no world edits or inventory changes there.
    private final java.util.Map<Long,int[]> proposedAccess = new java.util.HashMap<Long,int[]>();
    // x,y,z, action: -1 = clear/reserve headroom, 0 = full support, 2..5 = ladder face.
    private final List<int[]> accessBlocks = new java.util.ArrayList<int[]>();
    private final List<int[]> accessPlan = new java.util.ArrayList<int[]>();
    private int accessIndex, accessAttempts, requestedBlocks, requestedLadders;
    private boolean buildingAccess;
    private boolean staircaseAccess, staircaseInstalled;
    // Committed by a verified existing column or a real accepted rung.
    private int[] ladderPosition;
    private final List<int[]> discoveredLadders = new java.util.ArrayList<int[]>();
    private int ladderDiscoveryColumn;
    private boolean ladderDiscoveryComplete, repairingLadder, reconsideringLadder;
    private long staircaseLadderSupplies = Long.MIN_VALUE;
    private final java.util.Set<int[]> failedAccessOperations = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<int[],Boolean>());
    private final List<int[]> constructionRefuge = new java.util.ArrayList<int[]>();
    private String accessBlocker = "", ladderObstruction = "";
    private int[] accessDestination;
    private boolean accessSupplyChecked, accessStationReachable = true;
    private boolean resumeStorageAfterAccess;
    private StorageReason accessStorageReason = StorageReason.NONE;
    private java.util.Map<Long,Double> searchHeights;
    private int searchRemovedX, searchRemovedY, searchRemovedZ;
    // Failure belongs to the unfinished job, not to a transient task or selected block.
    private static final int OBJECTIVE_FAILURE_LIMIT = 24, OBJECTIVE_TICK_LIMIT = 12000;
    private int objectiveFailures, objectiveTicks;
    private boolean navigationBlocked, selectingTarget;
    private long lastObjectiveLog, lastRouteLog, lastAccessLog, lastResetLog, blockedSupplies;
    private final java.util.Set<Long> reachedPositions = new java.util.HashSet<Long>();
    // Record actual terrain consulted by the planner, including failed approaches. While
    // blocked, inspect a bounded slice instead of running the same searches every update.
    private final java.util.Map<Long,int[]> watchedTerrain = new java.util.LinkedHashMap<Long,int[]>();
    private java.util.Iterator<int[]> terrainChecks;
    private int[] blockedPosition;
    private int[] routeSearchTarget;
    private int routeSearchProposal = -1;
    private List<int[]> routeSearchRemaining;
    private boolean routeSearchPending;
    private int[] accessSearchTarget;
    private List<List<int[]>> accessProposals;
    private final java.util.Map<List<int[]>,int[]> staircaseLayouts = new java.util.IdentityHashMap<List<int[]>,int[]>();
    private int accessProposalIndex;
    private int staircaseVariantIndex;
    private int accessColumnIndex, accessBottom, accessTop;
    private final java.util.Set<String> accessLayouts = new java.util.HashSet<String>();
    private java.util.Set<Long> accessFloor, failedSearchPositions = java.util.Collections.emptySet();
    private boolean accessSearchExhausted;
    private String accessStage = "", accessFailure = "";
    private int occupiedAccessCells;
    private int[] occupiedAccessCell;
    private boolean enteringRoute;
    private int restockLadders, escapeColumn, escapeJumpTicks;
    private int[] escapeOrigin, escapePillar, escapeDestination;
    private boolean escapeJumping;
    private long accessSupplies = Long.MIN_VALUE;
    private boolean sentLadderReminder;
    private long lastLadderReminder;
    private boolean repairAfterRestock;
    private boolean accessRepairPending;

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
        running = false; escapeJumping = false; status = reason; resetPath(); clearRequests();
        if (!"navigation_failed".equals(reason)) navigationReason = navigationDetail = "";
    }
    private void resetPath() {
        getNavigator().clearPathEntity(); pathTicks = 0;
        enteringRoute = false;
        preciseGoal = null;
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
        // A phase change at the same objective does not invalidate its movement or
        // partial hardness progress. Only a changed destination cancels that work.
        if (!Arrays.equals(destination,goal)) {
            resetPath();
            if (accessProposals == null) clearRouteSearch();
            navigationReason = navigationDetail = arrivalFailure = "";
        }
        task = next; destination = goal == null ? null : goal.clone();
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
    private void clearRequests() { requestedTools = requestedTorches = requestedBlocks = requestedLadders = supplyCheckTicks = 0; }
    private void clearRouteSearch() {
        routeSearchTarget = null; routeSearchRemaining = null; routeSearchPending = false; routeSearchProposal = -1;
    }
    private void clearAccessSearch() {
        accessProposals = null; accessSearchTarget = null; accessProposalIndex = 0; accessSearchExhausted = false;
        accessStage = accessFailure = ""; occupiedAccessCells = 0; occupiedAccessCell = null;
        accessBlocker = "";
        accessColumnIndex = 0; accessLayouts.clear(); accessFloor = null; failedSearchPositions = java.util.Collections.emptySet();
        staircaseLayouts.clear();
        staircaseVariantIndex = 0;
        discoveredLadders.clear(); ladderDiscoveryColumn = 0; ladderDiscoveryComplete = false;
    }
    private void usefulProgress(String reason) {
        boolean hadFailures = objectiveFailures > 0 || objectiveTicks > 0;
        objectiveFailures = objectiveTicks = 0;
        if (buildingAccess) accessSearchExhausted = false;
        watchedTerrain.clear(); terrainChecks = null;
        if (hadFailures) diagnostic("reset",reason);
    }
    private void retryObjective(String reason) {
        if (task == Task.ESCAPE && escapePillar == null) escapeColumn = 0;
        if (reconsideringLadder) { reconsideringLadder = false; buildingAccess = !accessPlan.isEmpty(); }
        if (buildingAccess && staircaseAccess) staircaseLadderSupplies = Long.MIN_VALUE;
        if (buildingAccess && !staircaseAccess && task != Task.ESCAPE) reconcileAccessPlan();
        accessRepairPending = true;
        navigationBlocked = selectingTarget = false; rejectedTargets.clear(); searchOffset = 0;
        failedEndpoints.clear(); accessAttempts = returnFailures = 0; returnFailureTarget = -1;
        interactionTarget = selectedEndpoint = plannedEntrance = null;
        clearRouteSearch(); clearAccessSearch(); resetPath(); reachedPositions.clear();
        failedAccessOperations.clear();
        usefulProgress(reason); navigationReason = navigationDetail = arrivalFailure = "";
        accessSupplyChecked = false; supplyCheckTicks = 0;
        destination = storageTask() ? new int[] {plan[0],plan[1],plan[2]}
                : buildingAccess && accessIndex < accessPlan.size() ? Arrays.copyOf(accessPlan.get(accessIndex),3) : workDestination();
        status = !running ? "paused" : buildingAccess ? accessActivity() : task.key;
        diagnostic("objective",reason);
    }
    private void diagnostic(String category,String reason) {
        long now = System.nanoTime();
        long last = "route".equals(category) ? lastRouteLog : "access".equals(category) ? lastAccessLog
                : "reset".equals(category) ? lastResetLog : lastObjectiveLog;
        if (last != 0 && now-last < 10000000000L) return;
        if ("route".equals(category)) lastRouteLog = now;
        else if ("access".equals(category)) lastAccessLog = now;
        else if ("reset".equals(category)) lastResetLog = now;
        else lastObjectiveLog = now;
        XFLog.info(taskDiagnostic(category + ": " + reason) + " failures=" + objectiveFailures + "/" + OBJECTIVE_FAILURE_LIMIT
                + " noProgressTicks=" + objectiveTicks + " accessAttempts=" + accessAttempts + " detail=" + navigationDetail);
    }
    private void routeFailure() { objectiveFailures++; diagnostic("route",navigationReason); }
    private void watchTerrain(int x,int y,int z) {
        if (navigationBlocked || y < 0 || y >= worldObj.getHeight() || watchedTerrain.size() >= 32768) return;
        long key = positionKey(x,y,z);
        if (!watchedTerrain.containsKey(key)) {
            boolean loaded = worldObj.blockExists(x,y,z);
            watchedTerrain.put(key,new int[] {x,y,z,loaded ? Block.getIdFromBlock(worldObj.getBlock(x,y,z)) : -1,
                    loaded ? worldObj.getBlockMetadata(x,y,z) : 0});
        }
    }
    private void blockObjective(String state) {
        getNavigator().clearPathEntity(); preciseGoal = null; travelRoute.clear(); recoveryRoute.clear();
        navigationBlocked = true; status = state; terrainChecks = null;
        blockedSupplies = supplyStamp(true);
        blockedPosition = new int[] {(int)Math.floor(posX),(int)Math.floor(boundingBox.minY+.001),(int)Math.floor(posZ)};
    }
    private long supplyStamp() { return supplyStamp(false); }
    private long supplyStamp(boolean relevantOnly) {
        IInventory station = stationAssigned ? stationInventory(plan[0],plan[1],plan[2]) : null;
        long value = station == null ? 1 : 2;
        for (int i = 0, size = getSizeInventory()+(station == null ? 0 : station.getSizeInventory()); i < size; i++) {
            ItemStack stack = i < getSizeInventory() ? getStackInSlot(i) : station.getStackInSlot(i-getSizeInventory());
            // Access can be blocked before a plan is selected, too.
            if (relevantOnly && !(configured && (accessMaterial(stack,false) || accessMaterial(stack,true)))
                    && !(pickaxe(stack) && (getHeldItem() == null || requiredHarvest >= 0)) && !(needsLight && isTorch(stack))) continue;
            // Moving the same stack from an unreachable station into cargo is a
            // relevant delivery even when item/count/NBT totals do not change.
            value = 31*value+i;
            value = 31*value + (stack == null ? 0 : System.identityHashCode(stack.getItem()) + 31L*stack.stackSize
                    + 961L*stack.getItemDamage() + (stack.hasTagCompound() ? stack.getTagCompound().hashCode() : 0));
        }
        return value;
    }
    private boolean waitForObjectiveChange() {
        if (!navigationBlocked) return false;
        if (blockedSupplies != supplyStamp(true) || blockedPosition != null
                && (blockedPosition[0] != (int)Math.floor(posX) || blockedPosition[1] != (int)Math.floor(boundingBox.minY+.001)
                    || blockedPosition[2] != (int)Math.floor(posZ))) {
            retryObjective("supplies, station or supported position changed"); return false;
        }
        if (terrainChecks == null || !terrainChecks.hasNext()) terrainChecks = watchedTerrain.values().iterator();
        for (int checked = 0; checked < 512 && terrainChecks.hasNext(); checked++) {
            int[] p = terrainChecks.next(); boolean loaded = worldObj.blockExists(p[0],p[1],p[2]);
            if ((loaded ? Block.getIdFromBlock(worldObj.getBlock(p[0],p[1],p[2])) : -1) != p[3]
                    || loaded && worldObj.getBlockMetadata(p[0],p[1],p[2]) != p[4]) {
                retryObjective("consulted terrain or chunk availability changed"); return false;
            }
        }
        return true;
    }
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
        retryObjective("station instruction changed");
        return "xf.miner.station_set";
    }
    // Only Start can install a validated, confirmed replacement. Real overflow drops remain collectible.
    private void configure(int[] next) {
        pause(); plan = next; cursor = 0; configured = true; task = Task.TO_WORK; storageReason = StorageReason.NONE;
        targetCursor = -1; searchOffset = 0;
        rejectedTargets.clear(); returnRoute.clear();
        accessPlan.clear(); accessBlocks.clear(); buildingAccess = false; accessDestination = null; accessAttempts = 0;
        ladderPosition = null; staircaseAccess = staircaseInstalled = false; ladderObstruction = accessBlocker = "";
        restockLadders = escapeColumn = escapeJumpTicks = 0; escapeOrigin = escapePillar = escapeDestination = null; escapeJumping = repairAfterRestock = false;
        repairingLadder = reconsideringLadder = false; staircaseLadderSupplies = Long.MIN_VALUE;
        constructionRefuge.clear(); failedAccessOperations.clear();
        resumeStorageAfterAccess = false; accessStorageReason = StorageReason.NONE; accessSupplyChecked = false;
        requiredHarvest = -1; needsLight = false; toolBroken = false;
        retryObjective("installed area changed");
        accessRamp = (plan[5] > plan[3] || plan[6] > plan[4]) && plan[7] > plan[8];
        rampCleanup = accessRamp ? plan[7]-plan[8] : -1;
        rampOffset = 0; rampDirection = 1;
        if (accessRamp) {
            double best = Double.MAX_VALUE;
            for (int i = 0; i < rampPerimeter(); i++) {
                int[] r = rampColumn(i);
                double d = (double)(r[0]-plan[0])*(r[0]-plan[0])+(double)(r[1]-plan[2])*(r[1]-plan[2]);
                if (d < best) { best = d; rampOffset = i; }
            }
        }
        terrainRampOffset = rampOffset;
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
            running = true; retryObjective("Start / Resume instruction");
            // Resume an explicit saved trip, never infer a trip from partial cargo.
            destination = storageTask() ? new int[] {plan[0],plan[1],plan[2]} : workDestination();
        } else if (navigationBlocked) retryObjective("explicit Start instruction");
        if (!recoveringCorridor && !inCorridor((int)Math.floor(posX),(int)Math.floor(posZ))) beginCorridorRecovery();
        return new Activation("");
    }
    public void onLivingUpdate() {
        if (!worldObj.isRemote && running && !navigationBlocked && !recoveringCorridor && interactionTarget != null
                && worldObj.blockExists(interactionTarget[0],interactionTarget[1]-(interactionMining ? 1 : 0),interactionTarget[2])) {
            int ty = interactionTarget[1]-(interactionMining ? 1 : 0);
            if (worldObj.getBlock(interactionTarget[0],ty,interactionTarget[2]) != interactionBlock
                    || worldObj.getBlockMetadata(interactionTarget[0],ty,interactionTarget[2]) != interactionMeta) {
                travelRoute.clear(); preciseGoal = null; getNavigator().clearPathEntity();
                navigationReason = "target"; navigationDetail = endpointDiagnostic("target");
            }
        }
        if (!worldObj.isRemote && running && !navigationBlocked && configured && !recoveringCorridor
                && !inCorridor((int)Math.floor(posX),(int)Math.floor(posZ))) beginCorridorRecovery();
        // Native following checks neighboring cells while shortcutting. Do not let those checks
        // discover unloaded chunks even though our planned route itself only uses loaded cells.
        if (!worldObj.isRemote && (!getNavigator().noPath() || preciseGoal != null) && waypoint != null && !segmentChunksLoaded(waypoint)) {
            preciseGoal = null; motionX = motionZ = 0;
            getNavigator().clearPathEntity(); status = recoveringCorridor ? "corridor_recovery" : "chunk";
            navigationReason = "chunk"; pathTicks = 0;
        }
        if (!worldObj.isRemote && !recoveringCorridor && !getNavigator().noPath() && destination != null
                && atDestination(destination[0],destination[1],destination[2],interactionMining))
            arrived();
        super.onLivingUpdate();
        if (worldObj.isRemote || !running) return;
        try {
            if (task == Task.ESCAPE && escapeJumping && !navigationBlocked) pillarPhysics();
            if (ticksExisted % 10 != 0) return;
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
        long supplies = supplyStamp(true);
        if (accessSupplies != supplies) {
            boolean changed = accessSupplies != Long.MIN_VALUE; accessSupplies = supplies;
            if (changed && task == Task.ESCAPE && navigationBlocked && restockLadders > 0 && accessCount(true) >= restockLadders
                    && !escapeJumping && supported(posX,boundingBox.minY,posZ,-30000001,-1,0)) {
                buildingAccess = false; accessPlan.clear(); accessIndex = 0;
                restockLadders = 0; sentLadderReminder = false; repairAfterRestock = true;
                task = Task.TO_WORK; retryObjective("direct ladder delivery; repair fixed access from actual floor");
            }
            if (changed && task != Task.ESCAPE && (navigationBlocked || buildingAccess || accessSearchTarget != null))
                retryObjective("access supplies changed; discard stale route failures");
        }
        if (waitForObjectiveChange()) return;
        if (!"chunk".equals(status) && !"no_access".equals(status) && !"no_tool".equals(status)
                && !"tool_level".equals(status) && !"no_torches".equals(status) && !"no_ladders".equals(status) && !"storage_full".equals(status)
                && !"no_chest".equals(status) && !"station_blocked".equals(status)) objectiveTicks += 10;
        if (objectiveFailures >= OBJECTIVE_FAILURE_LIMIT || objectiveTicks >= OBJECTIVE_TICK_LIMIT) {
            navigationDetail = "Last obstacle=" + navigationReason + "; " + navigationDetail
                    + "; failures=" + objectiveFailures + "; active ticks=" + objectiveTicks + "; repair access or supply requested materials";
            navigationReason = "objective_limit";
            terminalNavigation(); return;
        }
        // Recover before inventory-driven trips, work scans or interaction can change the saved task.
        if (recoverCorridor()) return;
        equipCarriedTool();
        stockTorchesFromCargo();
        if (task == Task.ESCAPE) { escape(); return; }
        if (accessRepairPending && !buildingAccess) {
            accessRepairPending = false;
            int[] goal = workDestination();
            if (goal == null) goal = new int[] {plan[0],plan[1],plan[2]};
            boolean returning = storageTask(); StorageReason interrupted = storageReason;
            if (startAccessRepair(goal)) {
                if (returning) { resumeStorageAfterAccess = true; accessStorageReason = interrupted; returnToWork("repair interrupted storage access"); }
                return;
            }
        }
        if (!storageTask() && !buildingAccess && unfinished() && ladderPosition != null && restockLadders == 0
                && accessCount(true) < ladderRequirement()) { beginEscape(); return; }
        if (!storageTask() && repairAfterRestock && !buildingAccess && accessSearchTarget == null) {
            int[] goal = workDestination();
            if (goal != null && planAccess(goal[0],goal[1],goal[2])) return;
        }
        // Repair an exit before tool/capacity trips can demand that same broken exit.
        if (buildingAccess && !storageTask()) { constructAccess(); return; }
        if (reconsideringLadder && !storageTask()) { planAccess(accessSearchTarget[0],accessSearchTarget[1],accessSearchTarget[2]); return; }
        if (!storageTask() && accessSearchTarget != null && !accessSearchExhausted) {
            if (selectingTarget) { findAccessibleTarget((plan[5]-plan[3]+1)*(plan[6]-plan[4]+1)); return; }
            if (planAccess(accessSearchTarget[0],accessSearchTarget[1],accessSearchTarget[2])) return;
        }
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
        if (buildingAccess) { constructAccess(); return; }
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
        if (selectingTarget) { findAccessibleTarget(layer); return; }
        for (int scans = 0; scans < 64 && (cursor < total || rampCleanup >= 0); scans++) {
            boolean cleanup = cursor >= total;
            int workCursor = cleanup ? rampCursor(rampCleanup) : targetCursor >= cursor && targetCursor / layer == cursor / layer ? targetCursor : cursor;
            int x = plan[3] + workCursor % width, z = plan[4] + workCursor / width % length, y = plan[7] - workCursor / layer;
            watchTerrain(x,y,z);
            if (!worldObj.blockExists(x, y, z)) { status = "chunk"; return; }
            Block block = worldObj.getBlock(x, y, z);
            if (worldObj.isAirBlock(x, y, z) || block == net.minecraft.init.Blocks.torch || reservedAccess(x,y,z)) { advance(workCursor); continue; }
            if (!cleanup && accessRamp && workCursor == rampCursor(workCursor / layer)) { advance(workCursor); continue; }
            if (!WorkerAccess.ownsLand(worldObj, x, z, factionId)) { stop("denied"); return; }
            if (block == net.minecraft.init.Blocks.bedrock || block.getBlockHardness(worldObj, x, y, z) < 0) {
                advance(workCursor); continue;
            }
            if (BuilderPlacement.protectedBlock(worldObj, x, y, z)) { stop("protected"); return; }
            if (worldObj.getTileEntity(x, y, z) != null) { stop("container"); return; }
            if (block.getMaterial().isLiquid()) { stop("liquid"); return; }
            int[] workGoal = new int[] {x,y+1,z};
            if (!Arrays.equals(destination,workGoal)) transition(Task.TO_WORK,"approach unfinished target",workGoal);
            if (!moveNear(x, y + 1, z, true)) {
                if ("route_failed".equals(status)) {
                    if (cleanup) terminalNavigation();
                    else deferTarget(workCursor);
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
                else deferTarget(workCursor);
                return;
            }
            if (!ensureReturnRoute(x,y,z)) {
                if ("chunk".equals(status)) return;
                if (planAccess(x,y+1,z)) return;
                if (returnFailureTarget != workCursor) { returnFailureTarget = workCursor; returnFailures = 0; }
                routeFailure();
                if (++returnFailures < 3) { status = "recovering"; return; }
                if (cleanup) terminalNavigation();
                else deferTarget(workCursor);
                return;
            }
            returnFailures = 0; returnFailureTarget = -1;
            if (accessCount(false) < emergencyBlocks()) { beginStorage(StorageReason.ACCESS); return; }
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
            float previousProgress = miningProgress;
            miningProgress += block.getPlayerRelativeBlockHardness(player, worldObj, x, y, z) * 10;
            if (miningProgress <= 0) { stop("unmineable"); return; }
            if (miningProgress > previousProgress) objectiveTicks = 0;
            if (miningProgress < 1) return;
            boolean harvested = harvestWithTool(player,x,y,z);
            miningProgress = 0; miningBlock = null;
            if (!harvested) { stop("harvest_denied"); return; }
            usefulProgress("harvested target");
            advance(workCursor);
            if (accessRepairPending) return;
            if (!overflowDrops.isEmpty()) beginStorage(StorageReason.OVERFLOW);
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
        if (minedCursor == cursor || cursor >= total && rampCleanup >= 0) objectiveTicks = 0;
        if (cursor >= total && rampCleanup >= 0) rampCleanup--;
        else if (minedCursor == cursor) cursor++;
        targetCursor = -1; searchOffset = 0; resetMining();
        // A scan over air must not repeatedly cancel a useful movement path.
    }
    private int rampPerimeter() {
        int w = plan[5]-plan[3], l = plan[6]-plan[4];
        return w == 0 ? l+1 : l == 0 ? w+1 : 2*(w+l);
    }
    private int[] rampColumn(int index) {
        int w = plan[5]-plan[3], l = plan[6]-plan[4];
        index = (index % rampPerimeter()+rampPerimeter()) % rampPerimeter();
        if (w == 0) return new int[] {plan[3],plan[4]+index};
        if (l == 0) return new int[] {plan[3]+index,plan[4]};
        if (index < w) return new int[] {plan[3]+index,plan[4]};
        index -= w;
        if (index < l) return new int[] {plan[5],plan[4]+index};
        index -= l;
        if (index < w) return new int[] {plan[5]-index,plan[6]};
        return new int[] {plan[3],plan[6]-(index-w)};
    }
    private int rampCursor(int depth) {
        int[] r = rampColumn(terrainRampOffset+depth);
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
    private void deferTarget(int failed) {
        rejectedTargets.set(failed); targetCursor = -1; selectingTarget = true;
        resetPath(); clearRouteSearch(); clearAccessSearch(); searchOffset = 0;
        status = "searching";
        diagnostic("objective","deferred unfinished target " + failed);
    }
    private void useRoute(List<int[]> found) {
        if (!buildingAccess && ladderPosition == null) {
            // Executable work routes already include an independent storage-exit
            // proof; storage trips end at storage. Retain ladders on either leg.
            retainTravelLadder(found);
            if (ladderPosition == null) retainTravelLadder(returnRoute);
        }
        travelRoute.clear(); travelRoute.addAll(found); routeIndex = segmentEnd = 0;
        enteringRoute = getDistanceSq(found.get(0)[0]+.5,nodeY(found.get(0)),found.get(0)[2]+.5) > .0125;
        selectedEndpoint = found.get(found.size()-1).clone(); plannedEntrance = null;
        for (int i = 1; i < found.size(); i++) if (nodeY(found.get(i)) < nodeY(found.get(i-1))-.01) {
            plannedEntrance = found.get(i).clone(); break;
        }
        bestRouteProgress = -Double.MAX_VALUE; pathTicks = 0;
        navigationReason = navigationDetail = arrivalFailure = accessBlocker = "";
    }
    private void retainTravelLadder(List<int[]> route) {
        for (int[] p : route) if (p[1] >= plan[8] && p[1] <= plan[7] && ladder(p[0],p[1],p[2],-30000001,-1,0)) {
            int[] fixed = new int[] {p[0],p[2],worldObj.getBlockMetadata(p[0],p[1],p[2])};
            if (!validLadderPosition(fixed)) continue;
            ladderPosition = fixed;
            List<int[]> cells = new java.util.ArrayList<int[]>();
            net.minecraftforge.common.util.ForgeDirection face = net.minecraftforge.common.util.ForgeDirection.getOrientation(fixed[2]);
            for (int y = plan[8]; y <= plan[7]; y++) {
                int[] rung = new int[] {fixed[0],y,fixed[1],fixed[2]};
                if (accessPresent(rung)) {
                    cells.add(rung);
                    int[] backing = new int[] {fixed[0]-face.offsetX,y,fixed[1]-face.offsetZ,0};
                    if (accessArea(backing[0],y,backing[2]) && accessPresent(backing)) cells.add(backing);
                }
            }
            adoptLadder(cells); return;
        }
    }
    private String ladderActivity() { return ladderPosition == null ? "climbing" : "using_ladder"; }
    private boolean findAccessibleTarget(int layer) {
        int width = plan[5] - plan[3] + 1, end = (cursor / layer + 1) * layer;
        if (searchOffset == 0) searchUnloaded = false;
        for (int scans = 0; scans < 64 && cursor + searchOffset < end; scans++, searchOffset++) {
            int candidate = cursor + searchOffset;
            int x = plan[3] + candidate % width, z = plan[4] + candidate / width % (plan[6] - plan[4] + 1), y = plan[7] - candidate / layer;
            watchTerrain(x,y,z);
            if (rejectedTargets.get(candidate) || accessRamp && candidate == rampCursor(candidate/layer) || reservedAccess(x,y,z)) continue;
            if (!worldObj.blockExists(x, y, z)) { searchUnloaded = true; continue; }
            Block block = worldObj.getBlock(x, y, z);
            if (worldObj.isAirBlock(x, y, z) || block == net.minecraft.init.Blocks.torch || block.getMaterial().isLiquid()
                    || block.getBlockHardness(worldObj, x, y, z) < 0 || worldObj.getTileEntity(x, y, z) != null
                    || !WorkerAccess.ownsLand(worldObj, x, z, factionId) || BuilderPlacement.protectedBlock(worldObj, x, y, z)) continue;
            status = "searching";
            rememberInteraction(x,y+1,z,true);
            List<int[]> approach = accessSearchTarget == null ? executableRoute(standingCandidates(x,y+1,z,true),x,y+1,z,true) : null;
            // A retained proposal owns its pending endpoint checks. Let planAccess
            // resume them below instead of waiting forever on its own pending flag.
            if (accessSearchTarget == null && routeSearchPending) return false;
            if (approach != null) {
                int[] goal = new int[] {x,y+1,z};
                transition(Task.TO_WORK,"selected executable work",goal);
                targetCursor = candidate; selectingTarget = false; searchOffset = 0;
                useRoute(approach); return true;
            }
            if (planAccess(x,y+1,z)) {
                if (buildingAccess) {
                    targetCursor = candidate; selectingTarget = false; searchOffset = 0;
                    diagnostic("objective","selected work with supplied access");
                }
                return false;
            }
            // Retain the block as unfinished. Excavation/construction or an observed
            // external terrain change invalidates this deferred set.
            rejectedTargets.set(candidate); searchOffset++;
            return false; // At most one expensive target evaluation per work update.
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
        preciseGoal = null;
        pathTicks = navigationFailures = routeRetries = 0; bestRouteProgress = -Double.MAX_VALUE;
        recoveryOrigin = new int[] {(int)Math.floor(posX),(int)Math.floor(boundingBox.minY+.001),(int)Math.floor(posZ)};
        recoveryDestination = null; recoveryAttempts = recoveryTicks = 0; recoveringCorridor = true;
        resetMining(); status = "corridor_recovery";
        navigationReason = "outside"; navigationDetail = "Recovery origin=" + Arrays.toString(recoveryOrigin);
    }
    /** Returns true while recovery owns movement, including the tick that finishes or stops it. */
    private boolean recoverCorridor() {
        int x = (int)Math.floor(posX), y = (int)Math.floor(boundingBox.minY+.001), z = (int)Math.floor(posZ);
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
        if ((onGround || isOnLadder()) && inCorridor(x,z) && recoveryStanding(x,y,z)) {
            // Clear only temporary movement. The next work tick resumes the interrupted task.
            getNavigator().clearPathEntity(); recoveryRoute.clear(); travelRoute.clear(); waypoint = null;
            preciseGoal = null;
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
    private boolean finalApproachClear(int[] end) {
        double ey = nodeY(end);
        if (Double.isNaN(ey) || Math.abs(ey-boundingBox.minY) > .1) return false;
        for (int i = 0; i <= 8; i++) {
            double t = i/8.0, px = posX+(end[0]+.5-posX)*t, pz = posZ+(end[2]+.5-posZ)*t;
            if (!clearBody(px,ey,pz,-30000001,-1,0)) return false;
            if (!ladder((int)Math.floor(px),(int)Math.floor(ey),(int)Math.floor(pz),-30000001,-1,0)) {
                AxisAlignedBB sole = AxisAlignedBB.getBoundingBox(px-width/2,ey-.06,pz-width/2,px+width/2,ey+.001,pz+width/2);
                if (!collisions(sole,-30000001,-1,0,recoveryCollisions,false) || recoveryCollisions.isEmpty()) return false;
            }
        }
        return true;
    }
    /** PathNavigate cannot advance ladders in 1.7.10. Drive accepted special edges through
     * the ordinary living collision/step/Forge-ladder physics, on the owning world thread. */
    @Override public void moveEntityWithHeading(float strafe,float forward) {
        if (!worldObj.isRemote && running && task == Task.ESCAPE && escapeJumping) {
            motionX = motionZ = 0; super.moveEntityWithHeading(0,0); return;
        }
        if (!worldObj.isRemote && running && preciseGoal != null && segmentChunksLoaded(preciseGoal)) {
            double fy = nodeY(preciseGoal);
            if (!Double.isNaN(fy)) {
                double dx = preciseGoal[0]+.5-posX, dz = preciseGoal[2]+.5-posZ;
                // Finish the last fraction with real collision-resolved motion. An
                // asymptotic steering factor cannot execute an exactly-on-reach endpoint.
                double distance = Math.sqrt(dx*dx+dz*dz), scale = distance > .12 ? .12/distance : 1;
                motionX = dx*scale; motionZ = dz*scale;
                if (isOnLadder()) {
                    double dy = fy+(ladder(preciseGoal[0],preciseGoal[1],preciseGoal[2],-30000001,-1,0) ? 0 : .08)-boundingBox.minY;
                    // A minimum .08 impulse overshoots the last fraction and can
                    // oscillate forever across a rung's endpoint. Finish exactly.
                    motionY = Math.copySign(Math.min(Math.abs(dy),Math.max(.08,Math.min(.12,Math.abs(dy)*.4))),dy);
                }
                super.moveEntityWithHeading(0,0); return;
            }
            preciseGoal = null;
        }
        if (!worldObj.isRemote && running && preciseGoal == null && (travelRoute.isEmpty() && recoveryRoute.isEmpty() || "chunk".equals(navigationReason))
                && getNavigator().noPath() && isOnLadder()) { motionX = motionY = motionZ = 0; }
        super.moveEntityWithHeading(strafe,forward);
    }
    private boolean atDestination(int x,int y,int z,boolean mining) {
        arrivalFailure = interactionFailure(posX,boundingBox.minY,posZ,x,y,z,mining,true);
        return arrivalFailure.isEmpty();
    }
    private String interactionFailure(double px,double py,double pz,int x,int y,int z,boolean mining,boolean actual) {
        if (!inCorridor((int)Math.floor(px),(int)Math.floor(pz))) return "outside";
        if (!worldObj.blockExists(x,mining ? y-1 : y,z)) return "chunk";
        if (mining && actual && interactionTarget != null && Arrays.equals(interactionTarget,new int[] {x,y,z})
                && (worldObj.getBlock(x,y-1,z) != interactionBlock || worldObj.getBlockMetadata(x,y-1,z) != interactionMeta)) return "target";
        if (Double.isNaN(py)) return "footing";
        if (task == Task.ESCAPE && escapePillar != null && !mining && x == escapePillar[0] && z == escapePillar[2]
                && ((px-x-.5)*(px-x-.5)+(pz-z-.5)*(pz-z-.5) > .01 || Math.abs(py-y) > .06)) return "movement";
        if (Math.abs(py-y) > 3) return "elevation";
        if ((px-x-.5)*(px-x-.5)+(py-y)*(py-y)+(pz-z-.5)*(pz-z-.5) > 9) return "reach";
        int rx = mining ? x : -30000001, ry = mining ? y-1 : -1;
        if (!clearBody(px,py,pz,rx,ry,z) || !clearBody(px,py,pz,-30000001,-1,0)) return "clearance";
        if (!mining && buildingAccess && task != Task.ESCAPE && accessIndex < accessPlan.size()) {
            int[] cell = accessPlan.get(accessIndex);
            if (cell[0] == x && cell[1] == y && cell[2] == z && accessShape(cell).intersectsWith(body(px,py,pz))) return "clearance";
        }
        boolean climb = ladder((int)Math.floor(px),(int)Math.floor(py),(int)Math.floor(pz),rx,ry,z);
        if (!climb) {
            AxisAlignedBB sole = AxisAlignedBB.getBoundingBox(px-width/2,py-.06,pz-width/2,px+width/2,py+.001,pz+width/2);
            if (!collisions(sole,rx,ry,z,recoveryCollisions,false)) return "chunk";
            boolean support = false;
            for (AxisAlignedBB shape : recoveryCollisions) if (Math.abs(shape.maxY-py) <= .06) { support = true; break; }
            if (!support) return "footing";
        }
        if (actual && !onGround && !climb) return "movement";
        if (mining && !sight(px,py+getEyeHeight(),pz,x,y-1,z)) return "sight";
        if (!mining && !sight(px,py+getEyeHeight(),pz,x,y,z)) return "sight";
        return "";
    }
    private void rememberInteraction(int x,int y,int z,boolean mining) {
        Block block = mining ? worldObj.getBlock(x,y-1,z) : worldObj.getBlock(x,y,z);
        int meta = worldObj.getBlockMetadata(x,mining ? y-1 : y,z);
        if (!Arrays.equals(interactionTarget,new int[] {x,y,z}) || interactionMining != mining || interactionBlock != block || interactionMeta != meta) {
            boolean changedMiningBlock = mining && interactionMining && Arrays.equals(interactionTarget,new int[] {x,y,z})
                    && (interactionBlock != block || interactionMeta != meta);
            interactionTarget = new int[] {x,y,z}; interactionBlock = block; interactionMeta = meta;
            interactionMining = mining;
            failedEndpoints.clear(); selectedEndpoint = null; preciseGoal = null;
            plannedEntrance = null;
            // Endpoint/proposal progress belongs to the mining objective, not its
            // activity label or a temporary construction/storage interaction.
            if (mining && accessSearchTarget != null && (!Arrays.equals(accessSearchTarget,new int[] {x,y,z})
                    || changedMiningBlock)) {
                clearRouteSearch(); clearAccessSearch();
            } else if (accessProposals == null) clearRouteSearch();
            travelRoute.clear(); getNavigator().clearPathEntity(); routeRetries = navigationFailures = 0;
            pathTicks = 0; bestRouteProgress = -Double.MAX_VALUE;
            diagnostic("objective","interaction objective changed; job failure budget retained");
        }
    }
    private String endpointDiagnostic(String failure) {
        return "arrival=" + failure + " task=" + task.key + " target=" + Arrays.toString(interactionTarget)
                + " block=" + Block.blockRegistry.getNameForObject(interactionBlock) + ":" + interactionMeta
                + " actual=" + posX+","+boundingBox.minY+","+posZ + " endpoint=" + Arrays.toString(selectedEndpoint)
                + " distance=" + (destination == null ? -1 : Math.sqrt(getDistanceSq(destination[0]+.5,destination[1],destination[2]+.5)))
                + " ground=" + onGround + " ladder=" + isOnLadder()
                + " visible=" + (interactionTarget != null && sight(posX,boundingBox.minY+getEyeHeight(),posZ,
                        interactionTarget[0],interactionTarget[1]-(interactionMining ? 1 : 0),interactionTarget[2]));
    }
    private void arrived() {
        getNavigator().clearPathEntity(); travelRoute.clear(); waypoint = null;
        enteringRoute = false;
        preciseGoal = null;
        pathTicks = navigationFailures = routeRetries = 0; navigationReason = navigationDetail = arrivalFailure = accessBlocker = "";
    }
    private boolean moveNear(int x, int y, int z, boolean mining) {
        return moveNear(x,y,z,mining,false);
    }
    private boolean moveNear(int x, int y, int z, boolean mining, boolean recovery) {
        List<int[]> route = recovery ? recoveryRoute : travelRoute;
        status = recovery ? "corridor_recovery" : task.key;
        if (!travelSuppliesReady(route,recovery)) return false;
        int[] goal = new int[] {x,y,z};
        if (!recovery) {
            if (!Arrays.equals(destination,goal)) { resetPath(); destination = goal; }
            rememberInteraction(x,y,z,mining);
            if (atDestination(x,y,z,mining) && (!constructionTarget(x,y,z)
                    || !failedEndpoints.contains(positionKey((int)Math.floor(posX),(int)Math.floor(boundingBox.minY+.001),(int)Math.floor(posZ))))) {
                if (!constructionTarget(x,y,z) || constructionSafe(null,accessPlan.get(accessIndex))) {
                    arrived(); return true;
                }
                // Try another builder position; do not retry the same unsafe
                // actual position merely because its interaction reach is valid.
                int[] failed = selectedEndpoint == null ? new int[] {(int)Math.floor(posX),(int)Math.floor(boundingBox.minY+.001),(int)Math.floor(posZ)} : selectedEndpoint;
                failedEndpoints.add(positionKey(failed[0],failed[1],failed[2]));
                if (routeSearchRemaining != null) routeSearchRemaining.removeIf(p -> Arrays.equals(p,failed));
                arrivalFailure = "construction_exit";
            }
            if (task == Task.MINING || task == Task.DROPS) transition(Task.TO_WORK,"work position changed",goal);
            else if (storageTask() && task != Task.TO_STORAGE) transition(Task.TO_STORAGE,"station interaction position changed",goal);
        }
        String movingStatus = recovery ? "corridor_recovery" : task.key;
        resetMining();
        if (!onGround && !isOnLadder() && route.isEmpty()) {
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
            if (enteringRoute) {
                int[] entry = route.get(0);
                double distance = getDistanceSq(entry[0]+.5,nodeY(entry),entry[2]+.5);
                if (distance <= .0125 && (onGround || isOnLadder())) {
                    enteringRoute = false; preciseGoal = null; pathTicks = 0; bestRouteProgress = -Double.MAX_VALUE;
                    if (reachedPositions.size() < 4096 && reachedPositions.add(positionKey(entry[0],entry[1],entry[2]))) {
                        objectiveTicks = 0; diagnostic("reset","entered new route position");
                    }
                } else {
                    if (-distance > bestRouteProgress+.01) { bestRouteProgress = -distance; pathTicks = 0; }
                    else pathTicks += 10;
                    if (Double.isNaN(distance) || pathTicks >= 100 || !standing(entry[0],entry[1],entry[2],-30000001,-1,0)) {
                        navigationReason = "stalled"; navigationDetail = "Cannot enter verified route from actual position; entry=" + Arrays.toString(entry);
                        getNavigator().clearPathEntity(); preciseGoal = null; route.clear(); enteringRoute = false;
                        routeRetries++; clearRouteSearch(); routeFailure();
                    } else {
                        getNavigator().clearPathEntity(); preciseGoal = entry; waypoint = entry.clone();
                        status = recovery ? "corridor_recovery" : isOnLadder() ? ladderActivity()
                                : nodeY(entry) < boundingBox.minY-.1 ? "descending" : "final_approach";
                        return false;
                    }
                }
            }
        }
        if (!route.isEmpty()) {
            int previous = routeIndex;
            for (int i = routeIndex+1; i <= segmentEnd && i < route.size(); i++) {
                int[] next = route.get(i);
                double tolerance = movement(route.get(i-1),next) == Movement.CLIMB ? .04 : .36;
                if (getDistanceSq(next[0]+.5,nodeY(next),next[2]+.5) < tolerance) routeIndex = i;
            }
            int[] next = route.get(Math.min(routeIndex+1,route.size()-1));
            if (!recovery) movingStatus = movement(route.get(routeIndex),next) == Movement.CLIMB ? ladderActivity()
                    : movement(route.get(routeIndex),next) == Movement.DESCEND ? "descending"
                    : movement(route.get(routeIndex),next) == Movement.STEP ? "stepping"
                    : mining && (posX < plan[3] || posX > plan[5]+1 || posZ < plan[4] || posZ > plan[6]+1 || boundingBox.minY > y) ? "walking_rim" : task.key;
            double progress = routeIndex-Math.min(1,Math.sqrt(getDistanceSq(next[0]+.5,nodeY(next),next[2]+.5)));
            if (progress > bestRouteProgress+.1) { bestRouteProgress = progress; pathTicks = 0; }
            else pathTicks += 10;
            if (routeIndex > previous) {
                navigationFailures = 0;
                int[] reached = route.get(routeIndex);
                if (reachedPositions.size() < 4096 && (onGround || isOnLadder())
                        && reachedPositions.add(positionKey(reached[0],reached[1],reached[2]))) {
                    objectiveTicks = 0;
                    // Do not forgive a cycle through the same positions on a rebuilt path.
                    if (objectiveFailures > 0) { objectiveFailures = 0; diagnostic("reset","reached new route position"); }
                }
            }
            boolean intact = routeIntact(route,routeIndex,Math.min(route.size()-1,routeIndex+8),
                    -30000001,-1,0,recovery);
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
                if (!recovery && selectedEndpoint != null && failedEndpoints.size() < 64)
                    failedEndpoints.add(positionKey(selectedEndpoint[0],selectedEndpoint[1],selectedEndpoint[2]));
                getNavigator().clearPathEntity(); preciseGoal = null; route.clear(); routeRetries++; pathTicks = 0;
                clearRouteSearch(); routeFailure();
            } else if (preciseGoal != null && getDistanceSq(preciseGoal[0]+.5,nodeY(preciseGoal),preciseGoal[2]+.5) > .000001) {
                Movement kind = movement(route.get(routeIndex),preciseGoal);
                status = kind == Movement.CLIMB ? ladderActivity() : kind == Movement.DESCEND ? "descending"
                        : kind == Movement.STEP ? "stepping" : "final_approach"; return false;
            } else if (!getNavigator().noPath()) { status = movingStatus; return false; }
            else if (routeIndex+1 >= route.size()) {
                // A valid path endpoint is still not arrival unless the interaction check above succeeds.
                atDestination(x,y,z,mining);
                navigationReason = arrivalFailure; navigationDetail = endpointDiagnostic(arrivalFailure);
                int[] end = route.get(route.size()-1);
                if (!recovery && preciseGoal == null && ("reach".equals(arrivalFailure) || "sight".equals(arrivalFailure) || "footing".equals(arrivalFailure))
                        && interactionFailure(end[0]+.5,nodeY(end),end[2]+.5,x,y,z,mining,false).isEmpty()
                        && finalApproachClear(end)) {
                    preciseGoal = end; status = "final_approach"; return false;
                }
                if (!recovery && failedEndpoints.size() < 64) failedEndpoints.add(positionKey(end[0],end[1],end[2]));
                preciseGoal = null; route.clear(); routeRetries++;
                clearRouteSearch(); routeFailure();
            }
        }
        if (routeRetries >= 4 || navigationFailures >= 3) { status = "route_failed"; return false; }
        if (route.isEmpty()) {
            if (recovery && recoveryAttempts >= RECOVERY_ATTEMPTS) { status = "route_failed"; return false; }
            if (!recovery && mining && accessProposals != null) {
                if (planAccess(x,y,z)) return false;
                navigationFailures++; routeFailure();
                status = navigationFailures >= 3 ? "route_failed" : "recovering"; return false;
            }
            List<int[]> goals = recovery ? java.util.Collections.<int[]>emptyList() : standingCandidates(x,y,z,mining);
            if (!recovery && goals.isEmpty()) {
                navigationReason = failedEndpoints.isEmpty() ? "standing" : arrivalFailure;
                navigationDetail = failedEndpoints.isEmpty() ? standingObstruction(x,y,z) : endpointDiagnostic(arrivalFailure);
            }
            else {
                // Search complete routes first, including direct approaches; a verified cached
                // route remains a safe fallback when the bounded search cannot rediscover it.
                if (recovery) recoveryAttempts++;
                List<int[]> found = recovery ? searchRoute(goals,-30000001,-1,z,true) : executableRoute(goals,x,y,z,mining);
                if (!recovery && routeSearchPending) { status = "searching"; return false; }
                if (found != null && !found.isEmpty()) {
                    if (recovery) {
                        route.addAll(found); routeIndex = segmentEnd = 0;
                        enteringRoute = getDistanceSq(found.get(0)[0]+.5,nodeY(found.get(0)),found.get(0)[2]+.5) > .0125;
                        recoveryDestination = found.get(found.size()-1).clone(); bestRouteProgress = -Double.MAX_VALUE; pathTicks = 0;
                    } else useRoute(found);
                }
            }
            if (route.isEmpty()) {
                if (recovery) {
                    status = recoveryAttempts >= RECOVERY_ATTEMPTS || "footing".equals(navigationReason) ? "route_failed" : movingStatus;
                    return false;
                }
                if (constructionTarget(x,y,z) && !routeSearchPending) { status = "route_failed"; return false; }
                if (mining && !buildingAccess && planAccess(x,y,z)) return false;
                if (!failedEndpoints.isEmpty()) navigationDetail += "; " + endpointDiagnostic(arrivalFailure);
                if (!"chunk".equals(status)) { navigationFailures++; routeFailure(); status = navigationFailures >= 3 ? "route_failed" : "recovering"; }
                return false;
            }
        }
        if (!travelSuppliesReady(route,recovery)) return false;
        if (enteringRoute && !route.isEmpty()) {
            int[] entry = route.get(0);
            getNavigator().clearPathEntity(); preciseGoal = entry; waypoint = entry.clone();
            status = recovery ? "corridor_recovery" : nodeY(entry) < boundingBox.minY-.1 ? "descending" : "final_approach";
            return false;
        }
        // Route candidates are ranked by complete safe route cost, not an angular rotation.
        // Give vanilla only a straight, verified run (at most six edges). Its shortcutting cannot
        // cut a corner across water/a pit or bypass a height change on these segments.
        segmentEnd = Math.min(routeIndex+1,route.size()-1);
        int[] from = route.get(routeIndex), first = route.get(segmentEnd);
        int dx = first[0]-from[0], dz = first[2]-from[2];
        Movement kind = movement(from,first);
        if (kind != Movement.WALK || nodeY(first) != first[1] || nodeY(from) != from[1]) {
            getNavigator().clearPathEntity(); preciseGoal = first; waypoint = first.clone();
            status = kind == Movement.CLIMB ? ladderActivity() : kind == Movement.DESCEND ? "descending" : "stepping";
            return false;
        }
        preciseGoal = null;
        if (segmentEnd > routeIndex && kind == Movement.WALK) while (segmentEnd+1 < route.size() && segmentEnd-routeIndex < 6) {
            int[] a = route.get(segmentEnd), b = route.get(segmentEnd+1);
            if (movement(a,b) != Movement.WALK || nodeY(b) != b[1] || b[0]-a[0] != dx || b[2]-a[2] != dz) break;
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
            navigationReason = "navigator"; navigationDetail = "Native navigator rejected segment from "
                    + Arrays.toString(from) + " to " + Arrays.toString(waypoint) + "; target=" + Arrays.toString(goal);
            route.clear(); navigationFailures++; clearRouteSearch(); routeFailure(); status = recovery ? movingStatus : "recovering"; return false;
        }
        status = kind == Movement.DESCEND ? "descending" : kind == Movement.STEP ? "stepping" : movingStatus;
        navigationReason = navigationDetail = ""; return false;
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
            for (int cz = (z-2)>>4; cz <= (z+2)>>4; cz++) {
                watchTerrain(cx<<4,y,cz<<4);
                if (!worldObj.blockExists(cx<<4,y,cz<<4)) return false;
            }
        return true;
    }
    private List<int[]> standingCandidates(int x,int y,int z,boolean mining) {
        List<int[]> result = new java.util.ArrayList<int[]>();
        for (int dx = -3; dx <= 3; dx++) for (int dz = -3; dz <= 3; dz++) for (int dy = -3; dy <= 3; dy++) {
            if (dx*dx+dz*dz > 9) continue;
            int sx = x+dx, sy = y+dy, sz = z+dz;
            if (failedEndpoints.contains(positionKey(sx,sy,sz)) && Arrays.equals(interactionTarget,new int[] {x,y,z})) continue;
            double fy = feet(sx,sy,sz,mining ? x : -30000001,mining ? y-1 : -1,z);
            if (Double.isNaN(fy) || !interactionFailure(sx+.5,fy,sz+.5,x,y,z,mining,false).isEmpty()) continue;
            result.add(new int[] {sx,sy,sz});
        }
        java.util.Collections.sort(result,(a,b) -> Double.compare(getDistanceSq(a[0]+.5,a[1],a[2]+.5),getDistanceSq(b[0]+.5,b[1],b[2]+.5)));
        return result;
    }
    private boolean standing(int x,int y,int z,int removedX,int removedY,int removedZ) {
        return !Double.isNaN(feet(x,y,z,removedX,removedY,removedZ));
    }
    private boolean recoveryStanding(int x,int y,int z) {
        return standing(x,y,z,-30000001,-1,0);
    }
    private AxisAlignedBB body(double x,double y,double z) {
        return AxisAlignedBB.getBoundingBox(x+boundingBox.minX-posX,y+.001,z+boundingBox.minZ-posZ,
                x+boundingBox.maxX-posX,y+boundingBox.maxY-boundingBox.minY-.001,z+boundingBox.maxZ-posZ);
    }
    private Block routeBlock(int x,int y,int z,int rx,int ry,int rz) {
        if (x == rx && y == ry && z == rz) return net.minecraft.init.Blocks.air;
        int[] p = proposedAccess.get(positionKey(x,y,z));
        return p == null ? worldObj.getBlock(x,y,z) : p[3] < 0 ? net.minecraft.init.Blocks.air
                : p[3] == 0 ? net.minecraft.init.Blocks.cobblestone : net.minecraft.init.Blocks.ladder;
    }
    private AxisAlignedBB accessShape(int[] p) {
        int x = p[0], y = p[1], z = p[2];
        return p[3] == 0 ? AxisAlignedBB.getBoundingBox(x,y,z,x+1,y+1,z+1)
                : AxisAlignedBB.getBoundingBox(x+(p[3] == 4 ? .875 : 0),y,z+(p[3] == 2 ? .875 : 0),
                        x+(p[3] == 5 ? .125 : 1),y+1,z+(p[3] == 3 ? .125 : 1));
    }
    /** Loaded block collision shapes only: World.getCollidingBoundingBoxes also scans entities/chunks. */
    private boolean collisions(AxisAlignedBB box,int rx,int ry,int rz,List<AxisAlignedBB> result,boolean dry) {
        result.clear();
        for (int x = (int)Math.floor(box.minX); x <= (int)Math.floor(box.maxX); x++)
            for (int z = (int)Math.floor(box.minZ); z <= (int)Math.floor(box.maxZ); z++)
                for (int y = Math.max(0,(int)Math.floor(box.minY)-1); y <= Math.min(worldObj.getHeight()-1,(int)Math.floor(box.maxY)); y++) {
                    watchTerrain(x,y,z);
                    if (!worldObj.blockExists(x,y,z)) return false;
                    Block block = routeBlock(x,y,z,rx,ry,rz);
                    if (dry && y+1 > box.minY && block.getMaterial().isLiquid()) return false;
                    int[] p = proposedAccess.get(positionKey(x,y,z));
                    if (p != null && !(x == rx && y == ry && z == rz)) {
                        if (p[3] < 0) continue;
                        AxisAlignedBB shape = accessShape(p);
                        if (shape.intersectsWith(box)) result.add(shape);
                    } else block.addCollisionBoxesToList(worldObj,x,y,z,box,result,this);
                }
        return true;
    }
    private boolean clearBody(double x,double y,double z,int rx,int ry,int rz) {
        return collisions(body(x,y,z),rx,ry,rz,recoveryCollisions,true) && recoveryCollisions.isEmpty();
    }
    private boolean supported(double x,double y,double z,int rx,int ry,int rz) {
        if (ladder((int)Math.floor(x),(int)Math.floor(y),(int)Math.floor(z),rx,ry,rz)) return true;
        AxisAlignedBB sole = AxisAlignedBB.getBoundingBox(x-width/2,y-.06,z-width/2,x+width/2,y+.001,z+width/2);
        if (!collisions(sole,rx,ry,rz,recoveryCollisions,false)) return false;
        for (AxisAlignedBB shape : recoveryCollisions) if (Math.abs(shape.maxY-y) <= .06) return true;
        return false;
    }
    /** Join the graph from the real supported body, which may straddle a pit edge.
     * Flooring the entity coordinate is not proof that the center of that cell has a floor. */
    private boolean safeStart(int[] node,int rx,int ry,int rz,boolean recovery) {
        double ay = boundingBox.minY, by = feet(node[0],node[1],node[2],rx,ry,rz);
        double dx = node[0]+.5-posX, dz = node[2]+.5-posZ;
        if (Double.isNaN(by) || by-ay > stepHeight+.001 || ay-by > (recovery ? 1.001 : 2.001) || dx*dx+dz*dz > 2.01
                || !clearBody(posX,ay,posZ,rx,ry,rz) || !supported(posX,ay,posZ,rx,ry,rz)) return false;
        double high = Math.max(ay,by);
        for (int i = 0; i <= 8; i++) {
            double t = i/8.0, x = posX+dx*t, z = posZ+dz*t;
            if (!clearBody(x,high,z,rx,ry,rz) || !clearBody(posX,ay+(high-ay)*t,posZ,rx,ry,rz)
                    || !clearBody(node[0]+.5,by+(high-by)*t,node[2]+.5,rx,ry,rz)) return false;
            if (Math.abs(by-ay) < .01 && !supported(x,ay,z,rx,ry,rz)) return false;
            if (by < ay-.01 && !supported(x,ay,z,rx,ry,rz)
                    && (!supported(x,by,z,rx,ry,rz) || !clearBody(x,by,z,rx,ry,rz))) return false;
        }
        return true;
    }
    private boolean solidAttachment(int x,int y,int z,net.minecraftforge.common.util.ForgeDirection face,int rx,int ry,int rz) {
        watchTerrain(x,y,z);
        if (y < 0 || y >= worldObj.getHeight() || !worldObj.blockExists(x,y,z) || x == rx && y == ry && z == rz) return false;
        int[] p = proposedAccess.get(positionKey(x,y,z));
        return p != null ? p[3] == 0 : worldObj.isSideSolid(x,y,z,face);
    }
    private boolean ladder(int x,int y,int z,int rx,int ry,int rz) {
        watchTerrain(x,y,z);
        if (y < 0 || y >= worldObj.getHeight() || !recoveryCellLoaded(x,y,z)) return false;
        Block b = routeBlock(x,y,z,rx,ry,rz);
        if (!(b instanceof net.minecraft.block.BlockLadder) && proposedAccess.containsKey(positionKey(x,y,z))) return false;
        // A proposed vanilla rung has no block/metadata in World yet. Do not ask
        // a world-dependent ladder hook to validate that hypothetical placement.
        boolean proposed = proposedAccess.containsKey(positionKey(x,y,z));
        if (!proposed && !b.isLadder(worldObj,x,y,z,this)) return false;
        if (b instanceof net.minecraft.block.BlockLadder) {
            int[] p = proposedAccess.get(positionKey(x,y,z));
            int meta = p == null ? worldObj.getBlockMetadata(x,y,z) : p[3];
            net.minecraftforge.common.util.ForgeDirection face = meta == 2 ? net.minecraftforge.common.util.ForgeDirection.NORTH
                    : meta == 3 ? net.minecraftforge.common.util.ForgeDirection.SOUTH
                    : meta == 4 ? net.minecraftforge.common.util.ForgeDirection.WEST : net.minecraftforge.common.util.ForgeDirection.EAST;
            return meta >= 2 && meta <= 5 && solidAttachment(x-face.offsetX,y,z-face.offsetZ,face,rx,ry,rz);
        }
        return !proposed && b.canBlockStay(worldObj,x,y,z);
    }
    /** Node Y is the floor of feet Y, not an assumption that its support is a full cube. */
    private double feet(int x,int y,int z,int rx,int ry,int rz) {
        boolean cache = searchHeights != null && rx == searchRemovedX && ry == searchRemovedY && rz == searchRemovedZ;
        long key = positionKey(x,y,z);
        if (cache && searchHeights.containsKey(key)) return searchHeights.get(key);
        double value = computeFeet(x,y,z,rx,ry,rz);
        if (cache && searchHeights.size() < 32768) searchHeights.put(key,value);
        return value;
    }
    private double computeFeet(int x,int y,int z,int rx,int ry,int rz) {
        if (y < 0 || y+1 >= worldObj.getHeight() || !recoveryCellLoaded(x,y,z)) return Double.NaN;
        if (ladder(x,y,z,rx,ry,rz) && clearBody(x+.5,y,z+.5,rx,ry,rz)) return y;
        AxisAlignedBB probe = AxisAlignedBB.getBoundingBox(x+.5-width/2,y-.01,z+.5-width/2,x+.5+width/2,y+1,z+.5+width/2);
        if (!collisions(probe,rx,ry,rz,recoveryCollisions,false)) return Double.NaN;
        double top = Double.NaN;
        for (AxisAlignedBB shape : recoveryCollisions)
            if (shape.maxY >= y-.001 && shape.maxY < y+1-.001 && (Double.isNaN(top) || shape.maxY > top)) top = shape.maxY;
        return !Double.isNaN(top) && clearBody(x+.5,top,z+.5,rx,ry,rz) ? top : Double.NaN;
    }
    private double nodeY(int[] p) { return feet(p[0],p[1],p[2],-30000001,-1,0); }
    private Movement movement(int[] a,int[] b) {
        if (ladder(a[0],a[1],a[2],-30000001,-1,0) || ladder(b[0],b[1],b[2],-30000001,-1,0)) return Movement.CLIMB;
        double dy = nodeY(b)-nodeY(a);
        return dy > .01 ? Movement.STEP : dy < -.01 ? Movement.DESCEND : Movement.WALK;
    }
    private boolean sight(double sx,double sy,double sz,int x,int y,int z) {
        for (int cx = Math.min((int)Math.floor(sx),x)>>4; cx <= (Math.max((int)Math.floor(sx),x)>>4); cx++)
            for (int cz = Math.min((int)Math.floor(sz),z)>>4; cz <= (Math.max((int)Math.floor(sz),z)>>4); cz++)
                if (!worldObj.blockExists(cx<<4,y,cz<<4)) return false;
        net.minecraft.util.Vec3 start = net.minecraft.util.Vec3.createVectorHelper(sx,sy,sz);
        net.minecraft.util.Vec3 end = net.minecraft.util.Vec3.createVectorHelper(x+.5,y+.5,z+.5);
        for (int[] p : proposedAccess.values()) if (p[3] >= 0 && !(p[0] == x && p[1] == y && p[2] == z)
                && accessShape(p).calculateIntercept(start,end) != null) return false;
        // The world's ray tracer cannot see planned harvesting. Skip only cells
        // explicitly authorized as clearance in this proposal; all other hits count.
        for (int attempt = 0; attempt < 16; attempt++) {
            net.minecraft.util.MovingObjectPosition hit = worldObj.func_147447_a(start,end,false,true,false);
            if (hit == null || hit.blockX == x && hit.blockY == y && hit.blockZ == z) return true;
            int[] cleared = proposedAccess.get(positionKey(hit.blockX,hit.blockY,hit.blockZ));
            if (cleared == null || cleared[3] >= 0) return false;
            double dx = end.xCoord-start.xCoord, dy = end.yCoord-start.yCoord, dz = end.zCoord-start.zCoord;
            double exit = Math.min(dx == 0 ? Double.POSITIVE_INFINITY : ((dx > 0 ? hit.blockX+1 : hit.blockX)-start.xCoord)/dx,
                    Math.min(dy == 0 ? Double.POSITIVE_INFINITY : ((dy > 0 ? hit.blockY+1 : hit.blockY)-start.yCoord)/dy,
                            dz == 0 ? Double.POSITIVE_INFINITY : ((dz > 0 ? hit.blockZ+1 : hit.blockZ)-start.zCoord)/dz));
            if (exit >= 1) return true;
            if (exit < 0) return false;
            double t = exit+.00001;
            start = net.minecraft.util.Vec3.createVectorHelper(start.xCoord+dx*t,start.yCoord+dy*t,start.zCoord+dz*t);
        }
        return false;
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
    private boolean safeEdge(int[] a,int[] b,int rx,int ry,int rz) {
        return edgeFailure(a,b,rx,ry,rz) == EdgeFailure.NONE;
    }
    private EdgeFailure edgeFailure(int[] a,int[] b,int rx,int ry,int rz) {
        int horizontal = Math.abs(a[0]-b[0])+Math.abs(a[2]-b[2]);
        double ay = feet(a[0],a[1],a[2],rx,ry,rz), by = feet(b[0],b[1],b[2],rx,ry,rz);
        if (Double.isNaN(ay) || Double.isNaN(by)) return EdgeFailure.FOOTING;
        if (by-ay > stepHeight+.001) return EdgeFailure.RISE;
        if (ay-by > 2.001) return EdgeFailure.DROP;
        if (horizontal > 1) return EdgeFailure.SPAN;
        if (horizontal == 0 && (Math.abs(a[1]-b[1]) != 1
                || !ladder(a[0],a[1],a[2],rx,ry,rz) || !ladder(b[0],b[1],b[2],rx,ry,rz))) return EdgeFailure.LADDER;
        // Lift/translate/lower the actual body. A dry drop of at most two blocks is
        // directed; the independent exit search, not reversing this edge, proves escape.
        double high = Math.max(ay,by);
        for (int i = 0; i <= 8; i++) {
            double t = i/8.0;
            double x = a[0]+.5+(b[0]-a[0])*t, z = a[2]+.5+(b[2]-a[2])*t;
            if (!clearBody(x,high,z,rx,ry,rz)) return EdgeFailure.CLEARANCE;
            if (Math.abs(by-ay) < .01 && !supported(x,ay,z,rx,ry,rz)) return EdgeFailure.LANDING;
            // Gravity starts as soon as the actual sole leaves the lip. Its body
            // must already overlap a dry landing surface within the accepted drop.
            if (by < ay-.01 && !supported(x,ay,z,rx,ry,rz)
                    && (!supported(x,by,z,rx,ry,rz) || !clearBody(x,by,z,rx,ry,rz))) return EdgeFailure.LANDING;
            if (!clearBody(a[0]+.5,ay+(high-ay)*t,a[2]+.5,rx,ry,rz)
                    || !clearBody(b[0]+.5,by+(high-by)*t,b[2]+.5,rx,ry,rz)) return EdgeFailure.CLEARANCE;
        }
        return EdgeFailure.NONE;
    }
    private boolean routeIntact(List<int[]> route,int from,int to,int removedX,int removedY,int removedZ) {
        return routeIntact(route,from,to,removedX,removedY,removedZ,false);
    }
    private boolean routeIntact(List<int[]> route,int from,int to,int removedX,int removedY,int removedZ,boolean recovery) {
        int step = to >= from ? 1 : -1;
        for (int i = from;; i += step) {
            int[] p = route.get(i);
            if (!(recovery ? inRecoveryBounds(p[0],p[1],p[2]) && recoveryStanding(p[0],p[1],p[2])
                    : inCorridor(p[0],p[2]) && standing(p[0],p[1],p[2],removedX,removedY,removedZ))
                    || i != from && (!safeEdge(route.get(i-step),p,removedX,removedY,removedZ)
                        || recovery && !safeEdge(p,route.get(i-step),removedX,removedY,removedZ))) return false;
            if (i == to) break;
        }
        return true;
    }
    private int routePosition(List<int[]> route) {
        int px = (int)Math.floor(posX), py = (int)Math.floor(boundingBox.minY+.001), pz = (int)Math.floor(posZ);
        for (int i = 0; i < route.size(); i++) {
            int[] p = route.get(i); if (p[0] == px && p[1] == py && p[2] == pz) return i;
        }
        return -1;
    }
    private boolean stationEndpoint(int[] p) {
        return interactionFailure(p[0]+.5,nodeY(p),p[2]+.5,plan[0],plan[1],plan[2],false,false).isEmpty();
    }
    private List<int[]> cachedTravel(List<int[]> goals,int removedX,int removedY,int removedZ) {
        if (returnRoute.isEmpty() || !stationEndpoint(returnRoute.get(0))) { returnRoute.clear(); return null; }
        int here = routePosition(returnRoute), there = -1, cost = Integer.MAX_VALUE;
        if (here < 0) return null;
        for (int i = 0; i < returnRoute.size(); i++) for (int[] goal : goals) {
            if (Arrays.equals(returnRoute.get(i),goal) && Math.abs(i-here) < cost) { there = i; cost = Math.abs(i-here); }
        }
        if (there < 0) return null;
        if (!safeStart(returnRoute.get(here),removedX,removedY,removedZ,false)
                || !routeIntact(returnRoute,here,there,removedX,removedY,removedZ)) {
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
     * Steps climb at most one block; dry drops descend at most two. */
    private List<int[]> searchRoute(List<int[]> goals,int removedX,int removedY,int removedZ) {
        return searchRoute(goals,removedX,removedY,removedZ,false);
    }
    private List<int[]> searchRoute(List<int[]> goals,int removedX,int removedY,int removedZ,boolean recovery) {
        return searchRoute(goals,removedX,removedY,removedZ,recovery,null);
    }
    private List<int[]> searchRoute(List<int[]> goals,int removedX,int removedY,int removedZ,boolean recovery,int[] startPosition) {
        return searchRoute(goals,removedX,removedY,removedZ,recovery,startPosition,recovery);
    }
    private List<int[]> searchRoute(List<int[]> goals,int removedX,int removedY,int removedZ,boolean recovery,int[] startPosition,boolean reversible) {
        failedSearchPositions = java.util.Collections.emptySet();
        searchHeights = new java.util.HashMap<Long,Double>();
        searchRemovedX = removedX; searchRemovedY = removedY; searchRemovedZ = removedZ;
        try { return expandRoute(goals,removedX,removedY,removedZ,recovery,startPosition,reversible); }
        finally { searchHeights = null; }
    }
    private List<int[]> expandRoute(List<int[]> goals,int removedX,int removedY,int removedZ,boolean recovery,int[] startPosition,boolean reversible) {
        int sx = startPosition == null ? (int)Math.floor(posX) : startPosition[0];
        int sy = startPosition == null ? (int)Math.floor(boundingBox.minY+.001) : startPosition[1];
        int sz = startPosition == null ? (int)Math.floor(posZ) : startPosition[2];
        if (sy < 0 || sy+1 >= worldObj.getHeight()) {
            navigationReason = "footing"; navigationDetail = "Start outside supported world height: " + sy; return null;
        }
        if (!worldObj.blockExists(sx,Math.max(0,sy-1),sz) || !worldObj.blockExists(sx,Math.min(worldObj.getHeight()-1,sy+2),sz)
                || !recoveryCellLoaded(sx,sy,sz)) {
            navigationReason = "chunk"; navigationDetail = "Start chunk unavailable: " + sx+","+sy+","+sz;
            status = recovery ? "corridor_recovery" : "chunk"; return null;
        }
        if (startPosition != null && !standing(sx,sy,sz,removedX,removedY,removedZ)) {
            navigationReason = clearBody(sx+.5,sy,sz+.5,removedX,removedY,removedZ) ? "footing" : "clearance";
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
        if (startPosition != null) {
            int[] first = new int[] {sx,sy,sz,0,routeHeuristic(sx,sy,sz,goals)};
            visited.put(positionKey(sx,sy,sz),first); open.add(first);
        } else {
            // At most 36 local seeds. Each has a physically executable connection
            // from the real body; route execution explicitly visits the chosen seed.
            for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) for (int dy = reversible ? -1 : -2; dy <= 1; dy++) {
                int nx = sx+dx, ny = sy+dy, nz = sz+dz;
                int[] seed = new int[] {nx,ny,nz};
                if (recovery ? !inRecoveryBounds(nx,ny,nz) : !inCorridor(nx,nz)) continue;
                if (!safeStart(seed,removedX,removedY,removedZ,reversible)) continue;
                int cost = (int)Math.ceil(10*Math.sqrt((nx+.5-posX)*(nx+.5-posX)+(nz+.5-posZ)*(nz+.5-posZ)))
                        + 4*Math.abs(ny-sy);
                int[] first = new int[] {nx,ny,nz,cost,cost+(recovery ? recoveryHeuristic(nx,nz) : routeHeuristic(nx,ny,nz,goals))};
                visited.put(positionKey(nx,ny,nz),first); open.add(first);
            }
            if (open.isEmpty()) {
                navigationReason = clearBody(posX,boundingBox.minY,posZ,removedX,removedY,removedZ) ? "footing" : "clearance";
                navigationDetail = "No safe connection from actual supported body at " + posX+","+boundingBox.minY+","+posZ;
                return null;
            }
        }
        int expanded = 0; boolean unloaded = false, distanceLimited = false;
        int[] rejectedFrom = null, rejectedTo = null;
        EdgeFailure rejectedRule = EdgeFailure.NONE;
        int rejectedRank = Integer.MAX_VALUE;
        Long bestEndpoint = null; int bestCost = Integer.MAX_VALUE;
        while (!open.isEmpty() && expanded < 8192 && visited.size() < 16384 && open.peek()[4] < bestCost) {
            int[] p = open.poll(); long key = positionKey(p[0],p[1],p[2]);
            if (visited.get(key) != p) continue;
            expanded++;
            if (recovery ? inCorridor(p[0],p[2]) : endpoints.contains(key)) {
                // Native path completion permits width (.6) blocks of center error. Prefer
                // endpoints with that much reach margin, but retain exact final approaches.
                int penalty = !recovery && interactionTarget != null && !storageTask()
                        && getEndpointDistance(p,interactionTarget) > 2.35 ? 30 : 0;
                if (p[3]+penalty < bestCost) { bestCost = p[3]+penalty; bestEndpoint = key; }
                // A fringe endpoint can also be the entrance to a better, deeper endpoint.
                if (penalty == 0) continue;
            }
            // Construction stays in the current reachable area until the exit is built.
            // Recovery also retains its original reversible one-block envelope policy.
            boolean fromLadder = ladder(p[0],p[1],p[2],removedX,removedY,removedZ);
            for (int direction = 0; direction < 5; direction++) for (int stepY = reversible ? -1 : -2; stepY <= 1; stepY++) {
                if (direction == 4 && stepY == 0) continue;
                int nx = p[0]+(direction == 0 ? 1 : direction == 1 ? -1 : 0);
                int nz = p[2]+(direction == 2 ? 1 : direction == 3 ? -1 : 0), ny = p[1]+stepY;
                if (ny < 0 || ny+1 >= worldObj.getHeight()) continue;
                if (recovery ? !inRecoveryBounds(nx,ny,nz) : !inCorridor(nx,nz)) {
                    if (recovery) distanceLimited = true;
                    continue;
                }
                if (!worldObj.blockExists(nx,ny,nz) || !recoveryCellLoaded(nx,ny,nz)) { unloaded = true; continue; }
                long next = positionKey(nx,ny,nz); int cost = p[3]+10+4*Math.abs(stepY);
                if (recovery && cost > RECOVERY_COST) { distanceLimited = true; continue; }
                int[] old = visited.get(next);
                if (old != null && old[3] <= cost) continue;
                int heuristic = recovery ? recoveryHeuristic(nx,nz) : routeHeuristic(nx,ny,nz,goals);
                int[] node = standing(nx,ny,nz,removedX,removedY,removedZ) ? new int[] {nx,ny,nz,cost,cost+heuristic} : null;
                EdgeFailure rule = node == null ? EdgeFailure.FOOTING : edgeFailure(p,node,removedX,removedY,removedZ);
                boolean reverse = rule == EdgeFailure.NONE && reversible;
                if (reverse) rule = edgeFailure(node,p,removedX,removedY,removedZ);
                if (rule != EdgeFailure.NONE) {
                    // Retain one frontier connection, favoring ladder exits, for the
                    // failed search summary. Never emit a log per rejected neighbor.
                    int rank = (fromLadder ? 0 : 100000)+heuristic;
                    if (rank < rejectedRank) {
                        rejectedRank = rank; rejectedFrom = reverse ? node : p;
                        rejectedTo = reverse ? p : node == null ? new int[] {nx,ny,nz} : node; rejectedRule = rule;
                    }
                    continue;
                }
                if (old == null && visited.size() >= 16384) continue;
                visited.put(next,node); parents.put(next,key); open.add(node);
            }
        }
        if (bestEndpoint != null) {
            List<int[]> result = new java.util.ArrayList<int[]>(); long key = bestEndpoint;
            while (true) {
                int[] node = visited.get(key); result.add(new int[] {node[0],node[1],node[2]});
                if (!parents.containsKey(key)) break;
                if (result.size() >= 4096) { navigationReason = "detour_limit"; navigationDetail = "Route exceeds 4096 positions"; return null; }
                key = parents.get(key);
            }
            java.util.Collections.reverse(result); return result;
        }
        navigationReason = !open.isEmpty() ? "search_limit" : unloaded ? "chunk" : distanceLimited ? "recovery_limit" : "path";
        // An exhausted directed component cannot reach these exits from any of
        // its members. Reuse that proof for other work endpoints in this layout.
        if (open.isEmpty() && !unloaded && !distanceLimited) failedSearchPositions = visited.keySet();
        navigationDetail = "No complete safe route; expanded=" + expanded + " discovered=" + visited.size()
                + " endpoints=" + (recovery ? "any supported corridor entry" : endpoints.size())
                + "; phase=" + accessStage + " proposal=" + (accessProposalIndex+1)
                + "; start=" + sx+","+sy+","+sz + "; destination=" + (goals.isEmpty() ? "corridor" : Arrays.toString(goals.get(0)))
                + (rejectedFrom == null ? "" : "; rejected=" + Arrays.toString(Arrays.copyOf(rejectedFrom,3))
                        + " -> " + Arrays.toString(Arrays.copyOf(rejectedTo,3)) + " rule=" + rejectedRule)
                + (recovery ? "; fixed recovery origin=" + Arrays.toString(recoveryOrigin) : "; bounded station/work corridor");
        if ("chunk".equals(navigationReason)) status = recovery ? "corridor_recovery" : "chunk";
        return null;
    }
    private double getEndpointDistance(int[] p,int[] target) {
        double dy = nodeY(p)-target[1];
        return Math.sqrt((double)(p[0]-target[0])*(p[0]-target[0])+dy*dy+(double)(p[2]-target[2])*(p[2]-target[2]));
    }
    private List<int[]> executableRoute(List<int[]> goals,int x,int y,int z,boolean mining) {
        int rx = mining ? x : -30000001, ry = mining ? y-1 : -1;
        boolean construction = constructionTarget(x,y,z);
        if (!mining && !construction && (pendingDrops == null || storageTask())) return searchRoute(goals,-30000001,-1,0);
        int proposal = accessProposals == null ? -1 : accessProposalIndex;
        if (!Arrays.equals(routeSearchTarget,new int[] {x,y,z}) || routeSearchProposal != proposal) {
            routeSearchTarget = new int[] {x,y,z}; routeSearchProposal = proposal;
            routeSearchRemaining = new java.util.ArrayList<int[]>(goals);
        }
        routeSearchPending = false;
        // Three endpoint checks are a per-update budget, never an eligibility cutoff.
        for (int attempt = 0; attempt < 3 && !routeSearchRemaining.isEmpty(); attempt++) {
            // Travel happens before harvesting: never walk through hypothetical air.
            List<int[]> found = searchRoute(routeSearchRemaining,-30000001,-1,0,false,null,construction);
            if (found == null && !construction) found = cachedTravel(routeSearchRemaining,-30000001,-1,0);
            if (found == null) {
                if (construction) accessFailure("approach",accessPlan.get(accessIndex),navigationDetail);
                if ("chunk".equals(navigationReason)) { clearRouteSearch(); return null; }
                routeSearchRemaining.clear(); return null;
            }
            int[] end = found.get(found.size()-1);
            if (construction) {
                if (constructionSafe(found,accessPlan.get(accessIndex))) { clearRouteSearch(); return found; }
                failedEndpoints.add(positionKey(end[0],end[1],end[2]));
                routeSearchRemaining.removeIf(p -> Arrays.equals(p,end));
                continue;
            }
            // Check the exit from the chosen work position before any descent. The exit
            // search is independent: it need not retrace the entrance or reuse a stale cache.
            if (accessProposals != null) accessStage = "work_exit";
            List<int[]> exits = standingCandidates(plan[0],plan[1],plan[2],false);
            List<int[]> exit = exits.isEmpty() ? null : searchRoute(exits,rx,ry,z,false,end);
            if (exit != null) {
                java.util.Collections.reverse(exit); returnRoute.clear(); returnRoute.addAll(exit); clearRouteSearch(); return found;
            }
            navigationDetail = "Storage exit failed from endpoint=" + Arrays.toString(end) + "; "
                    + (exits.isEmpty() ? "No storage interaction positions" : navigationDetail);
            if (!failedSearchPositions.isEmpty()) routeSearchRemaining.removeIf(p -> failedSearchPositions.contains(positionKey(p[0],p[1],p[2])));
            routeSearchRemaining.removeIf(p -> Arrays.equals(p,end));
        }
        if (!routeSearchRemaining.isEmpty()) { routeSearchPending = true; return null; }
        if (construction) status = "route_failed";
        if (!construction) {
            navigationReason = "return_blocked";
            navigationDetail = "No verified storage exit from any reachable interaction endpoint; target=" + x+","+y+","+z + "; " + navigationDetail;
        }
        return null;
    }
    private boolean constructionTarget(int x,int y,int z) {
        if (!buildingAccess || storageTask() || accessIndex >= accessPlan.size()) return false;
        int[] p = accessPlan.get(accessIndex);
        if (task == Task.ESCAPE && p[3] >= 0) return false;
        return p[0] == x && p[1]+(p[3] < 0 ? 1 : 0) == y && p[2] == z;
    }
    private void accessFailure(String stage,int[] cell,String detail) {
        accessStage = stage;
        String blocker = "approach".equals(stage) || "first_placement".equals(stage) ? "No reachable position for the next access edit"
                : "work_approach".equals(stage) || "work_exit".equals(stage) ? "Work approach or storage return is blocked"
                : "existing_exit".equals(stage) ? "Current escape safety cannot be verified"
                : "preserve_exit".equals(stage) ? "Next access edit would block the current storage exit" : detail;
        accessBlocker = (cell == null ? "" : cell[0]+", "+cell[1]+", "+cell[2]+": ") + blocker;
        accessFailure = "Access " + (buildingAccess ? "cell " + (accessIndex+1) + "/" + accessPlan.size()
                : "proposal " + (accessProposalIndex+1) + "/" + (accessProposals == null ? 0 : accessProposals.size()))
                + " stage=" + stage + " target=" + Arrays.toString(buildingAccess ? accessDestination : accessSearchTarget)
                + " cell=" + Arrays.toString(cell) + "; " + detail;
        navigationDetail = accessFailure;
        diagnostic("access",stage);
    }
    /** The same ordered plan owns terrain clearance, supplied supports and ladders. */
    private boolean proposeAccess(List<int[]> cells) {
        java.util.Set<Long> clearance = new java.util.HashSet<Long>();
        for (int[] p : cells) if (p[3] < 0) clearance.add(positionKey(p[0],p[1],p[2]));
        for (int[] p : cells) if (!accessPresent(p)) {
            watchTerrain(p[0],p[1],p[2]);
            if (!accessArea(p[0],p[1],p[2]) || (p[3] < 0 ? !accessHarvestable(p,true)
                    : !worldObj.isAirBlock(p[0],p[1],p[2]) && !rungPresent(p) && !clearance.contains(positionKey(p[0],p[1],p[2])))
                    || worldObj.getTileEntity(p[0],p[1],p[2]) != null || BuilderPlacement.protectedBlock(worldObj,p[0],p[1],p[2])) {
                navigationReason = "access_blocked"; accessFailure("cells",p,"Missing access cell is occupied, protected or unavailable"); return false;
            }
        }
        List<int[]> pending = new java.util.ArrayList<int[]>(cells), ordered = new java.util.ArrayList<int[]>();
        boolean ladderPlan = hasRungs(cells);
        // The final overlay may replace a cleared cell, but execution must retain
        // both operations and finish tool actions before restoring their damage.
        for (java.util.Iterator<int[]> it = pending.iterator(); it.hasNext();) {
            int[] p = it.next();
            if (p[3] < 0 && ladderPlan) {
                if (!accessPresent(p)) proposedAccess.put(positionKey(p[0],p[1],p[2]),p);
                ordered.add(p); it.remove();
            }
        }
        while (!pending.isEmpty()) {
            int before = pending.size();
            for (java.util.Iterator<int[]> it = pending.iterator(); it.hasNext();) {
                int[] p = it.next();
                boolean present = accessPresent(p);
                if (!present && p[3] >= 0 && placementSide(p) < 0) continue;
                if (!present) proposedAccess.put(positionKey(p[0],p[1],p[2]),p);
                ordered.add(p); it.remove();
            }
            if (pending.size() == before) {
                navigationReason = "placement";
                accessFailure("attachment",pending.get(0),"No authorized attachment in any remaining placement order"); return false;
            }
        }
        cells.clear(); cells.addAll(ordered);
        return true;
    }
    private List<int[]> stationRoute(int[] start) {
        List<int[]> exits = standingCandidates(plan[0],plan[1],plan[2],false);
        if (exits.isEmpty()) {
            navigationReason = "standing"; navigationDetail = "No storage endpoint near " + plan[0]+","+plan[1]+","+plan[2]; return null;
        }
        return searchRoute(exits,-30000001,-1,0,false,start);
    }
    private void checkAccessStation() {
        accessStationReachable = stationRoute(null) != null;
        accessSupplyChecked = true;
    }
    /** Prove escape in the post-edit graph, independently of the entrance path.
     * The overlay never mutates World, and only this operation is hypothetical. */
    private boolean constructionSafe(List<int[]> approach,int[] cell) {
        int[] end = approach == null ? null : approach.get(approach.size()-1);
        double bx = end == null ? posX : end[0]+.5, bz = end == null ? posZ : end[2]+.5;
        double by = end == null ? boundingBox.minY : nodeY(end);
        boolean hadExit = stationRoute(null) != null;
        if (!hadExit && ("chunk".equals(navigationReason) || "search_limit".equals(navigationReason))) {
            accessFailure("existing_exit",cell,"Cannot establish current escape safety: " + navigationDetail); return false;
        }
        if (!hadExit && constructionRefuge.isEmpty()) {
            int sx = (int)Math.floor(posX), sy = (int)Math.floor(boundingBox.minY+.001), sz = (int)Math.floor(posZ);
            for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) for (int dy = -1; dy <= 1; dy++) {
                int[] p = new int[] {sx+dx,sy+dy,sz+dz};
                if (inCorridor(p[0],p[2]) && safeStart(p,-30000001,-1,0,true)) constructionRefuge.add(p);
            }
        }
        long key = positionKey(cell[0],cell[1],cell[2]);
        int[] previous = proposedAccess.get(key);
        try {
            proposedAccess.put(key,cell);
            // At commitment this is the actual body, including fractional feet
            // and edge support; virtual endpoint approval alone is insufficient.
            if (Double.isNaN(by) || !clearBody(bx,by,bz,-30000001,-1,0) || !supported(bx,by,bz,-30000001,-1,0)) {
                navigationReason = "construction_exit";
                accessFailure("builder_body",cell,"Edit would collide with or remove support from builder at " + bx+", "+by+", "+bz); return false;
            }
            if (hadExit) {
                if (stationRoute(end) == null) {
                    navigationReason = "construction_exit";
                    accessFailure("preserve_exit",cell,"Placement would remove an existing escape from builder=" + Arrays.toString(end) + "; " + navigationDetail);
                    return false;
                }
            } else {
                List<int[]> refuges = new java.util.ArrayList<int[]>();
                for (int[] p : constructionRefuge) if (standing(p[0],p[1],p[2],-30000001,-1,0)) refuges.add(p);
                // An edit may open a new exit, or require an entirely different
                // route back to the retained safe area. Neither must retrace approach.
                if (stationRoute(end) == null && (refuges.isEmpty()
                        || searchRoute(refuges,-30000001,-1,0,false,end) == null)) {
                    navigationReason = "construction_exit";
                    accessFailure("reachable_area",cell,"No post-edit route from builder=" + Arrays.toString(end)
                            + " to storage or the retained safe area; " + navigationDetail); return false;
                }
            }
            return true;
        } finally { if (previous == null) proposedAccess.remove(key); else proposedAccess.put(key,previous); }
    }
    private boolean accessArea(int x,int y,int z) {
        return x >= plan[3] && x <= plan[5] && z >= plan[4] && z <= plan[6] && y >= plan[8] && y <= plan[7]
                && recoveryCellLoaded(x,y,z) && WorkerAccess.ownsLand(worldObj,x,z,factionId);
    }
    private boolean reservedAccess(int x,int y,int z) {
        for (int[] p : accessBlocks) if (p[0] == x && p[1] == y && p[2] == z) return true;
        for (int[] p : accessPlan) if (p[0] == x && p[1] == y && p[2] == z) return true;
        if (ladderPosition != null && x == ladderPosition[0] && z == ladderPosition[1]) return true;
        if (ladderPosition != null) {
            net.minecraftforge.common.util.ForgeDirection d = net.minecraftforge.common.util.ForgeDirection.getOrientation(ladderPosition[2]);
            if (x == ladderPosition[0]-d.offsetX && z == ladderPosition[1]-d.offsetZ) return true;
        }
        return false;
    }
    private void retainAccess(int[] cell) {
        // A cleared placement prerequisite is not permanent air headroom.
        if (cell[3] < 0) for (int[] p : accessPlan) if (p[3] >= 0 && p[0] == cell[0] && p[1] == cell[1] && p[2] == cell[2]) return;
        for (int[] p : accessBlocks) if (p[0] == cell[0] && p[1] == cell[1] && p[2] == cell[2]) {
            p[3] = cell[3];
            return;
        }
        accessBlocks.add(cell.clone());
    }
    private boolean accessPresent(int[] p) {
        if (!worldObj.blockExists(p[0],p[1],p[2])) return false;
        if (p[3] < 0) return routeBlock(p[0],p[1],p[2],-30000001,-1,0) == net.minecraft.init.Blocks.air;
        if (p[3] == 0 && ladderPosition != null) {
            net.minecraftforge.common.util.ForgeDirection d = net.minecraftforge.common.util.ForgeDirection.getOrientation(ladderPosition[2]);
            if (p[0] == ladderPosition[0]-d.offsetX && p[2] == ladderPosition[1]-d.offsetZ)
                return solidAttachment(p[0],p[1],p[2],d,-30000001,-1,0);
        }
        return p[3] == 0 ? routeBlock(p[0],p[1],p[2],-30000001,-1,0).isNormalCube()
                        && !(routeBlock(p[0],p[1],p[2],-30000001,-1,0) instanceof net.minecraft.block.BlockFalling)
                : routeBlock(p[0],p[1],p[2],-30000001,-1,0) instanceof net.minecraft.block.BlockLadder
                        && (proposedAccess.containsKey(positionKey(p[0],p[1],p[2])) ? proposedAccess.get(positionKey(p[0],p[1],p[2]))[3]
                                : worldObj.getBlockMetadata(p[0],p[1],p[2])) == p[3]
                        && ladder(p[0],p[1],p[2],-30000001,-1,0);
    }
    private boolean rungPresent(int[] p) {
        return p[3] > 0 && worldObj.blockExists(p[0],p[1],p[2])
                && worldObj.getBlock(p[0],p[1],p[2]) instanceof net.minecraft.block.BlockLadder
                && worldObj.getBlockMetadata(p[0],p[1],p[2]) == p[3];
    }
    private boolean accessHarvestable(int[] p) {
        return accessHarvestable(p,false);
    }
    private boolean accessHarvestable(int[] p,boolean repair) {
        if (!accessArea(p[0],p[1],p[2])) return false;
        Block b = worldObj.getBlock(p[0],p[1],p[2]);
        if (b == net.minecraft.init.Blocks.bedrock || b.getMaterial().isLiquid() || b.getBlockHardness(worldObj,p[0],p[1],p[2]) < 0
                || worldObj.getTileEntity(p[0],p[1],p[2]) != null || BuilderPlacement.protectedBlock(worldObj,p[0],p[1],p[2])) return false;
        // A reservation protects ordinary excavation, not an authorized access edit.
        // Never clear a correctly attached fixed rung just to obtain air headroom.
        if (repair) return !(b instanceof net.minecraft.block.BlockLadder && ladderPosition != null
                && accessPresent(new int[] {p[0],p[1],p[2],ladderPosition[2]}));
        for (int[] retained : accessBlocks) if (retained[3] >= 0 && retained[0] == p[0] && retained[1] == p[1] && retained[2] == p[2]) return false;
        if (ladderPosition != null) {
            net.minecraftforge.common.util.ForgeDirection d = net.minecraftforge.common.util.ForgeDirection.getOrientation(ladderPosition[2]);
            if (p[0] == ladderPosition[0] && p[2] == ladderPosition[1]
                    || p[0] == ladderPosition[0]-d.offsetX && p[2] == ladderPosition[1]-d.offsetZ) return false;
        }
        return true;
    }
    private int availableAccess(boolean rung) {
        return availableAccess(rung,false);
    }
    private int availableAccess(boolean rung,boolean repair) {
        int count = accessCount(rung);
        IInventory station = accessSupplyChecked && accessStationReachable ? stationInventory(plan[0],plan[1],plan[2]) : null;
        if (station != null) for (int i = firstSlot(station); i < station.getSizeInventory(); i++) {
            ItemStack stack = station.getStackInSlot(i);
            if (accessMaterial(stack,rung)) count += stack.stackSize;
        }
        return rung || task == Task.ESCAPE || repair ? count : Math.max(0,count-emergencyBlocks());
    }
    private boolean hasRungs(List<int[]> cells) {
        for (int[] p : cells) if (p[3] > 0) return true;
        return false;
    }
    private String accessActivity() {
        if (task == Task.ESCAPE) return "escape";
        if (staircaseAccess) return "building_staircase";
        if (accessIndex < accessPlan.size()) {
            int[] p = accessPlan.get(accessIndex);
            if (p[3] < 0) return "clearing_ladder_entry";
            if (p[3] > 0) return "replacing_ladder_rungs";
            if (ladderPosition != null) {
                net.minecraftforge.common.util.ForgeDirection d = net.minecraftforge.common.util.ForgeDirection.getOrientation(ladderPosition[2]);
                if (p[0] == ladderPosition[0]-d.offsetX && p[2] == ladderPosition[1]-d.offsetZ) return "replacing_ladder_backing";
            }
            return "repairing_landing";
        }
        return repairingLadder ? "repairing_ladder" : "extending_ladder";
    }
    private void installStaircase(List<int[]> cells) {
        staircaseInstalled = accessRamp = true;
        int[] layout = staircaseLayouts.get(cells);
        if (layout != null) { rampOffset = layout[0]; rampDirection = layout[1]; }
    }
    private void recoverRecordedLadder() {
        if (ladderPosition != null) return;
        // MinerAccess records actual accepted/present cells, never hypothetical proposals.
        // Retain even a subsequently broken rung's historical column for repair.
        for (int[] p : accessBlocks) if (p[3] >= 2 && p[3] <= 5 && validLadderPosition(new int[] {p[0],p[2],p[3]})) {
            ladderPosition = new int[] {p[0],p[2],p[3]}; return;
        }
    }
    /** Discover player ladders too, without committing an unverified proposal.
     * Bounded slices run once per access search, including zero-supply searches. */
    private boolean recoverLadderPosition() {
        recoverRecordedLadder();
        if (ladderPosition != null || ladderDiscoveryComplete) return true;
        if (ladderDiscoveryColumn == 0) ladderObstruction = "";
        int width = plan[5]-plan[3]+1, columns = width*(plan[6]-plan[4]+1);
        int end = Math.min(columns,ladderDiscoveryColumn+16);
        for (; ladderDiscoveryColumn < end; ladderDiscoveryColumn++) {
            int x = plan[3]+ladderDiscoveryColumn%width, z = plan[4]+ladderDiscoveryColumn/width;
            boolean[] found = new boolean[6];
            for (int y = plan[8]; y <= plan[7]; y++) {
                watchTerrain(x,y,z);
                if (!worldObj.blockExists(x,y,z) || !(worldObj.getBlock(x,y,z) instanceof net.minecraft.block.BlockLadder)) continue;
                int face = worldObj.getBlockMetadata(x,y,z);
                if (face < 2 || face > 5) {
                    ladderObstruction = "Existing ladder has invalid attachment metadata at " + x+", "+y+", "+z; continue;
                }
                if (!ladder(x,y,z,-30000001,-1,0))
                    ladderObstruction = "Existing ladder attachment is missing at " + x+", "+y+", "+z + " face=" + face;
                // Even a broken attachment is evidence of a ladder column, and
                // may be repairable. Full connection proofs precede adoption.
                if (!found[face]) { discoveredLadders.add(new int[] {x,z,face}); found[face] = true; }
            }
        }
        ladderDiscoveryComplete = ladderDiscoveryColumn >= columns;
        return ladderDiscoveryComplete;
    }
    private boolean validLadderPosition(int[] fixed) {
        if (!configured || fixed.length != 3 || fixed[2] < 2 || fixed[2] > 5) return false;
        return fixed[0] >= plan[3] && fixed[0] <= plan[5] && fixed[1] >= plan[4] && fixed[1] <= plan[6];
    }
    private boolean existingLadder(List<int[]> cells) {
        for (int[] p : cells) if (p[3] > 0 && worldObj.blockExists(p[0],p[1],p[2])
                && worldObj.getBlock(p[0],p[1],p[2]) instanceof net.minecraft.block.BlockLadder
                && worldObj.getBlockMetadata(p[0],p[1],p[2]) == p[3]) return true;
        return false;
    }
    private void adoptLadder(List<int[]> cells) {
        if (ladderPosition == null && existingLadder(cells)) for (int[] p : cells) if (p[3] > 0) {
            ladderPosition = new int[] {p[0],p[2],p[3]}; break;
        }
        for (int[] p : cells) if (accessPresent(p) && accessArea(p[0],p[1],p[2])) retainAccess(p);
        retainLadderBacking(cells);
        retainLadderLandings(cells);
    }
    private void retainLadderBacking(List<int[]> cells) {
        if (ladderPosition == null) return;
        net.minecraftforge.common.util.ForgeDirection d = net.minecraftforge.common.util.ForgeDirection.getOrientation(ladderPosition[2]);
        List<int[]> backing = new java.util.ArrayList<int[]>();
        for (int[] p : cells) if (p[3] > 0 && fixedRung(p)) {
            int[] support = {p[0]-d.offsetX,p[1],p[2]-d.offsetZ,0};
            if (accessArea(support[0],support[1],support[2])) backing.add(support);
        }
        for (int[] p : backing) retainAccess(p);
    }
    private boolean fixedRung(int[] p) {
        return ladderPosition == null || p[0] == ladderPosition[0] && p[2] == ladderPosition[1] && p[3] == ladderPosition[2];
    }
    private void retainLadderLandings(List<int[]> cells) {
        if (ladderPosition == null) return;
        List<int[]> rungs = new java.util.ArrayList<int[]>();
        for (int[] p : cells) if (p[3] > 0 && fixedRung(p)) rungs.add(p);
        for (int[] landing : accessLandings(rungs)) for (int dy = -1; dy <= 1; dy++)
            if (accessArea(landing[0],landing[1]+dy,landing[2])) {
                int[] reservation = new int[] {landing[0],landing[1]+dy,landing[2],dy < 0 ? 0 : -1};
                if (accessPresent(reservation)) retainAccess(reservation);
            }
    }
    private void inspectFixedLadder() {
        if (ladderPosition == null) return;
        ladderObstruction = "";
        net.minecraftforge.common.util.ForgeDirection d = net.minecraftforge.common.util.ForgeDirection.getOrientation(ladderPosition[2]);
        for (int y = accessBottom; y <= accessTop; y++) for (int backing = 0; backing <= 1; backing++) {
            int x = ladderPosition[0]-backing*d.offsetX, z = ladderPosition[1]-backing*d.offsetZ;
            watchTerrain(x,y,z);
            if (!accessArea(x,y,z) && !(backing == 1 && solidAttachment(x,y,z,d,-30000001,-1,0))) {
                ladderObstruction = "Fixed ladder cell unavailable or outside owned land at " + x+", "+y+", "+z; return;
            }
            boolean present = backing == 1 ? solidAttachment(x,y,z,d,-30000001,-1,0) : accessPresent(new int[] {x,y,z,ladderPosition[2]});
            if (worldObj.getTileEntity(x,y,z) != null || BuilderPlacement.protectedBlock(worldObj,x,y,z)
                    || !present && !worldObj.isAirBlock(x,y,z) && !accessHarvestable(new int[] {x,y,z,-1},true)) {
                ladderObstruction = "Fixed ladder " + (backing == 0 ? "rung" : "backing") + " obstructed at " + x+", "+y+", "+z
                        + " by " + worldObj.getBlock(x,y,z).getUnlocalizedName(); return;
            }
        }
    }
    private boolean staircaseCell(List<int[]> cells,java.util.Map<Long,int[]> used,int x,int y,int z,int kind) {
        watchTerrain(x,y,z);
        if (kind < 0 && y > plan[7]) {
            if (worldObj.blockExists(x,y,z) && worldObj.isAirBlock(x,y,z)) return true;
            accessFailure("staircase",new int[] {x,y,z},"Required headroom is occupied/unavailable outside the assigned height; it cannot be mined"); return false;
        }
        if (!accessArea(x,y,z)) {
            accessFailure("staircase",new int[] {x,y,z},"Required staircase cell is outside owned bounds or loaded terrain"); return false;
        }
        long key = positionKey(x,y,z);
        int[] old = used.get(key);
        if (old != null) {
            if (old[3] == kind) return true;
            accessFailure("staircase",new int[] {x,y,z},"The staircase overlaps its own support/headroom in this footprint"); return false;
        }
        int[] p = new int[] {x,y,z,kind};
        if (!accessPresent(p) && (kind < 0 ? !accessHarvestable(p) : !worldObj.isAirBlock(x,y,z))) {
            accessFailure("staircase",p,"Staircase support or body clearance is obstructed by " + worldObj.getBlock(x,y,z).getUnlocalizedName());
            return false;
        }
        // Air headroom remains reserved after construction as well.
        used.put(key,p); cells.add(p); return true;
    }
    private void addStaircaseProposals(int y) {
        if (rampPerimeter() < 2) {
            accessFailure("staircase",null,"Assigned footprint has no horizontal room for a staircase"); return;
        }
        int bottom = Math.max(plan[8],Math.min(y-2,(int)Math.floor(boundingBox.minY)-1));
        // Continue the retained terrain helix. Additional alignments are considered
        // only before a staircase is installed, never once per target/layer.
        int variants = staircaseInstalled ? 1 : 2*rampPerimeter();
        int end = Math.min(variants,staircaseVariantIndex+16);
        for (; staircaseVariantIndex < end; staircaseVariantIndex++) {
            int variant = staircaseVariantIndex;
            int offset = (rampOffset+variant/2)%rampPerimeter();
            int direction = staircaseInstalled ? rampDirection : variant%2 == 0 ? 1 : -1;
            List<int[]> cells = new java.util.ArrayList<int[]>();
            java.util.Map<Long,int[]> used = new java.util.HashMap<Long,int[]>();
            boolean valid = true;
            for (int sy = bottom; valid && sy <= plan[7]; sy++) {
                int[] column = rampColumn(offset+direction*(plan[7]-sy));
                // Lift clearance includes the lower tread while rising to the next.
                for (int h = sy+1; valid && h <= sy+2; h++)
                    valid = staircaseCell(cells,used,column[0],h,column[1],-1);
                if (!valid) break;
                int[] tread = new int[] {column[0],sy,column[1],0};
                // A short foundation attaches sideways to the previous tread, then
                // the full step attaches above it. No floating/diagonal placements.
                if (!accessPresent(tread) && sy > bottom)
                    valid = staircaseCell(cells,used,column[0],sy-1,column[1],0);
                if (valid) valid = staircaseCell(cells,used,column[0],sy,column[1],0);
                // The builder can now stand on this tread to reach its lift
                // clearance, before attempting the next one-block rise.
                if (valid && sy < plan[7]) valid = staircaseCell(cells,used,column[0],sy+3,column[1],-1);
            }
            if (valid && !cells.isEmpty()) {
                accessProposals.add(cells); staircaseLayouts.put(cells,new int[] {offset,direction,bottom});
            }
        }
    }
    private boolean moreStaircases() {
        return rampPerimeter() >= 2 && staircaseVariantIndex < (staircaseInstalled ? 1 : 2*rampPerimeter());
    }
    private boolean staircaseConnected(List<int[]> cells) {
        int[] layout = staircaseLayouts.get(cells);
        if (layout == null) return false;
        int offset = layout[0], direction = layout[1], bottom = layout[2];
        int[] previous = null, entry = null;
        for (int y = bottom; y <= plan[7]; y++) {
            int[] c = rampColumn(offset+direction*(plan[7]-y)), node = new int[] {c[0],y+1,c[1]};
            if (Double.isNaN(nodeY(node)) || Math.abs(nodeY(node)-(y+1)) > .001
                    || previous != null && (!safeEdge(previous,node,-30000001,-1,0) || !safeEdge(node,previous,-30000001,-1,0))) {
                accessFailure("staircase",node,"Staircase has no body-clear reversible one-block step"); return false;
            }
            if (entry == null) entry = node;
            previous = node;
        }
        if (entry == null || searchRoute(java.util.Collections.singletonList(entry),-30000001,-1,0,false,null,true) == null) {
            accessFailure("staircase",entry,"No reversible connection from the current exposed floor to the first tread"); return false;
        }
        if (stationRoute(previous) == null) {
            accessFailure("staircase",previous,"The upper staircase landing has no usable route to storage"); return false;
        }
        return true;
    }
    /** Select supplied access after existing walking routes fail. All edits stay
     * inside the owned assignment; ladders use the selection's committed column. */
    private boolean planAccess(int x,int y,int z) {
        if (buildingAccess || accessSearchExhausted) return false;
        status = "planning_access";
        if (accessProposals == null || !Arrays.equals(accessSearchTarget,new int[] {x,y,z})) {
            accessSearchTarget = new int[] {x,y,z};
            accessStage = "locations";
            if (!recoverLadderPosition()) return true;
            accessAttempts++;
            diagnostic("access","begin supplied-access search; discovered ladder columns=" + discoveredLadders.size());
            retainLadderLandings(accessBlocks);
            checkAccessStation();
            // Repair retained access first, clearing authorized terrain but never
            // liquids, containers, infrastructure or unbreakable blocks.
            if (startAccessRepair(new int[] {x,y,z})) { reconsideringLadder = false; return true; }
            if (accessBlocks.size()+32 > 4096) {
                navigationReason = "access_limit";
                accessFailure("cells",null,"Retained access would exceed 4096 support/clearance cells"); blockObjective("no_access"); return true;
            }
            // This is a search window, not a required first rung. The target layer
            // may still be solid floor; only exposed, usable bands become proposals.
            // Later excavation extends these bands down into newly exposed air.
            accessBottom = Math.max(plan[8],Math.min(y-1,(int)Math.floor(boundingBox.minY)));
            accessTop = ladderPosition == null ? Math.min(plan[7],accessBottom+15) : plan[7];
            inspectFixedLadder();
            if (accessBottom > accessTop) {
                accessSearchExhausted = true; navigationReason = "access_blocked";
                accessSearchTarget = new int[] {x,y,z};
                accessFailure("floor",accessSearchTarget,"No exposed ladder window inside installed vertical bounds");
                blockObjective("no_access"); return true;
            }
            occupiedAccessCells = 0; occupiedAccessCell = null;
            accessProposals = new java.util.ArrayList<List<int[]>>();
            accessProposalIndex = accessColumnIndex = 0; accessLayouts.clear();
            accessSearchTarget = new int[] {x,y,z}; clearRouteSearch();
            getNavigator().clearPathEntity(); preciseGoal = null; travelRoute.clear(); waypoint = null;
            // One actual reversible floor search supplies the entry component for
            // every proposal. Hypothetical rungs never make a floating band reachable.
            List<int[]> exits = standingCandidates(plan[0],plan[1],plan[2],false);
            searchRoute(exits,-30000001,-1,0,false,null,true);
            accessFloor = failedSearchPositions.isEmpty() ? null : new java.util.HashSet<Long>(failedSearchPositions);
        }
        int existingColumns = discoveredLadders.size();
        int columns = ladderPosition != null ? 1 : existingColumns
                + (availableAccess(true) == 0 || reconsideringLadder ? 0 : (plan[5]-plan[3]+1)*(plan[6]-plan[4]+1));
        if (accessColumnIndex < columns) {
            accessStage = "locations";
            // Scan the entire assigned area, in bounded slices. A useful wall may
            // be on the far rim rather than beside the current mining target.
            int end = Math.min(columns,accessColumnIndex+16), width = plan[5]-plan[3]+1;
            for (; accessColumnIndex < end; accessColumnIndex++) for (int face = 2; face <= 5; face++) {
                int[] column = ladderPosition != null ? ladderPosition : accessColumnIndex < existingColumns ? discoveredLadders.get(accessColumnIndex) : null;
                if (column != null && face != column[2]) continue;
                net.minecraftforge.common.util.ForgeDirection d = net.minecraftforge.common.util.ForgeDirection.getOrientation(face);
                int lx = column == null ? plan[3]+(accessColumnIndex-existingColumns)%width : column[0];
                int lz = column == null ? plan[4]+(accessColumnIndex-existingColumns)/width : column[1];
                int bx = lx-d.offsetX, bz = lz-d.offsetZ;
                List<int[]> band = new java.util.ArrayList<int[]>();
                int top = column == null ? accessTop : plan[7];
                for (int ly = accessBottom; ly <= top+1; ly++) {
                    boolean valid = ly <= top;
                    if (valid) {
                        boolean backing = solidAttachment(bx,ly,bz,d,-30000001,-1,0);
                        if (!accessArea(lx,ly,lz) || !backing && !accessArea(bx,ly,bz) || lx == x && ly == y-1 && lz == z
                                || bx == x && ly == y-1 && bz == z) valid = false;
                        else if (worldObj.getTileEntity(bx,ly,bz) != null || worldObj.getTileEntity(lx,ly,lz) != null
                                || BuilderPlacement.protectedBlock(worldObj,bx,ly,bz) || BuilderPlacement.protectedBlock(worldObj,lx,ly,lz)) valid = false;
                        else if (!backing && !worldObj.isAirBlock(bx,ly,bz)
                                && (column == null || !accessHarvestable(new int[] {bx,ly,bz,-1},true))) {
                            valid = false;
                            if (column != null) ladderObstruction = "Ladder attachment face=" + face + " unavailable at " + bx+", "+ly+", "+bz;
                        }
                        if (worldObj.blockExists(lx,ly,lz) && !worldObj.isAirBlock(lx,ly,lz) && !rungPresent(new int[] {lx,ly,lz,face})) {
                            if (column == null || !accessHarvestable(new int[] {lx,ly,lz,-1},true)) valid = false;
                            occupiedAccessCells++;
                            if (occupiedAccessCell == null) occupiedAccessCell = new int[] {lx,ly,lz};
                            if (ladderPosition != null) ladderObstruction = "Fixed ladder obstructed at " + lx+", "+ly+", "+lz
                                    + " by " + worldObj.getBlock(lx,ly,lz).getUnlocalizedName();
                        }
                    }
                    if (valid) {
                        if (!worldObj.isAirBlock(lx,ly,lz) && !rungPresent(new int[] {lx,ly,lz,face})) band.add(new int[] {lx,ly,lz,-1});
                        if (accessArea(bx,ly,bz)) {
                            if (!solidAttachment(bx,ly,bz,d,-30000001,-1,0) && !worldObj.isAirBlock(bx,ly,bz)) band.add(new int[] {bx,ly,bz,-1});
                            band.add(new int[] {bx,ly,bz,0});
                        }
                        band.add(new int[] {lx,ly,lz,face});
                    }
                    else if (!band.isEmpty()) {
                        // Keep backing before its rung in both construction directions.
                        if (boundingBox.minY > (band.get(0)[1]+band.get(band.size()-1)[1])/2.0) {
                            List<int[]> descending = new java.util.ArrayList<int[]>();
                            for (int ly2 = band.get(band.size()-1)[1]; ly2 >= band.get(0)[1]; ly2--)
                                for (int[] p : band) if (p[1] == ly2) descending.add(p);
                            addAccessProposal(descending);
                        } else addAccessProposal(band);
                        band = new java.util.ArrayList<int[]>();
                    }
                }
            }
            if (accessColumnIndex < columns) return true;
            // Existing columns precede new ladders. Staircases are generated only
            // after every eligible ladder proposal has actually been evaluated.
            java.util.Collections.sort(accessProposals,(a,b) -> {
                int priority = Integer.compare(ladderPriority(a),ladderPriority(b));
                return priority != 0 ? priority : Double.compare(accessEstimate(a),accessEstimate(b));
            });
            accessStage = "cells";
            navigationDetail = "Distinct exposed ladder proposals=" + accessProposals.size() + "; occupied ladder cells=" + occupiedAccessCells
                    + " first=" + Arrays.toString(occupiedAccessCell) + "; target=" + Arrays.toString(accessSearchTarget);
        }
        // Retain the ranked proposals and any unfinished endpoint checks. One proposal
        // per update bounds work without losing a valid fifth (or later) alternative.
        if (!repairAfterRestock && !reconsideringLadder && accessProposalIndex >= accessProposals.size() && moreStaircases()) {
            int first = accessProposals.size();
            if (staircaseVariantIndex == 0) {
                navigationDetail = "Ladder routes evaluated before staircase fallback; usable carried ladders=" + accessCount(true)
                        + "; usable carried/reachable-station ladders=" + availableAccess(true) + "; ladder connection=" + ladderObstruction;
                diagnostic("access","ladder alternatives exhausted");
            }
            addStaircaseProposals(y);
            java.util.Collections.sort(accessProposals.subList(first,accessProposals.size()),(a,b) -> Double.compare(accessEstimate(a),accessEstimate(b)));
            if (accessProposalIndex >= accessProposals.size() && moreStaircases()) return true;
        }
        if (accessProposalIndex < accessProposals.size()) {
            List<int[]> proposal = accessProposals.get(accessProposalIndex);
            boolean stairs = !hasRungs(proposal);
            boolean pending = false;
            try {
                List<int[]> landings = stairs ? java.util.Collections.<int[]>emptyList() : accessLandings(proposal);
                if (!proposeAccess(proposal)) {
                    if (!stairs) ladderObstruction = accessBlocker;
                    return true;
                }
                if (!stairs && !connectedAccess(proposal,landings)) return true;
                java.util.Map<Long,int[]> completed = new java.util.HashMap<Long,int[]>(proposedAccess);
                proposedAccess.clear();
                boolean existing = true;
                for (int[] p : proposal) if (!accessPresent(p)) { existing = false; break; }
                proposedAccess.putAll(completed);
                accessStage = "work_approach";
                List<int[]> approach = null;
                if (stairs) {
                    // From the actual exposed floor, the completed steps must join a
                    // usable upper landing and the station route. A pillar alone fails.
                    if (!staircaseConnected(proposal)) return true;
                }
                boolean retainedTarget = false;
                if (stairs) for (int[] p : proposal) if (p[3] >= 0 && p[0] == x && p[1] == y-1 && p[2] == z) retainedTarget = true;
                if (!retainedTarget) {
                    List<int[]> goals = standingCandidates(x,y,z,true);
                    if (goals.isEmpty()) {
                        navigationReason = "standing"; accessFailure("work_position",null,standingObstruction(x,y,z)); return true;
                    }
                    approach = executableRoute(goals,x,y,z,true);
                    if (approach == null) {
                        pending = routeSearchPending;
                        accessFailure(accessStage,null,(pending ? "Endpoint checks remaining=" + routeSearchRemaining.size() + "; " : "") + navigationDetail);
                        if (!stairs && !pending) ladderObstruction = "Ladder " + Arrays.toString(proposal.get(0))
                                + " cannot serve work=" + x+", "+y+", "+z + ": " + navigationDetail;
                        return true;
                    }
                }
                if (existing) {
                    if (!stairs) adoptLadder(proposal);
                    if (selectingTarget) {
                        int width = plan[5]-plan[3]+1, layer = width*(plan[6]-plan[4]+1);
                        targetCursor = (plan[7]-y+1)*layer+(z-plan[4])*width+x-plan[3];
                        selectingTarget = false; searchOffset = 0;
                    }
                    if (stairs) {
                        installStaircase(proposal);
                        for (int[] p : proposal) retainAccess(p);
                        accessPlan.clear(); accessIndex = 0;
                        clearAccessSearch(); resetPath(); returnToWork("existing staircase verified");
                        if (resumeStorageAfterAccess) {
                            resumeStorageAfterAccess = false; beginStorage(accessStorageReason); accessStorageReason = StorageReason.NONE;
                        } else if (approach != null) useRoute(approach);
                        return true;
                    }
                    accessPlan.clear(); accessIndex = 0; buildingAccess = false; reconsideringLadder = false;
                    repairAfterRestock = false;
                    staircaseAccess = repairingLadder = false; constructionRefuge.clear(); failedAccessOperations.clear();
                    clearAccessSearch(); resetPath();
                    if (resumeStorageAfterAccess) {
                        resumeStorageAfterAccess = false; beginStorage(accessStorageReason); accessStorageReason = StorageReason.NONE;
                    } else {
                        transition(Task.TO_WORK,"existing ladder verified",new int[] {x,y,z});
                        useRoute(approach); status = "using_ladder";
                    }
                    return true;
                }
                // Check the preferred first builder approach on actual terrain;
                // execution retains the objective and can choose another edit.
                proposedAccess.clear();
                int[] firstMissing = null;
                for (int[] p : proposal) if (!accessPresent(p)) { firstMissing = p; break; }
                if (firstMissing == null) {
                    navigationReason = "access_blocked"; accessFailure("cells",null,"No missing air cell in the proposed access order"); return true;
                }
                final int[] firstCell = firstMissing;
                List<int[]> builders = standingCandidates(firstCell[0],firstCell[1]+(firstCell[3] < 0 ? 1 : 0),firstCell[2],firstCell[3] < 0);
                if (firstCell[3] >= 0) builders.removeIf(p -> accessShape(firstCell).intersectsWith(body(p[0]+.5,nodeY(p),p[2]+.5)));
                accessStage = "first_placement";
                if (searchRoute(builders,-30000001,-1,0,false,null,true) == null) {
                    // Retain the complete objective: execution can choose a later
                    // clearance/support first instead of rejecting this layout.
                    accessFailure("first_placement",firstCell,navigationDetail);
                }
                accessPlan.clear(); accessPlan.addAll(proposal); accessIndex = 0; buildingAccess = true;
                staircaseAccess = stairs;
                repairingLadder = !stairs && (ladderPosition != null || existingLadder(proposal));
                if (stairs) { installStaircase(proposal); staircaseLadderSupplies = ladderSupplyStamp(); }
                else adoptLadder(proposal);
                if (!stairs) reconcileAccessPlan();
                reconsideringLadder = false; failedAccessOperations.clear(); constructionRefuge.clear();
                accessDestination = new int[] {x,y,z};
                if (selectingTarget) {
                    int width = plan[5]-plan[3]+1, layer = width*(plan[6]-plan[4]+1);
                    targetCursor = (plan[7]-y+1)*layer+(z-plan[4])*width+x-plan[3];
                    selectingTarget = false; searchOffset = 0;
                }
                checkAccessStation();
                navigationReason = navigationDetail = arrivalFailure = accessBlocker = "";
                diagnostic("access","retained supplied-access proposal " + accessProposalIndex);
                resetPath(); status = accessActivity(); return true;
            } finally {
                proposedAccess.clear(); returnRoute.clear();
                if (!pending && accessProposals != null) { accessProposalIndex++; clearRouteSearch(); }
                if (buildingAccess) clearAccessSearch();
            }
        }
        if (reconsideringLadder) {
            // A delivery only replaces the retained staircase after a complete
            // ladder proof. Failed alternatives never discard its pending edits.
            reconsideringLadder = false; buildingAccess = true;
            clearAccessSearch(); clearRouteSearch(); resetPath(); navigationReason = navigationDetail = arrivalFailure = "";
            status = accessActivity(); return true;
        }
        if (accessFailure.isEmpty()) accessFailure = "No exposed band with an existing upper landing inside installed bounds";
        navigationReason = "access_blocked";
        if (!ladderObstruction.isEmpty()) accessBlocker = ladderObstruction + "; " + accessBlocker;
        navigationDetail = "Exhausted " + accessProposals.size() + " access proposals for " + Arrays.toString(accessSearchTarget)
                + "; occupied ladder cells=" + occupiedAccessCells + " first=" + Arrays.toString(occupiedAccessCell)
                + "; last failure=" + accessFailure;
        accessStage = "exhausted";
        diagnostic("access","exhausted supplied-access proposals");
        accessSearchExhausted = true; accessProposals = null;
        // An alternate staircase was only a proposal. Keep the previous selected
        // ladder order/index if no replacement could actually be selected.
        if (!buildingAccess && !accessPlan.isEmpty()) {
            buildingAccess = true;
            int blocks = Math.max(0,accessNeeded(false)-availableAccess(false));
            int rungs = Math.max(0,accessNeeded(true)-availableAccess(true));
            if (ladderObstruction.isEmpty() && (blocks > 0 || rungs > 0)) {
                navigationReason = "access_supplies";
                accessFailure("supplies",null,"No usable staircase; selected ladder needs " + blocks + " support blocks and " + rungs + " ladders");
            }
        }
        blockObjective("no_access"); return true;
    }
    private void addAccessProposal(List<int[]> cells) {
        // Placement direction and already-present backing do not define a new
        // layout. Do not repeat the same route proofs for equivalent proposals.
        List<String> missing = new java.util.ArrayList<String>();
        for (int[] p : cells) if (!accessPresent(p)) missing.add(Arrays.toString(p));
        int rungs = 0, supports = 0;
        for (int[] p : cells) if (!accessPresent(p)) { if (p[3] > 0) { if (!rungPresent(p)) rungs++; } else if (p[3] == 0) supports++; }
        // Material eligibility precedes route proofs, so a ladder shortage cannot
        // starve the block staircase behind hundreds of impossible layouts.
        if (rungs > availableAccess(true) || supports > availableAccess(false,existingLadder(cells) || ladderPosition != null)) {
            if (existingLadder(cells) || ladderPosition != null)
                ladderObstruction = "Ladder at " + Arrays.toString(cells.get(0)) + " needs " + rungs + " rungs and " + supports
                        + " backing blocks; usable supplies=" + availableAccess(true) + " rungs, " + availableAccess(false) + " blocks";
            return;
        }
        int bottom = Integer.MAX_VALUE;
        for (int[] p : cells) if (p[3] != 0) bottom = Math.min(bottom,p[1]);
        boolean upperLanding = false;
        for (int[] p : accessLandings(cells)) if (nodeY(p) > bottom+.01) { upperLanding = true; break; }
        if (!upperLanding) {
            accessFailure("connections",cells.get(0),"Ladder band has no existing upper landing above Y=" + bottom);
            if (existingLadder(cells) || ladderPosition != null) ladderObstruction = accessBlocker;
            return;
        }
        java.util.Collections.sort(missing);
        String layout = missing.isEmpty() ? "existing:" + Arrays.toString(cells.get(0)) : missing.toString();
        if (accessLayouts.add(layout)) accessProposals.add(cells);
    }
    /** Actual landings only, collected before installing the hypothetical overlay. */
    private List<int[]> accessLandings(List<int[]> cells) {
        java.util.Map<Long,int[]> landings = new java.util.HashMap<Long,int[]>();
        java.util.Set<Long> checked = new java.util.HashSet<Long>();
        for (int[] rung : cells) if (rung[3] > 0) {
            for (int side = 0; side < 5; side++) for (int dy = -1; dy <= 1; dy++) {
                int x = rung[0]+(side == 0 ? 1 : side == 1 ? -1 : 0);
                int z = rung[2]+(side == 2 ? 1 : side == 3 ? -1 : 0), y = rung[1]+dy;
                long key = positionKey(x,y,z);
                if (checked.add(key) && standing(x,y,z,-30000001,-1,0)) landings.put(key,new int[] {x,y,z});
            }
        }
        return new java.util.ArrayList<int[]>(landings.values());
    }
    /** Reject floating bands and pillars without a real upper landing before A*. */
    private boolean connectedAccess(List<int[]> cells,List<int[]> landings) {
        boolean entry = ladderFloorRoute(cells), exit = false;
        int bottom = Integer.MAX_VALUE;
        for (int[] p : cells) if (p[3] > 0) bottom = Math.min(bottom,p[1]);
        String storageFailure = "";
        for (int[] p : landings) for (int[] rung : cells) if (rung[3] > 0
                && Math.abs(p[0]-rung[0])+Math.abs(p[2]-rung[2]) <= 1 && Math.abs(p[1]-rung[1]) <= 1) {
            // The rung itself may already stand on the exposed floor. Entering
            // that cell horizontally needs no rung inside the solid block below.
            boolean same = p[0] == rung[0] && p[1] == rung[1] && p[2] == rung[2];
            if (!entry && p[1] <= bottom+1 && (same || safeEdge(p,rung,-30000001,-1,0) && safeEdge(rung,p,-30000001,-1,0))
                    && actualFloorConnection(p)) entry = true;
            if (!exit && nodeY(p) > bottom+.01 && (same || safeEdge(rung,p,-30000001,-1,0))) {
                exit = stationRoute(p) != null;
                if (!exit) storageFailure = "Upper ladder landing " + Arrays.toString(p) + " has no storage connection: " + navigationDetail;
            }
            if (entry && exit) return true;
        }
        if (!entry && connectLadderFloor(cells) && ladderFloorRoute(cells)) entry = true;
        if (entry && exit) return true;
        navigationReason = "access_blocked";
        accessFailure("connections",cells.get(0),!entry ? "Ladder bottom at Y=" + bottom + " has no reversible connection to the current exposed floor"
                : !storageFailure.isEmpty() ? storageFailure : "Ladder band has no usable existing upper landing; its own backing alone is not an exit");
        ladderObstruction = accessBlocker;
        return false;
    }
    private boolean ladderFloorRoute(List<int[]> cells) {
        int bottom = Integer.MAX_VALUE;
        for (int[] p : cells) if (p[3] > 0) bottom = Math.min(bottom,p[1]);
        List<int[]> rungs = new java.util.ArrayList<int[]>();
        for (int[] p : cells) if (p[3] > 0 && p[1] <= bottom+1) rungs.add(Arrays.copyOf(p,3));
        if (rungs.isEmpty() || searchRoute(rungs,-30000001,-1,0,false,null,true) == null) return false;
        if (accessSearchTarget == null) return true;
        List<int[]> work = standingCandidates(accessSearchTarget[0],accessSearchTarget[1],accessSearchTarget[2],true);
        for (int[] rung : rungs) if (searchRoute(work,-30000001,-1,0,false,rung,true) != null) return true;
        return false;
    }
    /** A missing bottom landing can need blocks even after all rungs arrive.
     * Try short, supplied connecting landings at the exposed work floor; retain only
     * a proposal that physically joins that floor, the fixed column and storage. */
    private boolean connectLadderFloor(List<int[]> cells) {
        if (ladderPosition == null) return false;
        int floor = accessSearchTarget == null ? (int)Math.floor(boundingBox.minY) : accessSearchTarget[1];
        for (int span = 1; span <= 5; span++) for (int dx = -span; dx <= span; dx++) for (int sign : new int[] {-1,1}) {
            int dz = sign*(span-Math.abs(dx));
            if (sign == 1 && dz == 0) continue;
            int ax = ladderPosition[0]+dx, az = ladderPosition[1]+dz;
            // An actual supported anchor, not the column's hypothetical backing.
            java.util.Map<Long,int[]> saved = new java.util.HashMap<Long,int[]>(proposedAccess);
            proposedAccess.clear();
            boolean anchor;
            try { anchor = standing(ax,floor,az,-30000001,-1,0); }
            finally { proposedAccess.putAll(saved); }
            if (!anchor) continue;
            for (int order = 0; order < 2; order++) {
                List<int[]> added = new java.util.ArrayList<int[]>();
                boolean accepted = false;
                try {
                    int x = ladderPosition[0], z = ladderPosition[1]; boolean valid = true, edits = false;
                    // Include entry footing and body/head clearance, even when the
                    // anchor is directly beside the ladder (span == 1).
                    for (int distance = 0; valid && distance <= span; distance++) {
                        for (int h = -1; valid && h <= 1; h++) {
                            int[] p = {x,floor+h,z,h < 0 ? 0 : -1};
                            int[] old = proposedAccess.get(positionKey(x,floor+h,z));
                            // Existing/planned rungs provide ladder body clearance
                            // and footing. Keep their column and attachment face.
                            if (old != null && old[3] > 0) continue;
                            if (old == null && worldObj.blockExists(x,floor+h,z)
                                    && worldObj.getBlock(x,floor+h,z) instanceof net.minecraft.block.BlockLadder
                                    && ladder(x,floor+h,z,-30000001,-1,0)) continue;
                            if (old != null && old[3] != p[3]) { valid = false; break; }
                            if (old != null) continue;
                            if (!accessArea(x,floor+h,z)) {
                                valid = p[3] < 0 && worldObj.blockExists(x,floor+h,z) && worldObj.isAirBlock(x,floor+h,z);
                                continue;
                            }
                            if (BuilderPlacement.protectedBlock(worldObj,x,floor+h,z) || worldObj.getTileEntity(x,floor+h,z) != null
                                    || !accessPresent(p) && (p[3] < 0 ? !accessHarvestable(p,true) : !worldObj.isAirBlock(x,floor+h,z))) {
                                valid = false; break;
                            }
                            if (!accessPresent(p)) edits = true;
                            proposedAccess.put(positionKey(x,floor+h,z),p); added.add(p);
                        }
                        if (distance == span) break;
                        if (order == 0 && x != ax || order == 1 && z == az) x += Integer.signum(ax-x);
                        else z += Integer.signum(az-z);
                    }
                    if (valid && edits && ladderFloorRoute(cells)) {
                        cells.addAll(added); accepted = true; return true;
                    }
                } finally { if (!accepted) { proposedAccess.clear(); proposedAccess.putAll(saved); } }
            }
        }
        return false;
    }
    private boolean actualFloorConnection(int[] p) {
        if (accessFloor != null) return accessFloor.contains(positionKey(p[0],p[1],p[2]));
        java.util.Map<Long,int[]> overlay = new java.util.HashMap<Long,int[]>(proposedAccess);
        proposedAccess.clear();
        try { return searchRoute(java.util.Collections.singletonList(p),-30000001,-1,0,false,null,true) != null; }
        finally { proposedAccess.putAll(overlay); }
    }
    private double accessEstimate(List<int[]> proposal) {
        double cost = 0;
        for (int[] p : proposal) if (!accessPresent(p)) cost += p[3] == 0 ? 40 : p[3] < 0 ? 50 : 30;
        int[] first = proposal.get(0);
        return cost+Math.sqrt(getDistanceSq(first[0]+.5,first[1],first[2]+.5))*10;
    }
    private int ladderPriority(List<int[]> cells) {
        boolean complete = true;
        for (int[] p : cells) if (!accessPresent(p)) { complete = false; break; }
        return complete ? 0 : existingLadder(cells) || ladderPosition != null ? 1 : 2;
    }
    private boolean startAccessRepair(int[] goal) {
        recoverRecordedLadder();
        if (ladderPosition == null) return false;
        retainLadderBacking(accessBlocks);
        boolean missing = false;
        for (int[] p : accessBlocks) if (!accessPresent(p)) { missing = true; break; }
        if (!missing) return false;
        // A broken exit cannot be a prerequisite for repairing that exit. Each
        // edit is instead checked from reachable actual builder positions.
        accessPlan.clear();
        for (int[] p : accessBlocks) accessPlan.add(p.clone());
        int[] previousTarget = accessSearchTarget;
        accessSearchTarget = goal;
        try {
            if (proposeAccess(accessPlan) && !ladderFloorRoute(accessPlan)) connectLadderFloor(accessPlan);
        } finally { proposedAccess.clear(); accessSearchTarget = previousTarget; }
        reconcileAccessPlan(); buildingAccess = true;
        staircaseAccess = false; repairingLadder = true;
        failedAccessOperations.clear(); constructionRefuge.clear();
        accessDestination = goal.clone();
        if (selectingTarget) {
            int width = plan[5]-plan[3]+1, layer = width*(plan[6]-plan[4]+1);
            targetCursor = (plan[7]-goal[1]+1)*layer+(goal[2]-plan[4])*width+goal[0]-plan[3];
            selectingTarget = false; searchOffset = 0;
        }
        checkAccessStation();
        navigationReason = navigationDetail = arrivalFailure = "";
        resetPath(); status = accessActivity(); return true;
    }
    /** Reconcile requirements, not just BreakEvents: backing loss can detach a
     * rung without firing its own break event. Never restore ordinary terrain. */
    private void reconcileAccessPlan() {
        java.util.Map<Long,int[]> required = new java.util.LinkedHashMap<Long,int[]>();
        for (int[] p : accessBlocks) required.put(positionKey(p[0],p[1],p[2]),p.clone());
        for (int[] p : accessPlan) required.put(positionKey(p[0],p[1],p[2]),p.clone());
        if (ladderPosition != null) {
            net.minecraftforge.common.util.ForgeDirection d = net.minecraftforge.common.util.ForgeDirection.getOrientation(ladderPosition[2]);
            for (int[] p : new java.util.ArrayList<int[]>(required.values())) if (p[3] > 0 && fixedRung(p)) {
                int[] backing = {p[0]-d.offsetX,p[1],p[2]-d.offsetZ,0};
                if (accessArea(backing[0],backing[1],backing[2])) required.put(positionKey(backing[0],backing[1],backing[2]),backing);
            }
        }
        List<int[]> clear = new java.util.ArrayList<int[]>(), supports = new java.util.ArrayList<int[]>(), rungs = new java.util.ArrayList<int[]>();
        for (int[] p : required.values()) {
            if (p[3] < 0) clear.add(p);
            else {
                if (!accessPresent(p) && !rungPresent(p) && worldObj.blockExists(p[0],p[1],p[2]) && !worldObj.isAirBlock(p[0],p[1],p[2]))
                    clear.add(new int[] {p[0],p[1],p[2],-1});
                (p[3] == 0 ? supports : rungs).add(p);
            }
        }
        accessPlan.clear(); accessPlan.addAll(clear); accessPlan.addAll(supports); accessPlan.addAll(rungs); accessIndex = 0;
    }
    private static boolean accessMaterial(ItemStack stack,boolean rung) {
        if (stack == null || stack.stackSize <= 0 || !(stack.getItem() instanceof net.minecraft.item.ItemBlock) || stack.hasTagCompound()) return false;
        Block b = ((net.minecraft.item.ItemBlock)stack.getItem()).field_150939_a;
        return rung ? b == net.minecraft.init.Blocks.ladder : b.isNormalCube() && !(b instanceof net.minecraft.block.BlockFalling)
                && !b.hasTileEntity(stack.getItemDamage()) && b != net.minecraft.init.Blocks.bedrock && b != net.minecraft.init.Blocks.command_block
                && (b.getMaterial() == net.minecraft.block.material.Material.rock || b.getMaterial() == net.minecraft.block.material.Material.ground);
    }
    private int accessCount(boolean rung) {
        int count = 0; for (ItemStack stack : cargo) if (accessMaterial(stack,rung)) count += stack.stackSize; return count;
    }
    private long ladderSupplyStamp() {
        long stamp = 1;
        IInventory station = stationAssigned ? stationInventory(plan[0],plan[1],plan[2]) : null;
        for (int i = 0; i < cargo.length+(station == null ? 0 : station.getSizeInventory()); i++) {
            ItemStack stack = i < cargo.length ? cargo[i] : station.getStackInSlot(i-cargo.length);
            if (accessMaterial(stack,true)) stamp = stamp*31+i+961L*stack.stackSize;
        }
        return stamp;
    }
    private void reconsiderStaircase() {
        if (task == Task.ESCAPE || restockLadders > 0) return;
        if (!staircaseAccess || storageTask() || miningProgress > 0 || !onGround && !isOnLadder()) return;
        long supplies = ladderSupplyStamp();
        if (supplies == staircaseLadderSupplies) return;
        staircaseLadderSupplies = supplies;
        if (availableAccess(true) == 0) return;
        int[] goal = accessDestination == null ? workDestination() : accessDestination;
        if (goal == null) return;
        buildingAccess = false; reconsideringLadder = true;
        clearAccessSearch(); clearRouteSearch(); resetPath();
        accessSearchTarget = goal.clone();
        diagnostic("access","ladder delivery: reconsider retained staircase at an edit boundary");
        planAccess(goal[0],goal[1],goal[2]);
    }
    /** Keep the objective and index. Only reorder pending operations after all
     * builder endpoints for this edit fail, or its prerequisites are unavailable. */
    private void deferAccessOperation(int[] cell) {
        failedAccessOperations.add(cell);
        String failedReason = navigationReason, failedDetail = navigationDetail, failedBlocker = accessBlocker;
        resetPath(); clearRouteSearch(); interactionTarget = selectedEndpoint = null; failedEndpoints.clear();
        for (int i = accessIndex+1; i < accessPlan.size(); i++) if (!accessPresent(accessPlan.get(i))
                && !failedAccessOperations.contains(accessPlan.get(i)) && clearanceOrderAllows(accessPlan.get(i))) {
            int[] next = accessPlan.remove(i); accessPlan.add(accessIndex,next);
            navigationReason = navigationDetail = arrivalFailure = ""; status = accessActivity();
            diagnostic("access","try another pending access operation after " + Arrays.toString(cell)); return;
        }
        navigationReason = failedReason.isEmpty() ? "construction_exit" : failedReason;
        navigationDetail = failedDetail + "; all remaining access operations exhausted their reachable builder positions or prerequisites";
        accessBlocker = failedBlocker;
        terminalNavigation();
    }
    private boolean clearanceOrderAllows(int[] cell) {
        if (staircaseAccess || task == Task.ESCAPE || cell[3] < 0) return true;
        for (int[] p : accessPlan) if (p[3] < 0 && !accessPresent(p)) return false;
        return true;
    }
    private boolean plannedClearance(int[] cell) {
        for (int[] p : accessPlan) if (p[3] < 0 && p[0] == cell[0] && p[1] == cell[1] && p[2] == cell[2]) return true;
        return false;
    }
    private int accessNeeded(boolean rung) {
        int count = 0;
        for (int i = accessIndex; i < accessPlan.size(); i++) if (accessPlan.get(i)[3] >= 0 && (accessPlan.get(i)[3] > 0) == rung
                && !accessPresent(accessPlan.get(i)) && (!rung || !rungPresent(accessPlan.get(i)))) count++;
        return count;
    }
    private int emergencyBlocks() {
        if (!configured) return 0;
        int[] goal = workDestination();
        int floor = (int)Math.floor(boundingBox.minY);
        if (goal != null) floor = Math.min(floor,goal[1]);
        return unfinished() ? Math.max(4,plan[7]+3-floor) : 0;
    }
    private boolean travelSuppliesReady(List<int[]> route,boolean recovery) {
        if (recovery || storageTask() || task == Task.ESCAPE || buildingAccess) return true;
        if (ladderPosition != null && accessCount(true) < ladderRequirement()) { beginEscape(); return false; }
        if (!route.isEmpty()) {
            int[] next = route.get(enteringRoute ? 0 : Math.min(route.size()-1,routeIndex+1));
            if (nodeY(next) < boundingBox.minY-.1 && accessCount(false) < emergencyBlocks()) {
                beginStorage(StorageReason.ACCESS); return false;
            }
        }
        return true;
    }
    private int supportRequirement() { return accessNeeded(false)+(task == Task.ESCAPE || buildingAccess && repairingLadder ? 0 : emergencyBlocks()); }
    private int ladderRequirement() {
        if (ladderPosition == null) return 0;
        int need = 0;
        int[] goal = workDestination();
        int floor = (int)Math.floor(boundingBox.minY);
        if (goal != null) floor = Math.min(floor,goal[1]);
        for (int y = Math.max(plan[8],floor-1); y <= plan[7]; y++) {
            int[] rung = {ladderPosition[0],y,ladderPosition[1],ladderPosition[2]};
            watchTerrain(rung[0],y,rung[2]);
            if (worldObj.blockExists(rung[0],y,rung[2]) && !rungPresent(rung) && worldObj.isAirBlock(rung[0],y,rung[2])) need++;
        }
        // Include the next exposed rung and a spare before accepting a descent.
        return Math.max(accessNeeded(true),need+2);
    }
    private void beginEscape() {
        restockLadders = Math.max(restockLadders,Math.max(ladderRequirement(),accessNeeded(true)));
        storageReason = StorageReason.LADDERS;
        escapeOrigin = new int[] {(int)Math.floor(posX),(int)Math.floor(boundingBox.minY+.001),(int)Math.floor(posZ)};
        escapeColumn = 0; escapePillar = escapeDestination = null; escapeJumping = false;
        // Retain completed terrain and the job target, replacing only unbuilt proposals.
        buildingAccess = reconsideringLadder = staircaseAccess = false; accessPlan.clear(); accessIndex = 0;
        resumeStorageAfterAccess = false; failedAccessOperations.clear(); constructionRefuge.clear();
        clearAccessSearch(); clearRouteSearch(); resetPath(); navigationBlocked = false;
        transition(Task.ESCAPE,"ladders insufficient before further excavation",new int[] {plan[0],plan[1],plan[2]});
    }
    private void escapeFailure(String stage,int[] cell,String detail) {
        navigationReason = "access_blocked"; accessFailure(stage,cell,detail);
        blockObjective("no_access");
    }
    /** A one-way escape starts on the reachable current floor, never at a helix alignment.
     * Final tower overlays prove a real upper landing/station connection, not a return
     * to the original floor. Actual edits still use native placement and harvesting. */
    private boolean planEscape() {
        if (escapeOrigin == null) escapeOrigin = new int[] {(int)Math.floor(posX),(int)Math.floor(boundingBox.minY),(int)Math.floor(posZ)};
        int end = Math.min(81,escapeColumn+3);
        for (; escapeColumn < end; escapeColumn++) {
            int dx = escapeColumn == 0 ? 0 : (escapeColumn+40)%81%9-4;
            int dz = escapeColumn == 0 ? 0 : (escapeColumn+40)%81/9-4;
            int x = escapeOrigin[0]+dx, z = escapeOrigin[2]+dz, base = -1;
            for (int dy : new int[] {0,-1,-2,1}) {
                int y = escapeOrigin[1]+dy;
                if (accessArea(x,y,z) && standing(x,y,z,-30000001,-1,0)
                        && Math.abs(feet(x,y,z,-30000001,-1,0)-y) < .001 && !reservedAccess(x,y,z)) { base = y; break; }
            }
            if (base < 0) continue;
            List<int[]> approach = searchRoute(java.util.Collections.singletonList(new int[] {x,base,z}),-30000001,-1,0);
            if (approach == null) continue;
            List<int[]> cells = new java.util.ArrayList<int[]>();
            try {
                for (int y = base; y <= Math.min(plan[7],base+15); y++) {
                    int[] support = {x,y,z,0};
                    if (!accessArea(x,y,z) || !worldObj.isAirBlock(x,y,z) && !accessHarvestable(new int[] {x,y,z,-1}) || reservedAccess(x,y,z)
                            || BuilderPlacement.protectedBlock(worldObj,x,y,z) || worldObj.getTileEntity(x,y,z) != null) break;
                    boolean clearance = true;
                    for (int h = y+1; h <= y+3; h++) {
                        watchTerrain(x,h,z);
                        if (!worldObj.blockExists(x,h,z) || !worldObj.isAirBlock(x,h,z)
                                && !accessHarvestable(new int[] {x,h,z,-1})) { clearance = false; break; }
                        if (!worldObj.isAirBlock(x,h,z)) proposedAccess.put(positionKey(x,h,z),new int[] {x,h,z,-1});
                    }
                    if (!clearance) break;
                    cells.add(support); proposedAccess.put(positionKey(x,y,z),support);
                    int[] top = {x,y+1,z};
                    boolean hasLanding = false;
                    for (int ex = -1; ex <= 1 && !hasLanding; ex++) for (int ez = -1; ez <= 1 && !hasLanding; ez++) {
                        if (ex == 0 && ez == 0) continue;
                        for (int ey = -2; ey <= 1; ey++) {
                            int[] nearby = {x+ex,y+1+ey,z+ez};
                            if (nearby[1] > base && standing(nearby[0],nearby[1],nearby[2],-30000001,-1,0)
                                    && safeEdge(top,nearby,-30000001,-1,0)) { hasLanding = true; break; }
                        }
                    }
                    if (!hasLanding) continue;
                    List<int[]> exit = stationRoute(top);
                    if (exit == null) continue;
                    int[] landing = null;
                    for (int[] p : exit) if ((p[0] != x || p[2] != z) && nodeY(p) >= base+1
                            && !proposedAccess.containsKey(positionKey(p[0],p[1]-1,p[2]))) { landing = Arrays.copyOf(p,3); break; }
                    if (landing == null) continue;
                    escapePillar = new int[] {x,base,z,y+1}; escapeDestination = landing;
                    accessPlan.clear(); accessPlan.addAll(cells); accessIndex = 0; buildingAccess = true;
                    accessDestination = landing.clone(); failedAccessOperations.clear();
                    resetPath(); navigationReason = navigationDetail = accessBlocker = "";
                    destination = new int[] {x,base,z};
                    rememberInteraction(x,base,z,false);
                    useRoute(approach); status = "escape"; return true;
                }
            } finally { proposedAccess.clear(); }
        }
        if (escapeColumn >= 81) escapeFailure("escape",escapeOrigin,
                "No clear climb of at most 16 blocks from the reachable current floor to a usable station landing; "
                + "protected/retained terrain and assigned bounds are preserved. " + navigationDetail);
        return false;
    }
    private void escape() {
        status = escapeJumping ? "escape_jump" : "escape";
        if (escapeJumping) return;
        if (onGround || isOnLadder()) {
            List<int[]> exit = stationRoute(null);
            if (exit != null) {
                buildingAccess = false; escapeJumping = false;
                accessPlan.clear(); accessIndex = 0;
                beginStorage(StorageReason.LADDERS); useRoute(exit); return;
            }
            accessSupplyChecked = true; accessStationReachable = false;
        }
        if (escapePillar == null) { planEscape(); return; }
        if (accessIndex >= accessPlan.size()) {
            escapeFailure("escape",escapeDestination,"Constructed climb no longer connects to its station landing; recheck changed terrain"); return;
        }
        int[] p = accessPlan.get(accessIndex);
        watchTerrain(p[0],p[1],p[2]);
        if (p[3] < 0) { clearAccessCell(p); return; }
        if (accessPresent(p)) {
            if (!onGround || boundingBox.minY < p[1]+.99) {
                status = "escape_landing";
                if ((pathTicks += 10) >= 100) escapeFailure("escape",p,"Existing escape support cannot be reached from actual feet=" + boundingBox.minY);
                return;
            }
            retainAccess(p); accessIndex++; constructionRefuge.clear(); usefulProgress("landed on escape support"); return;
        }
        if (accessCount(false) == 0) {
            escapeFailure("supplies",p,"Escape needs " + accessNeeded(false) + " real support blocks; station has not been reached; deliver blocks directly"); return;
        }
        if (!moveNear(p[0],p[1],p[2],false)) {
            if ("route_failed".equals(status)) escapeFailure("escape",p,"Cannot reach escape pillar footing: " + navigationDetail);
            else status = "escape";
            return;
        }
        // Clear only obstructing lift headroom, using the ordinary authorized harvest path.
        for (int h = p[1]+1; h <= p[1]+3; h++) if (!worldObj.isAirBlock(p[0],h,p[2])) {
            int[] clearance = {p[0],h,p[2],-1};
            if (!accessHarvestable(clearance)) { escapeFailure("clearance",clearance,"Escape lift headroom is protected, retained, unbreakable or outside owned assignment"); return; }
            accessPlan.add(accessIndex,clearance); resetPath(); return;
        }
        if (!clearBody(posX,boundingBox.minY+1.2,posZ,-30000001,-1,0)) {
            escapeFailure("clearance",p,"Actual body has insufficient jump clearance for escape placement"); return;
        }
        resetPath(); destination = Arrays.copyOf(p,3); escapeJumpTicks = 0; escapeJumping = true;
        jump(); status = "escape_jump";
    }
    private void pillarPhysics() {
        if (accessIndex >= accessPlan.size()) { escapeJumping = false; return; }
        int[] p = accessPlan.get(accessIndex);
        if (!authorized() || !accessArea(p[0],p[1],p[2]) || p[3] != 0
                || Math.abs(posX-p[0]-.5) > .15 || Math.abs(posZ-p[2]-.5) > .15) {
            escapeJumping = false; escapeFailure("escape",p,"Escape jump displaced or no longer authorized; no placement accepted"); return;
        }
        status = "escape_jump";
        if (!accessPresent(p) && boundingBox.minY >= p[1]+1.001 && !accessShape(p).intersectsWith(boundingBox)) {
            if (!placeAccess(p)) {
                escapeJumping = false; escapeFailure("placement",p,"Escape support rejected by attachment, ownership, body collision or Forge placement event"); return;
            }
            retainAccess(p); returnRoute.clear(); accessSupplyChecked = false;
        }
        if (accessPresent(p) && onGround && Math.abs(boundingBox.minY-p[1]-1) < .06) {
            escapeJumping = false; accessIndex++; constructionRefuge.clear(); usefulProgress("jumped and landed on supplied escape block");
        } else if (++escapeJumpTicks >= 40) {
            escapeJumping = false; escapeFailure("escape",p,"Escape jump did not land on the next support; actual feet=" + boundingBox.minY);
        }
    }
    private void constructAccess() {
        if (!buildingAccess) return;
        if (!accessSupplyChecked) checkAccessStation();
        reconsiderStaircase();
        if (!buildingAccess || reconsideringLadder) return;
        for (int i = 0; i < accessIndex; i++) if (!accessPresent(accessPlan.get(i))) {
            accessIndex = i; failedAccessOperations.clear(); resetPath(); clearRouteSearch(); break;
        }
        boolean obstructed = false;
        if (!staircaseAccess) for (int[] p : accessPlan) if (p[3] >= 0 && !accessPresent(p) && !rungPresent(p) && worldObj.blockExists(p[0],p[1],p[2])
                && !worldObj.isAirBlock(p[0],p[1],p[2]) && !plannedClearance(p)) {
            obstructed = true;
            ladderObstruction = "Fixed ladder access obstructed at " + p[0]+", "+p[1]+", "+p[2]
                    + " by " + worldObj.getBlock(p[0],p[1],p[2]).getUnlocalizedName(); break;
        }
        if (!staircaseAccess && obstructed) reconcileAccessPlan();
        if (!staircaseAccess && ladderPosition == null && !accessSearchExhausted && accessNeeded(true) > availableAccess(true)) {
            // Also migrate an unfinished ladder-only plan from an older save, or
            // react to removed supplies. Keep its real built cells and fixed column.
            int[] goal = accessDestination == null ? workDestination() : accessDestination;
            if (goal == null) goal = new int[] {plan[0],plan[1],plan[2]};
            clearAccessSearch(); accessSearchTarget = goal.clone();
            accessProposals = new java.util.ArrayList<List<int[]>>();
            addStaircaseProposals(goal[1]);
            java.util.Collections.sort(accessProposals,(a,b) -> Double.compare(accessEstimate(a),accessEstimate(b)));
            if (!accessProposals.isEmpty() || moreStaircases()) {
                buildingAccess = false;
                accessColumnIndex = Integer.MAX_VALUE;
                resetPath(); planAccess(goal[0],goal[1],goal[2]); return;
            }
            clearAccessSearch();
            accessSearchExhausted = true; // Retry this fallback only after progress or a relevant change.
        }
        while (accessIndex < accessPlan.size()) {
            int[] p = accessPlan.get(accessIndex);
            watchTerrain(p[0],p[1],p[2]);
            if (!worldObj.blockExists(p[0],p[1],p[2])) { status = "chunk"; return; }
            if (accessPresent(p)) {
                retainAccess(p);
                accessIndex++; continue;
            }
            if (!accessArea(p[0],p[1],p[2]) || (p[3] < 0 ? !accessHarvestable(p,task != Task.ESCAPE) : !worldObj.isAirBlock(p[0],p[1],p[2]) && !rungPresent(p))) {
                navigationReason = "access_blocked"; accessFailure("cells",p,"Access cell occupied/unavailable: " + worldObj.getBlock(p[0],p[1],p[2]).getUnlocalizedName());
                deferAccessOperation(p); return;
            }
            if (p[3] < 0) { clearAccessCell(p); return; }
            if (!staircaseAccess && !clearanceOrderAllows(p)) { deferAccessOperation(p); return; }
            if (accessNeeded(true) > accessCount(true) && restockLadders == 0) { beginEscape(); return; }
            if (accessNeeded(false) > 0 && accessCount(false) < supportRequirement()) {
                if (accessStationReachable) { beginStorage(StorageReason.ACCESS); return; }
                if (repairingLadder) {
                    requestedBlocks = Math.max(0,accessNeeded(false)-accessCount(false));
                    navigationReason = "access_supplies";
                    accessFailure("supplies",p,"Station unreachable; deliver " + requestedBlocks + " support blocks for retained access repair");
                    blockObjective("no_access"); return;
                }
                if (accessCount(false) <= emergencyBlocks()) { beginEscape(); return; }
            }
            if (placementSide(p) < 0) {
                navigationReason = "placement"; accessFailure("attachment",p,"No authorized attachment in the current terrain; try another pending operation");
                deferAccessOperation(p); return;
            }
            int blocks = Math.max(0,accessNeeded(false)-accessCount(false)), ladders = Math.max(0,accessNeeded(true)-accessCount(true));
            if ((blocks > 0 || ladders > 0) && accessCount(p[3] > 0) == 0) {
                requestedBlocks = blocks; requestedLadders = ladders;
                if (!accessSupplyChecked) checkAccessStation();
                if (!accessStationReachable) {
                    navigationReason = "access_supplies";
                    accessFailure("supplies",p,"Station=" + plan[0]+","+plan[1]+","+plan[2]
                            + " unreachable; deliver " + blocks + " support blocks" + (ladders > 0 ? " and " + ladders + " ladders" : "") + " directly");
                    blockObjective("no_access"); return;
                }
                beginStorage(StorageReason.ACCESS); return;
            }
            accessStage = "approach";
            if (!moveNear(p[0],p[1],p[2],false)) {
                if ("route_failed".equals(status)) deferAccessOperation(p);
                else if (!navigationBlocked && !"chunk".equals(status)) status = accessActivity();
                return;
            }
            status = accessActivity(); accessStage = "placement";
            if (!placeAccess(p)) {
                navigationReason = "placement"; accessFailure("placement",p,"Native placement, attachment or a protection event refused the cell");
                // Exhaust actual builder positions before giving up this edit.
                failedEndpoints.add(positionKey((int)Math.floor(posX),(int)Math.floor(boundingBox.minY+.001),(int)Math.floor(posZ)));
                if (selectedEndpoint != null) failedEndpoints.add(positionKey(selectedEndpoint[0],selectedEndpoint[1],selectedEndpoint[2]));
                resetPath(); clearRouteSearch(); selectedEndpoint = null; return;
            }
            retainAccess(p);
            if (p[3] > 0 && ladderPosition == null) {
                ladderPosition = new int[] {p[0],p[2],p[3]};
                retainLadderLandings(accessPlan);
            }
            accessIndex++; returnRoute.clear(); rejectedTargets.clear(); reachedPositions.clear();
            failedAccessOperations.clear(); clearRouteSearch();
            accessSupplyChecked = false;
            usefulProgress("placed supplied access cell"); return;
        }
        if (!staircaseAccess && ladderPosition != null && stationRoute(null) == null) {
            int[] oldTarget = accessSearchTarget;
            accessSearchTarget = accessDestination;
            boolean extended;
            try { extended = connectLadderFloor(accessPlan); }
            finally { proposedAccess.clear(); accessSearchTarget = oldTarget; }
            if (extended) {
                reconcileAccessPlan(); resetPath(); clearRouteSearch(); failedAccessOperations.clear(); return;
            }
            accessFailure("verify_access",null,"Access cells restored, but actual storage travel is still blocked: " + navigationDetail);
            blockObjective("no_access"); return;
        }
        buildingAccess = false; accessPlan.clear(); accessIndex = 0; requestedBlocks = requestedLadders = 0;
        repairAfterRestock = false;
        accessRepairPending = staircaseAccess;
        constructionRefuge.clear(); failedAccessOperations.clear();
        clearAccessSearch(); clearRouteSearch();
        failedEndpoints.clear(); accessAttempts = 0; returnRoute.clear();
        navigationReason = navigationDetail = arrivalFailure = "";
        if (resumeStorageAfterAccess) {
            resumeStorageAfterAccess = false; beginStorage(accessStorageReason); accessStorageReason = StorageReason.NONE;
        } else returnToWork("supplied access completed");
    }
    private void clearAccessCell(int[] p) {
        if (!suitable(getHeldItem())) {
            requestedTools = 1;
            if (accessStationReachable && task != Task.ESCAPE) beginStorage(StorageReason.TOOL);
            else { accessFailure("supplies",p,task == Task.ESCAPE ? "Escape clearance needs a suitable pickaxe; station has not been reached"
                    : "A suitable pickaxe is needed for access clearance; station unreachable"); blockObjective("no_tool"); }
            return;
        }
        if (!moveNear(p[0],p[1]+1,p[2],true)) {
            if ("route_failed".equals(status)) deferAccessOperation(p);
            else if (!navigationBlocked && !"chunk".equals(status)) status = accessActivity();
            return;
        }
        // Exactly the ordinary survival harvesting/custom-drop path, without
        // advancing the excavation cursor or replacing existing pending drops.
        if (!authorized() || !accessHarvestable(p,task != Task.ESCAPE) || !visible(p[0],p[1],p[2])) return;
        FakePlayer player = actor(); player.setPosition(posX,posY,posZ); player.onGround = onGround;
        player.inventory.mainInventory[0] = getHeldItem(); player.inventory.currentItem = 0;
        Block block = worldObj.getBlock(p[0],p[1],p[2]); int meta = worldObj.getBlockMetadata(p[0],p[1],p[2]);
        if (!block.canHarvestBlock(player,meta)) {
            requiredHarvest = Math.max(0,block.getHarvestLevel(meta)); player.inventory.mainInventory[0] = null; resetMining();
            if (accessStationReachable && task != Task.ESCAPE) beginStorage(StorageReason.TOOL);
            else { accessFailure("supplies",p,"Access clearance needs pickaxe harvest level " + requiredHarvest + "; station unreachable"); blockObjective("tool_level"); }
            return;
        }
        requiredHarvest = -1;
        if (miningBlock != block || miningMeta != meta || !ItemStack.areItemStacksEqual(miningTool,getHeldItem())) {
            miningBlock = block; miningMeta = meta; miningTool = getHeldItem().copy(); miningProgress = 0;
        }
        status = accessActivity(); accessStage = "clearance"; swingItem();
        float hardness = block.getPlayerRelativeBlockHardness(player,worldObj,p[0],p[1],p[2])*10;
        if (hardness <= 0) { accessFailure("clearance",p,"Tool cannot harvest access clearance"); deferAccessOperation(p); return; }
        miningProgress += hardness; objectiveTicks = 0;
        if (miningProgress < 1) return;
        boolean harvested = harvestWithTool(player,p[0],p[1],p[2]);
        resetMining();
        if (!harvested || !accessPresent(p)) { accessFailure("clearance",p,"Forge harvesting refused or did not clear the access cell"); deferAccessOperation(p); return; }
        // Keep a replacement at this coordinate if this was its prerequisite.
        boolean replacement = false;
        for (int[] required : accessPlan) if (required[3] >= 0 && required[0] == p[0] && required[1] == p[1] && required[2] == p[2]) { replacement = true; break; }
        if (!replacement) retainAccess(p);
        if (task == Task.ESCAPE || staircaseAccess) {
            if (accessIndex < accessPlan.size() && accessPlan.get(accessIndex) == p) accessIndex++;
        } else reconcileAccessPlan();
        failedAccessOperations.clear();
        usefulProgress("harvested access clearance");
    }
    private boolean harvestWithTool(FakePlayer player,int x,int y,int z) {
        retainLadderBacking(accessBlocks);
        retainLadderBacking(accessPlan);
        List<int[]> before = new java.util.ArrayList<int[]>();
        for (int[] p : accessBlocks) if (accessPresent(p)) before.add(p);
        for (int[] p : accessPlan) if (accessPresent(p)) before.add(p);
        boolean clearance = buildingAccess || task == Task.ESCAPE;
        try { return com.hfr.worker.MinerHarvestDrops.harvest(player,x,y,z,factionId,
                event -> !clearance || accessArea(event.x,event.y,event.z), changed -> finishHarvest(changed,before)); }
        finally {
            setCurrentItemOrArmor(0,player.inventory.mainInventory[0]); player.inventory.mainInventory[0] = null;
        }
    }
    private void finishHarvest(List<net.minecraftforge.event.world.BlockEvent.BreakEvent> changed,List<int[]> before) {
        List<int[]> detached = new java.util.ArrayList<int[]>();
        for (int[] p : before) if (!accessPresent(p)) detached.add(p);
        if (changed.isEmpty() && detached.isEmpty()) return;
        // Collect every affected site's real entities, even after a partial action/exception.
        // Overlapping pickup volumes skip dead entities; overflow retains each remainder once.
        boolean collected = true;
        for (net.minecraftforge.event.world.BlockEvent.BreakEvent event : changed)
            if (!collectDrops(event.x,event.y,event.z)) collected = false;
        for (int[] p : detached) if (!collectDrops(p[0],p[1],p[2])) collected = false;
        if (!collected && pendingDrops == null) {
            if (!changed.isEmpty()) {
                net.minecraftforge.event.world.BlockEvent.BreakEvent first = changed.get(0);
                pendingDrops = new int[] {first.x,first.y,first.z};
            } else pendingDrops = Arrays.copyOf(detached.get(0),3);
            pendingDropsCaptured = true;
        }
        // The pre-harvest exit proof modeled only the target. Rebuild from actual terrain;
        // retain the construction objective and fixed ladder column for repair/escape.
        resetPath(); returnRoute.clear(); clearRouteSearch(); clearAccessSearch();
        rejectedTargets.clear(); reachedPositions.clear(); failedEndpoints.clear();
        failedAccessOperations.clear();
        selectingTarget = false; searchOffset = accessAttempts = returnFailures = 0; returnFailureTarget = -1;
        interactionTarget = selectedEndpoint = plannedEntrance = null; arrivalFailure = "";
        accessSupplyChecked = false;
        if (buildingAccess && !staircaseAccess && task != Task.ESCAPE) reconcileAccessPlan();
        else if (buildingAccess || task == Task.ESCAPE) {
            for (int i = 0; i < accessIndex; i++) if (!accessPresent(accessPlan.get(i))) { accessIndex = i; break; }
        } else for (int[] p : accessBlocks) if (!accessPresent(p)) { accessRepairPending = true; break; }
        usefulProgress("terrain changed by tool harvest");
    }
    private boolean placeAccess(int[] p) {
        if (p[3] < 0) return false;
        if (p[3] > 0 && !fixedRung(p)) return false;
        int slot = -1; for (int i = 0; i < cargo.length; i++) if (accessMaterial(cargo[i],p[3] != 0)) { slot = i; break; }
        if (slot < 0 || accessShape(p).intersectsWith(boundingBox) || !authorized() || !accessArea(p[0],p[1],p[2]) || !worldObj.isAirBlock(p[0],p[1],p[2])
                || worldObj.getTileEntity(p[0],p[1],p[2]) != null || BuilderPlacement.protectedBlock(worldObj,p[0],p[1],p[2])) return false;
        ItemStack material = cargo[slot], offered = material.copy(); offered.stackSize = 1;
        net.minecraft.item.ItemBlock item = (net.minecraft.item.ItemBlock)material.getItem();
        Block block = item.field_150939_a;
        int side = placementSide(p);
        if (side < 0) return false;
        net.minecraftforge.common.util.ForgeDirection direction = net.minecraftforge.common.util.ForgeDirection.getOrientation(side);
        int bx = p[0]-direction.offsetX, by = p[1]-direction.offsetY, bz = p[2]-direction.offsetZ;
        FakePlayer player = actor(); player.setPosition(posX,posY,posZ); player.onGround = onGround;
        if (!solidAttachment(bx,by,bz,direction,-30000001,-1,0) || worldObj.getTileEntity(bx,by,bz) != null
                || BuilderPlacement.protectedBlock(worldObj,bx,by,bz) || !WorkerAccess.ownsLand(worldObj,bx,bz,factionId)
                || !player.canPlayerEdit(p[0],p[1],p[2],side,offered)
                || !worldObj.canPlaceEntityOnSide(block,p[0],p[1],p[2],false,side,null,offered)) return false;
        net.minecraftforge.common.util.BlockSnapshot before = net.minecraftforge.common.util.BlockSnapshot.getBlockSnapshot(worldObj,p[0],p[1],p[2]);
        ItemStack previous = player.inventory.mainInventory[0]; player.inventory.mainInventory[0] = offered;
        boolean accepted = false;
        try {
            int meta = block.onBlockPlaced(worldObj,p[0],p[1],p[2],side,.5F,.5F,.5F,item.getMetadata(offered.getItemDamage()));
            if (item.placeBlockAt(offered,player,worldObj,p[0],p[1],p[2],side,.5F,.5F,.5F,meta)) {
                net.minecraftforge.event.world.BlockEvent.PlaceEvent event = net.minecraftforge.event.ForgeEventFactory.onPlayerBlockPlace(player,before,direction);
                accepted = !event.isCanceled() && worldObj.getBlock(p[0],p[1],p[2]) == block && accessPresent(p);
            }
        } finally {
            player.inventory.mainInventory[0] = previous;
            if (!accepted) before.restore(true,false);
        }
        if (accepted && --material.stackSize <= 0) cargo[slot] = null;
        return accepted;
    }
    private int placementSide(int[] p) {
        for (int f = 0; f < 6; f++) {
            if (p[3] != 0 && f != p[3]) continue;
            net.minecraftforge.common.util.ForgeDirection d = net.minecraftforge.common.util.ForgeDirection.getOrientation(f);
            int bx = p[0]-d.offsetX, by = p[1]-d.offsetY, bz = p[2]-d.offsetZ;
            if (solidAttachment(bx,by,bz,d,-30000001,-1,0) && worldObj.getTileEntity(bx,by,bz) == null
                    && !BuilderPlacement.protectedBlock(worldObj,bx,by,bz) && WorkerAccess.ownsLand(worldObj,bx,bz,factionId)) return f;
        }
        return -1;
    }
    /** Canonical cache order is storage -> current work position. Check only the prefix needed
     * to leave now; discard stale suffixes rather than joining old trips in opposite directions. */
    private boolean ensureReturnRoute(int removedX,int removedY,int removedZ) {
        int here = routePosition(returnRoute);
        if (here >= 0 && stationEndpoint(returnRoute.get(0))
                && safeStart(returnRoute.get(here),removedX,removedY,removedZ,false)
                && routeIntact(returnRoute,here,0,removedX,removedY,removedZ)) {
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
        java.util.Map<String,Integer> failures = new java.util.TreeMap<String,Integer>();
        for (int dx = -3; dx <= 3; dx++) for (int dz = -3; dz <= 3; dz++) for (int dy = -3; dy <= 3; dy++) {
            if (dx*dx+dy*dy+dz*dz > 9) continue;
            double fy = feet(x+dx,y+dy,z+dz,x,y-1,z);
            String failure = Double.isNaN(fy) ? "footing/clearance/attachment" : interactionFailure(x+dx+.5,fy,z+dz+.5,x,y,z,true,false);
            if (!failure.isEmpty()) failures.put(failure,failures.containsKey(failure) ? failures.get(failure)+1 : 1);
        }
        return "Endpoint candidates near " + x+","+y+","+z + ": " + failures;
    }
    private void terminalNavigation() {
        // Preserve the final diagnostic while freezing execution until conditions change.
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
                    + " range=" + getNavigator().getPathSearchRange() + " bounds=" + Arrays.toString(plan) + " detail=" + navigationDetail
                    + " " + endpointDiagnostic(arrivalFailure));
        }
        blockObjective("navigation_failed"); navigationReason = reason; destination = dest.length == 3 ? dest : null; waypoint = step.length == 3 ? step : null;
        recoveryOrigin = recoveryStart; recoveryDestination = recoveryEnd; recoveryAttempts = attempts;
    }
    private void visitChest() {
        if (!worldObj.blockExists(plan[0], plan[1], plan[2])) { status = "chunk"; return; }
        IInventory chest = stationInventory(plan[0], plan[1], plan[2]);
        if (chest == null) { status = stationUnavailable(plan[0], plan[1], plan[2]); return; }
        if (!moveNear(plan[0], plan[1], plan[2])) {
            if ("route_failed".equals(status)) {
                if (storageReason == StorageReason.LADDERS) { beginEscape(); return; }
                StorageReason interrupted = storageReason;
                int[] goal = workDestination();
                if (goal == null) goal = new int[] {plan[0],plan[1],plan[2]};
                if (!buildingAccess && (startAccessRepair(goal) || planAccess(goal[0],goal[1],goal[2]))) {
                    resumeStorageAfterAccess = true; accessStorageReason = interrupted;
                    if (navigationBlocked) task = Task.TO_WORK;
                    else { transition(Task.TO_WORK,"construct storage exit",goal); status = buildingAccess ? accessActivity() : "planning_access"; }
                } else terminalNavigation();
            }
            return;
        }
        if (task != Task.RESTOCKING || hasCargo() || !overflowDrops.isEmpty())
            transition(Task.DEPOSITING,"at storage: " + storageReason.key,new int[] {plan[0],plan[1],plan[2]});
        accessSupplyChecked = accessStationReachable = true;
        long beforeSupplies = supplyStamp();
        equipCarriedTool();
        stockTorchesFromCargo();
        // Retain precisely the materials reserved for the interrupted construction.
        // Otherwise a missing-ladder wait deposits and withdraws the same backing
        // blocks on every visit and can mistake its own reserved cargo for full storage.
        boolean reserveAccess = cursor < totalWork() || rampCleanup >= 0 || pendingDrops != null && !pendingDropsCaptured;
        int keepBlocks = reserveAccess ? supportRequirement() : 0;
        int keepLadders = reserveAccess ? Math.max(restockLadders,Math.max(ladderRequirement(),accessNeeded(true))) : 0;
        boolean depositBlocked = false;
        for (ItemStack stack : cargo) if (stack != null) {
            int keep = accessMaterial(stack,false) ? Math.min(stack.stackSize,keepBlocks)
                    : accessMaterial(stack,true) ? Math.min(stack.stackSize,keepLadders) : 0;
            if (accessMaterial(stack,false)) keepBlocks -= keep;
            else if (accessMaterial(stack,true)) keepLadders -= keep;
            stack.stackSize -= keep; insert(chest,stack);
            depositBlocked |= stack.stackSize > 0; stack.stackSize += keep;
        }
        for (int i = 0; i < cargo.length; i++) if (cargo[i] != null && cargo[i].stackSize <= 0) cargo[i] = null;
        for (java.util.Iterator<ItemStack> items = overflowDrops.iterator(); items.hasNext();) {
            ItemStack stack = items.next(); insert(chest,stack); if (stack.stackSize <= 0) items.remove();
        }
        chest.markDirty();
        if (beforeSupplies != supplyStamp()) usefulProgress("deposited real cargo");
        if (depositBlocked || !overflowDrops.isEmpty()) { status = "storage_full"; supplyCheckTicks = 40; return; }
        // New overflow was entirely captured from actual entities. Once it is deposited there
        // is nothing left at that site; legacy coordinate-only saves still need a collection visit.
        if (pendingDropsCaptured) { pendingDrops = null; pendingDropsCaptured = false; }
        toolBroken = false;
        int total = (plan[5] - plan[3] + 1) * (plan[6] - plan[4] + 1) * (plan[7] - plan[8] + 1);
        if (cursor >= total && rampCleanup < 0 && pendingDrops == null) {
            buildingAccess = resumeStorageAfterAccess = false; accessPlan.clear(); accessIndex = 0;
            transition(Task.COMPLETE,"final deposit finished",null); stop("complete"); return;
        }
        transition(Task.RESTOCKING,"deposit finished; check required supplies",new int[] {plan[0],plan[1],plan[2]});
        stockAccess(chest,false); stockAccess(chest,true);
        if (storageReason == StorageReason.LADDERS && accessCount(true) < restockLadders) {
            status = "no_ladders"; supplyCheckTicks = 40; return;
        }
        if (storageReason == StorageReason.ACCESS && accessCount(false) < supportRequirement()) {
            status = "no_access"; supplyCheckTicks = 40; return;
        }
        if (storageReason == StorageReason.LADDERS) {
            restockLadders = 0; sentLadderReminder = false; repairAfterRestock = true;
            buildingAccess = false; accessPlan.clear(); accessIndex = 0;
            escapePillar = escapeDestination = escapeOrigin = null; escapeJumping = false;
            clearAccessSearch(); clearRouteSearch(); failedAccessOperations.clear(); constructionRefuge.clear();
        }
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
        if (buildingAccess) {
            stockAccess(chest,false); stockAccess(chest,true);
            if (beforeSupplies != supplyStamp()) usefulProgress("restocked required access materials");
            boolean canContinueStairs = staircaseAccess && accessCount(false) > 0;
            if (staircaseAccess && !canContinueStairs) for (int i = accessIndex; i < accessPlan.size(); i++) if (!accessPresent(accessPlan.get(i))) {
                canContinueStairs = accessPlan.get(i)[3] < 0; break;
            }
            if (!canContinueStairs && (accessCount(false) < accessNeeded(false) || accessCount(true) < accessNeeded(true))) {
                status = "no_access"; supplyCheckTicks = 40; return;
            }
        }
        if (beforeSupplies != supplyStamp()) usefulProgress("restocked required supplies");
        returnToWork("deposit and required restocking finished");
    }
    private void stockAccess(IInventory chest,boolean rung) {
        for (int i = firstSlot(chest); i < chest.getSizeInventory(); i++) {
            int need = (rung ? Math.max(restockLadders,Math.max(ladderRequirement(),accessNeeded(true)))
                    : supportRequirement())-accessCount(rung);
            if (need <= 0) break;
            ItemStack stack = chest.getStackInSlot(i);
            if (!accessMaterial(stack,rung)) continue;
            int amount = Math.min(need,Math.min(stack.stackSize,remainingCapacity(stack)));
            if (amount <= 0) break;
            ItemStack taken = chest.decrStackSize(i,amount);
            if (taken != null) insert(this,taken);
            chest.markDirty();
        }
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
        if (buildingAccess && accessIndex < accessPlan.size() && accessPlan.get(accessIndex)[3] < 0) {
            int[] cell = accessPlan.get(accessIndex); x = cell[0]; y = cell[1]; z = cell[2];
        }
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
        requestedBlocks = requestedLadders = 0;
        if (worldObj.isRemote || !configured || !authorized() || jobDimension != dimension) return;
        if (buildingAccess || restockLadders > 0 || storageReason == StorageReason.ACCESS && storageTask()) {
            requestedBlocks = Math.max(0,supportRequirement()-accessCount(false));
            requestedLadders = Math.max(0,Math.max(restockLadders,accessNeeded(true))-accessCount(true));
            IInventory supplies = stationAssigned ? stationInventory(plan[0],plan[1],plan[2]) : null;
            if (supplies != null && accessSupplyChecked && accessStationReachable) for (int i = firstSlot(supplies); i < supplies.getSizeInventory(); i++) {
                ItemStack stack = supplies.getStackInSlot(i);
                if (accessMaterial(stack,false)) requestedBlocks = Math.max(0,requestedBlocks-stack.stackSize);
                if (accessMaterial(stack,true)) requestedLadders = Math.max(0,requestedLadders-stack.stackSize);
            }
        }
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
        requestedTools = tool ? 0 : 1;
        requestedTorches = needsLight && lights == 0 ? 1 : 0;
        IInventory station = stationAssigned && accessSupplyChecked && accessStationReachable ? stationInventory(plan[0], plan[1], plan[2]) : null;
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
        if (running && restockLadders > 0 && (requestedLadders > 0 || task == Task.ESCAPE && navigationBlocked)
                && (!sentLadderReminder || now-lastLadderReminder >= 60000000000L)) {
            EntityPlayerMP recipient = supplyRecipient();
            if (recipient != null) {
                recipient.addChatMessage(new ChatComponentTranslation(restockLadders > accessCount(true) ? "xf.miner.ladder_notice" : "xf.miner.escape_notice",
                        getCommandSenderName(),getUniqueID().toString().substring(0,8),
                        requestedLadders,plan[0]+", "+plan[1]+", "+plan[2],
                        new ChatComponentTranslation(task == Task.RESTOCKING ? "xf.miner.waiting_at_station" : "xf.miner.station_not_reached"),
                        navigationBlocked ? accessBlocker : ""));
                lastLadderReminder = now; sentLadderReminder = true;
                lastSupplyReminder = now; sentSupplyReminder = true;
            }
        }
        if (!running || requestedTools == 0 && requestedTorches == 0 && requestedBlocks == 0 && requestedLadders == 0
                || sentSupplyReminder && now - lastSupplyReminder < 60000000000L) return;
        EntityPlayerMP recipient = supplyRecipient();
        if (recipient == null) return;
        // One private message per Miner per minute, including changing requests and reopen/Start spam.
        ChatComponentTranslation tool = new ChatComponentTranslation(requiredHarvest >= 0 ? "xf.miner.request_tier" : "xf.miner.request_pick", requiredHarvest);
        ChatComponentTranslation light = new ChatComponentTranslation("xf.miner.need_torches", requestedTorches);
        ChatComponentTranslation request = requestedTools > 0 ? tool : light;
        if (requestedTools > 0 && requestedTorches > 0) request = new ChatComponentTranslation("xf.miner.both_requests", tool, light);
        if (requestedBlocks > 0 || requestedLadders > 0) {
            ChatComponentTranslation access = requestedLadders == 0 ? new ChatComponentTranslation("xf.miner.need_blocks",requestedBlocks)
                    : new ChatComponentTranslation("xf.miner.need_access",requestedBlocks,requestedLadders);
            request = requestedTools > 0 || requestedTorches > 0 ? new ChatComponentTranslation("xf.miner.both_requests",request,access) : access;
        }
        recipient.addChatMessage(new ChatComponentTranslation("xf.miner.supply_notice", getCommandSenderName(),
                getUniqueID().toString().substring(0, 8), request,
                new ChatComponentTranslation(buildingAccess && accessSupplyChecked && !accessStationReachable
                        ? "xf.miner.direct_supplies" : "xf.miner.supplies")));
        lastSupplyReminder = now; sentSupplyReminder = true;
    }
    private EntityPlayerMP supplyRecipient() {
        for (Object value : MinecraftServer.getServer().getConfigurationManager().playerEntityList) {
            EntityPlayerMP player = (EntityPlayerMP)value;
            if (player.getUniqueID().equals(employerId) && WorkerAccess.member(player,factionId)
                    && player.playerNetServerHandler.netManager.isChannelOpen()) return player;
        }
        return null;
    }
    public NBTTagCompound snapshot() {
        updateRequests();
        NBTTagCompound tag = new NBTTagCompound(); tag.setIntArray("Plan", plan); tag.setBoolean("Configured", configured); tag.setInteger("Dimension", jobDimension);
        tag.setBoolean("StationAssigned", stationAssigned);
        tag.setBoolean("Down",installedDown);
        tag.setBoolean("AccessRamp",accessRamp); tag.setInteger("RampOffset",rampOffset); tag.setInteger("RampCleanup",rampCleanup);
        tag.setInteger("RampDirection",rampDirection); tag.setInteger("TerrainRampOffset",terrainRampOffset);
        tag.setString("NavigationReason",navigationReason);
        tag.setString("NavigationDetail",navigationReason.isEmpty() ? "" : navigationDetail);
        tag.setBoolean("NavigationBlocked",navigationBlocked);
        tag.setInteger("ObjectiveFailures",objectiveFailures); tag.setInteger("ObjectiveTicks",objectiveTicks);
        if (recoveryOrigin != null) tag.setIntArray("RecoveryOrigin",recoveryOrigin);
        if (recoveryDestination != null) tag.setIntArray("RecoveryDestination",recoveryDestination);
        tag.setInteger("RecoveryAttempts",recoveryAttempts);
        if (destination != null) tag.setIntArray("Destination",destination);
        if (waypoint != null) tag.setIntArray("Waypoint",waypoint);
        tag.setInteger("PickaxesRequested", requestedTools);
        tag.setInteger("HarvestLevel", requiredHarvest);
        tag.setInteger("TorchesRequested", requestedTorches);
        tag.setInteger("BlocksRequested",requestedBlocks); tag.setInteger("LaddersRequested",requestedLadders);
        tag.setInteger("RestockLadders",restockLadders); tag.setInteger("LaddersCarried",accessCount(true));
        tag.setInteger("BlocksCarried",accessCount(false)); tag.setInteger("EmergencyBlocks",emergencyBlocks());
        tag.setBoolean("RepairAfterRestock",repairAfterRestock);
        tag.setInteger("EscapeColumn",escapeColumn);
        if (escapeOrigin != null) tag.setIntArray("EscapeOrigin",escapeOrigin);
        if (escapePillar != null) tag.setIntArray("EscapePillar",escapePillar);
        if (escapeDestination != null) tag.setIntArray("EscapeDestination",escapeDestination);
        tag.setBoolean("BuildingAccess",buildingAccess || !accessPlan.isEmpty() && (accessProposals != null || reconsideringLadder)); tag.setInteger("AccessIndex",accessIndex);
        tag.setBoolean("StaircaseAccess",staircaseAccess); tag.setBoolean("StaircaseInstalled",staircaseInstalled);
        tag.setBoolean("RepairingLadder",repairingLadder);
        if (buildingAccess && accessIndex < accessPlan.size()) tag.setIntArray("AccessOperation",Arrays.copyOf(accessPlan.get(accessIndex),3));
        tag.setTag("ConstructionRefuge",writeAccess(constructionRefuge));
        tag.setInteger("LadderPositionVersion",1);
        if (ladderPosition != null) tag.setIntArray("LadderPosition",ladderPosition);
        tag.setString("AccessBlocker",accessBlocker);
        tag.setString("LadderBlocker",ladderObstruction);
        tag.setBoolean("AccessDirectDelivery",(buildingAccess || task == Task.ESCAPE) && accessSupplyChecked && !accessStationReachable);
        tag.setString("AccessStage",accessStage);
        if (accessProposals != null) {
            tag.setInteger("AccessColumnsChecked",accessColumnIndex);
            tag.setInteger("AccessColumns",(plan[5]-plan[3]+1)*(plan[6]-plan[4]+1));
            tag.setInteger("AccessProposal",Math.min(accessProposalIndex+1,accessProposals.size()));
            tag.setInteger("AccessProposals",accessProposals.size());
            tag.setInteger("AccessEndpointsRemaining",routeSearchRemaining == null ? 0 : routeSearchRemaining.size());
        } else if (accessSearchTarget != null && !ladderDiscoveryComplete && ladderPosition == null) {
            tag.setInteger("AccessColumnsChecked",ladderDiscoveryColumn);
            tag.setInteger("AccessColumns",(plan[5]-plan[3]+1)*(plan[6]-plan[4]+1));
        }
        tag.setBoolean("ResumeStorageAfterAccess",resumeStorageAfterAccess); tag.setString("AccessStorageReason",accessStorageReason.key);
        if (accessDestination != null) tag.setIntArray("AccessDestination",accessDestination);
        if (selectedEndpoint != null) tag.setIntArray("StandingPosition",selectedEndpoint);
        if (plannedEntrance != null) tag.setIntArray("Entrance",plannedEntrance);
        tag.setString("ArrivalFailure",arrivalFailure);
        tag.setString("ActualPosition",String.format(java.util.Locale.ROOT,"%.2f, %.2f, %.2f",posX,boundingBox.minY,posZ));
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
        if (selectingTarget && interactionTarget != null)
            tag.setIntArray("Target",new int[] {interactionTarget[0],interactionTarget[1]-1,interactionTarget[2]});
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
        tag.setTag("MinerAccess",writeAccess(accessBlocks)); tag.setTag("MinerAccessPlan",writeAccess(accessPlan));
    }
    private NBTTagList writeAccess(List<int[]> source) {
        NBTTagList list = new NBTTagList();
        for (int[] p : source) { NBTTagCompound entry = new NBTTagCompound(); entry.setIntArray("Position",p); list.appendTag(entry); }
        return list;
    }
    private void readAccess(NBTTagList saved,List<int[]> into,int limit) {
        into.clear();
        for (int i = 0; i < Math.min(limit,saved.tagCount()); i++) {
            int[] p = saved.getCompoundTagAt(i).getIntArray("Position");
            if (configured && p.length == 4 && p[0] >= plan[3] && p[0] <= plan[5] && p[2] >= plan[4] && p[2] <= plan[6]
                    && p[1] >= plan[8] && p[1] <= plan[7] && (p[3] == -1 || p[3] == 0 || p[3] >= 2 && p[3] <= 5)) into.add(p);
        }
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
        accessRamp = configured && job.getBoolean("AccessRamp") && (plan[5] > plan[3] || plan[6] > plan[4]);
        rampOffset = accessRamp ? Math.max(0,job.getInteger("RampOffset")) % rampPerimeter() : 0;
        rampDirection = job.getInteger("RampDirection") == -1 ? -1 : 1;
        terrainRampOffset = accessRamp && job.hasKey("TerrainRampOffset") ? Math.max(0,job.getInteger("TerrainRampOffset")) % rampPerimeter() : rampOffset;
        rampCleanup = accessRamp ? Math.max(-1,Math.min(plan[7]-plan[8],job.getInteger("RampCleanup"))) : -1;
        selectionRevision = Math.max(0,tag.getLong("SelectionRevision"));
        returnRoute.clear(); rejectedTargets.clear(); resetPath(); navigationReason = "";
        navigationBlocked = selectingTarget = false; objectiveFailures = objectiveTicks = 0;
        watchedTerrain.clear(); terrainChecks = null; reachedPositions.clear(); clearRouteSearch(); clearAccessSearch();
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
        readAccess(tag.getTagList("MinerAccess",10),accessBlocks,4096);
        // A retained requirement can also have a clearance prerequisite at the
        // same coordinate. Do not truncate its replacement on reload.
        readAccess(tag.getTagList("MinerAccessPlan",10),accessPlan,8192);
        ladderPosition = null;
        int[] fixed = job.getIntArray("LadderPosition");
        if (validLadderPosition(fixed)) ladderPosition = fixed;
        recoverRecordedLadder();
        retainLadderLandings(accessBlocks);
        // Old saves may contain a never-built proposal for a different column.
        // Its real placed access is retained, but it cannot move the committed ladder.
        for (int[] p : accessPlan) if (p[3] > 0 && !fixedRung(p)) { accessPlan.clear(); break; }
        staircaseInstalled = accessRamp && job.getBoolean("StaircaseInstalled");
        staircaseAccess = job.hasKey("StaircaseAccess") ? job.getBoolean("StaircaseAccess") : staircaseInstalled && !hasRungs(accessPlan);
        repairingLadder = job.getBoolean("RepairingLadder"); reconsideringLadder = false; staircaseLadderSupplies = Long.MIN_VALUE;
        failedAccessOperations.clear(); constructionRefuge.clear();
        NBTTagList refuges = job.getTagList("ConstructionRefuge",10);
        for (int i = 0; i < Math.min(27,refuges.tagCount()); i++) {
            int[] p = refuges.getCompoundTagAt(i).getIntArray("Position");
            if (p.length == 3 && p[1] >= 0 && p[1]+1 < worldObj.getHeight() && inCorridor(p[0],p[2])) constructionRefuge.add(p);
        }
        accessBlocker = ladderObstruction = "";
        accessIndex = Math.max(0,Math.min(accessPlan.size(),job.getInteger("AccessIndex")));
        buildingAccess = job.getBoolean("BuildingAccess") && !accessPlan.isEmpty(); accessAttempts = 0;
        int[] accessGoal = job.getIntArray("AccessDestination"); accessDestination = accessGoal.length == 3 ? accessGoal : null;
        interactionTarget = selectedEndpoint = preciseGoal = null; failedEndpoints.clear(); proposedAccess.clear();
        accessSupplyChecked = false; accessStationReachable = true;
        resumeStorageAfterAccess = job.getBoolean("ResumeStorageAfterAccess") && buildingAccess;
        accessStorageReason = StorageReason.NONE;
        for (StorageReason reason : StorageReason.values()) if (reason.key.equals(job.getString("AccessStorageReason"))) accessStorageReason = reason;
        restockLadders = Math.max(0,Math.min(512,job.getInteger("RestockLadders")));
        repairAfterRestock = job.getBoolean("RepairAfterRestock");
        escapeColumn = Math.max(0,Math.min(81,job.getInteger("EscapeColumn")));
        int[] origin = job.getIntArray("EscapeOrigin"), pillar = job.getIntArray("EscapePillar"), landing = job.getIntArray("EscapeDestination");
        escapeOrigin = origin.length == 3 && inCorridor(origin[0],origin[2]) ? origin : null;
        escapePillar = pillar.length == 4 && pillar[0] >= plan[3] && pillar[0] <= plan[5] && pillar[2] >= plan[4] && pillar[2] <= plan[6]
                && pillar[1] >= plan[8] && pillar[3] > pillar[1] && pillar[3] <= plan[7]+1 && pillar[3]-pillar[1] <= 16 ? pillar : null;
        escapeDestination = landing.length == 3 && inCorridor(landing[0],landing[2]) ? landing : null;
        escapeJumping = false; escapeJumpTicks = 0; accessSupplies = Long.MIN_VALUE;
        sentLadderReminder = false;
        accessRepairPending = true;
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
    public void markDirty() { supplyCheckTicks = 0; accessSupplyChecked = false; }
    public boolean isUseableByPlayer(EntityPlayer player) { return mayManage(player); }
    public void openInventory() { }
    public void closeInventory() { }
    public boolean isItemValidForSlot(int slot, ItemStack stack) { return slot >= 0 && slot < 27 || slot == 27 && pickaxe(stack) || slot == 28 && isTorch(stack); }
}
