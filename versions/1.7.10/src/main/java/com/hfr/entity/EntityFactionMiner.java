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
    private enum Movement { WALK, STEP, DESCEND, CLIMB, LADDER_EXIT }
    private enum PrecisePhase { FLOOR, CLIMB, EXIT_LIFT, EXIT, DROP, LANDING }
    private enum EdgeFailure { NONE, FOOTING, RISE, DROP, SPAN, LADDER, CLEARANCE, LANDING }
    private int[] preciseGoal, interactionTarget, selectedEndpoint, plannedEntrance;
    // An accepted drop owns gravity until actual landing, even if a route is invalidated.
    private int[] descentLanding;
    private static final double PRECISE_HORIZONTAL = .01, PRECISE_VERTICAL = .01, LADDER_VERTICAL = .06;
    private PrecisePhase precisePhase = PrecisePhase.FLOOR;
    private boolean walkingSegment;
    private int preciseDirection, ladderStallTicks, lastLadderDirection, lastLadderTick;
    private double preciseHeight, preciseSurfaceHeight;
    private double bestSegmentError;
    private double lastRequestedX, lastRequestedZ, lastMovedX, lastMovedZ;
    private String segmentFailure = "";
    // Failed execution edges survive replanning until actual terrain changes or an explicit retry.
    private final java.util.Map<Long,java.util.Set<Long>> failedMovementEdges = new java.util.HashMap<Long,java.util.Set<Long>>();
    private int[] lastLadderGoal;
    private boolean lastLadderCompleted = true;
    private long lastLadderLog;
    private Block interactionBlock;
    private int interactionMeta;
    private boolean interactionMining;
    private final java.util.Set<Long> failedEndpoints = new java.util.HashSet<Long>();
    private String arrivalFailure = "";
    // Hypothetical access exists only during planning; no world edits or inventory changes there.
    private final java.util.Map<Long,int[]> proposedAccess = new java.util.HashMap<Long,int[]>();
    // x,y,z, action: -1 = clear/reserve headroom, 0 = full support, 2..5 = ladder face.
    private final List<int[]> accessBlocks = new java.util.ArrayList<int[]>();
    // Required fixed-route geometry, independent of historical staircase/escape edits.
    private final List<int[]> ladderAccess = new java.util.ArrayList<int[]>();
    private int[] ladderLanding;
    // Mining feet and target survive temporary storage/construction interactions.
    private int[] workPosition, workPositionTarget;
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
    private String accessBlocker = "", ladderObstruction = "", accessPlacementFailure = "";
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
    private boolean routeExitLimited;
    private long routeSearchLayout;
    private double routeSearchX, routeSearchY, routeSearchZ;
    private long endpointLayout;
    private final java.util.Map<Long,Integer> endpointAccess = new java.util.HashMap<Long,Integer>();
    private int[] endpointOrigin;
    private double endpointX, endpointY, endpointZ;
    private int[] accessSearchTarget;
    private List<List<int[]>> accessProposals;
    private final java.util.Map<List<int[]>,int[]> staircaseLayouts = new java.util.IdentityHashMap<List<int[]>,int[]>();
    private int accessProposalIndex;
    private int staircaseVariantIndex;
    private int accessColumnIndex, accessBottom, accessTop;
    private final java.util.Set<String> accessLayouts = new java.util.HashSet<String>();
    private boolean accessSearchExhausted;
    private String accessStage = "", accessFailure = "";
    private int occupiedAccessCells;
    private int[] occupiedAccessCell;
    private boolean enteringRoute;
    private int restockLadders, restockBlocks, escapeColumn, escapeJumpTicks;
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
        clearMovementCommands(); pathTicks = 0;
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
        if (reason == StorageReason.TOOL) equipCarriedTool();
        if (reason == StorageReason.TORCHES) stockTorchesFromCargo();
        if (!storageNeeded(reason)) return;
        storageReason = reason;
        if (reason != StorageReason.OVERFLOW) capacityDetail = "";
        transition(Task.TO_STORAGE,reason.key,new int[] {plan[0],plan[1],plan[2]});
    }
    private boolean storageNeeded(StorageReason reason) {
        switch (reason) {
        case CAPACITY: return cargoFull();
        case OVERFLOW: return !overflowDrops.isEmpty();
        case TOOL: return !suitable(getHeldItem());
        case TORCHES: return needsLight && torchCount() == 0;
        case LADDERS: return accessCount(true) < Math.max(restockLadders,ladderRequirement());
        case ACCESS: return accessCount(false) < supportRequirement() || accessCount(true) < accessNeeded(true);
        case COMPLETE: return !unfinished() && hasCargo();
        default: return false;
        }
    }
    private boolean resumeStorageTrip() {
        StorageReason reason = accessStorageReason;
        boolean needed = resumeStorageAfterAccess && storageNeeded(reason);
        resumeStorageAfterAccess = false; accessStorageReason = StorageReason.NONE;
        if (needed) beginStorage(reason);
        return needed;
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
        routeExitLimited = false;
    }
    private void clearAccessSearch() {
        accessProposals = null; accessSearchTarget = null; accessProposalIndex = 0; accessSearchExhausted = false;
        accessStage = accessFailure = ""; occupiedAccessCells = 0; occupiedAccessCell = null;
        accessBlocker = "";
        accessColumnIndex = 0; accessLayouts.clear();
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
        failedEndpoints.clear(); failedMovementEdges.clear(); segmentFailure = ""; accessAttempts = 0;
        interactionTarget = selectedEndpoint = plannedEntrance = null;
        clearRouteSearch(); clearAccessSearch(); resetPath(); reachedPositions.clear();
        failedAccessOperations.clear();
        usefulProgress(reason); navigationReason = navigationDetail = arrivalFailure = "";
        accessPlacementFailure = "";
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
            // Active endpoint validation also iterates this map between searches.
            terrainChecks = null;
        }
    }
    private void blockObjective(String state) {
        if ("no_access".equals(state)) state = supportRequirement() > accessCount(false) || ladderRequirement() > accessCount(true)
                ? "access_materials" : "access_route";
        clearMovementCommands(); preciseGoal = null; travelRoute.clear(); recoveryRoute.clear();
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
    private void refreshEndpointContext() {
        int[] origin = {(int)Math.floor(posX),(int)Math.floor(boundingBox.minY+.001),(int)Math.floor(posZ)};
        boolean layoutChanged = endpointAccess.size() != proposedAccess.size();
        if (!layoutChanged) for (int[] p : proposedAccess.values()) {
            Integer kind = endpointAccess.get(positionKey(p[0],p[1],p[2]));
            if (kind == null || kind.intValue() != p[3]) { layoutChanged = true; break; }
        }
        boolean changed = !Arrays.equals(endpointOrigin,origin) || layoutChanged
                || endpointX != posX || endpointY != boundingBox.minY || endpointZ != posZ;
        boolean terrainChanged = false;
        if ((!changed && (!failedEndpoints.isEmpty() || routeSearchRemaining != null)) || !failedMovementEdges.isEmpty()) {
            if (terrainChecks == null || !terrainChecks.hasNext()) terrainChecks = watchedTerrain.values().iterator();
            for (int checked = 0; checked < 512 && terrainChecks.hasNext(); checked++) {
                int[] p = terrainChecks.next(); boolean loaded = worldObj.blockExists(p[0],p[1],p[2]);
                if ((loaded ? Block.getIdFromBlock(worldObj.getBlock(p[0],p[1],p[2])) : -1) != p[3]
                        || loaded && worldObj.getBlockMetadata(p[0],p[1],p[2]) != p[4]) { changed = terrainChanged = true; break; }
            }
        }
        if (changed) {
            if (terrainChanged) failedMovementEdges.clear();
            failedEndpoints.clear(); clearRouteSearch();
            endpointOrigin = origin; endpointLayout++;
            endpointAccess.clear();
            for (int[] p : proposedAccess.values()) endpointAccess.put(positionKey(p[0],p[1],p[2]),p[3]);
            endpointX = posX; endpointY = boundingBox.minY; endpointZ = posZ;
            if (failedMovementEdges.isEmpty()) watchedTerrain.clear();
            terrainChecks = null;
        }
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
        workPosition = workPositionTarget = null;
        rejectedTargets.clear(); returnRoute.clear();
        accessPlan.clear(); accessBlocks.clear(); ladderAccess.clear(); ladderLanding = null;
        buildingAccess = false; accessDestination = null; accessAttempts = 0;
        ladderPosition = null; staircaseAccess = staircaseInstalled = false; ladderObstruction = accessBlocker = accessPlacementFailure = "";
        restockLadders = restockBlocks = escapeColumn = escapeJumpTicks = 0; escapeOrigin = escapePillar = escapeDestination = null; escapeJumping = repairAfterRestock = false;
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
        if (!worldObj.isRemote && running && !landingInProgress() && !navigationBlocked && !recoveringCorridor && interactionTarget != null
                && worldObj.blockExists(interactionTarget[0],interactionTarget[1]-(interactionMining ? 1 : 0),interactionTarget[2])) {
            int ty = interactionTarget[1]-(interactionMining ? 1 : 0);
            if (worldObj.getBlock(interactionTarget[0],ty,interactionTarget[2]) != interactionBlock
                    || worldObj.getBlockMetadata(interactionTarget[0],ty,interactionTarget[2]) != interactionMeta) {
                travelRoute.clear(); preciseGoal = null; clearMovementCommands();
                navigationReason = "target"; navigationDetail = endpointDiagnostic("target");
            }
        }
        if (!worldObj.isRemote && running && !navigationBlocked && configured && !recoveringCorridor
                && !inCorridor((int)Math.floor(posX),(int)Math.floor(posZ))) beginCorridorRecovery();
        // Native following checks neighboring cells while shortcutting. Do not let those checks
        // discover unloaded chunks even though our planned route itself only uses loaded cells.
        if (!worldObj.isRemote && (!getNavigator().noPath() || preciseGoal != null) && waypoint != null && !segmentChunksLoaded(waypoint)) {
            preciseGoal = null; clearMovementCommands();
            status = recoveringCorridor ? "corridor_recovery" : "chunk";
            navigationReason = "chunk"; pathTicks = 0;
        }
        if (!worldObj.isRemote && !recoveringCorridor && !getNavigator().noPath() && destination != null
                && atWorkEndpoint(interactionMining) && atDestination(destination[0],destination[1],destination[2],interactionMining))
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
        // Finish the accepted physics transition before target/supply changes
        // can clear its steering or issue another ascent/descent command.
        if (landingInProgress()) { status = "descending"; return; }
        long supplies = supplyStamp(true);
        if (accessSupplies != supplies) {
            boolean changed = accessSupplies != Long.MIN_VALUE; accessSupplies = supplies;
            if (changed && task == Task.ESCAPE && !storageNeeded(storageReason)
                    && !escapeJumping && supported(posX,boundingBox.minY,posZ,-30000001,-1,0)) {
                buildingAccess = false; accessPlan.clear(); accessIndex = 0;
                restockLadders = restockBlocks = 0; sentLadderReminder = false; repairAfterRestock = true;
                task = Task.TO_WORK; retryObjective("direct ladder delivery; repair fixed access from actual floor");
            }
            if (changed && task != Task.ESCAPE && (navigationBlocked || accessSearchTarget != null))
                retryObjective("access supplies changed; discard stale route failures");
        }
        if (waitForObjectiveChange()) return;
        if (!"chunk".equals(status) && !"no_access".equals(status) && !"access_materials".equals(status) && !"no_tool".equals(status)
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
        for (java.util.Iterator<ItemStack> it = overflowDrops.iterator(); it.hasNext();) {
            ItemStack stack = it.next(); insert(this,stack);
            if (stack.stackSize <= 0) it.remove();
        }
        equipCarriedTool(); stockTorchesFromCargo();
        if (task == Task.ESCAPE) { escape(); return; }
        if (ladderPosition != null && !storageTask() && !buildingAccess && !accessRepairPending) {
            int bottom = Integer.MAX_VALUE;
            for (int[] p : ladderAccess) if (p[3] > 0 && fixedRung(p)) bottom = Math.min(bottom,p[1]);
            if (Math.floor(boundingBox.minY+.001) < bottom) accessRepairPending = true;
        }
        if (accessRepairPending) {
            accessRepairPending = false;
            int[] goal = workDestination();
            if (goal == null) goal = new int[] {plan[0],plan[1],plan[2]};
            boolean returning = storageTask(); StorageReason interrupted = storageReason;
            if (buildingAccess && !staircaseAccess) {
                reconcileAccessPlan();
                if (returning && stationRoute(null) == null) {
                    resumeStorageAfterAccess = true; accessStorageReason = interrupted;
                    returnToWork("repair interrupted storage access");
                }
            }
            else if (startAccessRepair(goal)) {
                if (returning) { resumeStorageAfterAccess = true; accessStorageReason = interrupted; returnToWork("repair interrupted storage access"); }
                return;
            }
        }
        if (!buildingAccess && resumeStorageAfterAccess && !resumeStorageTrip()) returnToWork("interrupted storage need resolved");
        if (!storageTask() && repairAfterRestock && !buildingAccess && accessSearchTarget == null) {
            int[] goal = workDestination();
            if (goal != null && planAccess(goal[0],goal[1],goal[2])) return;
        }
        // Repair an exit before tool/capacity trips can demand that same broken exit.
        if (buildingAccess && !storageTask() && (!overflowDrops.isEmpty() || cargoFull()) && stationRoute(null) != null)
            beginStorage(!overflowDrops.isEmpty() ? StorageReason.OVERFLOW : StorageReason.CAPACITY);
        if (buildingAccess && !storageTask()) { constructAccess(); return; }
        if (reconsideringLadder && !storageTask()) { planAccess(accessSearchTarget[0],accessSearchTarget[1],accessSearchTarget[2]); return; }
        if (!storageTask() && accessSearchTarget != null && !accessSearchExhausted) {
            if (selectingTarget) { findAccessibleTarget((plan[5]-plan[3]+1)*(plan[6]-plan[4]+1)); return; }
            if (planAccess(accessSearchTarget[0],accessSearchTarget[1],accessSearchTarget[2])) return;
        }
        if (suitable(getHeldItem())) requestedTools = 0;
        if (torchCount() > 0) { requestedTorches = 0; needsLight = false; }
        if (task == Task.TO_STORAGE && !storageNeeded(storageReason)) {
            if (storageReason == StorageReason.LADDERS || storageReason == StorageReason.ACCESS) {
                restockLadders = restockBlocks = 0; repairAfterRestock = true;
            }
            returnToWork("storage need resolved by inventory change");
        }
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
                // Re-select an executable endpoint before reconsidering the target
                // or its fixed ladder. Never mine from footing lost by removal.
                failedEndpoints.add(positionKey((int)Math.floor(posX),(int)Math.floor(boundingBox.minY+.001),(int)Math.floor(posZ)));
                if (selectedEndpoint != null) failedEndpoints.add(positionKey(selectedEndpoint[0],selectedEndpoint[1],selectedEndpoint[2]));
                selectedEndpoint = null; resetPath(); clearRouteSearch();
                status = "recovering";
                return;
            }
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
        clearMovementCommands(); preciseGoal = null;
        if (!buildingAccess && ladderPosition == null) {
            // Executable work routes already include an independent storage-exit
            // proof; storage trips end at storage. Retain ladders on either leg.
            retainTravelLadder(found);
            if (ladderPosition == null) retainTravelLadder(returnRoute);
        }
        travelRoute.clear(); travelRoute.addAll(found); routeIndex = segmentEnd = 0;
        // A verified dry drop may leave the column before its final rungs. Keep
        // the same two-block edge rules and the independently proved exit.
        for (int i = 0; i+2 < travelRoute.size(); i++) {
            int[] from = travelRoute.get(i);
            if (!ladderNode(from)) continue;
            int exit = i+1;
            while (exit < travelRoute.size() && ladderNode(travelRoute.get(exit))
                    && travelRoute.get(exit)[0] == from[0] && travelRoute.get(exit)[2] == from[2]
                    && nodeY(travelRoute.get(exit)) < nodeY(from)) exit++;
            if (exit > i+1 && exit < travelRoute.size() && !ladderNode(travelRoute.get(exit))
                    && nodeY(travelRoute.get(exit)) <= nodeY(from)
                    && !failedMovementEdge(from,travelRoute.get(exit)) && safeEdge(from,travelRoute.get(exit),-30000001,-1,0))
                travelRoute.subList(i+1,exit).clear();
        }
        enteringRoute = !atPreciseNode(found.get(0),ladderNode(found.get(0)));
        selectedEndpoint = found.get(found.size()-1).clone(); plannedEntrance = null;
        for (int i = 1; i < travelRoute.size(); i++) if (nodeY(travelRoute.get(i)) < nodeY(travelRoute.get(i-1))-.01) {
            plannedEntrance = travelRoute.get(i).clone(); break;
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
        clearMovementCommands(); travelRoute.clear(); recoveryRoute.clear(); waypoint = null;
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
            clearMovementCommands(); recoveryRoute.clear(); travelRoute.clear(); waypoint = null;
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
    private boolean ladderNode(int[] node) { return ladder(node[0],node[1],node[2],-30000001,-1,0); }
    private boolean atPreciseNode(int[] node,boolean climbing) {
        double dx = node[0]+.5-posX, dz = node[2]+.5-posZ;
        return dx*dx+dz*dz <= PRECISE_HORIZONTAL*PRECISE_HORIZONTAL
                && Math.abs(nodeY(node)-boundingBox.minY) <= (climbing ? LADDER_VERTICAL : PRECISE_VERTICAL);
    }
    private void clearMovementCommands() {
        walkingSegment = false;
        getNavigator().clearPathEntity();
        getMoveHelper().setMoveTo(posX,Math.floor(boundingBox.minY+.5),posZ,0);
        getMoveHelper().onUpdateMoveHelper();
        getJumpHelper().doJump(); setJumping(false);
        moveStrafing = moveForward = 0; motionX = motionZ = 0;
    }
    private boolean verticalControl() {
        return precisePhase == PrecisePhase.CLIMB || precisePhase == PrecisePhase.EXIT_LIFT;
    }
    private String movementPhase() {
        return preciseGoal != null ? precisePhase.name() : walkingSegment ? "WALK" : "IDLE";
    }
    private void startPreciseMovement(int[] goal,boolean climbing) {
        if (preciseGoal == goal) return;
        clearMovementCommands();
        preciseGoal = goal; waypoint = goal.clone();
        preciseHeight = preciseSurfaceHeight = nodeY(goal);
        precisePhase = ladderNode(goal) ? PrecisePhase.CLIMB : climbing || isOnLadder() ? PrecisePhase.EXIT : PrecisePhase.FLOOR;
        if (precisePhase == PrecisePhase.EXIT) {
            if (!safeStart(goal,-30000001,-1,0,recoveringCorridor)) {
                failPreciseMovement("exit outside actual clearance/support/drop bounds",0,0,motionY); return;
            }
            if (preciseHeight < boundingBox.minY-.001) {
                precisePhase = PrecisePhase.DROP; descentLanding = goal.clone();
            } else if (preciseHeight > boundingBox.minY+.001) {
                precisePhase = PrecisePhase.EXIT_LIFT; preciseHeight += .02;
            }
            // Release the previous rung's velocity once. EXIT/LANDING subsequently
            // retain normal gravity, including while the body still touches a ladder.
            motionY = 0;
        }
        List<int[]> route = recoveringCorridor ? recoveryRoute : travelRoute;
        double originY = !enteringRoute && segmentEnd > routeIndex && segmentEnd < route.size()
                && goal == route.get(segmentEnd) ? nodeY(route.get(routeIndex)) : boundingBox.minY;
        preciseDirection = precisePhase == PrecisePhase.EXIT_LIFT ? 1 : (int)Math.signum(preciseHeight-originY);
        ladderStallTicks = 0;
        bestSegmentError = segmentError();
        if (verticalControl() && preciseDirection != 0) {
            if (!lastLadderCompleted && lastLadderDirection != 0 && preciseDirection != lastLadderDirection
                    && ticksExisted-lastLadderTick <= 20)
                ladderDiagnostic("changed_command before completion; previous=" + Arrays.toString(lastLadderGoal)
                        + " previousDirection=" + lastLadderDirection,0,0,motionY);
            lastLadderDirection = preciseDirection; lastLadderGoal = goal;
            lastLadderCompleted = false; lastLadderTick = ticksExisted;
        }
    }
    private double segmentError() {
        double dx = preciseGoal[0]+.5-posX, dz = preciseGoal[2]+.5-posZ;
        double vertical = precisePhase == PrecisePhase.CLIMB
                ? Math.max(0,preciseDirection*(preciseHeight-boundingBox.minY)) : Math.abs(preciseHeight-boundingBox.minY);
        return (precisePhase == PrecisePhase.EXIT_LIFT ? 0 : Math.sqrt(dx*dx+dz*dz))+vertical;
    }
    private boolean preciseMovementComplete() {
        List<int[]> route = recoveringCorridor ? recoveryRoute : travelRoute;
        if (precisePhase == PrecisePhase.CLIMB && segmentEnd+1 < route.size()
                && preciseGoal == route.get(segmentEnd) && !ladderNode(route.get(segmentEnd+1))
                && boundingBox.minY-nodeY(route.get(segmentEnd+1)) > (recoveringCorridor ? 1.001 : 2.001)) return false;
        if (atPreciseNode(preciseGoal,precisePhase == PrecisePhase.CLIMB))
            return precisePhase == PrecisePhase.CLIMB || onGround;
        if (precisePhase != PrecisePhase.CLIMB || preciseDirection == 0) return false;
        double crossed = preciseDirection*(boundingBox.minY-preciseSurfaceHeight);
        double dx = preciseGoal[0]+.5-posX, dz = preciseGoal[2]+.5-posZ;
        // Collision stepping can carry the feet past a rung. Consume a crossed
        // boundary only inside the clear, attached column; never steer back to it.
        return crossed >= 0 && crossed <= 1.001 && dx*dx+dz*dz <= PRECISE_HORIZONTAL*PRECISE_HORIZONTAL
                && ladder((int)Math.floor(posX),(int)Math.floor(boundingBox.minY),(int)Math.floor(posZ),-30000001,-1,0)
                && clearBody(posX,boundingBox.minY,posZ,-30000001,-1,0);
    }
    private void reachedRouteNode(int[] node) {
        navigationFailures = pathTicks = 0; bestRouteProgress = -Double.MAX_VALUE;
        if (reachedPositions.size() < 4096 && reachedPositions.add(positionKey(node[0],node[1],node[2]))) {
            objectiveTicks = 0;
            if (objectiveFailures > 0) { objectiveFailures = 0; diagnostic("reset","reached new route position"); }
        }
    }
    /** Only consume/issue already planned edges here. Route searches stay in work(). */
    private void completePreciseMovement() {
        if (preciseGoal == null || !preciseMovementComplete()
                || Math.abs(nodeY(preciseGoal)-preciseSurfaceHeight) > .001) return;
        int[] completed = preciseGoal;
        boolean climbing = verticalControl();
        if (onGround) descentLanding = null;
        preciseGoal = null; clearMovementCommands();
        if (climbing) { motionY = 0; lastLadderCompleted = true; }
        List<int[]> route = recoveringCorridor ? recoveryRoute : travelRoute;
        if (route.isEmpty()) return;
        if (enteringRoute && completed == route.get(0)) {
            enteringRoute = false; reachedRouteNode(completed);
        } else if (segmentEnd > routeIndex && completed == route.get(segmentEnd)) {
            routeIndex = segmentEnd; reachedRouteNode(completed);
        } else return; // A final interaction approach does not advance another edge.
        startRouteSegment(route);
    }
    private boolean failedMovementEdge(int[] from,int[] to) {
        java.util.Set<Long> failures = failedMovementEdges.get(positionKey(from[0],from[1],from[2]));
        return failures != null && failures.contains(positionKey(to[0],to[1],to[2]));
    }
    private void rejectMovementEdge(int[] from,int[] to) {
        long key = positionKey(from[0],from[1],from[2]);
        java.util.Set<Long> failures = failedMovementEdges.get(key);
        if (failures == null && failedMovementEdges.size() < 64) {
            failures = new java.util.HashSet<Long>(); failedMovementEdges.put(key,failures);
        }
        if (failures != null && failures.size() < 64) failures.add(positionKey(to[0],to[1],to[2]));
    }
    private void failPreciseMovement(String reason,double requestedY,double movedY,double nativeY) {
        List<int[]> route = recoveringCorridor ? recoveryRoute : travelRoute;
        int[] actual = {(int)Math.floor(posX),(int)Math.floor(boundingBox.minY+.001),(int)Math.floor(posZ)};
        int[] from = !enteringRoute && routeIndex < route.size() ? route.get(routeIndex) : actual;
        segmentFailure = movementPhase() + ": " + reason + "; edge=" + Arrays.toString(from)
                + " -> " + Arrays.toString(preciseGoal) + "; actual=" + posX+","+boundingBox.minY+","+posZ;
        ladderDiagnostic("segment failed: " + segmentFailure,requestedY,movedY,nativeY);
        boolean usableDrop = preciseGoal != null && ladderNode(from) && !ladderNode(preciseGoal)
                && nodeY(preciseGoal) < nodeY(from) && edgeFailure(from,preciseGoal,-30000001,-1,0) == EdgeFailure.NONE;
        if (preciseGoal != null && !usableDrop) {
            rejectMovementEdge(from,preciseGoal); rejectMovementEdge(actual,preciseGoal);
        }
        if (usableDrop && selectedEndpoint != null)
            failedEndpoints.remove(positionKey(selectedEndpoint[0],selectedEndpoint[1],selectedEndpoint[2]));
        navigationReason = "stalled"; navigationDetail = segmentFailure;
        boolean climbing = verticalControl();
        preciseGoal = null; clearMovementCommands(); if (climbing) motionY = 0;
        route.clear(); enteringRoute = false; waypoint = null; routeIndex = segmentEnd = 0;
        routeRetries++; pathTicks = 0; bestRouteProgress = -Double.MAX_VALUE;
        clearRouteSearch(); routeFailure(); status = recoveringCorridor ? "corridor_recovery" : "recovering";
        // Planning is deliberately deferred to work(), preserving target and task.
    }
    private void advanceWalkingMovement() {
        List<int[]> route = recoveringCorridor ? recoveryRoute : travelRoute;
        if (route.isEmpty() || enteringRoute || preciseGoal != null) return;
        if (routeIndex < segmentEnd && !walkingSegment) return;
        while (routeIndex < segmentEnd && segmentEnd < route.size()) {
            int[] next = route.get(routeIndex+1);
            if (getDistanceSq(next[0]+.5,nodeY(next),next[2]+.5) >= .36) break;
            routeIndex++; reachedRouteNode(next);
        }
        if (routeIndex == segmentEnd && routeIndex+1 < route.size()) {
            clearMovementCommands(); startRouteSegment(route);
        }
    }
    private void startRouteSegment(List<int[]> route) {
        if (routeIndex+1 >= route.size()) return;
        segmentEnd = routeIndex+1;
        int[] from = route.get(routeIndex), first = route.get(segmentEnd);
        Movement kind = movement(from,first);
        EdgeFailure failure = edgeFailure(from,first,-30000001,-1,0);
        if (failure != EdgeFailure.NONE) {
            preciseGoal = first; failPreciseMovement("transition changed: " + failure,0,0,motionY); return;
        }
        if (failedMovementEdge(from,first)) {
            preciseGoal = first; failPreciseMovement("previously rejected edge",0,0,motionY); return;
        }
        if (kind != Movement.WALK || nodeY(first) != first[1] || nodeY(from) != from[1]) {
            startPreciseMovement(first,kind == Movement.CLIMB || kind == Movement.LADDER_EXIT); return;
        }
        int dx = first[0]-from[0], dz = first[2]-from[2];
        while (segmentEnd+1 < route.size() && segmentEnd-routeIndex < 6) {
            int[] a = route.get(segmentEnd), b = route.get(segmentEnd+1);
            if (movement(a,b) != Movement.WALK || nodeY(b) != b[1] || b[0]-a[0] != dx || b[2]-a[2] != dz
                    || failedMovementEdge(a,b)) break;
            segmentEnd++;
        }
        preciseGoal = null; clearMovementCommands();
        PathPoint[] points = new PathPoint[segmentEnd-routeIndex+1];
        for (int i = 0; i < points.length; i++) {
            int[] node = route.get(routeIndex+i); points[i] = new PathPoint(node[0],node[1],node[2]);
        }
        waypoint = route.get(segmentEnd).clone();
        if (!segmentChunksLoaded(waypoint)) return; // work() owns unloaded-route recovery.
        if (!getNavigator().setPath(new PathEntity(points),1)) {
            preciseGoal = first; failPreciseMovement("native navigator rejected walking segment",0,0,motionY);
        } else walkingSegment = true;
    }
    private void ladderDiagnostic(String reason,double requestedY,double movedY,double nativeY) {
        long now = System.nanoTime();
        if (lastLadderLog != 0 && now-lastLadderLog < 10000000000L) return;
        lastLadderLog = now;
        XFLog.warn(taskDiagnostic("ladder " + reason) + " route=" + routeIndex + "/" + segmentEnd
                + " phase=" + movementPhase() + " precise=" + Arrays.toString(preciseGoal) + " feetY=" + boundingBox.minY
                + " direction=" + preciseDirection + " requestedY=" + requestedY + " movedY=" + movedY
                + " nativeMotionY=" + nativeY + " motionY=" + motionY + " noProgress=" + ladderStallTicks
                + " lastRequestedXZ=" + lastRequestedX+","+lastRequestedZ + " lastMovedXZ=" + lastMovedX+","+lastMovedZ);
    }
    @Override protected void updateAITasks() {
        // Clearing a path alone does not cancel EntityMoveHelper's queued command.
        // Consume a neutral command before vanilla's helper/jump phase can use it.
        boolean precise = !worldObj.isRemote && running && (preciseGoal != null || holdingLadder());
        if (precise) {
            getNavigator().clearPathEntity();
            getMoveHelper().setMoveTo(posX,Math.floor(boundingBox.minY+.5),posZ,0);
        }
        super.updateAITasks();
        if (precise) { setJumping(false); moveStrafing = moveForward = 0; }
    }
    /** Drive accepted special edges through ordinary collision/step resolution. */
    @Override public void moveEntityWithHeading(float strafe,float forward) {
        if (!worldObj.isRemote && running && task == Task.ESCAPE && escapeJumping) {
            motionX = motionZ = 0; super.moveEntityWithHeading(0,0); return;
        }
        if (!worldObj.isRemote && running) {
            if (descentLanding != null && onGround && preciseGoal == null) descentLanding = null;
            completePreciseMovement(); advanceWalkingMovement();
            strafe = moveStrafing; forward = moveForward;
        }
        if (!worldObj.isRemote && running && preciseGoal != null && segmentChunksLoaded(preciseGoal)) {
            double fy = nodeY(preciseGoal);
            if (!Double.isNaN(fy) && Math.abs(fy-preciseSurfaceHeight) <= .001) {
                double dx = preciseGoal[0]+.5-posX, dz = preciseGoal[2]+.5-posZ;
                double distance = Math.sqrt(dx*dx+dz*dz), scale = distance > .12 ? .12/distance : 1;
                motionX = dx*scale; motionZ = dz*scale;
                if (precisePhase == PrecisePhase.EXIT_LIFT && boundingBox.minY >= preciseHeight-.001) {
                    precisePhase = PrecisePhase.EXIT; motionY = 0; lastLadderCompleted = true;
                    bestSegmentError = segmentError(); ladderStallTicks = 0;
                }
                if (verticalControl()) {
                    double remaining = preciseHeight-boundingBox.minY;
                    // Direction belongs to this segment. Crossing its height never
                    // creates a command back toward the rung we just passed.
                    motionY = preciseDirection*remaining > 0
                            ? preciseDirection*Math.min(Math.abs(remaining),.12) : 0;
                    if (precisePhase == PrecisePhase.EXIT_LIFT) motionX = motionZ = 0;
                }
                if (precisePhase == PrecisePhase.EXIT && distance <= PRECISE_HORIZONTAL) precisePhase = PrecisePhase.LANDING;
                double beforeX = posX, beforeZ = posZ, beforeY = boundingBox.minY, requestedY = motionY;
                lastRequestedX = motionX; lastRequestedZ = motionZ;
                super.moveEntityWithHeading(0,0);
                lastMovedX = posX-beforeX; lastMovedZ = posZ-beforeZ;
                double movedY = boundingBox.minY-beforeY, nativeY = motionY;
                if (verticalControl()) {
                    // Vanilla adds gravity, or +.2 on horizontal ladder collision,
                    // AFTER moveEntity. Neither is the next ladder command.
                    motionY = 0;
                    if (preciseDirection*movedY < -.001)
                        ladderDiagnostic("unchanged_command failed to execute",requestedY,movedY,nativeY);
                    lastLadderTick = ticksExisted;
                }
                double error = segmentError();
                if (error < bestSegmentError-.001) { bestSegmentError = error; ladderStallTicks = 0; }
                else ladderStallTicks++;
                // Includes zero vertical commands and a horizontal exit that never
                // lands. Moving in a loop is not progress toward this segment.
                if (ladderStallTicks >= 20) {
                    failPreciseMovement(verticalControl() && Math.abs(requestedY) < .001
                            ? "zero vertical command outside completion bounds" : "no progress toward segment completion",requestedY,movedY,nativeY); return;
                }
                completePreciseMovement(); return;
            }
            failPreciseMovement("endpoint feet height changed",0,0,motionY);
        }
        boolean holdingLadder = holdingLadder();
        if (holdingLadder) { motionX = motionY = motionZ = 0; strafe = forward = 0; }
        super.moveEntityWithHeading(strafe,forward);
        if (holdingLadder) motionY = 0;
    }
    private boolean holdingLadder() {
        return !worldObj.isRemote && running && preciseGoal == null && getNavigator().noPath()
                && descentLanding == null && !getMoveHelper().isUpdating() && moveForward == 0 && moveStrafing == 0 && isOnLadder();
    }
    private boolean landingInProgress() { return descentLanding != null && (preciseGoal != null || !onGround); }
    private boolean atDestination(int x,int y,int z,boolean mining) {
        arrivalFailure = interactionFailure(posX,boundingBox.minY,posZ,x,y,z,mining,true);
        return arrivalFailure.isEmpty();
    }
    private boolean atWorkEndpoint(boolean mining) {
        return !mining || selectedEndpoint == null
                || atPreciseNode(selectedEndpoint,ladderNode(selectedEndpoint));
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
        if (accessProposals == null) refreshEndpointContext();
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
            travelRoute.clear(); clearMovementCommands(); routeRetries = navigationFailures = 0;
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
        clearMovementCommands(); travelRoute.clear(); waypoint = null;
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
        int[] goal = new int[] {x,y,z};
        if (!recovery) {
            if (!Arrays.equals(destination,goal)) { resetPath(); destination = goal; }
            rememberInteraction(x,y,z,mining);
            // Execute the endpoint whose post-harvest footing/exit was approved.
            // Merely entering reach of the target must not cancel the descent.
            if (preciseGoal == null && atWorkEndpoint(mining) && atDestination(x,y,z,mining) && (!mining && !constructionTarget(x,y,z)
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
            clearMovementCommands(); preciseGoal = null; status = recovery ? movingStatus : "chunk";
            navigationReason = "chunk"; pathTicks = 0;
            if (!recovery) return false;
            // A different loaded entry may remain reachable. Replan inside the original bounds.
            route.clear(); waypoint = null; routeRetries++;
        }
        if (!route.isEmpty()) {
            if (enteringRoute) {
                int[] entry = route.get(0);
                startPreciseMovement(entry,ladderNode(entry));
                status = recovery ? "corridor_recovery" : isOnLadder() ? ladderActivity()
                        : nodeY(entry) < boundingBox.minY-.1 ? "descending" : "final_approach";
                return false;
            }
        }
        if (!route.isEmpty()) {
            int[] next = route.get(Math.min(routeIndex+1,route.size()-1));
            if (!recovery) movingStatus = movement(route.get(routeIndex),next) == Movement.CLIMB ? ladderActivity()
                    : movement(route.get(routeIndex),next) == Movement.DESCEND ? "descending"
                    : movement(route.get(routeIndex),next) == Movement.STEP ? "stepping"
                    : mining && (posX < plan[3] || posX > plan[5]+1 || posZ < plan[4] || posZ > plan[6]+1 || boundingBox.minY > y) ? "walking_rim" : task.key;
            double progress = routeIndex-Math.min(1,Math.sqrt(getDistanceSq(next[0]+.5,nodeY(next),next[2]+.5)));
            if (progress > bestRouteProgress+.1) { bestRouteProgress = progress; pathTicks = 0; }
            else pathTicks += 10;
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
                if (preciseGoal != null) failPreciseMovement(navigationDetail,0,0,motionY);
                else {
                    if (intact) rejectMovementEdge(route.get(routeIndex),next);
                    clearMovementCommands(); route.clear(); routeRetries++; pathTicks = 0;
                    clearRouteSearch(); routeFailure();
                }
            } else if (preciseGoal != null) {
                Movement kind = movement(route.get(routeIndex),preciseGoal);
                status = kind == Movement.CLIMB ? ladderActivity() : kind == Movement.DESCEND ? "descending"
                        : kind == Movement.STEP ? "stepping" : "final_approach"; return false;
            } else if (!getNavigator().noPath()) { status = movingStatus; return false; }
            else if (routeIndex+1 >= route.size()) {
                // A valid path endpoint is still not arrival unless the interaction check above succeeds.
                atDestination(x,y,z,mining);
                navigationReason = arrivalFailure; navigationDetail = endpointDiagnostic(arrivalFailure);
                int[] end = route.get(route.size()-1);
                if (!recovery && preciseGoal == null && (mining && !atWorkEndpoint(true)
                        || "reach".equals(arrivalFailure) || "sight".equals(arrivalFailure) || "footing".equals(arrivalFailure))
                        && interactionFailure(end[0]+.5,nodeY(end),end[2]+.5,x,y,z,mining,false).isEmpty()
                        && finalApproachClear(end)) {
                    startPreciseMovement(end,ladderNode(end)); status = "final_approach"; return false;
                }
                if (!recovery && failedEndpoints.size() < 64) failedEndpoints.add(positionKey(end[0],end[1],end[2]));
                preciseGoal = null; clearMovementCommands(); route.clear(); routeRetries++;
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
            List<int[]> goals = recovery ? java.util.Collections.<int[]>emptyList()
                    : standingCandidates(x,y,z,mining);
            if (!recovery && goals.isEmpty()) {
                navigationReason = failedEndpoints.isEmpty() ? "standing" : arrivalFailure;
                navigationDetail = failedEndpoints.isEmpty() ? standingObstruction(x,y,z) : endpointDiagnostic(arrivalFailure);
            }
            else {
                // Search complete routes first, including direct approaches; a verified cached
                // route remains a safe fallback when the bounded search cannot rediscover it.
                if (recovery) recoveryAttempts++;
                List<int[]> found = recovery ? searchRoute(goals,-30000001,-1,z,true)
                        : executableRoute(goals,x,y,z,mining);
                if (!recovery && routeSearchPending) { status = "searching"; return false; }
                if (found != null && !found.isEmpty()) {
                    if (recovery) {
                        clearMovementCommands(); preciseGoal = null;
                        route.addAll(found); routeIndex = segmentEnd = 0;
                        enteringRoute = !atPreciseNode(found.get(0),ladderNode(found.get(0)));
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
        if (enteringRoute && !route.isEmpty()) {
            int[] entry = route.get(0);
            startPreciseMovement(entry,ladderNode(entry));
            status = recovery ? "corridor_recovery" : nodeY(entry) < boundingBox.minY-.1 ? "descending" : "final_approach";
            return false;
        }
        startRouteSegment(route);
        status = preciseGoal != null ? verticalControl() ? ladderActivity() : "descending" : movingStatus;
        return false;
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
        return standingCandidates(x,y,z,mining,true);
    }
    private List<int[]> standingCandidates(int x,int y,int z,boolean mining,boolean filterFailures) {
        return standingCandidates(x,y,z,mining,filterFailures,true);
    }
    private List<int[]> standingCandidates(int x,int y,int z,boolean mining,boolean filterFailures,boolean interaction) {
        List<int[]> result = new java.util.ArrayList<int[]>();
        for (int dx = -3; dx <= 3; dx++) for (int dz = -3; dz <= 3; dz++) for (int dy = -3; dy <= 3; dy++) {
            if (dx*dx+dz*dz > 9) continue;
            int sx = x+dx, sy = y+dy, sz = z+dz;
            if (filterFailures && failedEndpoints.contains(positionKey(sx,sy,sz)) && Arrays.equals(interactionTarget,new int[] {x,y,z})) continue;
            double fy = feet(sx,sy,sz,mining ? x : -30000001,mining ? y-1 : -1,z);
            if (Double.isNaN(fy)) continue;
            if (interaction ? !interactionFailure(sx+.5,fy,sz+.5,x,y,z,mining,false).isEmpty()
                    : !inCorridor(sx,sz) || dx*dx+(fy-y)*(fy-y)+dz*dz > 9
                        || !solidFooting(sx+.5,fy,sz+.5,mining ? x : -30000001,mining ? y-1 : -1,z)) continue;
            // A different floor exposed by removal is not the position we can
            // occupy before harvesting. Choose another supported endpoint.
            double before = mining ? feet(sx,sy,sz,-30000001,-1,0) : fy;
            if (Double.isNaN(before) || Math.abs(fy-before) > .06) continue;
            result.add(new int[] {sx,sy,sz});
        }
        java.util.Collections.sort(result,(a,b) -> Double.compare(getDistanceSq(a[0]+.5,a[1],a[2]+.5),getDistanceSq(b[0]+.5,b[1],b[2]+.5)));
        return result;
    }
    private List<int[]> workPositions() {
        int[] goal = accessSearchTarget != null ? accessSearchTarget
                : buildingAccess && accessDestination != null ? accessDestination : workDestination();
        if ((pendingDrops != null || workDestination() == null) && workPositionTarget != null) goal = workPositionTarget;
        if (goal == null) return java.util.Collections.emptyList();
        final int[] retainedGoal = goal;
        // Discover actual terrain, never a proposed platform or a surface origin.
        java.util.Map<Long,int[]> overlay = new java.util.HashMap<Long,int[]>(proposedAccess);
        proposedAccess.clear();
        List<int[]> positions;
        // Floor discovery must survive an obstructed sight line to the retained
        // block. Mining still selects its own fully validated interaction endpoint.
        try { positions = standingCandidates(goal[0],goal[1],goal[2],true,false,false); }
        finally { proposedAccess.putAll(overlay); }
        java.util.Collections.sort(positions,(a,b) -> {
            int support = Boolean.compare(obsoleteLadderSupport(a[0],a[1]-1,a[2]),obsoleteLadderSupport(b[0],b[1]-1,b[2]));
            if (support != 0) return support;
            int height = Integer.compare(Math.abs(a[1]-(retainedGoal[1]-1)),Math.abs(b[1]-(retainedGoal[1]-1)));
            return height != 0 ? height : Double.compare(getEndpointDistance(a,retainedGoal),getEndpointDistance(b,retainedGoal));
        });
        if (Arrays.equals(workPositionTarget,goal) && workPosition != null)
            for (int i = 0; i < positions.size(); i++) if (Arrays.equals(positions.get(i),workPosition)
                    && !obsoleteLadderSupport(workPosition[0],workPosition[1]-1,workPosition[2])) {
                positions.add(0,positions.remove(i)); break;
            }
        workPositionTarget = goal.clone();
        workPosition = positions.isEmpty() ? null : positions.get(0).clone();
        return positions;
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
        return solidFooting(x,y,z,rx,ry,rz);
    }
    private boolean solidFooting(double x,double y,double z,int rx,int ry,int rz) {
        AxisAlignedBB sole = AxisAlignedBB.getBoundingBox(x-width/2,y-.06,z-width/2,x+width/2,y+.001,z+width/2);
        if (!collisions(sole,rx,ry,rz,recoveryCollisions,false)) return false;
        for (AxisAlignedBB shape : recoveryCollisions) if (Math.abs(shape.maxY-y) <= .06) return true;
        return false;
    }
    /** Join the graph from the real supported body, which may straddle a pit edge.
     * Flooring the entity coordinate is not proof that the center of that cell has a floor. */
    private boolean safeStart(int[] node,int rx,int ry,int rz,boolean recovery) {
        if (proposedAccess.isEmpty() && failedMovementEdge(new int[] {(int)Math.floor(posX),
                (int)Math.floor(boundingBox.minY+.001),(int)Math.floor(posZ)},node)) return false;
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
        if (ladderNode(a) && !ladderNode(b)) return Movement.LADDER_EXIT;
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
        return (!proposedAccess.isEmpty() || !failedMovementEdge(a,b)) && edgeFailure(a,b,rx,ry,rz) == EdgeFailure.NONE;
    }
    private EdgeFailure edgeFailure(int[] a,int[] b,int rx,int ry,int rz) {
        int horizontal = Math.abs(a[0]-b[0])+Math.abs(a[2]-b[2]);
        double ay = feet(a[0],a[1],a[2],rx,ry,rz), by = feet(b[0],b[1],b[2],rx,ry,rz);
        if (Double.isNaN(ay) || Double.isNaN(by)) return EdgeFailure.FOOTING;
        if (by-ay > stepHeight+.001) return EdgeFailure.RISE;
        if (ay-by > 2.001) return EdgeFailure.DROP;
        if (horizontal > 1) return EdgeFailure.SPAN;
        if (horizontal == 0) {
            boolean fromLadder = ladder(a[0],a[1],a[2],rx,ry,rz), toLadder = ladder(b[0],b[1],b[2],rx,ry,rz);
            boolean climb = Math.abs(a[1]-b[1]) == 1 && fromLadder && toLadder;
            boolean drop = fromLadder && !toLadder && by < ay-.001;
            if (!climb && !drop) return EdgeFailure.LADDER;
        }
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
                if (proposedAccess.isEmpty()) {
                    java.util.Set<Long> failures = failedMovementEdges.get(key);
                    if (failures != null && failures.contains(next)) continue;
                }
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
        refreshEndpointContext();
        int rx = mining ? x : -30000001, ry = mining ? y-1 : -1;
        boolean construction = constructionTarget(x,y,z);
        if (!mining && !construction && (pendingDrops == null || storageTask())) return searchRoute(goals,-30000001,-1,0);
        int proposal = accessProposals == null ? -1 : accessProposalIndex;
        long layout = endpointLayout;
        if (!Arrays.equals(routeSearchTarget,new int[] {x,y,z}) || routeSearchProposal != proposal
                || routeSearchLayout != layout || routeSearchX != posX || routeSearchY != boundingBox.minY || routeSearchZ != posZ) {
            routeSearchTarget = new int[] {x,y,z}; routeSearchProposal = proposal;
            routeSearchRemaining = new java.util.ArrayList<int[]>(goals);
            routeSearchLayout = layout; routeSearchX = posX; routeSearchY = boundingBox.minY; routeSearchZ = posZ;
            routeExitLimited = false;
        }
        routeSearchPending = false;
        // Three endpoint checks are a per-update budget, never an eligibility cutoff.
        for (int attempt = 0; attempt < 3 && !routeSearchRemaining.isEmpty(); attempt++) {
            // Travel happens before harvesting: never walk through hypothetical air.
            boolean descending = !construction && mining && ladderLanding != null && boundingBox.minY >= ladderLanding[1]-.1;
            List<int[]> found = descending ? fixedLadderTravel(routeSearchRemaining,null,-30000001,-1,0,true) : null;
            String descentFailure = descending && found == null ? navigationDetail : "";
            boolean descentLimited = !descentFailure.isEmpty() && ("search_limit".equals(navigationReason) || "detour_limit".equals(navigationReason));
            if (found == null) found = searchRoute(routeSearchRemaining,-30000001,-1,0,false,null,construction);
            if (found == null && !construction) found = cachedTravel(routeSearchRemaining,-30000001,-1,0);
            if (found == null) {
                if (!descentFailure.isEmpty()) {
                    navigationDetail += "; fixed descent: " + descentFailure;
                    if (descentLimited && !"chunk".equals(navigationReason)) navigationReason = "search_limit";
                }
                if (construction) accessFailure("approach",accessPlan.get(accessIndex),navigationDetail);
                else navigationDetail = "Work approach failed; target=" + x+","+(y-1)+","+z + "; " + navigationDetail;
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
            List<int[]> exit = stationRoute(end,rx,ry,z);
            if (exit != null) {
                if (mining) { workPosition = end.clone(); workPositionTarget = new int[] {x,y,z}; }
                if (accessProposals == null) clearAccessSearch();
                java.util.Collections.reverse(exit); returnRoute.clear(); returnRoute.addAll(exit); clearRouteSearch(); return found;
            }
            routeExitLimited |= "search_limit".equals(navigationReason) || "detour_limit".equals(navigationReason);
            navigationDetail = "Post-harvest exit failed; removed=" + x+","+(y-1)+","+z
                    + "; work=" + Arrays.toString(end) + "; " + navigationDetail;
            routeSearchRemaining.removeIf(p -> Arrays.equals(p,end));
        }
        if (!routeSearchRemaining.isEmpty()) { routeSearchPending = true; return null; }
        if (construction) status = "route_failed";
        if (!construction) {
            navigationReason = routeExitLimited ? "return_limit" : "return_blocked";
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
        return stationRoute(start,-30000001,-1,0);
    }
    private List<int[]> stationRoute(int[] start,int rx,int ry,int rz) {
        List<int[]> exits = standingCandidates(plan[0],plan[1],plan[2],false);
        if (exits.isEmpty()) {
            navigationReason = "standing"; navigationDetail = "No storage endpoint near " + plan[0]+","+plan[1]+","+plan[2]; return null;
        }
        boolean below = ladderLanding != null && (start == null ? boundingBox.minY : start[1]) < ladderLanding[1]-.1;
        List<int[]> fixed = below ? fixedLadderTravel(exits,start,rx,ry,rz,false) : null;
        if (fixed != null) return fixed;
        String fixedFailure = below ? navigationDetail : "";
        boolean fixedLimited = below && ("search_limit".equals(navigationReason) || "detour_limit".equals(navigationReason));
        List<int[]> route = searchRoute(exits,rx,ry,rz,false,start);
        if (route == null && !fixedFailure.isEmpty()) {
            navigationDetail += "; fixed ladder: " + fixedFailure;
            if (fixedLimited && !"chunk".equals(navigationReason)) navigationReason = "search_limit";
        }
        return route;
    }
    /** Use the committed column as waypoints, with the same edge rules as A*.
     * This is a route proof only; it never changes the live interaction/task. */
    private List<int[]> fixedLadderTravel(List<int[]> goals,int[] start,int rx,int ry,int rz,boolean descending) {
        if (ladderPosition == null || ladderLanding == null) return null;
        int bottom = Integer.MAX_VALUE, top = Integer.MIN_VALUE;
        for (int[] p : ladderAccess) if (p[3] > 0 && fixedRung(p)) { bottom = Math.min(bottom,p[1]); top = Math.max(top,p[1]); }
        if (bottom == Integer.MAX_VALUE) return null;
        List<int[]> column = new java.util.ArrayList<int[]>();
        for (int y = bottom; y <= top; y++) column.add(new int[] {ladderPosition[0],y,ladderPosition[1]});
        column.add(ladderLanding.clone());
        if (descending) java.util.Collections.reverse(column);
        for (int i = 0; i < column.size(); i++) {
            int[] p = column.get(i), from = column.get(Math.max(0,i-1));
            EdgeFailure rule = i == 0 ? standing(p[0],p[1],p[2],rx,ry,rz) ? EdgeFailure.NONE : EdgeFailure.FOOTING
                    : edgeFailure(from,p,rx,ry,rz);
            if (rule != EdgeFailure.NONE) {
                navigationReason = "path";
                navigationDetail = (i == 1 && descending || i == column.size()-1 && !descending ? "Upper landing/ladder" : "Ladder column")
                        + "; rejected=" + Arrays.toString(from) + " -> " + Arrays.toString(p) + " rule=" + rule;
                return null;
            }
        }
        // Join the upper landing through a searched route. Forcing every journey
        // through the historical bottom can command descent followed by ascent
        // when today's floor is above that bottom.
        List<int[]> route = searchRoute(java.util.Collections.singletonList(ladderLanding),rx,ry,rz,false,start);
        if (route == null) {
            navigationDetail = (descending ? "Upper landing approach" : "Work floor to ladder") + "; " + navigationDetail; return null;
        }
        List<int[]> tail = searchRoute(goals,rx,ry,rz,false,ladderLanding);
        if (tail == null) {
            navigationDetail = (descending ? "Ladder bottom to mining position" : "Upper landing to station") + "; " + navigationDetail; return null;
        }
        for (int i = 1; i < tail.size(); i++) route.add(tail.get(i));
        return route;
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
        if (!hadExit && (constructionRefuge.isEmpty() || !staircaseAccess && task != Task.ESCAPE)) {
            // AOE can sever yesterday's lower refuge after the builder has
            // climbed. Retain today's actual supported working area as well;
            // repairing one stage must not require the whole old exit first.
            List<int[]> previousRefuge = new java.util.ArrayList<int[]>(constructionRefuge);
            constructionRefuge.clear();
            int sx = (int)Math.floor(posX), sy = (int)Math.floor(boundingBox.minY+.001), sz = (int)Math.floor(posZ);
            for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) for (int dy = -1; dy <= 1; dy++) {
                int[] p = new int[] {sx+dx,sy+dy,sz+dz};
                if (inCorridor(p[0],p[2]) && safeStart(p,-30000001,-1,0,true)) constructionRefuge.add(p);
            }
            for (int[] p : previousRefuge) if (constructionRefuge.size() < 27 && standing(p[0],p[1],p[2],-30000001,-1,0)) {
                boolean duplicate = false;
                for (int[] current : constructionRefuge) if (Arrays.equals(current,p)) { duplicate = true; break; }
                if (!duplicate) constructionRefuge.add(p);
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
        for (int[] p : ladderPosition == null ? accessBlocks : ladderAccess) if (p[0] == x && p[1] == y && p[2] == z) return true;
        for (int[] p : accessPlan) if (p[0] == x && p[1] == y && p[2] == z) return true;
        if (ladderPosition != null && x == ladderPosition[0] && z == ladderPosition[1]) return true;
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
        if (p[3] < 0) return routeBlock(p[0],p[1],p[2],-30000001,-1,0) == net.minecraft.init.Blocks.air
                || ladderPosition != null && p[0] == ladderPosition[0] && p[2] == ladderPosition[1]
                        && accessPresent(new int[] {p[0],p[1],p[2],ladderPosition[2]});
        if (p[3] == 0 && ladderPosition != null) {
            net.minecraftforge.common.util.ForgeDirection d = net.minecraftforge.common.util.ForgeDirection.getOrientation(ladderPosition[2]);
            if (p[0] == ladderPosition[0]-d.offsetX && p[2] == ladderPosition[1]-d.offsetZ)
                return solidAttachment(p[0],p[1],p[2],d,-30000001,-1,0);
            if (ladderLanding != null && p[0] == ladderLanding[0] && p[1] == ladderLanding[1]-1 && p[2] == ladderLanding[2])
                return solidFooting(ladderLanding[0]+.5,ladderLanding[1],ladderLanding[2]+.5,-30000001,-1,0);
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
        for (int[] retained : ladderPosition == null ? accessBlocks : ladderAccess) if (retained[3] >= 0 && retained[0] == p[0] && retained[1] == p[1] && retained[2] == p[2]) return false;
        if (ladderPosition != null) {
            if (p[0] == ladderPosition[0] && p[2] == ladderPosition[1]) return false;
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
        return rung || ladderPosition != null || task == Task.ESCAPE || repair ? count : Math.max(0,count-emergencyBlocks());
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
        if (ladderPosition != null) requireLadderCells(cells);
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
            // A target's local approach is not a reason to replace a usable fixed exit.
            if (ladderPosition != null) { clearAccessSearch(); return false; }
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
            clearMovementCommands(); preciseGoal = null; travelRoute.clear(); waypoint = null;
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
                if (stairs && !retainedTarget) {
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
                        if (!resumeStorageTrip() && approach != null) useRoute(approach);
                        return true;
                    }
                    accessPlan.clear(); accessIndex = 0; buildingAccess = false; reconsideringLadder = false;
                    repairAfterRestock = false;
                    staircaseAccess = repairingLadder = false; constructionRefuge.clear(); failedAccessOperations.clear();
                    clearAccessSearch(); resetPath();
                    if (!resumeStorageTrip()) {
                        transition(Task.TO_WORK,"existing ladder verified",new int[] {x,y,z});
                        if (approach != null) useRoute(approach);
                        status = "using_ladder";
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
            boolean same = p[0] == rung[0] && p[1] == rung[1] && p[2] == rung[2];
            if (!exit && nodeY(p) > bottom+.01 && (same || safeEdge(rung,p,-30000001,-1,0))) {
                exit = stationRoute(p) != null;
                if (!exit) storageFailure = "Upper ladder landing " + Arrays.toString(p) + " has no storage connection: " + navigationDetail;
            }
            if (entry && exit) return true;
        }
        if (entry && exit) return true;
        navigationReason = "access_blocked";
        accessFailure("connections",cells.get(0),!entry ? "Ladder bottom at Y=" + bottom + " has no usable descent and return connection to the current exposed floor"
                : !storageFailure.isEmpty() ? storageFailure : "Ladder band has no usable existing upper landing; its own backing alone is not an exit");
        ladderObstruction = accessBlocker;
        return false;
    }
    private boolean ladderFloorRoute(List<int[]> cells) {
        int bottom = Integer.MAX_VALUE;
        for (int[] p : cells) if (p[3] > 0) bottom = Math.min(bottom,p[1]);
        List<int[]> rungs = new java.util.ArrayList<int[]>();
        for (int[] p : cells) if (p[3] > 0 && p[1] <= bottom+1) rungs.add(Arrays.copyOf(p,3));
        List<int[]> positions = workPositions();
        if (positions.isEmpty()) {
            navigationReason = "standing";
            navigationDetail = "Work-floor entry has no supported mining position near retained target=" + Arrays.toString(workPositionTarget);
            return false;
        }
        int[] goal = workPositionTarget;
        for (int[] rung : rungs) {
            // Drops are directed. Prove return independently through the maintained
            // ladder, rather than demanding that every entrance edge reverse.
            List<int[]> route = searchRoute(positions,goal[0],goal[1]-1,goal[2],false,rung);
            if (route == null) continue;
            int[] floor = route.get(route.size()-1);
            if (searchRoute(rungs,goal[0],goal[1]-1,goal[2],false,floor) != null) {
                workPosition = floor.clone(); return true;
            }
            navigationDetail = "Floor return to maintained ladder from " + Arrays.toString(floor) + "; " + navigationDetail;
        }
        navigationDetail = "Ladder bottom/work-floor connection; work=" + Arrays.toString(workPosition)
                + "; removed=" + goal[0]+","+(goal[1]-1)+","+goal[2] + "; " + navigationDetail;
        return false;
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
        collectLadderRequirements();
        if (ladderAccess.isEmpty()) return false;
        boolean missing = false;
        for (int[] p : ladderAccess) if (!accessPresent(p)) { missing = true; break; }
        // Intact historical rungs do not prove entry from today's exposed floor.
        if (!missing && ladderRouteAvailable()) {
            buildingAccess = staircaseAccess = repairingLadder = false; accessPlan.clear(); accessIndex = 0;
            repairAfterRestock = false; clearAccessSearch();
            return false;
        }
        // A broken exit cannot be a prerequisite for repairing that exit. Each
        // edit is instead checked from reachable actual builder positions.
        accessPlan.clear();
        for (int[] p : ladderAccess) accessPlan.add(p.clone());
        staircaseAccess = false; repairingLadder = true;
        reconcileAccessPlan(); buildingAccess = true;
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
    private static boolean sameAccessCell(int[] a,int[] b) {
        return a[0] == b[0] && a[1] == b[1] && a[2] == b[2];
    }
    private boolean fixedBacking(int[] cell) {
        if (ladderPosition == null) return false;
        net.minecraftforge.common.util.ForgeDirection d = net.minecraftforge.common.util.ForgeDirection.getOrientation(ladderPosition[2]);
        if (cell[0] != ladderPosition[0]-d.offsetX || cell[2] != ladderPosition[1]-d.offsetZ) return false;
        for (int[] p : ladderAccess) if (p[3] > 0 && fixedRung(p) && p[1] == cell[1]) return true;
        return false;
    }
    private boolean ladderPassage(int[] cell) {
        if (ladderPosition == null) return false;
        if (cell[0] == ladderPosition[0] && cell[2] == ladderPosition[1]) return true;
        if (ladderLanding != null && cell[0] == ladderLanding[0] && cell[2] == ladderLanding[2]
                && cell[1] >= ladderLanding[1] && cell[1] <= ladderLanding[1]+1) return true;
        for (int[] p : ladderAccess) if (p[3] != 0 && sameAccessCell(p,cell)) return true;
        return false;
    }
    private boolean requireLadderCell(int[] cell) {
        if (cell[3] > 0 && !fixedRung(cell) || cell[3] == 0 && ladderPassage(cell)) return false;
        if (cell[3] == 0 && !fixedBacking(cell) && !(ladderLanding != null
                && cell[0] == ladderLanding[0] && cell[1] == ladderLanding[1]-1 && cell[2] == ladderLanding[2])) return false;
        for (int[] p : ladderAccess) if (sameAccessCell(p,cell)) {
            if (p[3] == cell[3] || cell[3] == 0 || cell[3] < 0 && p[3] > 0) return false;
            p[3] = cell[3]; return true;
        }
        ladderAccess.add(cell.clone()); return true;
    }
    private void requireLadderCells(List<int[]> cells) {
        for (int[] p : cells) if (p[3] >= 0) requireLadderCell(p);
        for (int[] p : cells) if (p[3] < 0) {
            boolean replacement = false;
            for (int[] other : cells) if (other[3] >= 0 && sameAccessCell(other,p)) { replacement = true; break; }
            if (!replacement) requireLadderCell(p);
        }
    }
    /** Reserve body clearance to an existing floor. The final rungs provide
     * access; this search never adds footing, platforms or attachment chains. */
    private void reserveLadderEntry(List<int[]> cells) {
        if (workPositions().isEmpty()) return;
        int floor = workPosition[1];
        java.util.Map<Long,int[]> saved = new java.util.HashMap<Long,int[]>(proposedAccess);
        try {
            proposedAccess.clear();
            for (int[] p : cells) proposedAccess.put(positionKey(p[0],p[1],p[2]),p);
            if (ladderFloorRoute(cells)) return;
            for (int span = 1; span <= 5; span++) for (int dx = -span; dx <= span; dx++) for (int sign : new int[] {-1,1}) {
                int dz = sign*(span-Math.abs(dx));
                if (sign == 1 && dz == 0) continue;
                for (int order = 0; order < 2; order++) {
                    List<int[]> clearance = new java.util.ArrayList<int[]>();
                    int x = ladderPosition[0], z = ladderPosition[1];
                    int ax = x+dx, az = z+dz;
                    boolean valid = true;
                    for (int distance = 0; valid && distance <= span; distance++) {
                        for (int h = 0; valid && h <= 1; h++) {
                            int[] cell = {x,floor+h,z,-1};
                            int[] existing = proposedAccess.get(positionKey(x,floor+h,z));
                            if (existing != null && existing[3] > 0) continue;
                            if (fixedBacking(cell) || !accessArea(x,floor+h,z)
                                    || !accessPresent(cell) && !accessHarvestable(cell,true)) { valid = false; break; }
                            if (!accessPresent(cell)) {
                                clearance.add(cell); proposedAccess.put(positionKey(x,floor+h,z),cell);
                            }
                        }
                        // Every sole needs existing terrain; air below the body
                        // cannot authorize another bridge or platform.
                        if (valid && Double.isNaN(feet(x,floor,z,-30000001,-1,0))) valid = false;
                        if (distance == span) break;
                        if (order == 0 && x != ax || order == 1 && z == az) x += Integer.signum(ax-x);
                        else z += Integer.signum(az-z);
                    }
                    if (valid && !clearance.isEmpty() && ladderFloorRoute(cells)) {
                        for (int[] cell : clearance) requireLadderCell(cell);
                        return;
                    }
                    proposedAccess.clear();
                    for (int[] p : cells) proposedAccess.put(positionKey(p[0],p[1],p[2]),p);
                }
            }
        } finally { proposedAccess.clear(); proposedAccess.putAll(saved); }
    }
    private boolean obsoleteLadderSupport(int x,int y,int z) {
        if (ladderPosition == null || fixedBacking(new int[] {x,y,z,0})
                || ladderLanding != null && x == ladderLanding[0] && y == ladderLanding[1]-1 && z == ladderLanding[2]) return false;
        for (int[] p : accessBlocks) if (p[3] == 0 && p[0] == x && p[1] == y && p[2] == z) return true;
        return false;
    }
    /** Rebuild from the current fixed column, floor and upper landing every time.
     * History identifies the column's extent; it never authorizes old connectors. */
    private void collectLadderRequirements() {
        if (ladderPosition == null) return;
        int bottom = Integer.MAX_VALUE, top = Integer.MIN_VALUE;
        for (List<int[]> source : java.util.Arrays.asList(ladderAccess,accessBlocks,accessPlan))
            for (int[] p : source) if (p[3] > 0 && fixedRung(p)) {
                bottom = Math.min(bottom,p[1]); top = Math.max(top,p[1]);
            }
        if (top == Integer.MIN_VALUE) return;
        // Storage/repair travel does not move the underground work floor. Extend
        // only to actual supported terrain near the retained unfinished target.
        if (!workPositions().isEmpty()) bottom = Math.max(plan[8],Math.min(bottom,workPosition[1]));
        ladderAccess.clear();
        net.minecraftforge.common.util.ForgeDirection d = net.minecraftforge.common.util.ForgeDirection.getOrientation(ladderPosition[2]);
        if (ladderLanding != null && (ladderLanding[0] == ladderPosition[0]-d.offsetX && ladderLanding[2] == ladderPosition[1]-d.offsetZ
                || Math.abs(ladderLanding[0]-ladderPosition[0])+Math.abs(ladderLanding[2]-ladderPosition[1]) > 1)) ladderLanding = null;
        for (int y = bottom; y <= top; y++) {
            requireLadderCell(new int[] {ladderPosition[0],y,ladderPosition[1],ladderPosition[2]});
            int[] backing = {ladderPosition[0]-d.offsetX,y,ladderPosition[1]-d.offsetZ,0};
            ladderAccess.removeIf(p -> p[3] < 0 && sameAccessCell(p,backing));
            if (accessArea(backing[0],y,backing[2])) requireLadderCell(backing);
        }
        if (accessArea(ladderPosition[0],top+1,ladderPosition[1]))
            requireLadderCell(new int[] {ladderPosition[0],top+1,ladderPosition[1],-1});
        if (ladderLanding == null) {
            // Include damaged historical landings, but only a one-step exit at
            // the upper end with a station connection. Never adopt a floating tread.
            List<int[]> candidates = new java.util.ArrayList<int[]>();
            for (int y = top+1; y >= top-1; y--) for (int side = 0; side < 4; side++)
                candidates.add(new int[] {ladderPosition[0]+(side == 0 ? 1 : side == 1 ? -1 : 0),y,
                        ladderPosition[1]+(side == 2 ? 1 : side == 3 ? -1 : 0)});
            for (int[] landing : candidates) {
                if (landing[1] <= bottom) continue;
                List<int[]> historical = new java.util.ArrayList<int[]>();
                boolean recordedFooting = false, repairable = true;
                for (int dy = -1; dy <= 1; dy++) {
                    int[] cell = {landing[0],landing[1]+dy,landing[2],dy < 0 ? 0 : -1};
                    if (dy >= 0 && fixedBacking(cell) || dy < 0 && ladderPassage(cell)) repairable = false;
                    if (dy < 0) for (int[] p : accessBlocks) if (Arrays.equals(p,cell)) { recordedFooting = true; break; }
                    if (!accessPresent(cell) && (!accessArea(cell[0],cell[1],cell[2])
                            || !worldObj.isAirBlock(cell[0],cell[1],cell[2]) && !accessHarvestable(cell,true))) repairable = false;
                    historical.add(cell);
                }
                if (!repairable || !recordedFooting && (!standing(landing[0],landing[1],landing[2],-30000001,-1,0)
                        || Math.abs(nodeY(landing)-landing[1]) > .001)) continue;
                try {
                    for (int[] p : ladderAccess) proposedAccess.put(positionKey(p[0],p[1],p[2]),p);
                    for (int[] p : historical) proposedAccess.put(positionKey(p[0],p[1],p[2]),p);
                    int[] upperRung = {ladderPosition[0],top,ladderPosition[1]};
                    if (!safeEdge(upperRung,landing,-30000001,-1,0) || !safeEdge(landing,upperRung,-30000001,-1,0)
                            || stationRoute(landing) == null) continue;
                    ladderLanding = landing;
                } finally { proposedAccess.clear(); }
                break;
            }
        }
        if (ladderLanding != null) for (int dy = -1; dy <= 1; dy++) {
            int[] p = {ladderLanding[0],ladderLanding[1]+dy,ladderLanding[2],dy < 0 ? 0 : -1};
            if (accessArea(p[0],p[1],p[2])) requireLadderCell(p);
        }
        reserveLadderEntry(ladderAccess);
        if (task != Task.ESCAPE) {
            int blocks = 0, rungs = 0;
            for (int[] p : ladderAccess) if (!accessPresent(p)) {
                if (p[3] == 0) blocks++;
                else if (p[3] > 0 && !rungPresent(p)) rungs++;
            }
            restockBlocks = Math.min(restockBlocks,blocks); restockLadders = Math.min(restockLadders,rungs);
        }
    }
    /** Reconcile requirements, not just BreakEvents: backing loss can detach a
     * rung without firing its own break event. Never restore ordinary terrain. */
    private void reconcileAccessPlan() {
        collectLadderRequirements();
        java.util.Map<Long,int[]> required = new java.util.LinkedHashMap<Long,int[]>();
        for (int[] p : ladderAccess) required.put(positionKey(p[0],p[1],p[2]),p.clone());
        if (ladderPosition == null) for (int[] p : accessPlan) required.put(positionKey(p[0],p[1],p[2]),p.clone());
        List<int[]> clear = new java.util.ArrayList<int[]>(), supports = new java.util.ArrayList<int[]>(), rungs = new java.util.ArrayList<int[]>();
        for (int[] p : required.values()) {
            if (p[3] < 0) clear.add(p);
            else {
                if (!accessPresent(p) && !rungPresent(p) && worldObj.blockExists(p[0],p[1],p[2]) && !worldObj.isAirBlock(p[0],p[1],p[2]))
                    clear.add(new int[] {p[0],p[1],p[2],-1});
                (p[3] == 0 ? supports : rungs).add(p);
            }
        }
        accessPlan.clear(); accessPlan.addAll(clear); accessPlan.addAll(supports); accessPlan.addAll(rungs);
        // Work upwards. Clear as high as the current working stage can reach
        // before restoring its lower structure, reducing AOE destruction of fresh
        // repairs. Unreachable upper clearance never gates a lower replacement.
        final int workingY = (int)Math.floor(boundingBox.minY+.001);
        java.util.Collections.sort(accessPlan,(a,b) -> {
            int ay = a[3] < 0 && a[1] <= workingY+2 ? workingY-1 : a[1];
            int by = b[3] < 0 && b[1] <= workingY+2 ? workingY-1 : b[1];
            int order = Integer.compare(ay,by);
            if (order != 0) return order;
            if (a[3] < 0 && b[3] < 0) return Integer.compare(b[1],a[1]);
            return Integer.compare(a[3],b[3]);
        });
        accessIndex = 0;
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
        for (int i = accessIndex+1; i < accessPlan.size(); i++) if (!accessOperationComplete(accessPlan.get(i))
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
        for (int[] p : accessPlan) if (p[3] < 0 && p[0] == cell[0] && p[2] == cell[2]
                && (p[1] == cell[1] || cell[3] > 0 && p[1] == cell[1]+1) && !accessOperationComplete(p)) return false;
        if (cell[3] > 0) {
            net.minecraftforge.common.util.ForgeDirection d = net.minecraftforge.common.util.ForgeDirection.getOrientation(cell[3]);
            return solidAttachment(cell[0]-d.offsetX,cell[1],cell[2]-d.offsetZ,d,-30000001,-1,0);
        }
        return true;
    }
    private boolean accessOperationComplete(int[] cell) {
        if (accessPresent(cell)) return true;
        if (cell[3] < 0) for (int[] p : accessPlan) if (p[3] >= 0
                && p[0] == cell[0] && p[1] == cell[1] && p[2] == cell[2] && accessPresent(p)) return true;
        return false;
    }
    /** A rung has one backing cell on the committed face. Missing attachment
     * never authorizes extra chains, pillars or a replacement access layout. */
    private boolean scheduleAttachment(int[] cell) {
        if (ladderPosition == null || staircaseAccess || task == Task.ESCAPE || cell[3] <= 0) return false;
        net.minecraftforge.common.util.ForgeDirection d = net.minecraftforge.common.util.ForgeDirection.getOrientation(ladderPosition[2]);
        int[] backing = {cell[0]-d.offsetX,cell[1],cell[2]-d.offsetZ,0};
        for (int[] p : accessPlan) if (Arrays.equals(p,backing)) return false;
        if (!accessArea(backing[0],backing[1],backing[2]) || !requireLadderCell(backing)) return false;
        reconcileAccessPlan(); return true;
    }
    /** Geometry proof only; actual traversal is checked during useful travel. */
    private boolean ladderRouteAvailable() {
        return ladderPosition != null && ladderLanding != null && ladderFloorRoute(ladderAccess)
                && fixedLadderTravel(standingCandidates(plan[0],plan[1],plan[2],false),workPosition,
                        workPositionTarget[0],workPositionTarget[1]-1,workPositionTarget[2],false) != null;
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
    private int supportRequirement() { return ladderPosition != null && task != Task.ESCAPE ? accessNeeded(false) : Math.max(restockBlocks,accessNeeded(false)); }
    private int ladderRequirement() {
        if (ladderPosition == null) return 0;
        int need = 0;
        for (int[] rung : ladderAccess) if (rung[3] > 0 && fixedRung(rung) && !rungPresent(rung)) need++;
        return Math.max(accessNeeded(true),need);
    }
    private void beginEscape() {
        if (ladderRequirement() <= accessCount(true) && supportRequirement() <= accessCount(false)) {
            accessFailure("escape",null,"Required supplies are carried; retain fixed-ladder repair instead of escape construction");
            blockObjective("no_access"); return;
        }
        if (ladderPosition != null) {
            requestedBlocks = Math.max(0,supportRequirement()-accessCount(false));
            requestedLadders = Math.max(0,ladderRequirement()-accessCount(true));
            if (!accessSupplyChecked) checkAccessStation();
            if (accessStationReachable) {
                restockLadders = ladderRequirement(); restockBlocks = supportRequirement();
                beginStorage(requestedLadders > 0 ? StorageReason.LADDERS : StorageReason.ACCESS);
            } else {
                navigationReason = "access_supplies";
                accessFailure("supplies",accessIndex < accessPlan.size() ? accessPlan.get(accessIndex) : null,
                        "Fixed-ladder repair needs " + requestedBlocks + " support blocks and " + requestedLadders
                        + " ladders beyond carried supplies; station route unavailable: " + navigationDetail);
                blockObjective("no_access");
            }
            return;
        }
        restockLadders = Math.max(restockLadders,Math.max(ladderRequirement(),accessNeeded(true)));
        restockBlocks = Math.max(restockBlocks,accessNeeded(false));
        storageReason = accessCount(true) < restockLadders ? StorageReason.LADDERS : StorageReason.ACCESS;
        escapeOrigin = new int[] {(int)Math.floor(posX),(int)Math.floor(boundingBox.minY+.001),(int)Math.floor(posZ)};
        escapeColumn = 0; escapePillar = escapeDestination = null; escapeJumping = false;
        // Retain completed terrain and the job target, replacing only unbuilt proposals.
        buildingAccess = reconsideringLadder = staircaseAccess = false; accessPlan.clear(); accessIndex = 0;
        resumeStorageAfterAccess = false; failedAccessOperations.clear(); constructionRefuge.clear();
        clearAccessSearch(); clearRouteSearch(); resetPath(); navigationBlocked = false;
        transition(Task.ESCAPE,"required access supplies unavailable in carried inventory",new int[] {plan[0],plan[1],plan[2]});
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
                beginStorage(storageReason); useRoute(exit); return;
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
        for (int i = 0; i < accessIndex; i++) if (!accessOperationComplete(accessPlan.get(i))) {
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
            if (accessOperationComplete(p)) {
                if (accessPresent(p)) retainAccess(p);
                accessIndex++; continue;
            }
            if (!accessArea(p[0],p[1],p[2]) || (p[3] < 0 ? !accessHarvestable(p,task != Task.ESCAPE) : !worldObj.isAirBlock(p[0],p[1],p[2]) && !rungPresent(p))) {
                navigationReason = "access_blocked"; accessFailure("cells",p,"Access cell occupied/unavailable: " + worldObj.getBlock(p[0],p[1],p[2]).getUnlocalizedName());
                deferAccessOperation(p); return;
            }
            if (p[3] < 0) { clearAccessCell(p); return; }
            if (!staircaseAccess && !clearanceOrderAllows(p)) {
                if (p[3] > 0 && placementSide(p) < 0) {
                    if (scheduleAttachment(p)) return;
                    accessFailure("attachment",p,"Rung requires its actual fixed backing before placement");
                } else accessFailure("clearance",p,"Placement waits for its own cell/body clearance, not clearance of the entire ladder");
                deferAccessOperation(p); return;
            }
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
                if (scheduleAttachment(p)) { accessStage = "attachment"; status = accessActivity(); return; }
                navigationReason = "placement"; accessFailure("attachment",p,"Missing actual authorized attachment for this required backing or upper landing; no extra supports will be built");
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
            status = accessActivity(); accessStage = p[3] > 0 ? "rungs" : "backing";
            if (!placeAccess(p)) {
                navigationReason = "placement";
                // Exhaust actual builder positions before giving up this edit.
                failedEndpoints.add(positionKey((int)Math.floor(posX),(int)Math.floor(boundingBox.minY+.001),(int)Math.floor(posZ)));
                if (selectedEndpoint != null) failedEndpoints.add(positionKey(selectedEndpoint[0],selectedEndpoint[1],selectedEndpoint[2]));
                resetPath(); clearRouteSearch(); selectedEndpoint = null; return;
            }
            retainAccess(p);
            if (p[3] > 0 && ladderPosition == null) {
                ladderPosition = new int[] {p[0],p[2],p[3]};
                requireLadderCells(accessPlan);
                retainLadderLandings(accessPlan);
            }
            accessIndex++; returnRoute.clear(); rejectedTargets.clear(); reachedPositions.clear();
            failedAccessOperations.clear(); clearRouteSearch();
            accessSupplyChecked = false;
            usefulProgress("placed supplied access cell"); return;
        }
        if (!staircaseAccess && ladderPosition != null) {
            collectLadderRequirements();
            for (int[] required : ladderAccess) if (!accessPresent(required)) {
                reconcileAccessPlan();
                resetPath(); clearRouteSearch(); failedAccessOperations.clear(); return;
            }
            if (ladderLanding == null) {
                int top = Integer.MIN_VALUE;
                for (int[] p : ladderAccess) if (p[3] > 0 && fixedRung(p)) top = Math.max(top,p[1]);
                accessFailure("verify_access",new int[] {ladderPosition[0],top,ladderPosition[1]},"Fixed ladder has no verified upper landing connected to storage");
                blockObjective("no_access"); return;
            }
            if (!ladderRouteAvailable()) {
                navigationReason = "search_limit".equals(navigationReason) ? "return_limit" : "return_blocked";
                accessFailure("verify_access",workPosition,"Repaired geometry has no complete work-floor/ladder/station route: " + navigationDetail);
                blockObjective("no_access"); return;
            }
        }
        buildingAccess = false; accessPlan.clear(); accessIndex = 0; requestedBlocks = requestedLadders = 0;
        restockLadders = restockBlocks = 0;
        repairAfterRestock = false;
        accessRepairPending = false; staircaseAccess = repairingLadder = false;
        constructionRefuge.clear(); failedAccessOperations.clear();
        clearAccessSearch(); clearRouteSearch();
        failedEndpoints.clear(); accessAttempts = 0; returnRoute.clear();
        navigationReason = navigationDetail = arrivalFailure = "";
        ladderObstruction = accessBlocker = accessPlacementFailure = "";
        if (!resumeStorageTrip()) returnToWork("supplied access completed");
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
            if ("route_failed".equals(status)) {
                accessFailure("unreachable_clearance",p,"No reachable safe builder position for this obstruction: " + navigationDetail);
                deferAccessOperation(p);
            }
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
        if (!harvested || !accessPresent(p)) {
            // finishHarvest may have rebuilt the operation objects after partial AOE.
            for (int[] current : accessPlan) if (Arrays.equals(current,p)) { p = current; break; }
            accessFailure("clearance",p,"Tool action did not clear this obstruction; inspect harvest refusal separately from placement"); deferAccessOperation(p); return;
        }
        // Keep a replacement at this coordinate if this was its prerequisite.
        boolean replacement = false;
        for (int[] required : accessPlan) if (required[3] >= 0 && required[0] == p[0] && required[1] == p[1] && required[2] == p[2]) { replacement = true; break; }
        if (!replacement) {
            retainAccess(p);
            if (!staircaseAccess && task != Task.ESCAPE) requireLadderCell(p);
        }
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
        for (int[] p : ladderAccess) if (accessPresent(p) || rungPresent(p)) before.add(p);
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
        // Always inspect canonical requirements after a tool action, including
        // neighbor-detached ladders without a matching BreakEvent.
        collectLadderRequirements();
        boolean ladderDamage = false;
        for (int[] p : ladderAccess) if (!accessPresent(p)) { ladderDamage = true; break; }
        if (ladderDamage) accessRepairPending = true;
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
        rejectedTargets.clear(); reachedPositions.clear(); failedEndpoints.clear(); failedMovementEdges.clear();
        failedAccessOperations.clear();
        selectingTarget = false; searchOffset = accessAttempts = 0;
        interactionTarget = selectedEndpoint = plannedEntrance = null; arrivalFailure = "";
        accessSupplyChecked = false;
        if (buildingAccess && !staircaseAccess && task != Task.ESCAPE) reconcileAccessPlan();
        else if (buildingAccess || task == Task.ESCAPE) {
            for (int i = 0; i < accessIndex; i++) if (!accessPresent(accessPlan.get(i))) { accessIndex = i; break; }
        }
        usefulProgress("terrain changed by tool harvest");
    }
    private boolean placeAccess(int[] p) {
        if (p[3] < 0 || p[3] > 0 && !fixedRung(p)) return accessPlacementFailure(p,"placement","Operation is not a placement on the fixed route");
        if (p[3] == 0 && ladderPassage(p)) return accessPlacementFailure(p,"clearance","Support would obstruct the reserved ladder passage");
        int slot = -1; for (int i = 0; i < cargo.length; i++) if (accessMaterial(cargo[i],p[3] != 0)) { slot = i; break; }
        if (slot < 0) return accessPlacementFailure(p,"supplies","Missing supplied " + (p[3] > 0 ? "ladder" : "support block"));
        if (accessShape(p).intersectsWith(boundingBox) || !authorized() || !accessArea(p[0],p[1],p[2]) || !worldObj.isAirBlock(p[0],p[1],p[2])
                || worldObj.getTileEntity(p[0],p[1],p[2]) != null || BuilderPlacement.protectedBlock(worldObj,p[0],p[1],p[2]))
            return accessPlacementFailure(p,"placement","Placement body, ownership, bounds or occupied/protected-cell check failed before placement");
        ItemStack material = cargo[slot], offered = material.copy(); offered.stackSize = 1;
        net.minecraft.item.ItemBlock item = (net.minecraft.item.ItemBlock)material.getItem();
        Block block = item.field_150939_a;
        int side = placementSide(p);
        if (side < 0) return accessPlacementFailure(p,"attachment","No actual authorized attachment before placement");
        net.minecraftforge.common.util.ForgeDirection direction = net.minecraftforge.common.util.ForgeDirection.getOrientation(side);
        int bx = p[0]-direction.offsetX, by = p[1]-direction.offsetY, bz = p[2]-direction.offsetZ;
        FakePlayer player = actor(); player.setPosition(posX,posY,posZ); player.onGround = onGround;
        if (!solidAttachment(bx,by,bz,direction,-30000001,-1,0) || worldObj.getTileEntity(bx,by,bz) != null
                || BuilderPlacement.protectedBlock(worldObj,bx,by,bz) || !WorkerAccess.ownsLand(worldObj,bx,bz,factionId)
                || !player.canPlayerEdit(p[0],p[1],p[2],side,offered)
                || !worldObj.canPlaceEntityOnSide(block,p[0],p[1],p[2],false,side,null,offered))
            return accessPlacementFailure(p,"placement","Native edit/placement or actual attachment check failed before PlaceEvent");
        net.minecraftforge.common.util.BlockSnapshot before = net.minecraftforge.common.util.BlockSnapshot.getBlockSnapshot(worldObj,p[0],p[1],p[2]);
        ItemStack previous = player.inventory.mainInventory[0]; player.inventory.mainInventory[0] = offered;
        boolean accepted = false;
        try {
            int meta = block.onBlockPlaced(worldObj,p[0],p[1],p[2],side,.5F,.5F,.5F,item.getMetadata(offered.getItemDamage()));
            if (item.placeBlockAt(offered,player,worldObj,p[0],p[1],p[2],side,.5F,.5F,.5F,meta)) {
                net.minecraftforge.event.world.BlockEvent.PlaceEvent event = net.minecraftforge.event.ForgeEventFactory.onPlayerBlockPlace(player,before,direction);
                accepted = !event.isCanceled() && worldObj.getBlock(p[0],p[1],p[2]) == block && accessPresent(p);
                if (!accepted) accessPlacementFailure(p,event.isCanceled() ? "event_cancelled" : "placement",
                        event.isCanceled() ? "Forge PlaceEvent canceled this placement; restored previous block" : "Placed block did not satisfy the access requirement; rolled back");
            } else accessPlacementFailure(p,"placement","Native ItemBlock placement returned false before PlaceEvent");
        } finally {
            player.inventory.mainInventory[0] = previous;
            if (!accepted) before.restore(true,false);
        }
        if (accepted) {
            accessPlacementFailure = "";
            failedMovementEdges.clear();
            if (--material.stackSize <= 0) cargo[slot] = null;
        }
        return accepted;
    }
    private boolean accessPlacementFailure(int[] p,String stage,String reason) {
        accessPlacementFailure = p[0]+", "+p[1]+", "+p[2]+": " + reason;
        accessFailure(stage,p,reason); return false;
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
        List<int[]> route = stationRoute(null,removedX,removedY,removedZ);
        if (route != null) {
            java.util.Collections.reverse(route); returnRoute.addAll(route); navigationReason = navigationDetail = ""; return true;
        }
        if (!"chunk".equals(status)) {
            navigationReason = "search_limit".equals(navigationReason) || "detour_limit".equals(navigationReason) ? "return_limit" : "return_blocked";
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
        if (task != Task.RESTOCKING)
            transition(Task.DEPOSITING,"at storage: " + storageReason.key,new int[] {plan[0],plan[1],plan[2]});
        accessSupplyChecked = accessStationReachable = true;
        long beforeSupplies = supplyStamp();
        equipCarriedTool();
        stockTorchesFromCargo();
        // Retain precisely the materials reserved for the interrupted construction.
        // Otherwise a missing-ladder wait deposits and withdraws the same backing
        // blocks on every visit and can mistake its own reserved cargo for full storage.
        boolean reserveAccess = cursor < totalWork() || rampCleanup >= 0 || pendingDrops != null && !pendingDropsCaptured;
        int keepBlocks = reserveAccess ? Math.max(supportRequirement(),emergencyBlocks()) : 0;
        int keepLadders = reserveAccess ? Math.max(2,Math.max(restockLadders,ladderRequirement())) : 0;
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
            status = "access_materials"; supplyCheckTicks = 40; return;
        }
        if (storageReason == StorageReason.LADDERS || storageReason == StorageReason.ACCESS && task == Task.RESTOCKING && escapeOrigin != null) {
            restockLadders = restockBlocks = 0; sentLadderReminder = false; repairAfterRestock = true;
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
                status = "access_materials"; supplyCheckTicks = 40; return;
            }
        }
        if (beforeSupplies != supplyStamp()) usefulProgress("restocked required supplies");
        returnToWork("deposit and required restocking finished");
    }
    private void stockAccess(IInventory chest,boolean rung) {
        for (int i = firstSlot(chest); i < chest.getSizeInventory(); i++) {
            // Optional reserves may be replenished on an already required visit;
            // only supportRequirement/ladderRequirement can initiate or delay it.
            int need = (rung ? Math.max(2,Math.max(restockLadders,Math.max(ladderRequirement(),accessNeeded(true))))
                    : Math.max(supportRequirement(),emergencyBlocks()))-accessCount(rung);
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
        return "occupied=" + occupied + "/" + cargo.length + " emptySlots=" + (cargo.length-occupied) + " partialStackCapacity=" + partialCapacity;
    }
    private void updateRequests() {
        requestedTools = requestedTorches = 0;
        requestedBlocks = requestedLadders = 0;
        if (worldObj.isRemote || !configured || !authorized() || jobDimension != dimension) return;
        if (buildingAccess || restockLadders > 0 || storageReason == StorageReason.ACCESS && storageTask()) {
            requestedBlocks = Math.max(0,supportRequirement()-accessCount(false));
            requestedLadders = Math.max(0,Math.max(restockLadders,accessNeeded(true))-accessCount(true));
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
        tag.setString("MovementPhase",movementPhase()); tag.setString("SegmentFailure",segmentFailure);
        tag.setInteger("RouteIndex",routeIndex); tag.setInteger("SegmentEnd",segmentEnd);
        tag.setInteger("SegmentStallTicks",ladderStallTicks);
        if (preciseGoal != null) tag.setIntArray("PreciseGoal",Arrays.copyOf(preciseGoal,3));
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
        tag.setInteger("RestockBlocks",restockBlocks);
        tag.setInteger("BlocksCarried",accessCount(false)); tag.setInteger("EmergencyBlocks",emergencyBlocks());
        tag.setBoolean("RepairAfterRestock",repairAfterRestock);
        tag.setInteger("EscapeColumn",escapeColumn);
        if (escapeOrigin != null) tag.setIntArray("EscapeOrigin",escapeOrigin);
        if (escapePillar != null) tag.setIntArray("EscapePillar",escapePillar);
        if (escapeDestination != null) tag.setIntArray("EscapeDestination",escapeDestination);
        tag.setBoolean("BuildingAccess",buildingAccess || !accessPlan.isEmpty() && (accessProposals != null || reconsideringLadder)); tag.setInteger("AccessIndex",accessIndex);
        tag.setBoolean("StaircaseAccess",staircaseAccess); tag.setBoolean("StaircaseInstalled",staircaseInstalled);
        tag.setBoolean("RepairingLadder",repairingLadder);
        tag.setInteger("AccessPolicyVersion",4);
        if (workPosition != null) tag.setIntArray("WorkPosition",workPosition);
        if (workPositionTarget != null) tag.setIntArray("WorkPositionTarget",workPositionTarget);
        if (buildingAccess && accessIndex < accessPlan.size()) tag.setIntArray("AccessOperation",Arrays.copyOf(accessPlan.get(accessIndex),3));
        if (ladderLanding != null) tag.setIntArray("LadderLanding",ladderLanding);
        tag.setTag("ConstructionRefuge",writeAccess(constructionRefuge));
        tag.setInteger("LadderPositionVersion",1);
        if (ladderPosition != null) tag.setIntArray("LadderPosition",ladderPosition);
        tag.setString("AccessBlocker",accessBlocker);
        tag.setString("AccessPlacementFailure",accessPlacementFailure);
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
        tag.setString("CargoCapacity",cargoCapacityDetail()); tag.setString("CapacityDetail",capacityDetail);
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
        tag.setTag("MinerLadderAccess",writeAccess(ladderAccess));
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
        endpointOrigin = null;
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
        capacityDetail = job.getString("CapacityDetail");
        // Legacy Returning encoded both partial cargo and supply trips. Reevaluate actual needs
        // on Start rather than translating that ambiguous boolean into a mandatory deposit.
        int layer = (plan[5] - plan[3] + 1) * (plan[6] - plan[4] + 1);
        targetCursor = job.hasKey("WorkCursor") ? job.getInteger("WorkCursor") : -1;
        if (layer <= 0 || targetCursor < cursor || targetCursor / layer != cursor / layer) targetCursor = -1;
        int[] savedWork = job.getIntArray("WorkPosition"), savedWorkTarget = job.getIntArray("WorkPositionTarget");
        workPosition = configured && savedWork.length == 3 && inCorridor(savedWork[0],savedWork[2])
                && savedWork[1] >= plan[8] && savedWork[1] <= plan[7]+1 ? savedWork : null;
        workPositionTarget = configured && savedWorkTarget.length == 3 && inCorridor(savedWorkTarget[0],savedWorkTarget[2])
                && savedWorkTarget[1] >= plan[8] && savedWorkTarget[1] <= plan[7]+1 ? savedWorkTarget : null;
        if (workPositionTarget == null) workPosition = null;
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
        readAccess(tag.getTagList("MinerLadderAccess",10),ladderAccess,4096);
        for (java.util.Iterator<int[]> it = ladderAccess.iterator(); it.hasNext();) {
            int[] p = it.next(); if (p[3] > 0 && !fixedRung(p)) it.remove();
        }
        int[] upperLanding = job.getIntArray("LadderLanding");
        ladderLanding = ladderPosition != null && upperLanding.length == 3 && inCorridor(upperLanding[0],upperLanding[2])
                && upperLanding[1] >= plan[8] && upperLanding[1] <= plan[7]+1 ? upperLanding : null;
        // Retire legacy verification trips; rebuild geometry from the actual position.
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
        accessBlocker = ladderObstruction = accessPlacementFailure = "";
        accessIndex = Math.max(0,Math.min(accessPlan.size(),job.getInteger("AccessIndex")));
        buildingAccess = job.getBoolean("BuildingAccess") && !accessPlan.isEmpty(); accessAttempts = 0;
        int[] accessGoal = job.getIntArray("AccessDestination"); accessDestination = accessGoal.length == 3 ? accessGoal : null;
        interactionTarget = selectedEndpoint = preciseGoal = null; failedEndpoints.clear(); proposedAccess.clear();
        accessSupplyChecked = false; accessStationReachable = true;
        resumeStorageAfterAccess = job.getBoolean("ResumeStorageAfterAccess");
        accessStorageReason = StorageReason.NONE;
        for (StorageReason reason : StorageReason.values()) if (reason.key.equals(job.getString("AccessStorageReason"))) accessStorageReason = reason;
        restockLadders = Math.max(0,Math.min(512,job.getInteger("RestockLadders")));
        restockBlocks = Math.max(0,Math.min(4096,job.getInteger("RestockBlocks")));
        repairAfterRestock = job.getBoolean("RepairAfterRestock");
        escapeColumn = Math.max(0,Math.min(81,job.getInteger("EscapeColumn")));
        int[] origin = job.getIntArray("EscapeOrigin"), pillar = job.getIntArray("EscapePillar"), landing = job.getIntArray("EscapeDestination");
        escapeOrigin = origin.length == 3 && inCorridor(origin[0],origin[2]) ? origin : null;
        escapePillar = pillar.length == 4 && pillar[0] >= plan[3] && pillar[0] <= plan[5] && pillar[2] >= plan[4] && pillar[2] <= plan[6]
                && pillar[1] >= plan[8] && pillar[3] > pillar[1] && pillar[3] <= plan[7]+1 && pillar[3]-pillar[1] <= 16 ? pillar : null;
        escapeDestination = landing.length == 3 && inCorridor(landing[0],landing[2]) ? landing : null;
        escapeJumping = false; escapeJumpTicks = 0; accessSupplies = Long.MIN_VALUE;
        sentLadderReminder = false;
        if (ladderPosition != null && job.getInteger("AccessPolicyVersion") < 4) {
            // Saved spare-material requests and verification/staircase travel do
            // not survive as commands. Reevaluate the actual fixed route first.
            restockLadders = restockBlocks = 0;
            if (task == Task.ESCAPE) task = Task.TO_WORK;
            buildingAccess = staircaseAccess = repairingLadder = false;
            accessPlan.clear(); accessIndex = 0;
            constructionRefuge.clear();
            escapeOrigin = escapePillar = escapeDestination = null;
        }
        descentLanding = null;
        accessRepairPending = true;
        running = configured && job.getBoolean("Running"); status = running ? task.key : "paused";
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
