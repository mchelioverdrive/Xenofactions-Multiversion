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
    // Legacy depot reference X/Y/Z, min X/Z, max X/Z, top Y, bottom Y.
    // Travel and inventory ownership live in depots/activeDepot, independently of work.
    private int[] plan = new int[PLAN_SIZE];
    private static final int MAX_DEPOTS = 256;
    private static final class Depot {
        final int dimension, x, y, z;
        Depot(int dimension,int x,int y,int z) { this.dimension=dimension; this.x=x; this.y=y; this.z=z; }
        int[] position() { return new int[] {x,y,z}; }
        public boolean equals(Object other) {
            if (!(other instanceof Depot)) return false;
            Depot d = (Depot)other; return dimension == d.dimension && x == d.x && y == d.y && z == d.z;
        }
        public int hashCode() { return ((dimension*31+x)*31+y)*31+z; }
    }
    private final List<Depot> depots = new java.util.ArrayList<Depot>();
    private Depot activeDepot;
    private final List<Depot> reachableSupplyDepots = new java.util.ArrayList<Depot>();
    // True records an exhausted route; false records a depot visited without resolving the need.
    private final java.util.Map<Depot,Boolean> triedDepots = new java.util.HashMap<Depot,Boolean>();
    private long depotWaitSupplies;
    private boolean waitingForDepot;
    private String depotFailure = "depots_unavailable";
    private String depotFailureDetail = "";
    private long depotDecisionStamp = Long.MIN_VALUE;
    private long depotTraceStamp = Long.MIN_VALUE;
    // Transient requirements for the next real repair stage, not the entire column.
    private int stageSupports = -1, stageLadders = -1;
    private int[] supplyOperation;
    private String supplyBlocker = "";
    private int cursor, pathTicks, jobDimension;
    private boolean configured, running, toolBroken;
    private enum Task {
        MINING("excavate"), TO_STORAGE("to_storage"), DEPOSITING("deposit"), RESTOCKING("restock"), TO_WORK("to_work"), DROPS("drops"), COMPLETE("complete");
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
    // Cursor traversal is not proof of excavation: required access remains work.
    private final java.util.BitSet retainedTargets = new java.util.BitSet();
    private final java.util.BitSet releasedTargets = new java.util.BitSet();
    private final java.util.BitSet rejectedRetainedTargets = new java.util.BitSet();
    private int retainedTarget = -1;
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
    private enum RecoveryPhase { IDLE, REPATH, LOCAL, REPAIR, WAIT }
    private enum PrecisePhase { FLOOR, CLIMB, LADDER_DESCEND, EXIT_LIFT, EXIT, DROP, LANDING }
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
    // One selected entrance footprint, retained while its floor is still current.
    private final List<int[]> ladderEntrance = new java.util.ArrayList<int[]>();
    private int[] ladderLanding;
    // Mining feet and target survive temporary storage/construction interactions.
    private int[] workPosition, workPositionTarget;
    private final List<int[]> accessPlan = new java.util.ArrayList<int[]>();
    private int accessIndex, accessAttempts, requestedBlocks, requestedLadders;
    private boolean buildingAccess;
    private boolean staircaseAccess, staircaseInstalled;
    // Committed by a verified existing column or a real accepted rung.
    private int[] ladderPosition;
    // A proposal's column is temporary; only accepted/existing rungs commit it.
    private int[] planningLadder;
    private final List<int[]> discoveredLadders = new java.util.ArrayList<int[]>();
    private int ladderDiscoveryColumn;
    private boolean ladderDiscoveryComplete, repairingLadder, reconsideringLadder;
    private long staircaseLadderSupplies = Long.MIN_VALUE;
    private final java.util.Set<int[]> failedAccessOperations = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<int[],Boolean>());
    private final List<int[]> constructionRefuge = new java.util.ArrayList<int[]>();
    private String accessBlocker = "", ladderObstruction = "", accessPlacementFailure = "";
    private int[] accessDestination;
    private int accessTripFloor = -1;
    private boolean accessSupplyChecked, accessStationReachable = true;
    // Task owns the trip; destination/interactionTarget belong only to its current action.
    private final NavigationRecovery movementRecovery = new NavigationRecovery();
    private java.util.Map<Long,Double> searchHeights;
    private int searchRemovedX, searchRemovedY, searchRemovedZ;
    // Failure belongs to the unfinished job, not to a transient task or selected block.
    private static final int OBJECTIVE_FAILURE_LIMIT = 24, OBJECTIVE_TICK_LIMIT = 12000;
    private int objectiveFailures, objectiveTicks;
    private boolean navigationBlocked, selectingTarget;
    private long lastObjectiveLog, lastRouteLog, lastAccessLog, lastResetLog, blockedSupplies;
    // Record actual terrain consulted by the planner, including failed approaches. While
    // blocked, inspect a bounded slice instead of running the same searches every update.
    private final java.util.Map<Long,int[]> watchedTerrain = new java.util.LinkedHashMap<Long,int[]>();
    private java.util.Iterator<int[]> terrainChecks;
    private int[] blockedPosition;
    private boolean blockedSettling;
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
    private String feetRejection = "";
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
    private int restockLadders, restockBlocks;
    private long accessSupplies = Long.MIN_VALUE;
    private boolean sentLadderReminder;
    private long lastLadderReminder;
    private boolean repairAfterRestock;
    private boolean accessRepairPending;
    // Recovery is subordinate to the saved job; it never owns the excavation cursor.
    private int[] localRecoveryCell, localRecoveryMove;
    private final List<int[]> localRecoveryEdits = new java.util.ArrayList<int[]>();
    private EntityItem localRecoveryDrop;
    private boolean gatheringSupport;
    private int supplySearch;
    private int supplyProtected, supplyWrongTool, supplyUnreachable, supplyUnsuitable;
    private int[] supplyOrigin;
    private String recoveryAction = "", recoveryBlocker = "";
    private final java.util.Set<Long> rejectedRecovery = new java.util.HashSet<Long>();
    private final java.util.Set<String> unsuitableRecoveryDrops = new java.util.HashSet<String>();
    // Separate from planner watches: hardness progress and endpoint searches may clear those.
    private final java.util.Map<Long,int[]> settlingTerrain = new java.util.LinkedHashMap<Long,int[]>();
    private final java.util.Map<Long,int[]> ladderTerrain = new java.util.HashMap<Long,int[]>();
    private int settlingQuietTicks;
    private static final int ATTACHMENT_DISTANCE = 2, ATTACHMENT_OPERATIONS = 65536, ATTACHMENT_CELLS = 32;
    private int attachmentOperations;
    // Transient, opt-in server diagnostics; no history allocation while disabled.
    private java.util.ArrayDeque<String> debugHistory;
    private String debugLastEvent = "", debugLastRejection = "", debugState = "", movementDecision = "initial";
    private int debugDirection, debugReversals, debugProgress;
    private int[] gravityTarget;
    private final java.util.Map<Long,int[]> gravityRemoved = new java.util.HashMap<Long,int[]>();
    private final List<int[]> gravitySweeps = new java.util.ArrayList<int[]>();

    public void setDebugTracing(boolean enabled) {
        if (!enabled) { debugHistory = null; debugLastEvent = debugLastRejection = debugState = ""; return; }
        if (debugHistory != null) return;
        debugHistory = new java.util.ArrayDeque<String>(200);
        debugDirection = debugReversals = debugProgress = 0;
        movementDecision = "tracing enabled during " + movementPhase();
        trace("state",movementDecision); traceState();
    }
    private void trace(String kind,String detail) {
        if (debugHistory == null) return;
        String key = kind + ": " + detail;
        if (key.equals(debugLastEvent) || "rejected".equals(kind) && key.equals(debugLastRejection)) return;
        if ("rejected".equals(kind)) debugLastRejection = key;
        if (detail.length() > 2048) detail = detail.substring(0,2048) + " [truncated]";
        debugLastEvent = key;
        if (debugHistory.size() == 200) debugHistory.removeFirst();
        debugHistory.addLast("tick=" + ticksExisted + " dim=" + dimension + " pos=" + posX+","+boundingBox.minY+","+posZ
                + " state=" + task.key + "/" + status + " objective=" + cursor + "/" + targetCursor
                + " target=" + Arrays.toString(interactionTarget) + " [" + kind + "] " + detail);
    }
    private void traceState() {
        String current = task.key + "/" + status + " running=" + running + " blocked=" + navigationBlocked
                + " objective=" + cursor + "/" + targetCursor + " repair=" + buildingAccess
                + " operation=" + accessIndex + " recovery=" + recoveryAction;
        if (!current.equals(debugState)) { debugState = current; trace("state",current); }
    }
    public List<String> minerDebugReport(boolean movementOnly) {
        List<String> lines = new java.util.ArrayList<String>();
        lines.add("Miner id=" + getEntityId() + " uuid=" + getUniqueID() + " dim=" + dimension + " tracing=" + (debugHistory != null));
        lines.add(taskDiagnostic("report") + " status=" + status + " running=" + running + " blocked=" + navigationBlocked);
        lines.add("Saved work=" + Arrays.toString(workDestination()) + " cursor=" + cursor + " selected=" + targetCursor
                + " retained=" + retainedTarget + "/" + retainedTargets.cardinality()
                + " taskDestination=" + Arrays.toString(taskDestination()) + " temporary=" + temporaryActivity()
                + " action=" + Arrays.toString(interactionTarget));
        lines.add("Recovery phase=" + movementRecovery.phase + " retainedAction=" + Arrays.toString(movementRecovery.goal)
                + " localEndpoint=" + Arrays.toString(movementRecovery.localEndpoint) + " failure=" + movementRecovery.failure);
        lines.add("Movement=" + movementPhase() + " goal=" + Arrays.toString(preciseGoal) + " endpoint=" + Arrays.toString(selectedEndpoint)
                + " route=" + routeIndex + "/" + segmentEnd + "/" + travelRoute.size() + " direction=" + preciseDirection
                + " reversalsWithoutWork=" + debugReversals + " stall=" + ladderStallTicks + " decision=" + movementDecision);
        lines.add("Navigation=" + navigationReason + "; " + navigationDetail + "; segment=" + segmentFailure);
        if (!movementOnly) {
            lines.add("Repair=" + buildingAccess + " pending=" + accessRepairPending + " operation=" + accessIndex + "/" + accessPlan.size()
                    + " savedObjective=" + Arrays.toString(accessDestination) + " work=" + Arrays.toString(workPositionTarget));
            lines.add("Supports carried=" + accessCount(false) + " required=" + requiredSupports() + " missing=" + Math.max(0,requiredSupports()-accessCount(false)));
            lines.add("Ladders carried=" + accessCount(true) + " required=" + requiredLadders() + " missing=" + Math.max(0,requiredLadders()-accessCount(true)));
            lines.add("Remaining plan supports=" + supportRequirement() + " ladders=" + Math.max(restockLadders,ladderRequirement())
                    + "; optional reserves supports=" + emergencyBlocks() + " ladders=2");
            lines.add("Storage reason=" + storageReason.key + " waiting=" + waitingForDepot + " unloading=" + depositCargo().size()
                    + " next repair=" + Arrays.toString(supplyOperation) + "; " + supplyBlocker + "; " + depotFailureDetail);
            lines.add("Access=" + accessFailure + "; blocker=" + accessBlocker + "; placement=" + accessPlacementFailure);
            lines.add("Recovery=" + recoveryAction + " cell=" + Arrays.toString(localRecoveryCell) + "; " + recoveryBlocker);
            lines.add("Bounds=" + Arrays.toString(plan) + " ladder=" + Arrays.toString(ladderPosition) + " landing=" + Arrays.toString(ladderLanding)
                    + " attachmentSearch=" + attachmentOperations + "/" + ATTACHMENT_OPERATIONS);
            for (int i = accessIndex; i < Math.min(accessPlan.size(),accessIndex+16); i++) {
                int[] cell = accessPlan.get(i);
                lines.add("Planned " + i + " " + Arrays.toString(cell) + " outsideMine=" + !excavationArea(cell[0],cell[1],cell[2])
                        + " rejected=" + failedAccessOperations.contains(cell));
            }
        }
        if (debugHistory == null) lines.add("Tracing is off; enable before reproducing to record decisions (maximum 200 events).");
        else for (String event : debugHistory) if (!movementOnly || event.contains("[movement]") || event.contains("[objective]")
                || event.contains("[recovery]") || event.contains("[progress]") || event.contains("[endpoint]")) lines.add(event);
        return lines;
    }
    /** Read observations only: no planners, fake player, events, watches or chunk requests. */
    public List<String> inspectMinerCell(int x,int y,int z) {
        List<String> lines = new java.util.ArrayList<String>();
        lines.add("Miner " + getUniqueID() + " dim=" + dimension + " inspect=" + x+","+y+","+z);
        if (y < 0 || y >= worldObj.getHeight() || !worldObj.blockExists(x,y,z)) {
            lines.add("Outside height or chunk not loaded; inspection stopped."); return lines;
        }
        lines.add("Block=" + Block.blockRegistry.getNameForObject(worldObj.getBlock(x,y,z)) + ":" + worldObj.getBlockMetadata(x,y,z)
                + " outsideMine=" + !excavationArea(x,y,z));
        for (int[] cell : ladderAccess) if (cell[0] == x && cell[1] == y && cell[2] == z) lines.add("Required=" + Arrays.toString(cell));
        for (int i = 0; i < accessPlan.size(); i++) {
            int[] cell = accessPlan.get(i);
            if (cell[0] == x && cell[1] == y && cell[2] == z)
                lines.add("Operation=" + i + " " + Arrays.toString(cell) + " rejected=" + failedAccessOperations.contains(cell));
        }
        lines.add("Last execution rejection=" + accessFailure + "; " + accessPlacementFailure);
        lines.add("Inspection does not simulate placement or protection events. Report contains recorded decisions.");
        return lines;
    }

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
        if (debugHistory != null && (preciseGoal != null || walkingSegment)) trace("movement", "cancel route: " + movementDecision);
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
        boolean nextStorage = next == Task.TO_STORAGE || next == Task.DEPOSITING || next == Task.RESTOCKING;
        if (storageTask() != nextStorage) movementRecovery.clear();
        if (debugHistory != null) { movementDecision = "task " + task.key + " -> " + next.key + ": " + reason; trace("objective",movementDecision); }
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
    private int[] depotDestination() {
        if (activeDepot != null) return activeDepot.position();
        for (Depot depot : depots) if (depot.dimension == dimension) return depot.position();
        // Only a diagnostic/recovery hint when there are no links; never grants inventory access.
        return new int[] {plan[0],plan[1],plan[2]};
    }
    private int[] taskDestination() {
        return storageTask() ? depotDestination()
                : buildingAccess && accessDestination != null ? accessDestination.clone() : workDestination();
    }
    private boolean depotRepair() { return storageTask() && Arrays.equals(accessDestination,depotDestination()); }
    private String temporaryActivity() {
        return movementRecovery.active() ? "recovery" : localRecoveryCell != null || localRecoveryMove != null || localRecoveryDrop != null
                ? "local_access" : buildingAccess && (!storageTask() || depotRepair()) ? "access" : storageTask() ? "depot"
                : pendingDrops != null ? "drops" : "work";
    }
    private void resumeAfterAccess(String reason) {
        if (storageTask()) transition(Task.TO_STORAGE,reason,depotDestination());
        else returnToWork(reason);
    }
    private int totalWork() { return (plan[5]-plan[3]+1)*(plan[6]-plan[4]+1)*(plan[7]-plan[8]+1); }
    private boolean blockWorkRemaining() { return cursor < totalWork() || rampCleanup >= 0 || !retainedTargets.isEmpty(); }
    private boolean unfinished() { return blockWorkRemaining() || pendingDrops != null || !overflowDrops.isEmpty(); }
    private int[] workCell(int index) {
        int width = plan[5]-plan[3]+1;
        return new int[] {plan[3]+index%width,plan[7]-index/(width*(plan[6]-plan[4]+1)),plan[4]+index/width%(plan[6]-plan[4]+1)};
    }
    private int workIndex(int x,int y,int z) {
        return (plan[7]-y)*(plan[5]-plan[3]+1)*(plan[6]-plan[4]+1)+(z-plan[4])*(plan[5]-plan[3]+1)+x-plan[3];
    }
    private int retainedWork() {
        if (retainedTarget >= 0 && retainedTargets.get(retainedTarget) && !rejectedRetainedTargets.get(retainedTarget)) return retainedTarget;
        for (int candidate = retainedTargets.previousSetBit(totalWork()-1); candidate >= 0; candidate = retainedTargets.previousSetBit(candidate-1))
            if (!rejectedRetainedTargets.get(candidate)) return retainedTarget = candidate;
        return -1;
    }
    private boolean releasedAccess(int x,int y,int z) { return excavationArea(x,y,z) && releasedTargets.get(workIndex(x,y,z)); }
    private void retireAccess(int x,int y,int z) {
        releasedTargets.set(workIndex(x,y,z));
        if (ladderPosition != null) {
            net.minecraftforge.common.util.ForgeDirection face = net.minecraftforge.common.util.ForgeDirection.getOrientation(ladderPosition[2]);
            if (x == ladderPosition[0]-face.offsetX && z == ladderPosition[1]-face.offsetZ
                    && excavationArea(ladderPosition[0],y,ladderPosition[1])) releasedTargets.set(workIndex(ladderPosition[0],y,ladderPosition[1]));
        }
        for (List<int[]> cells : Arrays.asList(accessBlocks,ladderAccess,accessPlan,ladderEntrance))
            cells.removeIf(p -> releasedAccess(p[0],p[1],p[2]));
        accessIndex = Math.min(accessIndex,accessPlan.size());
        accessRepairPending = false;
        for (int[] p : ladderAccess) if (!accessPresent(p)) { accessRepairPending = true; break; }
    }
    private int[] workDestination() {
        if (pendingDrops != null) return new int[] {pendingDrops[0],pendingDrops[1]+1,pendingDrops[2]};
        if (cursor >= totalWork() && rampCleanup < 0 && !retainedTargets.isEmpty()) {
            int selected = retainedWork();
            if (selected < 0) selected = retainedTargets.previousSetBit(totalWork()-1);
            int[] cell = workCell(selected); cell[1]++; return cell;
        }
        int width = plan[5]-plan[3]+1, layer = width*(plan[6]-plan[4]+1);
        int current = cursor >= totalWork() && rampCleanup >= 0 ? rampCursor(rampCleanup)
                : targetCursor >= cursor && targetCursor/layer == cursor/layer ? targetCursor : cursor;
        return current < totalWork() ? new int[] {plan[3]+current%width,plan[7]-current/layer+1,plan[4]+current/width%(plan[6]-plan[4]+1)} : null;
    }
    private void returnToWork(String reason) { transition(Task.TO_WORK,reason,workDestination()); }
    private void beginStorage(StorageReason reason) {
        if (reason == StorageReason.TOOL) equipCarriedTool();
        if (reason == StorageReason.TORCHES) stockTorchesFromCargo();
        refreshSupplyRequirements();
        if (!storageNeeded(reason)) return;
        storageReason = reason;
        triedDepots.clear(); waitingForDepot = false; activeDepot = null;
        if (reason != StorageReason.OVERFLOW) capacityDetail = "";
        transition(Task.TO_STORAGE,reason.key,depotDestination());
        chooseDepot();
    }
    private boolean storageNeeded(StorageReason reason) {
        switch (reason) {
        case CAPACITY: return cargoFull();
        case OVERFLOW: return !overflowDrops.isEmpty();
        case TOOL: return !suitable(getHeldItem());
        case TORCHES: return needsLight && torchCount() == 0;
        case LADDERS: return accessCount(true) < requiredLadders();
        case ACCESS: return accessCount(false) < requiredSupports() || accessCount(true) < requiredLadders();
        case COMPLETE: return !unfinished() && hasCargo();
        default: return false;
        }
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
        if (!movementRecovery.active()) movementRecovery.clear();
        rejectedRetainedTargets.clear();
        if (debugHistory != null) { debugProgress++; debugReversals = 0; debugLastRejection = ""; trace("progress",reason); }
        boolean hadFailures = objectiveFailures > 0 || objectiveTicks > 0;
        objectiveFailures = objectiveTicks = 0;
        if (buildingAccess) accessSearchExhausted = false;
        watchedTerrain.clear(); terrainChecks = null;
        if (hadFailures) diagnostic("reset",reason);
    }
    private void retryObjective(String reason) {
        triedDepots.clear(); waitingForDepot=false;
        movementRecovery.clear();
        if (debugHistory != null) { movementDecision = "retry: " + reason; trace("recovery",movementDecision); }
        localRecoveryCell = localRecoveryMove = null; localRecoveryDrop = null;
        localRecoveryEdits.clear();
        gatheringSupport = false; supplySearch = 0; supplyOrigin = null;
        supplyProtected = supplyWrongTool = supplyUnreachable = supplyUnsuitable = 0;
        rejectedRecovery.clear(); unsuitableRecoveryDrops.clear(); recoveryAction = recoveryBlocker = "";
        if (reconsideringLadder) { reconsideringLadder = false; buildingAccess = !accessPlan.isEmpty(); }
        if (buildingAccess && staircaseAccess) staircaseLadderSupplies = Long.MIN_VALUE;
        if (buildingAccess && !staircaseAccess) reconcileAccessPlan();
        accessRepairPending = true;
        navigationBlocked = selectingTarget = false; rejectedTargets.clear(); rejectedRetainedTargets.clear(); searchOffset = 0;
        failedEndpoints.clear(); failedMovementEdges.clear(); segmentFailure = ""; accessAttempts = 0;
        interactionTarget = selectedEndpoint = plannedEntrance = null;
        clearRouteSearch(); clearAccessSearch(); resetPath();
        failedAccessOperations.clear();
        usefulProgress(reason); navigationReason = navigationDetail = arrivalFailure = "";
        accessPlacementFailure = "";
        accessSupplyChecked = false; supplyCheckTicks = 0;
        destination = taskDestination();
        status = !running ? "paused" : buildingAccess ? accessActivity() : task.key;
        diagnostic("objective",reason);
    }
    private void diagnostic(String category,String reason) {
        if (debugHistory != null) trace(category,reason);
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
    /**
     * Travel recovery owns no work cursor, inventory, or world edits. Adapted from
     * MineColonies 1.16.5's proxy destination and PathingStuckHandler reset/away
     * flow (0df80c8652a5bdcc094fc7f4da66437f6cecaff2); independently implemented
     * with the miner's loaded-terrain graph and Forge 1.7.10 movement controller.
     */
    private final class NavigationRecovery {
        private RecoveryPhase phase = RecoveryPhase.IDLE;
        private int[] goal, localEndpoint;
        private boolean mining, triedAway;
        private int ladderAttempts;
        private final java.util.Set<Long> landings = new java.util.HashSet<Long>();
        private String waitState = "navigation_failed", failure = "", reason = "";
        boolean active() { return phase != RecoveryPhase.IDLE && phase != RecoveryPhase.WAIT; }
        void clear() {
            phase = RecoveryPhase.IDLE; goal = localEndpoint = null; triedAway = false; ladderAttempts = 0; landings.clear();
            failure = reason = "";
        }
        boolean begin(String state) {
            if (phase == RecoveryPhase.WAIT || active() || !running || !configured || recoveringCorridor) return false;
            goal = interactionTarget == null ? taskDestination() : interactionTarget.clone();
            if (buildingAccess && accessIndex < accessPlan.size() && !"navigation_failed".equals(state)) {
                int[] cell = accessPlan.get(accessIndex);
                goal = new int[] {cell[0],cell[1]+(cell[3] < 0 ? 1 : 0),cell[2]};
                // Reaching a builder position does not resolve exhausted supplies.
                // Try the actual supply exit before requesting direct delivery.
                if (accessCount(true) == 0 && requiredLadders() > 0 || accessCount(false) == 0 && requiredSupports() > 0) {
                    if (!storageTask()) beginStorage(StorageReason.ACCESS);
                    goal = depotDestination();
                }
            }
            // A repair operation may temporarily own movement, but a depot failure
            // always retains the chest, never a newly computed mining destination.
            if (storageTask() && !constructionTarget(goal == null ? 0 : goal[0],goal == null ? 0 : goal[1],goal == null ? 0 : goal[2])) goal = depotDestination();
            if (goal == null) return false;
            int[] operation = constructionCell(goal[0],goal[1],goal[2]);
            mining = operation != null ? operation[3] < 0 : interactionTarget != null && Arrays.equals(goal,interactionTarget) && interactionMining;
            waitState = state; rememberFailure(); resetPath(); clearRouteSearch();
            navigationBlocked = false; phase = RecoveryPhase.REPATH;
            status = "recovering"; recoveryAction = "path_reset";
            trace("recovery","reset path; retained action=" + Arrays.toString(goal) + "; " + failure);
            return true;
        }
        private void rememberFailure() {
            if (!navigationDetail.isEmpty()) { failure = navigationDetail; reason = navigationReason; }
        }
        private void replan() {
            resetPath(); clearRouteSearch(); selectedEndpoint = null;
            localEndpoint = null; phase = RecoveryPhase.REPATH; recoveryAction = "path_reset";
        }
        boolean tick() {
            if (!active()) return false;
            status = "recovering";
            if (phase == RecoveryPhase.LOCAL) {
                if (travelRoute.isEmpty()) { rememberFailure(); replan(); return true; }
                if (atPreciseNode(localEndpoint,ladderNode(localEndpoint)) && (ladderNode(localEndpoint) || onGround)) {
                    trace("recovery","reached temporary landing " + Arrays.toString(localEndpoint) + "; resume " + Arrays.toString(goal));
                    replan(); return true;
                }
                if (!routeIntact(travelRoute,routeIndex,Math.min(routeIndex+8,travelRoute.size()-1),-30000001,-1,0)) {
                    preciseGoal = localEndpoint;
                    failPreciseMovement("recovery landing route changed: " + edgeDiagnostic(travelRoute.get(routeIndex),localEndpoint),0,0,motionY);
                    rememberFailure(); replan(); return true;
                }
                if (preciseGoal == null && routeIndex+1 >= travelRoute.size()) {
                    if (finalApproachClear(localEndpoint)) startPreciseMovement(localEndpoint,ladderNode(localEndpoint));
                    else { failure = "Recovery landing final approach rejected at " + Arrays.toString(localEndpoint); replan(); }
                } else startRouteSegment(travelRoute);
                pathTicks += 10;
                if (pathTicks >= 100 && preciseGoal == null) {
                    preciseGoal = localEndpoint; failPreciseMovement("recovery movement-away endpoint stalled",0,0,motionY);
                    rememberFailure(); replan();
                }
                return true;
            }
            if (phase == RecoveryPhase.REPATH) {
                recoveryAction = "path_reset";
                if (moveNear(goal[0],goal[1],goal[2],mining)) {
                    trace("recovery","retained action reachable " + Arrays.toString(goal));
                    // A recovered builder action gets one execution attempt. Keep
                    // its failure epoch until a real edit, delivery or terrain change.
                    if (constructionTarget(goal[0],goal[1],goal[2])) phase = RecoveryPhase.WAIT;
                    else clear();
                    recoveryAction = recoveryBlocker = ""; return true;
                }
                if (!"route_failed".equals(status)) return true;
                rememberFailure(); resetPath(); clearRouteSearch();
                List<int[]> route = null;
                if (ladderAttempts < 3) {
                    ladderAttempts++;
                    route = ladderRecoveryRoute(goal);
                    if (route != null) {
                        int[] end = route.get(route.size()-1); landings.add(positionKey(end[0],end[1],end[2]));
                        recoveryAction = "ladder_move";
                    }
                }
                if (route == null && !triedAway) {
                    triedAway = true;
                    route = movementAwayRoute(goal);
                    if (route != null) recoveryAction = "move_away";
                }
                if (route != null) {
                    useRoute(route); localEndpoint = route.get(route.size()-1).clone();
                    phase = RecoveryPhase.LOCAL;
                    trace("recovery",recoveryAction + " endpoint=" + Arrays.toString(localEndpoint) + "; retained=" + Arrays.toString(goal));
                    startRouteSegment(travelRoute); return true;
                }
                phase = RecoveryPhase.REPAIR;
            }
            if (phase == RecoveryPhase.REPAIR) {
                // Movement-only alternatives have completed or failed. Only actual
                // missing geometry may now create a construction objective.
                phase = RecoveryPhase.WAIT;
                int[] repairGoal = storageTask() ? depotDestination() : taskDestination();
                if ((!buildingAccess || storageTask() && !depotRepair()) && repairGoal != null && startAccessRepair(repairGoal)) {
                    recoveryAction = "repairing"; return true;
                }
                if (buildingAccess) {
                    // Exhaustion of this action must not become a fresh reset loop.
                    if (accessCount(true) == 0 && requiredLadders() > 0 && startStaircaseFallback(repairGoal)) return true;
                    navigationReason = reason; navigationDetail = failure;
                    recoveryBlocker = failure;
                    blockObjective(waitState); return true;
                }
                navigationReason = reason.isEmpty() ? "path" : reason;
                navigationDetail = failure + "; movement-only recovery exhausted for " + Arrays.toString(goal)
                        + "; restore the reported rung/landing/interaction or use Start / Resume after repair";
                recoveryBlocker = navigationDetail; recoveryAction = "blocked";
                blockObjective(waitState); return true;
            }
            return true;
        }
    }
    /** Find a supported exit from the CURRENT attached column, with no edits.
     * Direction is selected once by destination/landing distance and then belongs
     * to that route. Failed directed transitions remain excluded. */
    private List<int[]> ladderRecoveryRoute(final int[] goal) {
        int x = (int)Math.floor(posX), y = (int)Math.floor(boundingBox.minY+.001), z = (int)Math.floor(posZ);
        if (!ladder(x,y,z,-30000001,-1,0) || !clearBody(posX,boundingBox.minY,posZ,-30000001,-1,0)) return null;
        List<List<int[]>> alternatives = new java.util.ArrayList<List<int[]>>();
        for (int direction : new int[] {1,-1}) {
            List<int[]> column = new java.util.ArrayList<int[]>();
            int[] first = {x,y,z};
            if (!safeStart(first,-30000001,-1,0,false)) continue;
            column.add(first);
            for (int h = y; h >= 0 && h+1 < worldObj.getHeight(); h += direction) {
                int[] rung = {x,h,z};
                if (h != y) {
                    int[] previous = column.get(column.size()-1);
                    if (!ladderNode(rung) || !safeEdge(previous,rung,-30000001,-1,0)) break;
                    column.add(rung);
                }
                for (int side = 0; side < 4; side++) for (int dy = -2; dy <= 1; dy++) {
                    int[] exit = {x+(side == 0 ? 1 : side == 1 ? -1 : 0),h+dy,z+(side == 2 ? 1 : side == 3 ? -1 : 0)};
                    if (!inCorridor(exit[0],exit[2]) || movementRecovery.landings.contains(positionKey(exit[0],exit[1],exit[2]))
                            || ladderNode(exit) || !safeEdge(rung,exit,-30000001,-1,0)) {
                        if (debugHistory != null && Math.abs(h-goal[1]) <= 1)
                            trace("endpoint","ladder exit " + Arrays.toString(rung) + " -> " + Arrays.toString(exit) + "; " + edgeDiagnostic(rung,exit));
                        continue;
                    }
                    List<int[]> route = new java.util.ArrayList<int[]>(column); route.add(exit); alternatives.add(route);
                }
            }
        }
        java.util.Collections.sort(alternatives,(a,b) -> Double.compare(
                getEndpointDistance(a.get(a.size()-1),goal)+a.size()*.05,getEndpointDistance(b.get(b.size()-1),goal)+b.size()*.05));
        return alternatives.isEmpty() ? null : alternatives.get(0);
    }
    private List<int[]> movementAwayRoute(final int[] goal) {
        if (!onGround) return null;
        List<int[]> candidates = new java.util.ArrayList<int[]>();
        int x = (int)Math.floor(posX), y = (int)Math.floor(boundingBox.minY+.001), z = (int)Math.floor(posZ);
        for (int dx = -3; dx <= 3; dx++) for (int dz = -3; dz <= 3; dz++) for (int dy = -1; dy <= 1; dy++) {
            if (dx*dx+dz*dz < 4 || dx*dx+dz*dz > 9) continue;
            int[] p = {x+dx,y+dy,z+dz};
            if (inCorridor(p[0],p[2]) && standing(p[0],p[1],p[2],-30000001,-1,0) && !ladderNode(p)) candidates.add(p);
        }
        java.util.Collections.sort(candidates,(a,b) -> Double.compare(getEndpointDistance(a,goal),getEndpointDistance(b,goal)));
        return searchRoute(candidates,-30000001,-1,0,false,null,true);
    }
    private void watchTerrain(int x,int y,int z) {
        if (navigationBlocked || y < 0 || y >= worldObj.getHeight() || watchedTerrain.size() >= 32768) return;
        long key = positionKey(x,y,z);
        if (!watchedTerrain.containsKey(key)) {
            boolean loaded = worldObj.blockExists(x,y,z);
            watchedTerrain.put(key,new int[] {x,y,z,loaded ? Block.getIdFromBlock(worldObj.getBlock(x,y,z)) : -1,
                    loaded ? worldObj.getBlockMetadata(x,y,z) : 0, WorkerAccess.ownsLand(worldObj,x,z,factionId) ? 1 : 0});
            // Active endpoint validation also iterates this map between searches.
            terrainChecks = null;
        }
    }
    private void blockObjective(String state) {
        // A wait is the final recovery state, never the gate in front of recovery.
        if (movementRecovery.begin(state)) return;
        if (storageTask() && activeDepot != null) { nextDepot(true); return; }
        if (debugHistory != null) trace("blocked",state + "; " + navigationReason + "; " + navigationDetail);
        if ("no_access".equals(state)) state = requiredSupports() > accessCount(false) || requiredLadders() > accessCount(true)
                ? "access_materials" : "access_route";
        if (preciseGoal != null || walkingSegment) ladderDiagnostic("blocked: cancel active movement",motionY,0,motionY);
        clearMovementCommands(); preciseGoal = null; travelRoute.clear(); recoveryRoute.clear();
        motionY = Math.min(0,motionY);
        navigationBlocked = true; status = state; terrainChecks = null;
        blockedSupplies = supplyStamp(true);
        blockedPosition = new int[] {(int)Math.floor(posX),(int)Math.floor(boundingBox.minY+.001),(int)Math.floor(posZ)};
        blockedSettling = !onGround;
    }
    private long supplyStamp() { return supplyStamp(false); }
    private long supplyStamp(boolean relevantOnly) {
        long value = 1;
        List<IInventory> inventories=new java.util.ArrayList<IInventory>(); inventories.add(this);
        for (Depot depot : depots) {
            IInventory station=depotInventory(depot); value=31*value+depot.hashCode()+(station == null ? 0 : 1);
            if (station != null) inventories.add(station);
        }
        for (IInventory inventory : inventories) for (int i=firstSlot(inventory); i<inventory.getSizeInventory(); i++) {
            ItemStack stack = inventory.getStackInSlot(i);
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
        if (usefulRecoveryDrop() != null) { retryObjective("useful dropped repair supplies appeared"); return false; }
        if (blockedSettling && onGround) {
            // Our own gravity after cancellation is not an external repair and
            // must not erase the same failed exit and start another climb loop.
            blockedPosition = new int[] {(int)Math.floor(posX),(int)Math.floor(boundingBox.minY+.001),(int)Math.floor(posZ)};
            blockedSettling = false;
        }
        if (blockedSupplies != supplyStamp(true) || onGround && blockedPosition != null
                && (blockedPosition[0] != (int)Math.floor(posX) || blockedPosition[1] != (int)Math.floor(boundingBox.minY+.001)
                    || blockedPosition[2] != (int)Math.floor(posZ))) {
            retryObjective("supplies, station or supported position changed"); return false;
        }
        if (terrainChecks == null || !terrainChecks.hasNext()) terrainChecks = watchedTerrain.values().iterator();
        for (int checked = 0; checked < 512 && terrainChecks.hasNext(); checked++) {
            int[] p = terrainChecks.next(); boolean loaded = worldObj.blockExists(p[0],p[1],p[2]);
            if ((loaded ? Block.getIdFromBlock(worldObj.getBlock(p[0],p[1],p[2])) : -1) != p[3]
                    || loaded && worldObj.getBlockMetadata(p[0],p[1],p[2]) != p[4]
                    || (WorkerAccess.ownsLand(worldObj,p[0],p[2],factionId) ? 1 : 0) != p[5]) {
                retryObjective("consulted terrain, land permission or chunk availability changed"); return false;
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
        String error=validateArea(next);
        if (!error.isEmpty()) return error;
        boolean available=false;
        for (Depot depot : depots) if (depot.dimension == dimension) {
            if (!depotFitsArea(depot,next)) return "xf.miner.invalid_area";
            if (depotInventory(depot) != null) available=true;
        }
        return available ? "" : "xf.miner.state.depots_unavailable";
    }
    private boolean depotFitsArea(Depot depot,int[] next) {
        return Math.abs((long)next[3]-depot.x) <= 128 && Math.abs((long)next[5]-depot.x) <= 128
                && Math.abs((long)next[4]-depot.z) <= 128 && Math.abs((long)next[6]-depot.z) <= 128;
    }
    private String validateArea(int[] next) {
        long width = (long)next[5] - next[3] + 1, length = (long)next[6] - next[4] + 1, depth = (long)next[7] - next[8] + 1;
        if (width < 1 || width > 64 || length < 1 || length > 64 || depth < 1 || depth > worldObj.getHeight()
                || width * length * depth > 262144 || next[8] < 0 || next[7] >= worldObj.getHeight()) return "xf.miner.invalid_area";
        for (int i : new int[] {3, 4, 5, 6}) if (Math.abs((long)next[i]) > 30000000) return "xf.miner.invalid_area";
        for (int x = next[3]; x <= next[5]; x++) for (int z = next[4]; z <= next[6]; z++)
            if (!WorkerAccess.ownsLand(worldObj, x, z, factionId)) return "xf.worker.denied";
        return "";
    }
    public String selectStation(EntityPlayer player, int x, int y, int z) {
        return selectStation(player,x,y,z,false);
    }
    public String selectStation(EntityPlayer player, int x, int y, int z, boolean remove) {
        if (worldObj.isRemote) return "xf.worker.denied";
        if (!maySelect(player) || !WorkerAccess.ownsLand(worldObj, x, z, factionId)) return "xf.worker.denied";
        if (Math.abs((long)x) > 30000000 || Math.abs((long)z) > 30000000 || y < 0 || y >= worldObj.getHeight()) return "xf.miner.no_chest";
        if (!worldObj.blockExists(x,y,z)) return "xf.miner.state.chunk";
        if (remove) {
            if (!station(worldObj.getTileEntity(x,y,z))) return "xf.miner.no_chest";
            String unavailable=stationUnavailable(x,y,z);
            if ("chunk".equals(unavailable) || "denied".equals(unavailable)) return "xf.miner.state."+unavailable;
        }
        if (!remove && stationInventory(x, y, z) == null) return "xf.miner.state." + stationUnavailable(x, y, z);
        normalizeDepots();
        Depot selected = canonicalDepot(new Depot(dimension,x,y,z));
        if (remove) {
            if (!depots.remove(selected)) return "xf.miner.station_not_linked";
            if (selected.equals(activeDepot)) cancelDepotTravel();
            stationAssigned = !depots.isEmpty(); accessSupplyChecked = false; waitingForDepot = false; triedDepots.clear();
            return "xf.miner.station_removed";
        }
        if (depots.contains(selected)) return "xf.miner.station_duplicate";
        if (depots.size() >= MAX_DEPOTS) return "xf.miner.station_limit";
        int[] next = plan.clone(); next[0] = x; next[1] = y; next[2] = z;
        String error = configured ? validateArea(next) : "";
        if (!error.isEmpty()) return error;
        if (configured && !depotFitsArea(selected,next)) return "xf.miner.invalid_area";
        depots.add(selected); stationAssigned = true; jobDimension = dimension;
        // Keep old Plan readers/save migration compatible; adding links never replaces work.
        if (depots.size() == 1) { plan[0]=x; plan[1]=y; plan[2]=z; }
        accessSupplyChecked = false; waitingForDepot = false; triedDepots.clear();
        return "xf.miner.station_set";
    }
    private static int compareDepots(Depot a,Depot b) {
        int c = Integer.compare(a.dimension,b.dimension);
        if (c == 0) c = Integer.compare(a.x,b.x);
        if (c == 0) c = Integer.compare(a.y,b.y);
        return c == 0 ? Integer.compare(a.z,b.z) : c;
    }
    private Depot canonicalDepot(Depot depot) {
        if (depot.dimension != dimension || !worldObj.blockExists(depot.x,depot.y,depot.z)
                || worldObj.getTileEntity(depot.x,depot.y,depot.z) == null
                || worldObj.getTileEntity(depot.x,depot.y,depot.z).getClass() != net.minecraft.tileentity.TileEntityChest.class) return depot;
        Depot first = depot; Block block = worldObj.getBlock(depot.x,depot.y,depot.z);
        for (int[] d : new int[][] {{-1,0},{1,0},{0,-1},{0,1}}) {
            int x=depot.x+d[0], z=depot.z+d[1];
            if (!worldObj.blockExists(x,depot.y,z) || worldObj.getBlock(x,depot.y,z) != block) continue;
            TileEntity tile = worldObj.getTileEntity(x,depot.y,z);
            if (tile != null && tile.getClass() == net.minecraft.tileentity.TileEntityChest.class) {
                Depot other = new Depot(dimension,x,depot.y,z); if (compareDepots(other,first) < 0) first = other;
            }
        }
        return first;
    }
    private void normalizeDepots() {
        java.util.LinkedHashSet<Depot> unique = new java.util.LinkedHashSet<Depot>();
        for (Depot depot : depots) unique.add(canonicalDepot(depot));
        depots.clear(); depots.addAll(unique);
        if (activeDepot != null) activeDepot = canonicalDepot(activeDepot);
        stationAssigned = !depots.isEmpty();
    }
    private IInventory depotInventory(Depot depot) {
        return depot.dimension == dimension ? stationInventory(depot.x,depot.y,depot.z) : null;
    }
    private List<Depot> orderedDepots() {
        List<Depot> ordered = new java.util.ArrayList<Depot>(depots);
        java.util.Collections.sort(ordered,(a,b) -> {
            int c = Double.compare(getDistanceSq(a.x+.5,a.y+.5,a.z+.5),getDistanceSq(b.x+.5,b.y+.5,b.z+.5));
            return c == 0 ? compareDepots(a,b) : c;
        });
        return ordered;
    }
    private void cancelDepotTravel() {
        boolean repairingDepot = depotRepair();
        activeDepot = null; returnRoute.clear(); reachableSupplyDepots.clear();
        if (!storageTask()) return;
        // Cancel the temporary action only; cursor, work position, drops and progress survive.
        clearMovementCommands(); preciseGoal = null; destination = waypoint = null;
        travelRoute.clear(); recoveryRoute.clear(); recoveringCorridor = false; movementRecovery.clear();
        interactionTarget = selectedEndpoint = plannedEntrance = null; failedEndpoints.clear(); clearRouteSearch();
        pathTicks = navigationFailures = objectiveFailures = objectiveTicks = 0;
        if (repairingDepot) { buildingAccess = false; accessPlan.clear(); accessIndex = 0; accessDestination = null; clearAccessSearch(); }
        navigationBlocked = false; task = Task.TO_STORAGE;
    }
    // Only Start can install a validated, confirmed replacement. Real overflow drops remain collectible.
    private void configure(int[] next) {
        retainedTargets.clear(); releasedTargets.clear(); rejectedRetainedTargets.clear(); retainedTarget = -1;
        pause(); plan = next; cursor = 0; configured = true; task = Task.TO_WORK; storageReason = StorageReason.NONE;
        targetCursor = -1; searchOffset = 0;
        workPosition = workPositionTarget = null;
        rejectedTargets.clear(); returnRoute.clear();
        accessPlan.clear(); accessBlocks.clear(); ladderAccess.clear(); ladderEntrance.clear(); ladderTerrain.clear(); ladderLanding = null;
        buildingAccess = false; accessDestination = null; accessAttempts = 0;
        ladderPosition = null; staircaseAccess = staircaseInstalled = false; ladderObstruction = accessBlocker = accessPlacementFailure = "";
        restockLadders = restockBlocks = 0; repairAfterRestock = false; accessTripFloor = -1;
        repairingLadder = reconsideringLadder = false; staircaseLadderSupplies = Long.MIN_VALUE;
        constructionRefuge.clear(); failedAccessOperations.clear();
        movementRecovery.clear(); accessSupplyChecked = false;
        requiredHarvest = -1; needsLight = false; toolBroken = false;
        retryObjective("installed area changed");
        accessRamp = (plan[5] > plan[3] || plan[6] > plan[4]) && plan[7] > plan[8];
        rampCleanup = accessRamp ? plan[7]-plan[8] : -1;
        rampOffset = 0; rampDirection = 1;
        if (accessRamp) {
            double best = Double.MAX_VALUE;
            int[] depot=depotDestination();
            for (int i = 0; i < rampPerimeter(); i++) {
                int[] r = rampColumn(i);
                double d = (double)(r[0]-depot[0])*(r[0]-depot[0])+(double)(r[1]-depot[2])*(r[1]-depot[2]);
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
            boolean unfinished = configured && (blockWorkRemaining() || pendingDrops != null || hasCargo());
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
            triedDepots.clear(); waitingForDepot=false;
            destination = storageTask() ? depotDestination() : workDestination();
        } else if (navigationBlocked) retryObjective("explicit Start instruction");
        if (!recoveringCorridor && !inCorridor((int)Math.floor(posX),(int)Math.floor(posZ))) beginCorridorRecovery();
        return new Activation("");
    }
    public void onLivingUpdate() {
        // Permission and explicit Pause own every controller, including movement ticks.
        if (!worldObj.isRemote && running && (!configured || !authorized() || jobDimension != dimension)) stop("denied");
        if (!worldObj.isRemote && running && preciseGoal == null && !landingInProgress() && !navigationBlocked && !recoveringCorridor && interactionTarget != null
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
        if (!worldObj.isRemote && !recoveringCorridor && !movementRecovery.active() && preciseGoal == null && !getNavigator().noPath() && destination != null
                && !constructionTarget(destination[0],destination[1],destination[2])
                && atWorkEndpoint(interactionMining) && atDestination(destination[0],destination[1],destination[2],interactionMining))
            arrived();
        super.onLivingUpdate();
        if (worldObj.isRemote || !running) return;
        try {
            if (ticksExisted % 10 != 0) return;
            if (supplyCheckTicks > 0 && !movementRecovery.active() && preciseGoal == null && !recoveringCorridor && inCorridor((int)Math.floor(posX),(int)Math.floor(posZ))) supplyCheckTicks -= 10;
            else work();
            updateRequests(); notifySupplies();
        }
        catch (RuntimeException error) { stop("error"); XFLog.error("Miner stopped: " + getUniqueID(), error); }
        finally {
            if (actor != null) actor.inventory.mainInventory[0] = null;
            if (debugHistory != null) traceState();
        }
    }
    private boolean authorized() {
        Clowder faction = factionId == null ? null : Clowder.getClowderFromUUID(factionId.toString());
        return faction != null && employerId != null && faction.getRole(employerId) != null && !faction.isInfrastructureDisabled();
    }
    private void work() {
        if (!configured || !authorized()) { stop("denied"); return; }
        if (jobDimension != dimension) { stop("dimension"); return; }
        // Finish the accepted physics transition before target/supply changes
        // can clear its steering or issue another ascent/descent command.
        if (landingInProgress() || preciseGoal != null) {
            status = verticalControl() ? ladderActivity() : precisePhase == PrecisePhase.FLOOR ? "stepping" : "descending";
            return;
        }
        normalizeDepots();
        // Deliveries and loaded jobs must resolve before ANY depot availability,
        // selection, recovery or stable-wait return can hide completion.
        equipCarriedTool(); stockTorchesFromCargo();
        for (java.util.Iterator<ItemStack> it = overflowDrops.iterator(); it.hasNext();) {
            ItemStack stack = it.next(); insert(this,stack);
            if (stack.stackSize <= 0) it.remove();
        }
        equipCarriedTool(); stockTorchesFromCargo();
        if (storageTask() && accessRepairPending && !depotRepair() && ladderPosition != null && !staircaseAccess) {
            reconcileAccessPlan();
            buildingAccess = !accessPlan.isEmpty();
        }
        refreshSupplyRequirements();
        completeDepotTrip();
        if (!running) return;
        reconsiderDepotFailures();
        if (storageTask() && (activeDepot == null || depotInventory(activeDepot) == null)) {
            if (activeDepot != null) { nextDepot(); return; }
            if (waitingForDepot) {
                if (depotWaitSupplies != supplyStamp() || depotTerrainChanged()) {
                    triedDepots.clear(); failedMovementEdges.clear(); failedEndpoints.clear();
                }
                else if ("depots_unreachable".equals(depotFailure)) { status=depotFailure; supplyCheckTicks=40; return; }
            }
            if (!chooseDepot()) return;
            transition(Task.TO_STORAGE,"linked depot selected",depotDestination());
        }
        if (observeRecoveryTerrain()) return;
        // Recovery runs before the stable wait and before inventory/work decisions.
        if (movementRecovery.tick()) return;
        long supplies = supplyStamp(true);
        if (accessSupplies != supplies) {
            boolean changed = accessSupplies != Long.MIN_VALUE; accessSupplies = supplies;
            if (changed && (navigationBlocked || accessSearchTarget != null))
                retryObjective("access supplies changed; discard stale route failures");
        }
        if (waitForObjectiveChange()) return;
        if (!waitingForDepot && !"chunk".equals(status) && !"no_access".equals(status) && !"access_materials".equals(status) && !"no_tool".equals(status)
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
        if (runLocalRecovery()) return;
        if (ladderPosition != null && !storageTask() && !buildingAccess && !accessRepairPending) {
            int bottom = Integer.MAX_VALUE;
            for (int[] p : ladderAccess) if (p[3] > 0 && fixedRung(p)) bottom = Math.min(bottom,p[1]);
            if (Math.floor(boundingBox.minY+.001) < bottom) accessRepairPending = true;
        }
        if (accessRepairPending) {
            accessRepairPending = false;
            int[] goal = taskDestination();
            if (goal == null) goal = depotDestination();
            if (buildingAccess && !staircaseAccess) {
                reconcileAccessPlan();
            }
            else if (!storageTask() && startAccessRepair(goal)) return;
        }
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
        if (!storageTask()) {
            if (!unfinished()) {
                if (hasCargo()) beginStorage(StorageReason.COMPLETE);
                else { transition(Task.COMPLETE,"work and deposits finished",null); stop("complete"); return; }
            } else if (!overflowDrops.isEmpty()) beginStorage(StorageReason.OVERFLOW);
            else if (cargoFull()) beginStorage(StorageReason.CAPACITY);
            else if (blockWorkRemaining() && !suitable(getHeldItem())) beginStorage(StorageReason.TOOL);
            else if (blockWorkRemaining() && needsLight && torchCount() == 0) beginStorage(StorageReason.TORCHES);
        }
        if (storageTask()) {
            if (!buildingAccess && accessProposals != null && Arrays.equals(accessSearchTarget,depotDestination()) && !accessSearchExhausted) {
                int[] depot=depotDestination(); planAccess(depot[0],depot[1],depot[2]); return;
            }
            if (depotRepair() && buildingAccess) { constructAccess(); return; }
            visitChest(); return;
        }
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
        for (int scans = 0; scans < 64 && blockWorkRemaining(); scans++) {
            boolean cleanup = cursor >= total;
            boolean retained = cleanup && rampCleanup < 0;
            int workCursor = retained ? retainedWork() : cleanup ? rampCursor(rampCleanup)
                    : targetCursor >= cursor && targetCursor / layer == cursor / layer ? targetCursor : cursor;
            if (workCursor < 0) {
                navigationReason = "return_blocked";
                navigationDetail = "Retained access still contains " + retainedTargets.cardinality() + " unfinished blocks; no safe removal endpoint. " + navigationDetail;
                terminalNavigation(); return;
            }
            int x = plan[3] + workCursor % width, z = plan[4] + workCursor / width % length, y = plan[7] - workCursor / layer;
            watchTerrain(x,y,z);
            if (!worldObj.blockExists(x, y, z)) { status = "chunk"; return; }
            Block block = worldObj.getBlock(x, y, z);
            if (worldObj.isAirBlock(x, y, z) || block == net.minecraft.init.Blocks.torch) {
                if (retained) retireAccess(x,y,z);
                advance(workCursor); continue;
            }
            if (!cleanup && reservedAccess(x,y,z)) { retainedTargets.set(workCursor); advance(workCursor); continue; }
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
                    if (retained) { rejectedRetainedTargets.set(workCursor); retainedTarget = -1; }
                    else if (cleanup) terminalNavigation();
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
                if (retained) { rejectedRetainedTargets.set(workCursor); retainedTarget = -1; }
                else if (cleanup) { navigationReason = "sight"; terminalNavigation(); }
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
            if (retained) retireAccess(x,y,z);
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
        if (!blockWorkRemaining()) {
            if (hasCargo()) { beginStorage(StorageReason.COMPLETE); visitChest(); }
            else { transition(Task.COMPLETE,"no work or cargo remains",null); stop("complete"); }
        }
        else status = "scanning";
    }
    private void advance(int minedCursor) {
        int total = (plan[5]-plan[3]+1)*(plan[6]-plan[4]+1)*(plan[7]-plan[8]+1);
        if (minedCursor == cursor || cursor >= total && rampCleanup >= 0) objectiveTicks = 0;
        if (cursor >= total && rampCleanup < 0 && retainedTargets.get(minedCursor)) {
            retainedTargets.clear(minedCursor); rejectedRetainedTargets.clear(minedCursor); retainedTarget = -1;
        }
        else if (cursor >= total && rampCleanup >= 0) rampCleanup--;
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
        // Keep descent waypoints: proximity to an exit cannot discard its required height.
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
            if (ladderPosition != null && !Arrays.equals(ladderPosition,fixed)) continue;
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
        // An upward exit retains its accepted lift while crossing the lip. At
        // .12 blocks/tick the first horizontal tick need not overlap the landing;
        // releasing gravity there drops the feet below its collision top.
        // LANDING releases the lift after reaching the supported endpoint.
        return ladderTravel() || precisePhase == PrecisePhase.EXIT_LIFT
                || precisePhase == PrecisePhase.EXIT && preciseHeight > preciseSurfaceHeight;
    }
    private boolean ladderTravel() { return precisePhase == PrecisePhase.CLIMB || precisePhase == PrecisePhase.LADDER_DESCEND; }
    private String movementPhase() {
        return preciseGoal != null ? precisePhase.name() : walkingSegment ? "WALK" : "IDLE";
    }
    private void startPreciseMovement(int[] goal,boolean climbing) {
        if (preciseGoal != null && Arrays.equals(preciseGoal,goal)) return;
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
        if (precisePhase == PrecisePhase.FLOOR && preciseHeight < boundingBox.minY-.001) {
            precisePhase = PrecisePhase.DROP; descentLanding = goal.clone();
        }
        List<int[]> route = recoveringCorridor ? recoveryRoute : travelRoute;
        double originY = !enteringRoute && segmentEnd > routeIndex && segmentEnd < route.size()
                && goal == route.get(segmentEnd) ? nodeY(route.get(routeIndex)) : boundingBox.minY;
        preciseDirection = precisePhase == PrecisePhase.EXIT_LIFT ? 1 : (int)Math.signum(preciseHeight-originY);
        if (precisePhase == PrecisePhase.CLIMB && preciseDirection < 0) precisePhase = PrecisePhase.LADDER_DESCEND;
        ladderStallTicks = 0;
        bestSegmentError = segmentError();
        if (debugHistory != null) {
            trace("movement","target " + Arrays.toString(goal) + " phase=" + precisePhase + "; decision=" + movementDecision);
            if (verticalControl() && preciseDirection != 0) {
                if (debugDirection != 0 && debugDirection != preciseDirection) {
                    debugReversals++;
                    trace("movement","vertical reversal #" + debugReversals + " since useful progress " + debugProgress
                            + "; previous=" + Arrays.toString(lastLadderGoal) + "; decision=" + movementDecision);
                }
                debugDirection = preciseDirection;
            }
        }
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
        double vertical = ladderTravel()
                ? Math.max(0,preciseDirection*(preciseHeight-boundingBox.minY)) : Math.abs(preciseHeight-boundingBox.minY);
        return (precisePhase == PrecisePhase.EXIT_LIFT ? 0 : Math.sqrt(dx*dx+dz*dz))+vertical;
    }
    private boolean preciseMovementComplete() {
        List<int[]> route = recoveringCorridor ? recoveryRoute : travelRoute;
        if (ladderTravel() && segmentEnd+1 < route.size()
                && preciseGoal == route.get(segmentEnd) && !ladderNode(route.get(segmentEnd+1))
                && (nodeY(route.get(segmentEnd+1))-boundingBox.minY > stepHeight+.001
                    || boundingBox.minY-nodeY(route.get(segmentEnd+1)) > (recoveringCorridor ? 1.001 : 2.001))) return false;
        if (atPreciseNode(preciseGoal,ladderTravel())) return ladderTravel() || onGround;
        if (!ladderTravel() || preciseDirection == 0) return false;
        double crossed = preciseDirection*(boundingBox.minY-preciseSurfaceHeight);
        double dx = preciseGoal[0]+.5-posX, dz = preciseGoal[2]+.5-posZ;
        // Collision stepping can carry the feet past a rung. Consume a crossed
        // boundary only inside the clear, attached column; never steer back to it.
        return crossed >= 0 && crossed <= 1.001 && dx*dx+dz*dz <= PRECISE_HORIZONTAL*PRECISE_HORIZONTAL
                && ladder((int)Math.floor(posX),(int)Math.floor(boundingBox.minY),(int)Math.floor(posZ),-30000001,-1,0)
                && clearBody(posX,boundingBox.minY,posZ,-30000001,-1,0);
    }
    private void reachedRouteNode(int[] node) {
        if (debugHistory != null) trace("movement","route node complete " + Arrays.toString(node));
        navigationFailures = pathTicks = 0; bestRouteProgress = -Double.MAX_VALUE;
        // Route movement only resets its own stall timer. Climbing the same
        // column in either direction cannot count as excavation/repair progress.
    }
    /** Only consume/issue already planned edges here. Route searches stay in work(). */
    private void completePreciseMovement() {
        if (preciseGoal == null || !preciseMovementComplete()
                || Math.abs(nodeY(preciseGoal)-preciseSurfaceHeight) > .001) return;
        if (debugHistory != null) trace("movement","completed " + Arrays.toString(preciseGoal) + " phase=" + precisePhase);
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
        if (debugHistory != null) movementDecision = "segment rejected: " + segmentFailure;
        ladderDiagnostic("segment failed: " + segmentFailure,requestedY,movedY,nativeY);
        if (preciseGoal != null) {
            rejectMovementEdge(from,preciseGoal); rejectMovementEdge(actual,preciseGoal);
        }
        navigationReason = ladderTravel() ? "ladder_traversal" : precisePhase == PrecisePhase.EXIT_LIFT || precisePhase == PrecisePhase.EXIT
                || precisePhase == PrecisePhase.LANDING ? "ladder_exit" : "stalled";
        navigationDetail = segmentFailure + (preciseGoal == null ? "" : "; " + edgeDiagnostic(from,preciseGoal));
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
        if (navigationBlocked || preciseGoal != null || walkingSegment) return;
        if (enteringRoute && !route.isEmpty()) {
            int[] entry = route.get(0);
            startPreciseMovement(entry,ladderNode(entry)); return;
        }
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
        if (debugHistory != null) trace("movement","walk target=" + Arrays.toString(waypoint) + "; decision=" + movementDecision);
        if (!segmentChunksLoaded(waypoint)) return; // work() owns unloaded-route recovery.
        if (!getNavigator().setPath(new PathEntity(points),1)) {
            preciseGoal = first; failPreciseMovement("native navigator rejected walking segment",0,0,motionY);
        } else walkingSegment = true;
    }
    private void ladderDiagnostic(String reason,double requestedY,double movedY,double nativeY) {
        if (debugHistory != null) trace("movement",reason);
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
        boolean precise = !worldObj.isRemote && (navigationBlocked || !running || preciseGoal != null || holdingLadder());
        if (precise) {
            getNavigator().clearPathEntity();
            getMoveHelper().setMoveTo(posX,Math.floor(boundingBox.minY+.5),posZ,0);
        }
        super.updateAITasks();
        if (precise) { setJumping(false); moveStrafing = moveForward = 0; }
    }
    /** Drive accepted special edges through ordinary collision/step resolution. */
    @Override public void moveEntityWithHeading(float strafe,float forward) {
        if (!worldObj.isRemote && running && !navigationBlocked) {
            if (descentLanding != null && onGround && preciseGoal == null) descentLanding = null;
            completePreciseMovement(); advanceWalkingMovement();
            strafe = moveStrafing; forward = moveForward;
        }
        if (!worldObj.isRemote && running && !navigationBlocked && preciseGoal != null && segmentChunksLoaded(preciseGoal)) {
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
                if (!verticalControl() && isCollidedHorizontally && isOnLadder() && !onGround) {
                    // Native horizontal ladder collision injects +.2 AFTER movement.
                    // An accepted exit/drop owns gravity, not another ascent command.
                    motionY = (Math.min(0,requestedY)-.08)*.9800000190734863;
                }
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
        if (!worldObj.isRemote && (navigationBlocked || !running)) {
            strafe = forward = 0; motionX = motionZ = 0;
        }
        if (holdingLadder) { motionX = motionY = motionZ = 0; strafe = forward = 0; }
        super.moveEntityWithHeading(strafe,forward);
        if (holdingLadder) motionY = 0;
    }
    private boolean holdingLadder() {
        return !worldObj.isRemote && running && !navigationBlocked && preciseGoal == null && getNavigator().noPath()
                && descentLanding == null && !getMoveHelper().isUpdating() && moveForward == 0 && moveStrafing == 0 && isOnLadder()
                && !solidFooting(posX,boundingBox.minY,posZ,-30000001,-1,0);
    }
    private boolean landingInProgress() { return descentLanding != null && (preciseGoal != null || !onGround); }
    private boolean atDestination(int x,int y,int z,boolean mining) {
        StringBuilder detail = new StringBuilder();
        arrivalFailure = interactionFailure(posX,boundingBox.minY,posZ,x,y,z,mining,true,detail);
        if (!arrivalFailure.isEmpty()) {
            navigationDetail = "Interaction rejected at " + posX+","+boundingBox.minY+","+posZ
                    + " target=" + x+","+y+","+z + " predicate=" + arrivalFailure + detail;
        }
        return arrivalFailure.isEmpty();
    }
    private boolean atWorkEndpoint(boolean mining) {
        if (movementRecovery.active() && movementRecovery.localEndpoint != null)
            return atPreciseNode(movementRecovery.localEndpoint,ladderNode(movementRecovery.localEndpoint));
        if (localRecoveryMove != null && localRecoveryCell == null) return atPreciseNode(localRecoveryMove,ladderNode(localRecoveryMove));
        boolean construction = interactionTarget != null && constructionTarget(interactionTarget[0],interactionTarget[1],interactionTarget[2]);
        return !mining && !construction || selectedEndpoint == null
                || atPreciseNode(selectedEndpoint,ladderNode(selectedEndpoint));
    }
    private String interactionFailure(double px,double py,double pz,int x,int y,int z,boolean mining,boolean actual) {
        return interactionFailure(px,py,pz,x,y,z,mining,actual,null);
    }
    private String interactionFailure(double px,double py,double pz,int x,int y,int z,boolean mining,boolean actual,StringBuilder detail) {
        if (!inCorridor((int)Math.floor(px),(int)Math.floor(pz))) return "outside";
        if (!worldObj.blockExists(x,mining ? y-1 : y,z)) return "chunk";
        if (mining && actual && interactionTarget != null && Arrays.equals(interactionTarget,new int[] {x,y,z})
                && (worldObj.getBlock(x,y-1,z) != interactionBlock || worldObj.getBlockMetadata(x,y-1,z) != interactionMeta)) return "target";
        if (Double.isNaN(py)) return "footing";
        if (Math.abs(py-y) > 3) return "elevation";
        if ((px-x-.5)*(px-x-.5)+(py-y)*(py-y)+(pz-z-.5)*(pz-z-.5) > 9) return "reach";
        int rx = mining ? x : -30000001, ry = mining ? y-1 : -1;
        if (!clearBody(px,py,pz,rx,ry,z,detail) || !clearBody(px,py,pz,-30000001,-1,0,detail)) return "clearance";
        if (!mining && buildingAccess && accessIndex < accessPlan.size()) {
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
        if (mining && !sight(px,py+getEyeHeight(),pz,x,y-1,z,detail)) return "sight";
        if (mining && !fallingSafe(x,y-1,z,px,py,pz)) return "falling_terrain";
        if (!mining && !sight(px,py+getEyeHeight(),pz,x,y,z,detail)) return "sight";
        return "";
    }
    private void rememberInteraction(int x,int y,int z,boolean mining) {
        if (accessProposals == null) refreshEndpointContext();
        Block block = mining ? worldObj.getBlock(x,y-1,z) : worldObj.getBlock(x,y,z);
        int meta = worldObj.getBlockMetadata(x,mining ? y-1 : y,z);
        if (!Arrays.equals(interactionTarget,new int[] {x,y,z}) || interactionMining != mining || interactionBlock != block || interactionMeta != meta) {
            boolean changedMiningBlock = mining && interactionMining && Arrays.equals(interactionTarget,new int[] {x,y,z})
                    && (interactionBlock != block || interactionMeta != meta);
            if (debugHistory != null) {
                movementDecision = "interaction " + Arrays.toString(interactionTarget) + " -> " + x+","+y+","+z + " mining=" + mining;
                trace("objective",movementDecision);
            }
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
        StringBuilder detail = new StringBuilder();
        if (interactionTarget != null) interactionFailure(posX,boundingBox.minY,posZ,interactionTarget[0],interactionTarget[1],interactionTarget[2],interactionMining,true,detail);
        return "arrival=" + failure + " task=" + task.key + " target=" + Arrays.toString(interactionTarget)
                + " block=" + Block.blockRegistry.getNameForObject(interactionBlock) + ":" + interactionMeta
                + " actual=" + posX+","+boundingBox.minY+","+posZ + " endpoint=" + Arrays.toString(selectedEndpoint)
                + " distance=" + (destination == null ? -1 : Math.sqrt(getDistanceSq(destination[0]+.5,destination[1],destination[2]+.5)))
                + " ground=" + onGround + " ladder=" + isOnLadder()
                + " visible=" + (interactionTarget != null && sight(posX,boundingBox.minY+getEyeHeight(),posZ,
                        interactionTarget[0],interactionTarget[1]-(interactionMining ? 1 : 0),interactionTarget[2])) + detail;
    }
    private void arrived() {
        if (debugHistory != null) trace("movement","interaction complete at " + Arrays.toString(destination));
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
        // Recovery dispatches here independently of visitChest/work's checks.
        // Do not let rememberInteraction query or load an unavailable destination.
        if (!recovery && (y-(mining ? 1 : 0) < 0 || y-(mining ? 1 : 0) >= worldObj.getHeight()
                || !worldObj.blockExists(x,y-(mining ? 1 : 0),z))) {
            status = "chunk"; navigationReason = "chunk";
            navigationDetail = "Movement destination unavailable: " + x+","+y+","+z;
            return false;
        }
        int[] goal = new int[] {x,y,z};
        if (!recovery) {
            if (!Arrays.equals(destination,goal)) { resetPath(); destination = goal; }
            rememberInteraction(x,y,z,mining);
            // Execute the endpoint whose post-harvest footing/exit was approved.
            // Merely entering reach of the target must not cancel the descent.
            if (preciseGoal == null && atWorkEndpoint(mining) && atDestination(x,y,z,mining) && (!mining && !constructionTarget(x,y,z)
                    || !failedEndpoints.contains(positionKey((int)Math.floor(posX),(int)Math.floor(boundingBox.minY+.001),(int)Math.floor(posZ))))) {
                if (!constructionTarget(x,y,z) || constructionSafe(null,constructionCell(x,y,z))) {
                    arrived(); return true;
                }
                // Try another builder position; do not retry the same unsafe
                // actual position merely because its interaction reach is valid.
                int[] failed = selectedEndpoint == null ? new int[] {(int)Math.floor(posX),(int)Math.floor(boundingBox.minY+.001),(int)Math.floor(posZ)} : selectedEndpoint;
                failedEndpoints.add(positionKey(failed[0],failed[1],failed[2]));
                if (routeSearchRemaining != null) routeSearchRemaining.removeIf(p -> Arrays.equals(p,failed));
                arrivalFailure = "construction_exit";
            }
            if (!constructionTarget(x,y,z) && localRecoveryCell == null && localRecoveryMove == null && localRecoveryDrop == null) {
                if (task == Task.MINING || task == Task.DROPS) transition(Task.TO_WORK,"work position changed",goal);
                else if (storageTask() && task != Task.TO_STORAGE) transition(Task.TO_STORAGE,"station interaction position changed",goal);
            }
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
                if (!recovery && preciseGoal == null && (!atWorkEndpoint(mining)
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
            if (!recovery && mining && accessProposals != null && !movementRecovery.active()) {
                if (planAccess(x,y,z)) return false;
                navigationFailures++; routeFailure();
                status = navigationFailures >= 3 ? "route_failed" : "recovering"; return false;
            }
            List<int[]> goals = recovery ? java.util.Collections.<int[]>emptyList()
                    : standingCandidates(x,y,z,mining);
            if (!recovery && goals.isEmpty()) {
                navigationReason = failedEndpoints.isEmpty() ? "standing" : arrivalFailure;
                navigationDetail = failedEndpoints.isEmpty() ? standingObstruction(x,y,z,mining) : endpointDiagnostic(arrivalFailure);
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
                if (mining && !buildingAccess && !movementRecovery.active() && planAccess(x,y,z)) return false;
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
            if (!candidateFailure(sx,sy,sz,fy,x,y,z,mining,interaction,null).isEmpty()) continue;
            result.add(new int[] {sx,sy,sz});
        }
        java.util.Collections.sort(result,(a,b) -> Double.compare(getDistanceSq(a[0]+.5,a[1],a[2]+.5),getDistanceSq(b[0]+.5,b[1],b[2]+.5)));
        return result;
    }
    private String candidateFailure(int sx,int sy,int sz,double fy,int x,int y,int z,boolean mining,boolean interaction,StringBuilder detail) {
        if (interaction) {
            String failure = interactionFailure(sx+.5,fy,sz+.5,x,y,z,mining,false,detail);
            if (!failure.isEmpty()) return failure;
        } else {
            if (!inCorridor(sx,sz)) return "outside";
            if ((sx-x)*(sx-x)+(fy-y)*(fy-y)+(sz-z)*(sz-z) > 9) return "reach";
            if (!solidFooting(sx+.5,fy,sz+.5,mining ? x : -30000001,mining ? y-1 : -1,z)) return "footing";
        }
        // Post-removal footing cannot authorize occupying an obstruction first.
        double before = mining ? detail == null ? feet(sx,sy,sz,-30000001,-1,0)
                : computeFeet(sx,sy,sz,-30000001,-1,0,detail) : fy;
        return Double.isNaN(before) ? "pre_edit_position" : Math.abs(fy-before) > .06 ? "pre_edit_footing" : "";
    }
    private List<int[]> workPositions() {
        int[] goal = workDestination();
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
        return collisions(box,rx,ry,rz,result,dry,null);
    }
    private boolean collisions(AxisAlignedBB box,int rx,int ry,int rz,List<AxisAlignedBB> result,boolean dry,StringBuilder detail) {
        result.clear();
        for (int x = (int)Math.floor(box.minX); x <= (int)Math.floor(box.maxX); x++)
            for (int z = (int)Math.floor(box.minZ); z <= (int)Math.floor(box.maxZ); z++)
                for (int y = Math.max(0,(int)Math.floor(box.minY)-1); y <= Math.min(worldObj.getHeight()-1,(int)Math.floor(box.maxY)); y++) {
                    watchTerrain(x,y,z);
                    if (!worldObj.blockExists(x,y,z)) {
                        if (detail != null) detail.append(" unloaded=").append(x).append(',').append(y).append(',').append(z);
                        return false;
                    }
                    Block block = routeBlock(x,y,z,rx,ry,rz);
                    if (dry && y+1 > box.minY && block.getMaterial().isLiquid()) {
                        if (detail != null) detail.append(" liquid=").append(blockDescription(x,y,z));
                        return false;
                    }
                    int before = result.size();
                    int[] p = proposedAccess.get(positionKey(x,y,z));
                    if (p != null && !(x == rx && y == ry && z == rz)) {
                        if (p[3] < 0) continue;
                        AxisAlignedBB shape = accessShape(p);
                        if (shape.intersectsWith(box)) result.add(shape);
                    } else block.addCollisionBoxesToList(worldObj,x,y,z,box,result,this);
                    if (detail != null && before == 0 && !result.isEmpty()) detail.append(" collision=").append(blockDescription(x,y,z))
                            .append(" shape=").append(result.get(0));
                }
        return true;
    }
    private boolean clearBody(double x,double y,double z,int rx,int ry,int rz) {
        return clearBody(x,y,z,rx,ry,rz,null);
    }
    private boolean clearBody(double x,double y,double z,int rx,int ry,int rz,StringBuilder detail) {
        return collisions(body(x,y,z),rx,ry,rz,recoveryCollisions,true,detail) && recoveryCollisions.isEmpty();
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
        return computeFeet(x,y,z,rx,ry,rz,null);
    }
    private double computeFeet(int x,int y,int z,int rx,int ry,int rz,StringBuilder detail) {
        feetRejection = "";
        if (y < 0 || y+1 >= worldObj.getHeight() || !recoveryCellLoaded(x,y,z)) { feetRejection = "chunk"; return Double.NaN; }
        boolean attached = ladder(x,y,z,rx,ry,rz);
        if (attached && clearBody(x+.5,y,z+.5,rx,ry,rz,detail)) return y;
        AxisAlignedBB probe = AxisAlignedBB.getBoundingBox(x+.5-width/2,y-.01,z+.5-width/2,x+.5+width/2,y+1,z+.5+width/2);
        if (!collisions(probe,rx,ry,rz,recoveryCollisions,false)) { feetRejection = "chunk"; return Double.NaN; }
        double top = Double.NaN;
        for (AxisAlignedBB shape : recoveryCollisions)
            if (shape.maxY >= y-.001 && shape.maxY < y+1-.001 && (Double.isNaN(top) || shape.maxY > top)) top = shape.maxY;
        if (Double.isNaN(top)) {
            feetRejection = attached || !recoveryCollisions.isEmpty() ? "clearance"
                    : routeBlock(x,y,z,rx,ry,rz).isLadder(worldObj,x,y,z,this) ? "attachment" : "footing";
            if (detail != null) {
                detail.append(" footingProbe=").append(probe).append(" below=").append(blockDescription(x,Math.max(0,y-1),z));
                if (!attached) clearBody(x+.5,y,z+.5,rx,ry,rz,detail);
            }
            return Double.NaN;
        }
        if (clearBody(x+.5,top,z+.5,rx,ry,rz,detail)) return top;
        feetRejection = "clearance"; return Double.NaN;
    }
    private double nodeY(int[] p) { return feet(p[0],p[1],p[2],-30000001,-1,0); }
    private Movement movement(int[] a,int[] b) {
        if (ladderNode(a) && !ladderNode(b)) return Movement.LADDER_EXIT;
        if (ladder(a[0],a[1],a[2],-30000001,-1,0) || ladder(b[0],b[1],b[2],-30000001,-1,0)) return Movement.CLIMB;
        double dy = nodeY(b)-nodeY(a);
        return dy > .01 ? Movement.STEP : dy < -.01 ? Movement.DESCEND : Movement.WALK;
    }
    private boolean sight(double sx,double sy,double sz,int x,int y,int z) {
        return sight(sx,sy,sz,x,y,z,null);
    }
    private boolean sight(double sx,double sy,double sz,int x,int y,int z,StringBuilder detail) {
        for (int cx = Math.min((int)Math.floor(sx),x)>>4; cx <= (Math.max((int)Math.floor(sx),x)>>4); cx++)
            for (int cz = Math.min((int)Math.floor(sz),z)>>4; cz <= (Math.max((int)Math.floor(sz),z)>>4); cz++)
                if (!worldObj.blockExists(cx<<4,y,cz<<4)) return false;
        net.minecraft.util.Vec3 start = net.minecraft.util.Vec3.createVectorHelper(sx,sy,sz);
        net.minecraft.util.Vec3 end = net.minecraft.util.Vec3.createVectorHelper(x+.5,y+.5,z+.5);
        for (int[] p : proposedAccess.values()) if (p[3] >= 0 && !(p[0] == x && p[1] == y && p[2] == z)
                && accessShape(p).calculateIntercept(start,end) != null) {
            if (detail != null) detail.append(" rayProposedHit=").append(Arrays.toString(p)).append(" shape=").append(accessShape(p));
            return false;
        }
        // The world's ray tracer cannot see planned harvesting. Skip only cells
        // explicitly authorized as clearance in this proposal; all other hits count.
        for (int attempt = 0; attempt < 16; attempt++) {
            net.minecraft.util.MovingObjectPosition hit = worldObj.func_147447_a(start,end,false,true,false);
            if (hit == null || hit.blockX == x && hit.blockY == y && hit.blockZ == z) return true;
            int[] cleared = proposedAccess.get(positionKey(hit.blockX,hit.blockY,hit.blockZ));
            if (cleared == null || cleared[3] >= 0) {
                if (detail != null) detail.append(" rayHit=").append(blockDescription(hit.blockX,hit.blockY,hit.blockZ))
                        .append(" face=").append(hit.sideHit).append(" at=").append(hit.hitVec)
                        .append(" shape=").append(worldObj.getBlock(hit.blockX,hit.blockY,hit.blockZ)
                                .getCollisionBoundingBoxFromPool(worldObj,hit.blockX,hit.blockY,hit.blockZ));
                return false;
            }
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
        for (Depot depot : depots) if (depot.dimension == dimension) {
            int wx = Math.max(plan[3],Math.min(plan[5],depot.x)), wz = Math.max(plan[4],Math.min(plan[6],depot.z));
            if (corridorLegDistanceSq(x,z,depot,wx,wz) <= 256
                    || pendingDrops != null && corridorLegDistanceSq(x,z,depot,pendingDrops[0],pendingDrops[2]) <= 256) return true;
        }
        return false;
    }
    private double corridorLegDistanceSq(int x,int z,Depot depot,int wx,int wz) {
        double dx = wx-depot.x, dz = wz-depot.z, length = dx*dx+dz*dz;
        double t = length == 0 ? 0 : Math.max(0,Math.min(1,((x-depot.x)*dx+(z-depot.z)*dz)/length));
        double ox = x-depot.x-t*dx, oz = z-depot.z-t*dz;
        return ox*ox+oz*oz;
    }
    /** Lower bound to any corridor entry, not a commitment to the nearest geometric entry.
     * Every supported entry reached by the search is a candidate, including detours. */
    private int recoveryHeuristic(int x,int z) {
        int dx = Math.max(0,Math.max(plan[3]-8-x,x-plan[5]-8));
        int dz = Math.max(0,Math.max(plan[4]-8-z,z-plan[6]-8));
        double distance = Math.sqrt((double)dx*dx+(double)dz*dz);
        for (Depot depot : depots) if (depot.dimension == dimension) {
            int wx = Math.max(plan[3],Math.min(plan[5],depot.x)), wz = Math.max(plan[4],Math.min(plan[6],depot.z));
            distance = Math.min(distance,Math.max(0,Math.sqrt(corridorLegDistanceSq(x,z,depot,wx,wz))-16));
            if (pendingDrops != null) distance = Math.min(distance,Math.max(0,Math.sqrt(corridorLegDistanceSq(x,z,depot,pendingDrops[0],pendingDrops[2]))-16));
        }
        return (int)Math.floor(10*distance);
    }
    private boolean safeEdge(int[] a,int[] b,int rx,int ry,int rz) {
        return (!proposedAccess.isEmpty() || !failedMovementEdge(a,b)) && edgeFailure(a,b,rx,ry,rz) == EdgeFailure.NONE;
    }
    private String edgeDiagnostic(int[] from,int[] to) {
        StringBuilder detail = new StringBuilder();
        double fy = computeFeet(to[0],to[1],to[2],-30000001,-1,0,detail);
        String predicate = Double.isNaN(fy) ? feetRejection
                : failedMovementEdge(from,to) ? "rejected_transition" : edgeFailure(from,to,-30000001,-1,0).name();
        return "from=" + Arrays.toString(Arrays.copyOf(from,3)) + " rejectedPosition=" + Arrays.toString(Arrays.copyOf(to,3))
                + " predicate=" + predicate + " feet=" + fy + detail;
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
        for (Depot depot : depots) if (depotInventory(depot) != null && (!storageTask() || activeDepot == null || activeDepot.equals(depot))
                && interactionFailure(p[0]+.5,nodeY(p),p[2]+.5,depot.x,depot.y,depot.z,false,false).isEmpty()) return true;
        return false;
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
                        + " -> " + Arrays.toString(Arrays.copyOf(rejectedTo,3)) + " rule=" + rejectedRule + "; " + edgeDiagnostic(rejectedFrom,rejectedTo))
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
        if (localRecoveryDrop != null || localRecoveryMove != null && localRecoveryCell == null)
            return searchRoute(localRecoveryMove == null ? goals : java.util.Collections.singletonList(localRecoveryMove),-30000001,-1,0);
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
                if (construction) accessFailure("approach",constructionCell(x,y,z),navigationDetail);
                else navigationDetail = "Work approach failed; target=" + x+","+(y-1)+","+z + "; " + navigationDetail;
                if ("chunk".equals(navigationReason)) { clearRouteSearch(); return null; }
                routeSearchRemaining.clear(); return null;
            }
            int[] end = found.get(found.size()-1);
            if (construction) {
                if (constructionSafe(found,constructionCell(x,y,z))) { clearRouteSearch(); return found; }
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
        return constructionCell(x,y,z) != null;
    }
    private int[] constructionCell(int x,int y,int z) {
        if (localRecoveryCell != null && localRecoveryCell[0] == x && localRecoveryCell[2] == z
                && localRecoveryCell[1]+(localRecoveryCell[3] < 0 ? 1 : 0) == y) return localRecoveryCell;
        if (!buildingAccess || storageTask() && !depotRepair() || accessIndex >= accessPlan.size()) return null;
        int[] p = accessPlan.get(accessIndex);
        return p[0] == x && p[1]+(p[3] < 0 ? 1 : 0) == y && p[2] == z ? p : null;
    }
    private void accessFailure(String stage,int[] cell,String detail) {
        if (debugHistory != null) trace("rejected",stage + " cell=" + Arrays.toString(cell) + "; " + detail);
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
        for (Depot depot : orderedDepots()) if (depotInventory(depot) != null
                && (!storageTask() || activeDepot == null || activeDepot.equals(depot))) {
            List<int[]> route=routeToDepot(depot,start,rx,ry,rz);
            if (route != null) return route;
        }
        return null;
    }
    private List<int[]> routeToDepot(Depot depot,int[] start,int rx,int ry,int rz) {
        List<int[]> exits = standingCandidates(depot.x,depot.y,depot.z,false);
        if (exits.isEmpty()) {
            navigationReason = "standing"; navigationDetail = "No storage endpoint near " + depot.x+","+depot.y+","+depot.z; return null;
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
        reachableSupplyDepots.clear();
        for (Depot depot : orderedDepots()) if (depotInventory(depot) != null && routeToDepot(depot,null,-30000001,-1,0) != null)
            reachableSupplyDepots.add(depot);
        accessStationReachable = !reachableSupplyDepots.isEmpty();
        accessSupplyChecked = true;
    }
    /** Prove escape in the post-edit graph, independently of the entrance path.
     * The overlay never mutates World, and only this operation is hypothetical. */
    private boolean constructionSafe(List<int[]> approach,int[] cell) {
        int[] end = approach == null ? null : approach.get(approach.size()-1);
        double bx = end == null ? posX : end[0]+.5, bz = end == null ? posZ : end[2]+.5;
        double by = end == null ? boundingBox.minY : nodeY(end);
        boolean hadExit = stationRoute(null) != null;
        if (!hadExit && (constructionRefuge.isEmpty() || !staircaseAccess)) {
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
            if (cell[3] < 0 && !fallingSafe(cell[0],cell[1],cell[2],bx,by,bz)) {
                navigationReason = "falling_terrain";
                accessFailure("falling",cell,"Falling terrain would cross the builder or remove its footing/ladder attachment"); return false;
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
    private boolean excavationArea(int x,int y,int z) {
        return x >= plan[3] && x <= plan[5] && z >= plan[4] && z <= plan[6] && y >= plan[8] && y <= plan[7];
    }
    private String accessSiteFailure(int x,int y,int z) {
        if (y < 0 || y >= worldObj.getHeight()) return "outside world height";
        if (!recoveryCellLoaded(x,y,z)) return "required chunks are not loaded";
        if (!WorkerAccess.ownsLand(worldObj,x,z,factionId)) return "outside faction-owned land";
        return "";
    }
    /** Excavation bounds do not define permission to repair a boundary ladder.
     * Outside edits must belong to its column or an explicitly planned entrance/prerequisite. */
    private boolean accessGeometry(int x,int y,int z) {
        if (excavationArea(x,y,z)) return true;
        int[] fixed = ladderPosition == null ? planningLadder : ladderPosition;
        if (fixed == null) for (int[] p : accessPlan) if (p[3] > 0) { fixed = new int[] {p[0],p[2],p[3]}; break; }
        if (fixed == null) return false;
        int bottom = Integer.MAX_VALUE, top = Integer.MIN_VALUE;
        for (List<int[]> source : java.util.Arrays.asList(ladderAccess,accessBlocks,accessPlan))
            for (int[] p : source) if (p[3] == fixed[2] && p[0] == fixed[0] && p[2] == fixed[1]) {
                bottom = Math.min(bottom,p[1]); top = Math.max(top,p[1]);
            }
        if (top == Integer.MIN_VALUE) { bottom = plan[8]; top = plan[7]; }
        net.minecraftforge.common.util.ForgeDirection d = net.minecraftforge.common.util.ForgeDirection.getOrientation(fixed[2]);
        if (x == fixed[0] && z == fixed[1] && y >= bottom-1 && y <= top+1
                || x == fixed[0]-d.offsetX && z == fixed[1]-d.offsetZ && y >= bottom && y <= top) return true;
        if (ladderLanding != null && x == ladderLanding[0] && z == ladderLanding[2]
                && y >= ladderLanding[1]-1 && y <= ladderLanding[1]+1) return true;
        for (int[] p : ladderAccess) if (p[0] == x && p[1] == y && p[2] == z) return true;
        return false;
    }
    private boolean accessArea(int x,int y,int z) {
        return accessGeometry(x,y,z) && accessSiteFailure(x,y,z).isEmpty();
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
                return solidAttachment(p[0],p[1],p[2],d,-30000001,-1,0) && stableBacking(p[0],p[1],p[2]);
            if (routeBlock(p[0],p[1],p[2],-30000001,-1,0) instanceof net.minecraft.block.BlockFalling) return false;
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
    /** Existing sand is valid backing when its entire loaded column rests on
     * solid terrain. Replacing it behind intact rungs would first require a
     * sight line through those rungs. New placement anchors still exclude sand.
     * Consult the overlay too: a proposed removal must not retain this proof. */
    private boolean stableBacking(int x,int y,int z) {
        if (!(routeBlock(x,y,z,-30000001,-1,0) instanceof net.minecraft.block.BlockFalling)) return true;
        for (int h = y-1; h >= 0; h--) {
            watchTerrain(x,h,z);
            if (!worldObj.blockExists(x,h,z)) return false;
            Block block = routeBlock(x,h,z,-30000001,-1,0);
            if (!(block instanceof net.minecraft.block.BlockFalling))
                return solidAttachment(x,h,z,net.minecraftforge.common.util.ForgeDirection.UP,-30000001,-1,0);
            if (h == 0) return false;
        }
        return false;
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
        if (accessSupplyChecked) for (Depot depot : reachableSupplyDepots) {
            IInventory station=depotInventory(depot);
            if (station != null) for (int i = firstSlot(station); i < station.getSizeInventory(); i++) {
                ItemStack stack = station.getStackInSlot(i);
                if (accessMaterial(stack,rung)) count += stack.stackSize;
            }
        }
        return rung || ladderPosition != null || repair ? count : Math.max(0,count-emergencyBlocks());
    }
    private boolean hasRungs(List<int[]> cells) {
        for (int[] p : cells) if (p[3] > 0) return true;
        return false;
    }
    private String accessActivity() {
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
            if (!accessArea(x,y,z)) {
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
    private boolean startStaircaseFallback(int[] goal) {
        if (goal == null || staircaseAccess || accessCount(false) == 0 || accessSearchExhausted) return false;
        // A shortage is not an obstruction: first reuse any movement-only exit.
        if (ladderRecoveryRoute(goal) != null || stationRoute(null) != null) return false;
        clearAccessSearch(); accessSearchTarget = goal.clone();
        accessProposals = new java.util.ArrayList<List<int[]>>();
        accessColumnIndex = Integer.MAX_VALUE;
        addStaircaseProposals(goal[1]);
        if (accessProposals.isEmpty() && !moreStaircases()) { clearAccessSearch(); return false; }
        buildingAccess = false; reconsideringLadder = false; resetPath();
        recoveryAction = "repairing";
        trace("recovery","no usable ladder exit; evaluate terrain staircase for " + Arrays.toString(goal));
        return true;
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
    /** Select supplied access after existing walking routes fail. Ladder repairs
     * may cross excavation bounds, but never ownership or protection boundaries. */
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
                planningLadder = new int[] {lx,lz,face};
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
                planningLadder = null;
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
            if (!stairs) for (int[] p : proposal) if (p[3] > 0) { planningLadder = new int[] {p[0],p[2],p[3]}; break; }
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
                    List<int[]> goals = standingCandidates(x,y,z,!storageTask());
                    if (goals.isEmpty()) {
                        navigationReason = "standing"; accessFailure("work_position",null,standingObstruction(x,y,z,true)); return true;
                    }
                    approach = executableRoute(goals,x,y,z,!storageTask());
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
                        clearAccessSearch(); resetPath(); resumeAfterAccess("existing staircase verified");
                        if (!storageTask() && approach != null) useRoute(approach);
                        return true;
                    }
                    accessPlan.clear(); accessIndex = 0; buildingAccess = false; reconsideringLadder = false;
                    repairAfterRestock = false;
                    staircaseAccess = repairingLadder = false; constructionRefuge.clear(); failedAccessOperations.clear();
                    clearAccessSearch(); resetPath();
                    resumeAfterAccess("existing ladder verified");
                    if (!storageTask() && approach != null) useRoute(approach);
                    status = "using_ladder";
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
                planningLadder = null;
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
        List<int[]> positions = accessFloorPositions(cells);
        if (positions.isEmpty()) {
            navigationReason = "standing";
            navigationDetail = "No actual dry floor near the required ladder bottom or retained work target=" + Arrays.toString(workPositionTarget);
            return false;
        }
        for (int[] rung : rungs) {
            // Drops are directed. Prove return independently through the maintained
            // ladder, rather than demanding that every entrance edge reverse.
            List<int[]> route = searchRoute(positions,-30000001,-1,0,false,rung);
            if (route == null) continue;
            int[] floor = route.get(route.size()-1);
            if (searchRoute(rungs,-30000001,-1,0,false,floor) != null) return true;
            navigationDetail = "Floor return to maintained ladder from " + Arrays.toString(floor) + "; " + navigationDetail;
        }
        navigationDetail = "Ladder bottom/work-floor connection; work=" + Arrays.toString(workPosition)
                + "; " + navigationDetail;
        return false;
    }
    /** Access repair ends at an actual floor. An AOE-removed or distant retained
     * target must not hold completed repairs hostage to its old interaction radius.
     * Mining still proves reach, footing and a post-harvest exit independently. */
    private List<int[]> accessFloorPositions(List<int[]> cells) {
        List<int[]> floors = new java.util.ArrayList<int[]>(workPositions());
        int bottom = Integer.MAX_VALUE;
        for (int[] p : cells) if (p[3] > 0) bottom = Math.min(bottom,p[1]);
        int[] fixed = ladderPosition == null ? planningLadder : ladderPosition;
        if (fixed == null || bottom == Integer.MAX_VALUE) return floors;
        java.util.Map<Long,int[]> overlay = new java.util.HashMap<Long,int[]>(proposedAccess);
        proposedAccess.clear();
        try {
            for (int radius = 1; radius <= 5; radius++) for (int dx = -radius; dx <= radius; dx++)
                for (int dz = -radius; dz <= radius; dz++) if (Math.abs(dx)+Math.abs(dz) == radius)
                    for (int y = bottom; y >= Math.max(0,bottom-2); y--) {
                        int x = fixed[0]+dx, z = fixed[1]+dz;
                        double feetY = feet(x,y,z,-30000001,-1,0);
                        if (!Double.isNaN(feetY) && inCorridor(x,z) && solidFooting(x+.5,feetY,z+.5,-30000001,-1,0))
                            floors.add(new int[] {x,y,z});
                    }
        } finally { proposedAccess.putAll(overlay); }
        return floors;
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
        accessDestination = goal.clone();
        accessTripFloor = storageTask() ? (int)Math.floor(boundingBox.minY+.001) : -1;
        collectLadderRequirements();
        if (ladderAccess.isEmpty()) return false;
        boolean missing = false;
        for (int[] p : ladderAccess) if (requiredForTrip(p) && !accessPresent(p)) { missing = true; break; }
        // Intact historical rungs do not prove entry from today's exposed floor.
        if (!missing) {
            buildingAccess = staircaseAccess = repairingLadder = false; accessPlan.clear(); accessIndex = 0;
            repairAfterRestock = false; clearAccessSearch();
            return false;
        }
        // A broken exit cannot be a prerequisite for repairing that exit. Each
        // edit is instead checked from reachable actual builder positions.
        accessPlan.clear();
        for (int[] p : ladderAccess) if (requiredForTrip(p)) accessPlan.add(p.clone());
        staircaseAccess = false; repairingLadder = true;
        reconcileAccessPlan(); buildingAccess = true;
        failedAccessOperations.clear(); constructionRefuge.clear();
        if (selectingTarget && !storageTask()) {
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
        if (cell[0] == ladderPosition[0] && cell[2] == ladderPosition[1]) return !ladderBase(cell);
        if (ladderLanding != null && cell[0] == ladderLanding[0] && cell[2] == ladderLanding[2]
                && cell[1] >= ladderLanding[1] && cell[1] <= ladderLanding[1]+1) return true;
        for (int[] p : ladderAccess) if (p[3] != 0 && sameAccessCell(p,cell)) return true;
        return false;
    }
    private boolean ladderBase(int[] cell) {
        if (ladderPosition == null || cell[0] != ladderPosition[0] || cell[2] != ladderPosition[1]) return false;
        int bottom = Integer.MAX_VALUE;
        for (int[] p : ladderAccess) if (p[3] > 0 && fixedRung(p)) bottom = Math.min(bottom,p[1]);
        return bottom != Integer.MAX_VALUE && cell[1] == bottom-1;
    }
    private boolean requireLadderCell(int[] cell) {
        if (releasedAccess(cell[0],cell[1],cell[2])) return false;
        if (cell[3] > 0 && !fixedRung(cell) || cell[3] == 0 && ladderPassage(cell)) return false;
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
    /** Select one short entrance to the current floor, including its soles.
     * Candidate footprints grant no excavation permission until selected. */
    private void reserveLadderEntry(List<int[]> cells) {
        List<int[]> floors = accessFloorPositions(cells);
        if (floors.isEmpty()) return;
        int floor = floors.get(0)[1];
        int entranceFloor = Integer.MAX_VALUE;
        for (int[] p : ladderEntrance) if (p[3] == 0) entranceFloor = Math.min(entranceFloor,p[1]+1);
        if (entranceFloor != floor) ladderEntrance.clear();
        java.util.Map<Long,int[]> saved = new java.util.HashMap<Long,int[]>(proposedAccess);
        try {
            proposedAccess.clear();
            for (int[] p : cells) proposedAccess.put(positionKey(p[0],p[1],p[2]),p);
            if (!ladderEntrance.isEmpty()) {
                for (int[] p : ladderEntrance) {
                    requireLadderCell(p); proposedAccess.put(positionKey(p[0],p[1],p[2]),p);
                }
                // Keep the selected footprint through damage; do not grow another
                // entrance merely because its currently missing blocks need repair.
                return;
            }
            for (int span = 1; span <= 5; span++) for (int dx = -span; dx <= span; dx++) for (int sign : new int[] {-1,1}) {
                int dz = sign*(span-Math.abs(dx));
                if (sign == 1 && dz == 0) continue;
                for (int order = 0; order < 2; order++) {
                    List<int[]> clearance = new java.util.ArrayList<int[]>();
                    int x = ladderPosition[0], z = ladderPosition[1];
                    int ax = x+dx, az = z+dz;
                    boolean existingFloor = false;
                    for (int[] p : floors) if (p[0] == ax && p[1] == floor && p[2] == az) { existingFloor = true; break; }
                    if (!existingFloor) continue;
                    boolean valid = true;
                    for (int distance = 0; valid && distance <= span; distance++) {
                        for (int h = 0; valid && h <= 1; h++) {
                            int[] cell = {x,floor+h,z,-1};
                            int[] existing = proposedAccess.get(positionKey(x,floor+h,z));
                            if (existing != null && existing[3] > 0) continue;
                            if (fixedBacking(cell) || !repairCellAvailable(cell)) { valid = false; break; }
                            clearance.add(cell); proposedAccess.put(positionKey(x,floor+h,z),cell);
                        }
                        if (valid && !(x == ladderPosition[0] && z == ladderPosition[1])) {
                            int[] sole = {x,floor-1,z,0};
                            if (ladderPassage(sole) || !repairCellAvailable(sole)) valid = false;
                            else { clearance.add(sole); proposedAccess.put(positionKey(x,floor-1,z),sole); }
                        }
                        if (distance == span) break;
                        if (order == 0 && x != ax || order == 1 && z == az) x += Integer.signum(ax-x);
                        else z += Integer.signum(az-z);
                    }
                    if (valid && !clearance.isEmpty() && ladderFloorRoute(cells)) {
                        ladderEntrance.clear(); ladderEntrance.addAll(clearance);
                        for (int[] cell : clearance) requireLadderCell(cell);
                        return;
                    }
                    proposedAccess.clear();
                    for (int[] p : cells) proposedAccess.put(positionKey(p[0],p[1],p[2]),p);
                }
            }
        } finally { proposedAccess.clear(); proposedAccess.putAll(saved); }
    }
    private boolean repairCellAvailable(int[] cell) {
        watchTerrain(cell[0],cell[1],cell[2]);
        if (!accessSiteFailure(cell[0],cell[1],cell[2]).isEmpty()) return false;
        if (worldObj.getTileEntity(cell[0],cell[1],cell[2]) != null || BuilderPlacement.protectedBlock(worldObj,cell[0],cell[1],cell[2])) return false;
        Block block = worldObj.getBlock(cell[0],cell[1],cell[2]);
        return !block.getMaterial().isLiquid() && block.getBlockHardness(worldObj,cell[0],cell[1],cell[2]) >= 0;
    }
    private boolean obsoleteLadderSupport(int x,int y,int z) {
        for (int[] p : ladderAccess) if (p[3] == 0 && p[0] == x && p[1] == y && p[2] == z) return false;
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
        if (!storageTask() && !workPositions().isEmpty()) bottom = Math.max(plan[8],Math.min(bottom,workPosition[1]));
        // A fall can strand the worker below the retained target's floor. Repair
        // the SAME column down to that actual supported floor before asking it to climb.
        int actualFloor = (int)Math.floor(boundingBox.minY+.001);
        if (Math.abs(posX-ladderPosition[0]-.5)+Math.abs(posZ-ladderPosition[1]-.5) <= 5
                && supported(posX,boundingBox.minY,posZ,-30000001,-1,0))
            bottom = Math.max(plan[8],Math.min(bottom,actualFloor));
        ladderAccess.clear();
        net.minecraftforge.common.util.ForgeDirection d = net.minecraftforge.common.util.ForgeDirection.getOrientation(ladderPosition[2]);
        if (ladderLanding != null && (ladderLanding[0] == ladderPosition[0]-d.offsetX && ladderLanding[2] == ladderPosition[1]-d.offsetZ
                || Math.abs(ladderLanding[0]-ladderPosition[0])+Math.abs(ladderLanding[2]-ladderPosition[1]) > 1)) ladderLanding = null;
        for (int y = bottom; y <= top; y++) {
            requireLadderCell(new int[] {ladderPosition[0],y,ladderPosition[1],ladderPosition[2]});
            int[] backing = {ladderPosition[0]-d.offsetX,y,ladderPosition[1]-d.offsetZ,0};
            ladderAccess.removeIf(p -> p[3] < 0 && sameAccessCell(p,backing));
            requireLadderCell(backing);
        }
        // The one sole below the fixed shaft is a construction prerequisite,
        // not a connector platform. Keep it stable before replacing backing.
        if (bottom > 0)
            requireLadderCell(new int[] {ladderPosition[0],bottom-1,ladderPosition[1],0});
        if (top+1 < worldObj.getHeight())
            requireLadderCell(new int[] {ladderPosition[0],top+1,ladderPosition[1],-1});
        if (ladderLanding == null) {
            // Include damaged historical landings, but only a one-step exit at
            // the upper end with a station connection. Never adopt a floating tread.
            List<int[]> candidates = new java.util.ArrayList<int[]>();
            for (int y = top+1; y >= top-1; y--) for (int side = 0; side < 4; side++)
                candidates.add(new int[] {ladderPosition[0]+(side == 0 ? 1 : side == 1 ? -1 : 0),y,
                        ladderPosition[1]+(side == 2 ? 1 : side == 3 ? -1 : 0)});
            java.util.Collections.sort(candidates,(a,b) -> Integer.compare(landingRepairCost(a),landingRepairCost(b)));
            for (int[] landing : candidates) {
                if (landing[1] <= bottom) continue;
                List<int[]> historical = new java.util.ArrayList<int[]>();
                boolean repairable = true;
                for (int dy = -1; dy <= 1; dy++) {
                    int[] cell = {landing[0],landing[1]+dy,landing[2],dy < 0 ? 0 : -1};
                    if (dy >= 0 && fixedBacking(cell) || dy < 0 && ladderPassage(cell)) repairable = false;
                    if (!repairCellAvailable(cell)) repairable = false;
                    historical.add(cell);
                }
                if (!repairable) continue;
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
            requireLadderCell(p);
        }
        reserveLadderEntry(ladderAccess);
        requireAttachmentPrerequisites();
        {
            int blocks = 0, rungs = 0;
            for (int[] p : ladderAccess) if (!accessPresent(p)) {
                if (p[3] == 0) blocks++;
                else if (p[3] > 0 && !rungPresent(p)) rungs++;
            }
            restockBlocks = Math.min(restockBlocks,blocks); restockLadders = Math.min(restockLadders,rungs);
        }
    }
    private int landingRepairCost(int[] landing) {
        int cost = 0;
        for (int dy = -1; dy <= 1; dy++)
            if (!accessPresent(new int[] {landing[0],landing[1]+dy,landing[2],dy < 0 ? 0 : -1})) cost++;
        return cost;
    }
    /** Attach required supports to real terrain, exhausting already-required
     * supports first. At most two extra cells connect a floating requirement;
     * those cells never become seeds for unrelated platforms or branches. */
    private void requireAttachmentPrerequisites() {
        List<int[]> pending = new java.util.ArrayList<int[]>();
        for (int[] p : ladderAccess) if (p[3] == 0 && !accessPresent(p)) pending.add(p);
        java.util.Map<Long,int[]> saved = new java.util.HashMap<Long,int[]>(proposedAccess);
        proposedAccess.clear();
        attachmentOperations = 0;
        int added = 0;
        // Do not anchor to a support that must first be cleared (for example falling sand).
        for (int[] p : pending) proposedAccess.put(positionKey(p[0],p[1],p[2]),new int[] {p[0],p[1],p[2],-1});
        try {
            while (!pending.isEmpty()) {
                int before = pending.size();
                for (java.util.Iterator<int[]> it = pending.iterator(); it.hasNext() && attachmentOperations < ATTACHMENT_OPERATIONS;) {
                    int[] p = it.next(); attachmentOperations++;
                    if (placementSide(p) >= 0) { proposedAccess.put(positionKey(p[0],p[1],p[2]),p); it.remove(); }
                }
                if (pending.size() < before) continue;
                if (added >= ATTACHMENT_CELLS || attachmentOperations >= ATTACHMENT_OPERATIONS) break;
                List<int[]> attachment = null;
                int[] target = null;
                for (int depth = 1; depth <= Math.min(ATTACHMENT_DISTANCE,ATTACHMENT_CELLS-added) && attachment == null; depth++) for (int[] p : pending) {
                    java.util.Set<Long> visited = new java.util.HashSet<Long>();
                    visited.add(positionKey(p[0],p[1],p[2]));
                    attachment = attachmentPath(p,depth,visited);
                    if (attachment != null) { target = p; break; }
                }
                if (attachment == null) break; // Execution reports the exact unattached cell.
                for (int[] p : attachment) {
                    requireLadderCell(p); proposedAccess.put(positionKey(p[0],p[1],p[2]),p); added++;
                }
                proposedAccess.put(positionKey(target[0],target[1],target[2]),target); pending.remove(target);
            }
        } finally { proposedAccess.clear(); proposedAccess.putAll(saved); }
    }
    private List<int[]> attachmentPath(int[] cell,int remaining,java.util.Set<Long> visited) {
        for (int face = 0; face < 6 && attachmentOperations < ATTACHMENT_OPERATIONS; face++) {
            attachmentOperations++;
            net.minecraftforge.common.util.ForgeDirection d = net.minecraftforge.common.util.ForgeDirection.getOrientation(face);
            int[] next = {cell[0]-d.offsetX,cell[1]-d.offsetY,cell[2]-d.offsetZ,0};
            long key = positionKey(next[0],next[1],next[2]);
            if (visited.contains(key) || ladderPassage(next) || !repairCellAvailable(next)
                    || !worldObj.isAirBlock(next[0],next[1],next[2])) continue;
            boolean required = false;
            for (int[] p : ladderAccess) if (sameAccessCell(p,next)) { required = true; break; }
            if (required) continue;
            visited.add(key);
            // Register before shared geometry checks; withdraw rejected candidates.
            ladderAccess.add(next);
            List<int[]> path;
            try { path = accessArea(next[0],next[1],next[2]) && placementSide(next) >= 0 ? new java.util.ArrayList<int[]>()
                    : remaining > 1 ? attachmentPath(next,remaining-1,visited) : null;
            } finally { ladderAccess.remove(next); }
            visited.remove(key);
            if (path != null) { path.add(next); return path; }
        }
        return null;
    }
    /** Reconcile requirements, not just BreakEvents: backing loss can detach a
     * rung without firing its own break event. Never restore ordinary terrain. */
    private void reconcileAccessPlan() {
        collectLadderRequirements();
        java.util.Map<Long,int[]> required = new java.util.LinkedHashMap<Long,int[]>();
        for (int[] p : ladderAccess) if (requiredForTrip(p)) required.put(positionKey(p[0],p[1],p[2]),p.clone());
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
        // Secure the sole, then finish each backing/rung stage. A shaft cell
        // above a rung is that stage's headroom, not a reason to clear the whole
        // upper column first. Later AOE damage is reconciled after settling.
        java.util.Collections.sort(accessPlan,(a,b) -> {
            int ay = a[1]-(a[3] < 0 && !fixedBacking(a) ? 1 : 0);
            int by = b[1]-(b[3] < 0 && !fixedBacking(b) ? 1 : 0);
            int order = Integer.compare(ay,by);
            if (order != 0) return order;
            if (a[3] < 0 && b[3] < 0) return Integer.compare(b[1],a[1]);
            return Integer.compare(a[3],b[3]);
        });
        accessIndex = 0;
    }
    private boolean requiredForTrip(int[] cell) {
        return !depotRepair() || accessTripFloor < 0 || cell[1] >= accessTripFloor-1;
    }
    private static boolean accessMaterial(ItemStack stack,boolean rung) {
        if (stack == null || stack.stackSize <= 0 || !(stack.getItem() instanceof net.minecraft.item.ItemBlock)) return false;
        Block b = ((net.minecraft.item.ItemBlock)stack.getItem()).field_150939_a;
        return rung ? b == net.minecraft.init.Blocks.ladder : b.isNormalCube() && !(b instanceof net.minecraft.block.BlockFalling)
                && !b.hasTileEntity(stack.getItemDamage()) && b != net.minecraft.init.Blocks.bedrock && b != net.minecraft.init.Blocks.command_block
                && (b.getMaterial() == net.minecraft.block.material.Material.rock || b.getMaterial() == net.minecraft.block.material.Material.ground)
                && !oreMaterial(stack,b);
    }
    private static boolean oreMaterial(ItemStack stack,Block block) {
        for (int id : net.minecraftforge.oredict.OreDictionary.getOreIDs(stack))
            if (net.minecraftforge.oredict.OreDictionary.getOreName(id).toLowerCase(java.util.Locale.ROOT).startsWith("ore")) return true;
        Object itemId = net.minecraft.item.Item.itemRegistry.getNameForObject(stack.getItem());
        Object blockId = Block.blockRegistry.getNameForObject(block);
        return itemId != null && itemId.toString().toLowerCase(java.util.Locale.ROOT).contains("ore")
                || blockId != null && blockId.toString().toLowerCase(java.util.Locale.ROOT).contains("ore");
    }
    /** Shared order for placement, reserves and withdrawals; suitability always precedes preference. */
    private static int materialRank(ItemStack stack,boolean rung) {
        if (!accessMaterial(stack,rung)) return -1;
        if (rung || ((net.minecraft.item.ItemBlock)stack.getItem()).field_150939_a == net.minecraft.init.Blocks.cobblestone) return 0;
        for (int id : net.minecraftforge.oredict.OreDictionary.getOreIDs(stack))
            if ("cobblestone".equals(net.minecraftforge.oredict.OreDictionary.getOreName(id))) return 1;
        return 2;
    }
    private int accessSlot(boolean rung) {
        int best = -1, rank = Integer.MAX_VALUE;
        for (int i = 0; i < cargo.length; i++) {
            int next = materialRank(cargo[i],rung);
            if (next >= 0 && next < rank) { best = i; rank = next; }
        }
        return best;
    }
    private boolean reserveWorkSupplies() {
        return configured && (blockWorkRemaining() || pendingDrops != null && !pendingDropsCaptured);
    }
    private int[] cargoReservations() {
        int[] reserved = new int[cargo.length];
        if (!reserveWorkSupplies()) return reserved;
        int blocks = Math.max(requiredSupports(),supportTarget());
        // Spare backing must not occupy cargo needed for a mandatory delivery.
        // All carried ladders still remain reserved throughout active work.
        if (accessCount(true) < requiredLadders() || needsLight && torchCount() == 0
                || blockWorkRemaining() && !suitable(getHeldItem())) blocks = requiredSupports();
        for (int rank = 0; rank < 3; rank++) for (int i = 0; i < cargo.length; i++) {
            if (accessMaterial(cargo[i],true)) reserved[i] = cargo[i].stackSize; // Future descent/repair, even with no current missing rungs.
            else if (materialRank(cargo[i],false) == rank) { reserved[i] = Math.min(blocks,cargo[i].stackSize); blocks -= reserved[i]; }
        }
        return reserved;
    }
    private int accessCount(boolean rung) {
        int count = 0; for (ItemStack stack : cargo) if (accessMaterial(stack,rung)) count += stack.stackSize; return count;
    }
    private long ladderSupplyStamp() {
        return supplyStamp(true);
    }
    private void reconsiderStaircase() {
        if (restockLadders > 0) return;
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
        if (debugHistory != null) {
            movementDecision = "defer " + Arrays.toString(cell) + ": " + navigationReason + "; " + accessBlocker;
            trace("recovery",movementDecision);
        }
        if (cell == localRecoveryCell) {
            rejectedRecovery.add(positionKey(cell[0],cell[1],cell[2]));
            recoveryBlocker = navigationReason + ": " + accessBlocker;
            localRecoveryCell = localRecoveryMove = null; localRecoveryEdits.clear();
            resetPath(); clearRouteSearch(); failedEndpoints.clear(); return;
        }
        int[] prerequisite = unfinishedAccessPrerequisite(cell);
        // A dependency wait is not an exhausted endpoint or movement attempt.
        // Actual placement/harvest progress clears endpoint failures below.
        if (prerequisite == null) failedAccessOperations.add(cell);
        else if (debugHistory != null) trace("construction","pending " + Arrays.toString(cell)
                + "; unfinished prerequisite=" + Arrays.toString(prerequisite));
        String failedReason = navigationReason, failedDetail = navigationDetail, failedBlocker = accessBlocker;
        resetPath(); clearRouteSearch(); interactionTarget = selectedEndpoint = null; failedEndpoints.clear();
        for (int i = accessIndex+1; i < accessPlan.size(); i++) if (!accessOperationComplete(accessPlan.get(i))
                && !failedAccessOperations.contains(accessPlan.get(i)) && clearanceOrderAllows(accessPlan.get(i))
                && (accessPlan.get(i)[3] < 0 || placementSide(accessPlan.get(i)) >= 0)) {
            int[] next = accessPlan.remove(i); accessPlan.add(accessIndex,next);
            navigationReason = navigationDetail = arrivalFailure = ""; status = accessActivity();
            diagnostic("access","try another pending access operation after " + Arrays.toString(cell)); return;
        }
        navigationReason = failedReason.isEmpty() ? "construction_exit" : failedReason;
        navigationDetail = failedDetail + "; all remaining access operations exhausted their reachable builder positions or prerequisites";
        accessBlocker = failedBlocker;
        if (tryLocalRecovery()) return;
        blockObjective("no_access");
    }
    private boolean selectReadyAccessOperation() {
        for (int i = accessIndex+1; i < accessPlan.size(); i++) {
            int[] next = accessPlan.get(i);
            if (accessOperationComplete(next) || failedAccessOperations.contains(next) || !clearanceOrderAllows(next)
                    || next[3] >= 0 && placementSide(next) < 0) continue;
            int[] waiting = accessPlan.get(accessIndex);
            accessPlan.remove(i); accessPlan.add(accessIndex,next);
            if (debugHistory != null) {
                movementDecision = "prerequisite " + Arrays.toString(next) + " before " + Arrays.toString(waiting);
                trace("recovery",movementDecision);
            }
            resetPath(); clearRouteSearch(); interactionTarget = selectedEndpoint = null; failedEndpoints.clear();
            return true;
        }
        return false;
    }
    private boolean clearanceOrderAllows(int[] cell) {
        if (staircaseAccess || cell[3] < 0) return true;
        if (unfinishedAccessPrerequisite(cell) != null) return false;
        if (cell[3] > 0) {
            net.minecraftforge.common.util.ForgeDirection d = net.minecraftforge.common.util.ForgeDirection.getOrientation(cell[3]);
            return solidAttachment(cell[0]-d.offsetX,cell[1],cell[2]-d.offsetZ,d,-30000001,-1,0);
        }
        return true;
    }
    private int[] unfinishedAccessPrerequisite(int[] cell) {
        if (staircaseAccess || cell[3] < 0) return null;
        for (int[] p : accessPlan) if (p[3] < 0 && p[0] == cell[0] && p[2] == cell[2]
                && (p[1] == cell[1] || cell[3] > 0 && p[1] == cell[1]+1) && !accessOperationComplete(p)) return p;
        if (cell[3] > 0) {
            net.minecraftforge.common.util.ForgeDirection d = net.minecraftforge.common.util.ForgeDirection.getOrientation(cell[3]);
            for (int[] p : accessPlan) if (p[3] == 0 && p[0] == cell[0]-d.offsetX && p[1] == cell[1]
                    && p[2] == cell[2]-d.offsetZ && !accessOperationComplete(p)) return p;
        }
        return null;
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
        if (ladderPosition == null || staircaseAccess || cell[3] <= 0) return false;
        net.minecraftforge.common.util.ForgeDirection d = net.minecraftforge.common.util.ForgeDirection.getOrientation(ladderPosition[2]);
        int[] backing = {cell[0]-d.offsetX,cell[1],cell[2]-d.offsetZ,0};
        for (int[] p : accessPlan) if (Arrays.equals(p,backing)) return false;
        if (!repairCellAvailable(backing) || !requireLadderCell(backing)) return false;
        if (!accessArea(backing[0],backing[1],backing[2])) return false;
        reconcileAccessPlan(); return true;
    }
    /** Geometry proof only; actual traversal is checked during useful travel. */
    private boolean ladderRouteAvailable() {
        return ladderPosition != null && ladderLanding != null && ladderFloorRoute(ladderAccess)
                && stationRoute(new int[] {ladderPosition[0],lowestRequiredRung(),ladderPosition[1]}) != null;
    }
    private int lowestRequiredRung() {
        int bottom = Integer.MAX_VALUE;
        for (int[] p : ladderAccess) if (p[3] > 0 && fixedRung(p)) bottom = Math.min(bottom,p[1]);
        return bottom;
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
    private int supportRequirement() {
        if (depotRepair()) return accessNeeded(false);
        if (ladderPosition == null) return Math.max(restockBlocks,accessNeeded(false));
        int needed = 0;
        for (int[] p : ladderAccess) if (p[3] == 0 && !accessPresent(p)) needed++;
        return Math.max(needed,accessNeeded(false));
    }
    private int ladderRequirement() {
        if (depotRepair()) return accessNeeded(true);
        if (ladderPosition == null) return 0;
        int need = 0;
        for (int[] rung : ladderAccess) if (rung[3] > 0 && fixedRung(rung) && !rungPresent(rung)) need++;
        return Math.max(accessNeeded(true),need);
    }
    private int retainedMaterialRequirement(boolean rung) {
        int count = 0;
        for (int[] cell : ladderAccess) if ((rung ? cell[3] > 0 : cell[3] == 0) && !accessPresent(cell)) count++;
        return Math.max(count,accessNeeded(rung));
    }
    /** Vanilla 1.7.10 schedules BlockFalling two ticks later; stacked sources and
     * neighbor-detached rungs can keep changing after the tool callback returns. */
    private boolean gravityAir(int x,int y,int z) {
        return gravityRemoved.containsKey(positionKey(x,y,z)) || net.minecraft.block.BlockFalling.func_149831_e(worldObj,x,y,z);
    }
    private boolean fallingSafe(int x,int y,int z,double bx,double by,double bz) {
        if (gravityTarget == null || gravityTarget[0] != x || gravityTarget[1] != y || gravityTarget[2] != z) {
            gravityTarget = new int[] {x,y,z}; gravityRemoved.clear(); gravitySweeps.clear();
            gravityRemoved.put(positionKey(x,y,z),new int[] {x,y,z,-1});
            // Bottom-up propagation also models a rung disappearing when its
            // backing falls. Do not replace falling blocks with imaginary stone.
            for (int pass = 0; pass < 3; pass++) for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++)
                for (int h = Math.max(1,y-1); h < worldObj.getHeight(); h++) {
                    int cx = x+dx, cz = z+dz;
                    if (!worldObj.blockExists(cx,h,cz) || !worldObj.blockExists(cx,h-1,cz)) break;
                    long key = positionKey(cx,h,cz);
                    if (gravityRemoved.containsKey(key)) continue;
                    Block b = worldObj.getBlock(cx,h,cz);
                    if (b instanceof net.minecraft.block.BlockLadder) {
                        int face = worldObj.getBlockMetadata(cx,h,cz);
                        if (face >= 2 && face <= 5) {
                            net.minecraftforge.common.util.ForgeDirection d = net.minecraftforge.common.util.ForgeDirection.getOrientation(face);
                            if (gravityRemoved.containsKey(positionKey(cx-d.offsetX,h,cz-d.offsetZ)))
                                gravityRemoved.put(key,new int[] {cx,h,cz,-1});
                        }
                    } else if (b instanceof net.minecraft.block.BlockFalling && gravityAir(cx,h-1,cz)) {
                        int landing = h-1;
                        while (landing > 0 && worldObj.blockExists(cx,landing-1,cz) && gravityAir(cx,landing-1,cz)) landing--;
                        gravitySweeps.add(new int[] {cx,landing,cz,h});
                        gravityRemoved.put(key,new int[] {cx,h,cz,-1});
                    }
                }
        }
        AxisAlignedBB builder = body(bx,by,bz);
        for (int[] fall : gravitySweeps) if (AxisAlignedBB.getBoundingBox(fall[0],fall[1],fall[2],fall[0]+1,fall[3]+1,fall[2]+1).intersectsWith(builder)) return false;
        // Preserve a real sole or attached rung after all predicted sources leave.
        java.util.Map<Long,int[]> saved = new java.util.HashMap<Long,int[]>(proposedAccess);
        try {
            proposedAccess.putAll(gravityRemoved);
            return clearBody(bx,by,bz,-30000001,-1,0) && supported(bx,by,bz,-30000001,-1,0);
        } finally { proposedAccess.clear(); proposedAccess.putAll(saved); }
    }
    private void watchFallingTerrain(int x,int y,int z) {
        for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++)
            for (int h = Math.max(0,Math.min(plan[8],y-2)); h < worldObj.getHeight(); h++) {
                int cx = x+dx, cz = z+dz; long key = positionKey(cx,h,cz);
                if (settlingTerrain.size() >= 8192 || !worldObj.blockExists(cx,h,cz)) break;
                if (!settlingTerrain.containsKey(key)) settlingTerrain.put(key,new int[] {cx,h,cz,
                        Block.getIdFromBlock(worldObj.getBlock(cx,h,cz)),worldObj.getBlockMetadata(cx,h,cz)});
            }
        settlingQuietTicks = 0;
    }
    private void invalidateRecoveryGeometry() {
        movementRecovery.clear();
        gravityTarget = null; resetPath(); returnRoute.clear(); clearRouteSearch(); clearAccessSearch();
        localRecoveryCell = localRecoveryMove = null; localRecoveryDrop = null; localRecoveryEdits.clear();
        gatheringSupport = false; supplySearch = 0; supplyOrigin = null; recoveryAction = recoveryBlocker = "";
        failedEndpoints.clear(); failedMovementEdges.clear(); failedAccessOperations.clear(); rejectedRecovery.clear();
        interactionTarget = selectedEndpoint = null; accessSupplyChecked = false; accessRepairPending = true;
        navigationBlocked = false;
    }
    private boolean observeRecoveryTerrain() {
        gravityTarget = null;
        boolean changed = false;
        java.util.Set<Long> required = new java.util.HashSet<Long>();
        for (int[] p : ladderAccess) {
            watchTerrain(p[0],p[1],p[2]);
            long key = positionKey(p[0],p[1],p[2]); required.add(key);
            boolean loaded = worldObj.blockExists(p[0],p[1],p[2]);
            int block = loaded ? Block.getIdFromBlock(worldObj.getBlock(p[0],p[1],p[2])) : -1;
            int meta = loaded ? worldObj.getBlockMetadata(p[0],p[1],p[2]) : 0;
            int[] previous = ladderTerrain.put(key,new int[] {block,meta});
            if (previous != null && (previous[0] != block || previous[1] != meta)) changed = true;
        }
        ladderTerrain.keySet().retainAll(required);
        int minX = (int)Math.floor(posX)-3, maxX = minX+6, minZ = (int)Math.floor(posZ)-3, maxZ = minZ+6;
        for (int[] p : settlingTerrain.values()) {
            minX = Math.min(minX,p[0]); maxX = Math.max(maxX,p[0]); minZ = Math.min(minZ,p[2]); maxZ = Math.max(maxZ,p[2]);
            if (!worldObj.blockExists(p[0],p[1],p[2])) continue;
            int block = Block.getIdFromBlock(worldObj.getBlock(p[0],p[1],p[2])), meta = worldObj.getBlockMetadata(p[0],p[1],p[2]);
            if (block != p[3] || meta != p[4]) { p[3] = block; p[4] = meta; changed = true; }
        }
        boolean falling = !worldObj.getEntitiesWithinAABB(net.minecraft.entity.item.EntityFallingBlock.class,
                AxisAlignedBB.getBoundingBox(minX,0,minZ,maxX+1,worldObj.getHeight(),maxZ+1)).isEmpty();
        if (changed) {
            if (navigationBlocked) retryObjective("delayed falling terrain or fixed-ladder geometry changed");
            invalidateRecoveryGeometry(); settlingQuietTicks = 0;
        }
        if (!changed && !falling) settlingQuietTicks = Math.min(20,settlingQuietTicks+10);
        if (falling || !settlingTerrain.isEmpty() && settlingQuietTicks < 20) {
            clearMovementCommands(); preciseGoal = null;
            status = "settling_terrain"; recoveryAction = "settling";
            if (falling) settlingQuietTicks = 0;
            return true;
        }
        if (!settlingTerrain.isEmpty()) {
            // Quiet time is not a terrain change. In particular, a canceled
            // harvest must not erase its rejection and retry forever.
            settlingTerrain.clear();
            if ("settling".equals(recoveryAction)) recoveryAction = "";
        }
        return false;
    }
    private EntityItem usefulRecoveryDrop() {
        if (!configured || ladderPosition == null) return null;
        List<EntityItem> drops = worldObj.getEntitiesWithinAABB(EntityItem.class,
                AxisAlignedBB.getBoundingBox(posX-6,boundingBox.minY-3,posZ-6,posX+6,boundingBox.minY+4,posZ+6));
        EntityItem nearest = null; double best = Double.MAX_VALUE;
        for (EntityItem drop : drops) {
            if (drop.isDead || !accessMaterial(drop.getEntityItem(),true) && !accessMaterial(drop.getEntityItem(),false)) continue;
            if (accessMaterial(drop.getEntityItem(),true) ? requiredLadders() <= accessCount(true) : requiredSupports() <= accessCount(false)) continue;
            int x = (int)Math.floor(drop.posX), y = (int)Math.floor(drop.posY), z = (int)Math.floor(drop.posZ);
            if (!accessArea(x,y,z) || rejectedRecovery.contains(positionKey(x,y,z)) || remainingCapacity(drop.getEntityItem()) == 0) continue;
            double distance = getDistanceSqToEntity(drop);
            if (distance < best) { best = distance; nearest = drop; }
        }
        return nearest;
    }
    /** Called only after all currently possible edits were attempted. */
    private boolean tryLocalRecovery() {
        if (ladderPosition == null || staircaseAccess) return false;
        // Movement owns the first response to failed travel/build approaches.
        if (movementRecovery.begin("no_access")) return true;
        localRecoveryDrop = usefulRecoveryDrop();
        if (localRecoveryDrop != null) {
            recoveryAction = "collecting";
            if (debugHistory != null) { movementDecision = "collect reachable repair supplies"; trace("recovery",movementDecision); }
            return true;
        }
        if (!accessSupplyChecked) checkAccessStation();
        if (!storageTask() && accessStationReachable && (requiredSupports() > accessCount(false) || requiredLadders() > accessCount(true))) {
            beginStorage(StorageReason.ACCESS); return true;
        }
        if (requiredSupports() > accessCount(false)) {
            if (!suitable(getHeldItem())) recoveryBlocker = "Missing suitable pickaxe for local repair materials";
            else if (!cargoFull()) {
                gatheringSupport = true; recoveryAction = "searching_supplies";
                if (supplyOrigin == null) supplyOrigin = new int[] {(int)Math.floor(posX),(int)Math.floor(boundingBox.minY),(int)Math.floor(posZ)};
                if (supplySearch < 13*13*7) return true;
                gatheringSupport = false;
                recoveryBlocker = "Local material search exhausted: protected=" + supplyProtected + ", unsuitable drop hints=" + supplyUnsuitable
                        + ", missing tool tier=" + supplyWrongTool + ", unreachable/safety rejected=" + supplyUnreachable + "; " + recoveryBlocker;
            } else recoveryBlocker = "No cargo capacity for actual local repair drops";
        }
        if (planLocalExcavation()) return true;
        if (requiredLadders() > accessCount(true)) {
            if (accessCount(true) == 0 && startStaircaseFallback(taskDestination())) return true;
            recoveryBlocker = "Missing " + (requiredLadders()-accessCount(true)) + " ladders; no reachable dropped ladders or station";
        }
        if (recoveryBlocker.isEmpty()) recoveryBlocker = !navigationDetail.isEmpty() ? navigationDetail
                : !accessBlocker.isEmpty() ? accessBlocker : "No executable builder endpoint for the pending required access operation";
        navigationDetail += "; local recovery: " + recoveryBlocker;
        accessBlocker += "; " + recoveryBlocker;
        recoveryAction = "blocked";
        return false;
    }
    private String supplyBlockKey(Block block,int meta) { return Block.getIdFromBlock(block)+":"+meta; }
    private boolean findLocalSupply() {
        int end = Math.min(13*13*7,supplySearch+64);
        FakePlayer player = actor(); player.setPosition(posX,posY,posZ); player.onGround = onGround; player.inventory.mainInventory[0] = getHeldItem();
        for (; supplySearch < end; supplySearch++) {
            int i = supplySearch, x = supplyOrigin[0]+i%13-6, z = supplyOrigin[2]+i/13%13-6, y = supplyOrigin[1]+i/169-3;
            long key = positionKey(x,y,z); watchTerrain(x,y,z);
            int[] cell = {x,y,z,-1};
            if (!excavationArea(x,y,z) || !accessArea(x,y,z) || reservedAccess(x,y,z) || rejectedRecovery.contains(key)) continue;
            Block b = worldObj.getBlock(x,y,z); int meta = worldObj.getBlockMetadata(x,y,z);
            if (worldObj.isAirBlock(x,y,z)) continue;
            if (!accessHarvestable(cell)) { supplyProtected++; continue; }
            if (!b.canHarvestBlock(player,meta)) { supplyWrongTool++; continue; }
            if (unsuitableRecoveryDrops.contains(supplyBlockKey(b,meta)) || b instanceof net.minecraft.block.BlockFalling) { supplyUnsuitable++; continue; }
            // Selection is only a hint. Custom tool/Forge drops are authoritative;
            // never roll getDrops(), award guessed cobble, or change tool modes.
            net.minecraft.item.Item predicted = b.getItemDropped(meta,new java.util.Random(0),0);
            if (predicted == null || !accessMaterial(new ItemStack(predicted,1,b.damageDropped(meta)),false)) { supplyUnsuitable++; continue; }
            localRecoveryCell = cell;
            List<int[]> builders = standingCandidates(x,y+1,z,true,false);
            if (searchRoute(builders,-30000001,-1,0) != null) { supplySearch++; recoveryAction = "harvesting_supplies"; resetPath(); return true; }
            localRecoveryCell = null; rejectedRecovery.add(key); supplyUnreachable++;
        }
        return supplySearch < 13*13*7;
    }
    /** Open a short, body-sized passage over real footing, including an upward
     * step. Only an actual connection to existing access can authorize one tread. */
    private boolean planLocalExcavation() {
        int sx = (int)Math.floor(posX), sy = (int)Math.floor(boundingBox.minY+.001), sz = (int)Math.floor(posZ);
        int[] objective = {ladderPosition[0],sy+1,ladderPosition[1]};
        for (int[] p : accessPlan) if (!accessOperationComplete(p)) { objective = Arrays.copyOf(p,3); break; }
        double current = getDistanceSq(objective[0]+.5,objective[1],objective[2]+.5);
        for (int rise = 0; rise <= 1; rise++) for (int span = 1; span <= 4; span++)
            for (int dx = -span; dx <= span; dx++) for (int sign : new int[] {-1,1}) {
                int dz = sign*(span-Math.abs(dx)); if (sign == 1 && dz == 0) continue;
                int[] goal = {sx+dx,sy+rise,sz+dz};
                double gx = goal[0]-objective[0], gy = goal[1]-objective[1], gz = goal[2]-objective[2];
                if (gx*gx+gy*gy+gz*gz >= current-.01 || rejectedRecovery.contains(positionKey(goal[0],goal[1],goal[2]))) continue;
                List<int[]> edits = new java.util.ArrayList<int[]>();
                boolean valid = true;
                if (rise > 0 && !worldObj.isAirBlock(sx,sy+2,sz)) {
                    int[] lift = {sx,sy+2,sz,-1};
                    if (fixedBacking(lift) || !accessHarvestable(lift,true) || rejectedRecovery.contains(positionKey(sx,sy+2,sz))) continue;
                    edits.add(lift);
                }
                for (int h = 1; h >= 0; h--) {
                    int[] cell = {goal[0],goal[1]+h,goal[2],-1};
                    watchTerrain(cell[0],cell[1],cell[2]);
                    if (!accessArea(cell[0],cell[1],cell[2])) { valid = false; break; }
                    if (worldObj.isAirBlock(cell[0],cell[1],cell[2]) || ladder(cell[0],cell[1],cell[2],-30000001,-1,0)) continue;
                    if (fixedBacking(cell) || !accessHarvestable(cell,true) || rejectedRecovery.contains(positionKey(cell[0],cell[1],cell[2]))) { valid = false; break; }
                    edits.add(cell);
                }
                if (!valid) continue;
                int[] tread = null;
                try {
                    for (int[] p : edits) proposedAccess.put(positionKey(p[0],p[1],p[2]),p);
                    if (!standing(goal[0],goal[1],goal[2],-30000001,-1,0)) {
                        tread = new int[] {goal[0],goal[1]-1,goal[2],0};
                        if (rise == 0 || accessCount(false) == 0 || !failedMovementEdges.isEmpty() || ladderPassage(tread)
                                || !accessArea(tread[0],tread[1],tread[2]) || !worldObj.isAirBlock(tread[0],tread[1],tread[2])
                                || Math.abs(goal[0]-ladderPosition[0])+Math.abs(goal[2]-ladderPosition[1]) > 1 || placementSide(tread) < 0) continue;
                        proposedAccess.put(positionKey(tread[0],tread[1],tread[2]),tread);
                    }
                    if (searchRoute(java.util.Collections.singletonList(goal),-30000001,-1,0) == null) continue;
                    // A supplied tread must connect to an existing attached rung
                    // or station NOW; it is never a growing platform workaround.
                    if (tread != null) {
                        List<int[]> rungs = new java.util.ArrayList<int[]>();
                        for (int[] p : ladderAccess) if (p[3] > 0 && accessPresent(p)) rungs.add(Arrays.copyOf(p,3));
                        if (searchRoute(rungs,-30000001,-1,0,false,goal) == null && stationRoute(goal) == null) continue;
                    }
                } finally { proposedAccess.clear(); }
                if (edits.isEmpty() && tread == null) continue;
                // Harvest highest headroom first; after each real edit, replan.
                localRecoveryEdits.clear(); localRecoveryEdits.addAll(edits);
                if (tread != null) localRecoveryEdits.add(tread);
                localRecoveryCell = localRecoveryEdits.remove(0); localRecoveryMove = goal;
                recoveryAction = "digging_out";
                if (debugHistory != null) { movementDecision = "local excavation " + Arrays.toString(localRecoveryCell); trace("recovery",movementDecision); }
                resetPath(); return true;
            }
        return false;
    }
    private boolean runLocalRecovery() {
        if (storageTask() && !depotRepair()) return false;
        if (localRecoveryDrop != null) {
            EntityItem drop = localRecoveryDrop;
            if (drop.isDead) { localRecoveryDrop = null; return true; }
            if (accessMaterial(drop.getEntityItem(),true) ? requiredLadders() <= accessCount(true) : requiredSupports() <= accessCount(false)) {
                localRecoveryDrop = null; resetPath(); return true;
            }
            int x = (int)Math.floor(drop.posX), y = (int)Math.floor(drop.posY), z = (int)Math.floor(drop.posZ);
            recoveryAction = "collecting"; status = "recovering_supplies";
            if (atDestination(x,y,z,false)) {
                ItemStack stack = drop.getEntityItem(); int before = stack.stackSize; insert(this,stack);
                if (stack.stackSize <= 0) drop.setDead();
                if (stack.stackSize < before) { usefulProgress("collected actual dropped repair supplies"); failedAccessOperations.clear(); }
                else rejectedRecovery.add(positionKey(x,y,z));
                localRecoveryDrop = null; resetPath(); return true;
            }
            if (!moveNear(x,y,z,false) && "route_failed".equals(status)) {
                rejectedRecovery.add(positionKey(x,y,z)); localRecoveryDrop = null; resetPath();
            }
            return true;
        }
        if (localRecoveryCell != null) {
            if (gatheringSupport && requiredSupports() <= accessCount(false)) {
                localRecoveryCell = null; gatheringSupport = false; resetPath(); return true;
            }
            int[] cell = localRecoveryCell;
            if (cell[3] >= 0) {
                if (accessPresent(cell)) { localRecoveryCell = null; return true; }
                if (accessCount(false) == 0) { recoveryBlocker = "Missing one stable block for emergency access step"; deferAccessOperation(cell); return true; }
                if (!moveNear(cell[0],cell[1],cell[2],false)) {
                    if ("route_failed".equals(status)) deferAccessOperation(cell);
                    return true;
                }
                if (!constructionSafe(null,cell) || !placeAccess(cell)) { deferAccessOperation(cell); return true; }
                localRecoveryCell = null; usefulProgress("placed real emergency access step"); return true;
            }
            if (worldObj.isAirBlock(cell[0],cell[1],cell[2])) { localRecoveryCell = null; return true; }
            clearAccessCell(cell); return true;
        }
        if (!localRecoveryEdits.isEmpty()) { localRecoveryCell = localRecoveryEdits.remove(0); resetPath(); return true; }
        if (localRecoveryMove != null) {
            int[] goal = localRecoveryMove;
            recoveryAction = "reaching_access";
            if (moveNear(goal[0],goal[1],goal[2],false)) {
                localRecoveryMove = null; failedAccessOperations.clear(); recoveryAction = ""; resetPath();
            } else if ("route_failed".equals(status)) {
                rejectedRecovery.add(positionKey(goal[0],goal[1],goal[2])); localRecoveryMove = null; resetPath();
            }
            return true;
        }
        if (gatheringSupport) {
            if (requiredSupports() <= accessCount(false)) { gatheringSupport = false; recoveryAction = ""; failedAccessOperations.clear(); return true; }
            if (findLocalSupply()) { status = "recovering_supplies"; return true; }
            gatheringSupport = false;
            // Re-enter construction once, then its exhausted-edit handler tries
            // excavation or reports the retained, specific local blocker.
            return true;
        }
        return false;
    }
    private void constructAccess() {
        if (!buildingAccess) return;
        recoveryAction = "repairing";
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
        refreshSupplyRequirements();
        if (supplyOperation != null && supplyBlocker.isEmpty() && accessIndex < accessPlan.size() && accessPlan.get(accessIndex) != supplyOperation) {
            accessPlan.remove(supplyOperation); accessPlan.add(accessIndex,supplyOperation);
            resetPath(); clearRouteSearch(); interactionTarget = selectedEndpoint = null; failedEndpoints.clear();
        }
        if (!staircaseAccess && ladderPosition == null && !accessSearchExhausted && accessNeeded(true) > availableAccess(true)
                && (supplyOperation == null || !supplyBlocker.isEmpty())) {
            // Also migrate an unfinished ladder-only plan from an older save, or
            // react to removed supplies. Keep its real built cells and fixed column.
            int[] goal = accessDestination == null ? workDestination() : accessDestination;
            if (goal == null) goal = depotDestination();
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
                if (debugHistory != null) trace("construction","operation already satisfied " + Arrays.toString(p));
                if (accessPresent(p)) retainAccess(p);
                accessIndex++; continue;
            }
            String siteFailure = accessSiteFailure(p[0],p[1],p[2]);
            if (!siteFailure.isEmpty() || !accessGeometry(p[0],p[1],p[2])) {
                navigationReason = "access_blocked";
                accessFailure("cells",p,siteFailure.isEmpty() ? "Cell is not required by the fixed ladder or its entrances" : siteFailure);
                deferAccessOperation(p); return;
            }
            if (!accessArea(p[0],p[1],p[2]) || (p[3] < 0 ? !accessHarvestable(p,true) : !worldObj.isAirBlock(p[0],p[1],p[2]) && !rungPresent(p))) {
                navigationReason = "access_blocked"; accessFailure("cells",p,"Access cell occupied/unavailable: " + worldObj.getBlock(p[0],p[1],p[2]).getUnlocalizedName());
                deferAccessOperation(p); return;
            }
            if (p[3] < 0) { clearAccessCell(p); return; }
            if (!staircaseAccess && (!clearanceOrderAllows(p) || placementSide(p) < 0) && selectReadyAccessOperation()) return;
            if (!staircaseAccess && !clearanceOrderAllows(p)) {
                if (p[3] > 0 && placementSide(p) < 0) {
                    if (scheduleAttachment(p)) return;
                    accessFailure("attachment",p,"Rung requires its actual fixed backing before placement");
                } else accessFailure("clearance",p,"Placement waits for its own cell/body clearance, not clearance of the entire ladder");
                deferAccessOperation(p); return;
            }
            // Finish every reachable stage using carried supplies before gathering
            // for the rest of the column. Whole-plan shortages must not gate a rung.
            if (placementSide(p) < 0) {
                if (scheduleAttachment(p)) { accessStage = "attachment"; status = accessActivity(); return; }
                navigationReason = "placement"; accessFailure("attachment",p,"Missing actual authorized attachment; required supports and bounded attachment prerequisites must be reachable first");
                deferAccessOperation(p); return;
            }
            int blocks = Math.max(0,accessNeeded(false)-accessCount(false)), ladders = Math.max(0,accessNeeded(true)-accessCount(true));
            if ((blocks > 0 || ladders > 0) && accessCount(p[3] > 0) == 0) {
                if (!storageNeeded(StorageReason.ACCESS)) {
                    accessFailure("prerequisite",p,supplyBlocker); deferAccessOperation(p); return;
                }
                requestedBlocks = blocks; requestedLadders = ladders;
                if (!accessSupplyChecked) checkAccessStation();
                if (!accessStationReachable) {
                    accessFailure("supplies",p,"This stage needs a carried " + (p[3] > 0 ? "ladder" : "stable support block"));
                    deferAccessOperation(p); return;
                }
                if (storageTask()) {
                    // The route now works. Keep its original capacity/supply reason;
                    // reconstruct the interrupted required edits after this real visit.
                    buildingAccess = false; repairAfterRestock = true; accessRepairPending = true;
                    resumeAfterAccess("depot reachable for remaining repair supplies"); return;
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
            accessIndex++; returnRoute.clear(); rejectedTargets.clear();
            failedAccessOperations.clear(); clearRouteSearch();
            accessSupplyChecked = false;
            usefulProgress("placed supplied access cell"); return;
        }
        if (!staircaseAccess && ladderPosition != null) {
            collectLadderRequirements();
            for (int[] required : ladderAccess) if (requiredForTrip(required) && !accessPresent(required)) {
                reconcileAccessPlan();
                resetPath(); clearRouteSearch(); failedAccessOperations.clear(); return;
            }
            if (ladderLanding == null) {
                int top = Integer.MIN_VALUE;
                for (int[] p : ladderAccess) if (p[3] > 0 && fixedRung(p)) top = Math.max(top,p[1]);
                accessFailure("verify_access",new int[] {ladderPosition[0],top,ladderPosition[1]},"Fixed ladder has no verified upper landing connected to storage");
                if (!tryLocalRecovery()) blockObjective("no_access"); return;
            }
            if (depotRepair() ? stationRoute(null) == null : !ladderRouteAvailable()) {
                navigationReason = "search_limit".equals(navigationReason) ? "return_limit" : "return_blocked";
                accessFailure("verify_access",depotRepair() ? depotDestination() : workPosition,
                        (depotRepair() ? "Repaired depot approach failed: " : "Repaired work-floor/ladder/station route failed: ") + navigationDetail);
                if (!tryLocalRecovery()) blockObjective("no_access"); return;
            }
        }
        if (debugHistory != null) { movementDecision = "access complete; resume " + Arrays.toString(taskDestination()); trace("recovery",movementDecision); }
        buildingAccess = false; accessPlan.clear(); accessIndex = 0; requestedBlocks = requestedLadders = 0;
        restockLadders = restockBlocks = 0;
        repairAfterRestock = false;
        accessRepairPending = false; staircaseAccess = repairingLadder = false;
        constructionRefuge.clear(); failedAccessOperations.clear();
        clearAccessSearch(); clearRouteSearch();
        failedEndpoints.clear(); accessAttempts = 0; returnRoute.clear();
        navigationReason = navigationDetail = arrivalFailure = "";
        ladderObstruction = accessBlocker = accessPlacementFailure = "";
        recoveryAction = recoveryBlocker = ""; supplySearch = 0; supplyOrigin = null;
        resetPath();
        resumeAfterAccess("supplied access completed");
    }
    private void clearAccessCell(int[] p) {
        boolean local = p == localRecoveryCell;
        if (!suitable(getHeldItem())) {
            requestedTools = 1;
            if (accessStationReachable && !storageTask()) beginStorage(StorageReason.TOOL);
            else { accessFailure("supplies",p,"A suitable pickaxe is needed for access clearance; station unreachable"); blockObjective("no_tool"); }
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
        if (!authorized() || !accessHarvestable(p,true) || !visible(p[0],p[1],p[2])) {
            accessFailure("clearance",p,"Clearance is no longer authorized, harvestable or visible from this position");
            deferAccessOperation(p); return;
        }
        if (!constructionSafe(null,p)) { deferAccessOperation(p); return; }
        FakePlayer player = actor(); player.setPosition(posX,posY,posZ); player.onGround = onGround;
        player.inventory.mainInventory[0] = getHeldItem(); player.inventory.currentItem = 0;
        Block block = worldObj.getBlock(p[0],p[1],p[2]); int meta = worldObj.getBlockMetadata(p[0],p[1],p[2]);
        if (!block.canHarvestBlock(player,meta)) {
            requiredHarvest = Math.max(0,block.getHarvestLevel(meta)); player.inventory.mainInventory[0] = null; resetMining();
            if (accessStationReachable && !storageTask()) beginStorage(StorageReason.TOOL);
            else { accessFailure("supplies",p,"Access clearance needs pickaxe harvest level " + requiredHarvest + "; station unreachable"); blockObjective("tool_level"); }
            return;
        }
        requiredHarvest = -1;
        if (miningBlock != block || miningMeta != meta || !ItemStack.areItemStacksEqual(miningTool,getHeldItem())) {
            miningBlock = block; miningMeta = meta; miningTool = getHeldItem().copy(); miningProgress = 0;
        }
        status = local ? gatheringSupport ? "recovering_supplies" : "digging_out" : accessActivity(); accessStage = "clearance"; swingItem();
        float hardness = block.getPlayerRelativeBlockHardness(player,worldObj,p[0],p[1],p[2])*10;
        if (hardness <= 0) { accessFailure("clearance",p,"Tool cannot harvest access clearance"); deferAccessOperation(p); return; }
        miningProgress += hardness; objectiveTicks = 0;
        if (miningProgress < 1) return;
        int supportsBefore = accessCount(false);
        boolean harvested = harvestWithTool(player,p[0],p[1],p[2]);
        resetMining();
        if (local) {
            if (!harvested) { accessFailure("clearance",p,"Local harvest refused by tool or protection"); deferAccessOperation(p); return; }
            if (gatheringSupport && accessCount(false) <= supportsBefore) {
                unsuitableRecoveryDrops.add(supplyBlockKey(block,meta));
                recoveryBlocker = "Actual drops from " + block.getUnlocalizedName() + ":" + meta + " did not supply a usable repair block";
            }
            localRecoveryCell = null;
            // Reattempt reachable repair after EACH actual supply harvest.
            if (gatheringSupport) { gatheringSupport = false; failedAccessOperations.clear(); }
            usefulProgress("harvested local recovery terrain and collected actual drops"); return;
        }
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
            if (!staircaseAccess) requireLadderCell(p);
        }
        if (staircaseAccess) {
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
        boolean clearance = buildingAccess || localRecoveryCell != null;
        watchFallingTerrain(x,y,z);
        try { return com.hfr.worker.MinerHarvestDrops.harvest(player,x,y,z,factionId,
                event -> {
                    // Normal AOE remains normal inside the selected excavation.
                    // Outside it, only clearance for this required access is authorized.
                    if (!excavationArea(event.x,event.y,event.z)
                            && (!clearance || !requiredClearance(event.x,event.y,event.z))) return false;
                    if (!accessSiteFailure(event.x,event.y,event.z).isEmpty()) return false;
                    gravityTarget = null;
                    if (!fallingSafe(event.x,event.y,event.z,posX,boundingBox.minY,posZ)) {
                        recoveryBlocker = "Dangerous falling terrain or loss of builder footing at " + event.x+","+event.y+","+event.z;
                        return false;
                    }
                    watchFallingTerrain(event.x,event.y,event.z); return true;
                }, changed -> finishHarvest(changed,before)); }
        finally {
            setCurrentItemOrArmor(0,player.inventory.mainInventory[0]); player.inventory.mainInventory[0] = null;
        }
    }
    private boolean requiredClearance(int x,int y,int z) {
        if (!accessGeometry(x,y,z)) return false;
        for (int[] p : ladderAccess) if (p[0] == x && p[1] == y && p[2] == z && !accessPresent(p)) return true;
        for (int[] p : accessPlan) if (p[3] < 0 && p[0] == x && p[1] == y && p[2] == z && !accessOperationComplete(p)) return true;
        return localRecoveryCell != null && localRecoveryCell[3] < 0 && localRecoveryCell[0] == x
                && localRecoveryCell[1] == y && localRecoveryCell[2] == z && !gatheringSupport;
    }
    private void finishHarvest(List<net.minecraftforge.event.world.BlockEvent.BreakEvent> changed,List<int[]> before) {
        gravityTarget = null;
        List<int[]> detached = new java.util.ArrayList<int[]>();
        for (int[] p : before) if (!accessPresent(p)) detached.add(p);
        // Always inspect canonical requirements after a tool action, including
        // neighbor-detached ladders without a matching BreakEvent.
        collectLadderRequirements();
        boolean ladderDamage = false;
        for (int[] p : ladderAccess) if (!accessPresent(p)) { ladderDamage = true; break; }
        if (ladderDamage && (!changed.isEmpty() || !detached.isEmpty() || !buildingAccess)) accessRepairPending = true;
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
        rejectedTargets.clear(); failedEndpoints.clear(); failedMovementEdges.clear();
        failedAccessOperations.clear();
        selectingTarget = false; searchOffset = accessAttempts = 0;
        interactionTarget = selectedEndpoint = plannedEntrance = null; arrivalFailure = "";
        accessSupplyChecked = false;
        stageSupports = stageLadders = -1;
        if (buildingAccess && !staircaseAccess) reconcileAccessPlan();
        else if (buildingAccess) {
            for (int i = 0; i < accessIndex; i++) if (!accessPresent(accessPlan.get(i))) { accessIndex = i; break; }
        }
        usefulProgress("terrain changed by tool harvest");
    }
    private boolean placeAccess(int[] p) {
        if (p[3] < 0 || p[3] > 0 && !fixedRung(p)) return accessPlacementFailure(p,"placement","Operation is not a placement on the fixed route");
        if (p[3] == 0 && ladderPassage(p)) return accessPlacementFailure(p,"clearance","Support would obstruct the reserved ladder passage");
        int slot = accessSlot(p[3] != 0);
        if (slot < 0) return accessPlacementFailure(p,"supplies","Missing supplied " + (p[3] > 0 ? "ladder" : "support block"));
        String site = accessSiteFailure(p[0],p[1],p[2]);
        if (!site.isEmpty()) return accessPlacementFailure(p,"placement",site);
        if (!authorized()) return accessPlacementFailure(p,"placement","Employer no longer authorized");
        if (!accessGeometry(p[0],p[1],p[2])) return accessPlacementFailure(p,"placement","Cell is not required by the fixed ladder or its entrances");
        if (accessShape(p).intersectsWith(boundingBox)) return accessPlacementFailure(p,"clearance","Support intersects the Miner body");
        if (!worldObj.isAirBlock(p[0],p[1],p[2])) return accessPlacementFailure(p,"placement","Cell is occupied; required clearance must finish first");
        if (worldObj.getTileEntity(p[0],p[1],p[2]) != null || BuilderPlacement.protectedBlock(worldObj,p[0],p[1],p[2]))
            return accessPlacementFailure(p,"placement","Protected infrastructure or tile entity");
        ItemStack material = cargo[slot], offered = material.copy(); offered.stackSize = 1;
        net.minecraft.item.ItemBlock item = (net.minecraft.item.ItemBlock)material.getItem();
        Block block = item.field_150939_a;
        int side = placementSide(p);
        if (side < 0) return accessPlacementFailure(p,"attachment","No actual authorized attachment before placement");
        net.minecraftforge.common.util.ForgeDirection direction = net.minecraftforge.common.util.ForgeDirection.getOrientation(side);
        int bx = p[0]-direction.offsetX, by = p[1]-direction.offsetY, bz = p[2]-direction.offsetZ;
        FakePlayer player = actor(); player.setPosition(posX,posY,posZ); player.onGround = onGround;
        if (!solidAttachment(bx,by,bz,direction,-30000001,-1,0))
            return accessPlacementFailure(p,"attachment","Missing actual backing at " + bx+","+by+","+bz);
        if (worldObj.getTileEntity(bx,by,bz) != null || BuilderPlacement.protectedBlock(worldObj,bx,by,bz)
                || !WorkerAccess.ownsLand(worldObj,bx,bz,factionId))
            return accessPlacementFailure(p,"placement","Denied attachment at " + bx+","+by+","+bz);
        if (!player.canPlayerEdit(p[0],p[1],p[2],side,offered))
            return accessPlacementFailure(p,"placement","Native canPlayerEdit denied the target before PlaceEvent");
        if (!worldObj.canPlaceEntityOnSide(block,p[0],p[1],p[2],false,side,null,offered))
            return accessPlacementFailure(p,"placement","Native block placement/collision check failed before PlaceEvent");
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
            if (debugHistory != null) trace("construction","placed " + Arrays.toString(p));
            gravityTarget = null;
            accessPlacementFailure = "";
            failedMovementEdges.clear();
            if (--material.stackSize <= 0) cargo[slot] = null;
            stageSupports = stageLadders = -1;
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
            if (solidAttachment(bx,by,bz,d,-30000001,-1,0)
                    && !(routeBlock(bx,by,bz,-30000001,-1,0) instanceof net.minecraft.block.BlockFalling)
                    && worldObj.getTileEntity(bx,by,bz) == null
                    && !BuilderPlacement.protectedBlock(worldObj,bx,by,bz) && WorkerAccess.ownsLand(worldObj,bx,bz,factionId)) return f;
        }
        return -1;
    }
    /** Canonical cache order is storage -> current work position. Check only the prefix needed
     * to leave now; discard stale suffixes rather than joining old trips in opposite directions. */
    private boolean ensureReturnRoute(int removedX,int removedY,int removedZ) {
        if (!fallingSafe(removedX,removedY,removedZ,posX,boundingBox.minY,posZ)) {
            navigationReason = "falling_terrain"; navigationDetail = "Move outside falling terrain before harvesting " + removedX+","+removedY+","+removedZ;
            return false;
        }
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
    private String blockDescription(int x,int y,int z) {
        return x+","+y+","+z + "=" + (y < 0 || y >= worldObj.getHeight() || !worldObj.blockExists(x,y,z)
                ? "unloaded" : Block.blockRegistry.getNameForObject(worldObj.getBlock(x,y,z)) + ":" + worldObj.getBlockMetadata(x,y,z));
    }
    private String standingObstruction(int x,int y,int z,boolean mining) {
        java.util.Map<String,Integer> failures = new java.util.TreeMap<String,Integer>();
        int samples = 0;
        int[] from = {(int)Math.floor(posX),(int)Math.floor(boundingBox.minY+.001),(int)Math.floor(posZ)};
        for (int dx = -3; dx <= 3; dx++) for (int dz = -3; dz <= 3; dz++) for (int dy = -3; dy <= 3; dy++) {
            // Exactly the same enumeration and predicates as standingCandidates.
            if (dx*dx+dz*dz > 9) continue;
            int sx = x+dx, sy = y+dy, sz = z+dz;
            int horizontal = Math.abs(sx-from[0])+Math.abs(sz-from[2]);
            boolean relevant = (horizontal == 0 || horizontal == 1 && sy == from[1]) && Math.abs(sy-from[1]) <= 3;
            StringBuilder detail = debugHistory != null && relevant && samples < 8 ? new StringBuilder() : null;
            double fy = computeFeet(sx,sy,sz,mining ? x : -30000001,mining ? y-1 : -1,z,detail);
            String failure = Double.isNaN(fy) ? feetRejection : candidateFailure(sx,sy,sz,fy,x,y,z,mining,true,detail);
            if (!failure.isEmpty()) failures.put(failure,failures.containsKey(failure) ? failures.get(failure)+1 : 1);
            if (!failure.isEmpty() && detail != null) {
                int[] candidate = {sx,sy,sz};
                String attachment = "solid footing at feet";
                Block block = worldObj.blockExists(sx,sy,sz) ? worldObj.getBlock(sx,sy,sz) : net.minecraft.init.Blocks.air;
                if (block instanceof net.minecraft.block.BlockLadder) {
                    int meta = worldObj.getBlockMetadata(sx,sy,sz);
                    net.minecraftforge.common.util.ForgeDirection face = net.minecraftforge.common.util.ForgeDirection.getOrientation(meta);
                    attachment = "ladder face=" + meta + " backing=" + blockDescription(sx-face.offsetX,sy,sz-face.offsetZ);
                }
                int[] operation = constructionCell(x,y,z);
                trace("endpoint","candidate=" + Arrays.toString(candidate) + " movement=" + movement(from,candidate)
                        + " failed=" + failure + " body=" + body(sx+.5,Double.isNaN(fy) ? sy : fy,sz+.5)
                        + " requires=" + attachment + " interaction=" + x+","+y+","+z
                        + " blockTarget=" + x+","+(mining ? y-1 : y)+","+z + " reach=3 distance="
                        + Math.sqrt(dx*dx+Math.pow((Double.isNaN(fy) ? sy : fy)-y,2)+dz*dz)
                        + " prerequisite=" + Arrays.toString(operation == null ? null : unfinishedAccessPrerequisite(operation)) + detail);
                samples++;
            }
        }
        return "Endpoint candidates near " + x+","+y+","+z + ": " + failures;
    }
    private void terminalNavigation() {
        // Preserve the final diagnostic while freezing execution until conditions change.
        boolean wasStorage=storageTask(); Depot failedDepot=activeDepot;
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
        blockObjective("navigation_failed");
        // Recovery may have selected another depot. Old diagnostics cannot restore its failed travel action.
        if (storageTask() && (!wasStorage || !java.util.Objects.equals(failedDepot,activeDepot) || waitingForDepot)) return;
        navigationReason = reason; destination = dest.length == 3 ? dest : null; waypoint = step.length == 3 ? step : null;
        recoveryOrigin = recoveryStart; recoveryDestination = recoveryEnd; recoveryAttempts = attempts;
    }
    private int requiredSupports() { return stageSupports < 0 ? supportRequirement() : stageSupports; }
    private int requiredLadders() { return stageLadders < 0 ? Math.max(restockLadders,ladderRequirement()) : stageLadders; }
    private int supportTarget() { return Math.max(retainedMaterialRequirement(false),Math.max(supportRequirement(),emergencyBlocks())); }
    private int ladderTarget() { return Math.max(2,Math.max(restockLadders,ladderRequirement())); }

    /** Supply decisions use the same real prerequisites as construction. A
     * pending cell alone is not evidence of a useful, supplied repair stage.
     * This never edits terrain or transfers items; execution rechecks everything.
     */
    private void refreshSupplyRequirements() {
        stageSupports = stageLadders = -1; supplyOperation = null; supplyBlocker = "";
        if (accessPlan.isEmpty()) return;
        int[] missing = null;
        String reason = navigationReason, detail = navigationDetail, oldStatus = status;
        String oldStage = accessStage, oldFailure = accessFailure, oldBlocker = accessBlocker;
        try {
            for (int i = accessIndex; i < accessPlan.size(); i++) {
                int[] cell = accessPlan.get(i);
                if (accessOperationComplete(cell)) continue;
                if (supplyOperation == null) supplyOperation = cell;
                String blocker = repairStageBlocker(cell);
                if (!blocker.isEmpty()) { if (supplyBlocker.isEmpty()) supplyBlocker = blocker; continue; }
                if (cell[3] < 0 || accessCount(cell[3] > 0) > 0) {
                    supplyOperation = cell; supplyBlocker = "";
                    stageSupports = cell[3] == 0 ? 1 : 0;
                    stageLadders = cell[3] > 0 ? 1 : 0;
                    return;
                }
                if (missing == null) missing = cell;
            }
            // Structural/route failures belong to repair, not a fabricated
            // whole-column supply shortage. Return to that retained operation.
            stageSupports = missing != null && missing[3] == 0 ? 1 : 0;
            stageLadders = missing != null && missing[3] > 0 ? 1 : 0;
            if (missing != null) {
                supplyOperation = missing;
                supplyBlocker = missing[3] > 0 ? "Next repair stage needs one ladder" : "Next repair stage needs one suitable support block";
            }
        } finally {
            navigationReason = reason; navigationDetail = detail; status = oldStatus;
            accessStage = oldStage; accessFailure = oldFailure; accessBlocker = oldBlocker;
        }
    }
    private String repairStageBlocker(int[] cell) {
        String site = accessSiteFailure(cell[0],cell[1],cell[2]);
        if (!site.isEmpty()) return site;
        if (!accessGeometry(cell[0],cell[1],cell[2])) return "Cell is outside required access geometry";
        if (failedAccessOperations.contains(cell)) return "Repair operation exhausted its builder positions";
        if (cell[3] < 0) {
            if (!accessHarvestable(cell,true)) return "Clearance is protected or cannot be harvested";
            if (!suitable(getHeldItem())) return "Clearance needs a suitable pickaxe";
            FakePlayer player = actor(); ItemStack previous = player.inventory.mainInventory[0];
            player.inventory.mainInventory[0] = getHeldItem();
            try {
                if (!worldObj.getBlock(cell[0],cell[1],cell[2]).canHarvestBlock(player,worldObj.getBlockMetadata(cell[0],cell[1],cell[2])))
                    return "Clearance needs a pickaxe able to harvest this block";
            } finally { player.inventory.mainInventory[0] = previous; }
        } else {
            if (!worldObj.isAirBlock(cell[0],cell[1],cell[2])) return "Placement needs its cell cleared first";
            if (!clearanceOrderAllows(cell)) return "Placement needs its actual clearance/backing prerequisite";
            if (placementSide(cell) < 0) return "Placement needs an actual authorized stable attachment";
            if (cell[3] == 0 && ladderPassage(cell)) return "Support would obstruct the ladder passage";
            if (cell[3] > 0 && !fixedRung(cell)) return "Rung is outside the fixed ladder face";
            if (worldObj.getTileEntity(cell[0],cell[1],cell[2]) != null || BuilderPlacement.protectedBlock(worldObj,cell[0],cell[1],cell[2]))
                return "Placement is protected";
        }
        List<int[]> builders = standingCandidates(cell[0],cell[1]+(cell[3] < 0 ? 1 : 0),cell[2],cell[3] < 0);
        if (cell[3] >= 0) builders.removeIf(p -> accessShape(cell).intersectsWith(body(p[0]+.5,nodeY(p),p[2]+.5)));
        // Reuse the construction search's three-endpoint update budget.
        for (int attempt = 0; attempt < 3 && !builders.isEmpty(); attempt++) {
            List<int[]> route = searchRoute(builders,-30000001,-1,0,false,null,true);
            if (route == null) return "No reachable builder position: " + navigationDetail;
            if (constructionSafe(route,cell)) return "";
            int[] end = route.get(route.size()-1); builders.removeIf(p -> Arrays.equals(p,end));
        }
        return "No safe builder position: " + navigationDetail;
    }
    private boolean mandatorySuppliesMissing() {
        return blockWorkRemaining() && !suitable(getHeldItem()) || needsLight && torchCount() == 0
                || accessCount(false) < requiredSupports() || accessCount(true) < requiredLadders();
    }
    private boolean completeDepotTrip() {
        if (!storageTask() || mandatorySuppliesMissing() || !depositCargo().isEmpty() || !overflowDrops.isEmpty()) return false;
        if (pendingDropsCaptured) { pendingDrops = null; pendingDropsCaptured = false; }
        boolean repair = buildingAccess || repairAfterRestock || storageReason == StorageReason.ACCESS || storageReason == StorageReason.LADDERS;
        cancelDepotTravel();
        triedDepots.clear(); waitingForDepot = false; supplyCheckTicks = 0;
        failedAccessOperations.clear(); depotFailureDetail = "";
        restockLadders = restockBlocks = 0; requestedBlocks = requestedLadders = requestedTools = requestedTorches = 0;
        sentLadderReminder = false; repairAfterRestock = repair; accessRepairPending = true;
        navigationReason = navigationDetail = arrivalFailure = "";
        if (!unfinished() && !hasCargo()) { transition(Task.COMPLETE,"final deposit finished",null); stop("complete"); }
        else returnToWork("mandatory supplies carried and unloading finished");
        return true;
    }
    private void reconsiderDepotFailures() {
        if (!storageTask()) return;
        long stamp = supplyStamp();
        stamp = 31*stamp+requiredSupports(); stamp = 31*stamp+requiredLadders();
        stamp = 31*stamp+Arrays.hashCode(supplyOperation);
        stamp = 31*stamp+(suitable(getHeldItem()) ? 1 : 0); stamp = 31*stamp+(needsLight ? 1 : 0);
        if (depotDecisionStamp != stamp) {
            if (depotDecisionStamp != Long.MIN_VALUE) triedDepots.clear();
            depotWaitSupplies = Long.MIN_VALUE;
            depotDecisionStamp = stamp;
        }
    }
    private boolean depotTerrainChanged() {
        if (terrainChecks == null || !terrainChecks.hasNext()) terrainChecks=watchedTerrain.values().iterator();
        for (int checked=0; checked<512 && terrainChecks.hasNext(); checked++) {
            int[] p=terrainChecks.next(); boolean loaded=worldObj.blockExists(p[0],p[1],p[2]);
            if ((loaded ? Block.getIdFromBlock(worldObj.getBlock(p[0],p[1],p[2])) : -1) != p[3]
                    || loaded && worldObj.getBlockMetadata(p[0],p[1],p[2]) != p[4]
                    || (WorkerAccess.ownsLand(worldObj,p[0],p[2],factionId) ? 1 : 0) != p[5]) return true;
        }
        return false;
    }
    private List<ItemStack> depositCargo() {
        List<ItemStack> stacks = new java.util.ArrayList<ItemStack>(); int[] reserved = cargoReservations();
        for (int i=0; i<cargo.length; i++) if (cargo[i] != null && cargo[i].stackSize > reserved[i]) {
            ItemStack stack = cargo[i].copy(); stack.stackSize -= reserved[i]; stacks.add(stack);
        }
        for (ItemStack stack : overflowDrops) if (!(reserveWorkSupplies() && accessMaterial(stack,true))) stacks.add(stack.copy());
        return stacks;
    }
    private boolean acceptsCargo(IInventory inventory,List<ItemStack> stacks) {
        for (ItemStack stack : stacks) for (int i=firstSlot(inventory); i<inventory.getSizeInventory(); i++) {
            ItemStack stored=inventory.getStackInSlot(i);
            if (inventory.isItemValidForSlot(i,stack) && (stored == null || stored.isItemEqual(stack) && ItemStack.areItemStackTagsEqual(stored,stack))
                    && (stored == null ? 0 : stored.stackSize) < Math.min(inventory.getInventoryStackLimit(),stack.getMaxStackSize())) return true;
        }
        return false;
    }
    private boolean suppliesUseful(IInventory inventory) {
        for (int i=firstSlot(inventory); i<inventory.getSizeInventory(); i++) {
            ItemStack stack=inventory.getStackInSlot(i);
            if (stack == null || stack.stackSize <= 0) continue;
            if (blockWorkRemaining() && !suitable(getHeldItem()) && suitable(stack)) return true;
            if (needsLight && torchCount() == 0 && isTorch(stack)) return true;
            if (remainingCapacity(stack) > 0 && (accessCount(false) < requiredSupports() && accessMaterial(stack,false)
                    || accessCount(true) < requiredLadders() && accessMaterial(stack,true))) return true;
        }
        return false;
    }
    private String depotSupplyFailure() {
        boolean capacity = false, unsuitable = false;
        StringBuilder detail = new StringBuilder();
        boolean reportRejections = debugHistory != null && depotTraceStamp != depotDecisionStamp;
        depotTraceStamp = depotDecisionStamp;
        for (int type = 0; type < 4; type++) {
            boolean needed = type == 0 ? blockWorkRemaining() && !suitable(getHeldItem()) : type == 1 ? needsLight && torchCount() == 0
                    : type == 2 ? accessCount(false) < requiredSupports() : accessCount(true) < requiredLadders();
            if (!needed) continue;
            boolean found = false, rejected = false, room = false;
            for (Depot depot : depots) {
                IInventory inventory = depotInventory(depot); if (inventory == null) continue;
                for (int i = firstSlot(inventory); i < inventory.getSizeInventory(); i++) {
                    ItemStack stack = inventory.getStackInSlot(i); if (stack == null || stack.stackSize <= 0) continue;
                    boolean valid = type == 0 ? suitable(stack) : type == 1 ? isTorch(stack)
                            : accessMaterial(stack,type == 3);
                    boolean candidate = type == 0 ? pickaxe(stack) : type == 1 ? isTorch(stack) : stack.getItem() instanceof net.minecraft.item.ItemBlock;
                    if (valid) {
                        found = true;
                        boolean fits = type < 2 || remainingCapacity(stack) > 0;
                        room |= fits;
                        if (!fits && reportRejections) trace("rejected","depot item=" + itemIdentity(stack) + "; no compatible cargo capacity");
                    } else if (candidate) {
                        rejected = true;
                        if (reportRejections) trace("rejected","depot item=" + itemIdentity(stack) + "; " + supplyRejection(stack,type));
                    }
                }
            }
            String name = type == 0 ? "pickaxe" : type == 1 ? "torches" : type == 2 ? "supports" : "ladders";
            if (detail.length() > 0) detail.append("; ");
            detail.append(name).append(found ? room ? ": suitable supply in an already tried depot" : ": no compatible cargo capacity"
                    : rejected ? ": offered items are unsuitable" : ": required item absent");
            capacity |= found && !room; unsuitable |= !found && rejected;
        }
        depotFailureDetail = detail.toString();
        return capacity ? "depot_capacity" : unsuitable ? "depot_unsuitable" : detail.length() == 0 ? "depot_satisfied" : "depot_missing";
    }
    private static String itemIdentity(ItemStack stack) {
        return net.minecraft.item.Item.itemRegistry.getNameForObject(stack.getItem()) + ":" + stack.getItemDamage()
                + " count=" + stack.stackSize + " nbt=" + (stack.hasTagCompound() ? stack.getTagCompound().toString() : "{}");
    }
    private String supplyRejection(ItemStack stack,int type) {
        if (type == 0) return "pickaxe is broken, below the required tier or cannot harvest the target";
        if (type == 3) return "item is not a vanilla ladder";
        Block block = ((net.minecraft.item.ItemBlock)stack.getItem()).field_150939_a;
        if (oreMaterial(stack,block)) return "ore is excluded from support material";
        if (block instanceof net.minecraft.block.BlockFalling) return "falling blocks cannot be supplied as stable supports";
        if (block.hasTileEntity(stack.getItemDamage()) || block == net.minecraft.init.Blocks.bedrock || block == net.minecraft.init.Blocks.command_block)
            return "tile entity or protected infrastructure cannot be supplied as support";
        return "support must be a normal solid cube of stable stone or earth";
    }
    private int depotSupportRank(Depot depot) {
        if (accessCount(false) >= requiredSupports()) return 3;
        IInventory inventory=depotInventory(depot); int best=3;
        if (inventory != null) for (int i=firstSlot(inventory); i<inventory.getSizeInventory(); i++) {
            ItemStack stack=inventory.getStackInSlot(i); int rank=materialRank(stack,false);
            if (rank >= 0 && rank < best && remainingCapacity(stack) > 0) best=rank;
        }
        return best;
    }
    /** Select once per trip/failure. Inspection never loads a chunk or changes the work plan. */
    private boolean chooseDepot() {
        if (completeDepotTrip()) return false;
        reconsiderDepotFailures();
        normalizeDepots();
        depotFailureDetail = "";
        List<ItemStack> cargoToDeposit=depositCargo();
        boolean unloading=!cargoToDeposit.isEmpty();
        List<Depot> ordered=orderedDepots();
        if (!unloading) java.util.Collections.sort(ordered,(a,b) -> {
            int c=Integer.compare(depotSupportRank(a),depotSupportRank(b));
            if (c == 0) c=Double.compare(getDistanceSq(a.x+.5,a.y+.5,a.z+.5),getDistanceSq(b.x+.5,b.y+.5,b.z+.5));
            return c == 0 ? compareDepots(a,b) : c;
        });
        boolean available=false, useful=false, unreachable=false; Depot repair=null;
        for (Depot depot : ordered) {
            IInventory inventory=depotInventory(depot);
            if (inventory == null) continue;
            available=true;
            if (!(unloading ? acceptsCargo(inventory,cargoToDeposit) : suppliesUseful(inventory))) continue;
            useful=true;
            if (triedDepots.containsKey(depot)) { unreachable |= triedDepots.get(depot); continue; }
            if (routeToDepot(depot,null,-30000001,-1,0) != null) {
                activeDepot=depot; waitingForDepot=false; returnRoute.clear(); return true;
            }
            unreachable=true;
            if (repair == null) repair=depot;
        }
        // Preserve the established movement/access recovery for a useful but broken approach.
        if (repair != null) { activeDepot=repair; waitingForDepot=false; returnRoute.clear(); return true; }
        activeDepot=null;
        depotFailure=depots.isEmpty() ? "no_depots" : !available ? "depots_unavailable" : useful && unreachable ? "depots_unreachable"
                : unloading ? "storage_full" : depotSupplyFailure();
        status=depotFailure; waitingForDepot=true; depotWaitSupplies=supplyStamp(); supplyCheckTicks=40;
        return false;
    }
    private boolean nextDepot() { return nextDepot(false); }
    private boolean nextDepot(boolean unreachable) {
        refreshSupplyRequirements();
        if (completeDepotTrip()) return false;
        if (activeDepot != null) triedDepots.put(activeDepot,unreachable);
        cancelDepotTravel();
        if (!chooseDepot()) return false;
        transition(Task.TO_STORAGE,"try another linked depot",depotDestination()); return true;
    }
    private void waitForDepotSupplies(String missing) {
        if (nextDepot()) return;
        if (storageTask() && "storage_full".equals(missing) && !"depots_unreachable".equals(depotFailure)
                && !"depots_unavailable".equals(depotFailure)) {
            depotFailure = status = "storage_full";
            depotFailureDetail = "Available depots cannot accept the displaced unusable pickaxe";
        }
    }
    private void visitChest() {
        if (completeDepotTrip()) return;
        if (activeDepot == null && !chooseDepot()) return;
        IInventory chest = depotInventory(activeDepot);
        if (chest == null) { nextDepot(); return; }
        int[] depot=activeDepot.position();
        if (!moveNear(depot[0],depot[1],depot[2])) {
            if ("route_failed".equals(status)) terminalNavigation();
            return;
        }
        if (task != Task.RESTOCKING)
            transition(Task.DEPOSITING,"at storage: " + storageReason.key,depot);
        checkAccessStation();
        long beforeSupplies = supplyStamp();
        equipCarriedTool();
        stockTorchesFromCargo();
        int[] reserved = cargoReservations();
        boolean depositBlocked = false;
        for (int i=0; i<cargo.length; i++) { ItemStack stack=cargo[i]; if (stack != null) {
            int keep = reserved[i];
            stack.stackSize -= keep; insert(chest,stack);
            depositBlocked |= stack.stackSize > 0; stack.stackSize += keep;
        } }
        for (int i = 0; i < cargo.length; i++) if (cargo[i] != null && cargo[i].stackSize <= 0) cargo[i] = null;
        for (java.util.Iterator<ItemStack> items = overflowDrops.iterator(); items.hasNext();) {
            ItemStack stack = items.next();
            if (reserveWorkSupplies() && accessMaterial(stack,true)) insert(this,stack); else insert(chest,stack);
            if (stack.stackSize <= 0) items.remove();
        }
        chest.markDirty();
        if (beforeSupplies != supplyStamp()) { triedDepots.clear(); usefulProgress("deposited real cargo"); }
        if (depositBlocked || !depositCargo().isEmpty()) { nextDepot(); return; }
        // New overflow was entirely captured from actual entities. Once it is deposited there
        // is nothing left at that site; legacy coordinate-only saves still need a collection visit.
        if (pendingDropsCaptured) { pendingDrops = null; pendingDropsCaptured = false; }
        toolBroken = false;
        int total = (plan[5] - plan[3] + 1) * (plan[6] - plan[4] + 1) * (plan[7] - plan[8] + 1);
        if (!blockWorkRemaining() && pendingDrops == null) {
            buildingAccess = false; accessPlan.clear(); accessIndex = 0;
            transition(Task.COMPLETE,"final deposit finished",null); stop("complete"); return;
        }
        transition(Task.RESTOCKING,"deposit finished; check required supplies",depot);
        stockAccess(chest,false,false); refreshSupplyRequirements(); stockAccess(chest,true,false);
        if (beforeSupplies != supplyStamp()) triedDepots.clear();
        boolean miningRemains = blockWorkRemaining();
        if (miningRemains && !suitable(getHeldItem())) {
            if (getHeldItem() != null) {
                ItemStack old = getHeldItem(); insert(chest, old);
                if (old.stackSize > 0) { waitForDepotSupplies("storage_full"); return; }
                setCurrentItemOrArmor(0, null);
            }
            for (int i = firstSlot(chest); i < chest.getSizeInventory(); i++) {
                ItemStack stack = chest.getStackInSlot(i);
                if (suitable(stack)) { setCurrentItemOrArmor(0, chest.decrStackSize(i, 1)); chest.markDirty(); break; }
            }
            if (!suitable(getHeldItem())) {
                requestedTools = 1; status = requiredHarvest >= 0 ? "tool_level" : "no_tool";
                waitForDepotSupplies(status); return;
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
            requestedTorches = 1; waitForDepotSupplies("no_torches"); return;
        }
        requestedTorches = 0;
        if (beforeSupplies != supplyStamp()) usefulProgress("restocked required supplies");
        refreshSupplyRequirements();
        if (!mandatorySuppliesMissing()) { stockAccess(chest,false,true); stockAccess(chest,true,true); refreshSupplyRequirements(); }
        if (!completeDepotTrip()) waitForDepotSupplies(accessCount(false) < requiredSupports() ? "no_supports" : "no_ladders");
    }
    private void stockAccess(IInventory chest,boolean rung,boolean reserves) {
        int preferred=2;
        if (!rung) for (Depot depot : reachableSupplyDepots) preferred=Math.min(preferred,depotSupportRank(depot));
        int mandatory = rung ? requiredLadders() : requiredSupports();
        int target = reserves ? Math.max(mandatory,rung ? ladderTarget() : supportTarget()) : mandatory;
        for (int rank=0; rank < (rung ? 1 : 3); rank++) for (int i = firstSlot(chest); i < chest.getSizeInventory(); i++) {
            // Mandatory deliveries precede optional reserves of either type.
            int need = target-accessCount(rung);
            if (need <= 0) break;
            ItemStack stack = chest.getStackInSlot(i);
            if (materialRank(stack,rung) != rank) continue;
            // Only a real shortage can justify another trip. Never exchange usable carried backing for cobble.
            if (!rung && rank > preferred && accessCount(false) >= mandatory) continue;
            int amount = Math.min(need,Math.min(stack.stackSize,remainingCapacity(stack)));
            if (amount <= 0) continue;
            ItemStack taken = chest.decrStackSize(i,amount);
            if (taken != null) {
                insert(this,taken);
                // Capacity was checked, but retain any remainder even if a
                // custom inventory returned more than requested.
                if (taken.stackSize > 0) { insert(chest,taken); if (taken.stackSize > 0) overflowDrops.add(taken); }
            }
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
        if (cursor >= totalWork() && rampCleanup < 0 && !retainedTargets.isEmpty()) current = retainedWork();
        if (current < 0) current = retainedTargets.previousSetBit(totalWork()-1);
        int x = plan[3]+current%width, z = plan[4]+current/width%length, y = plan[7]-current/(width*length);
        if (localRecoveryCell != null || buildingAccess && accessIndex < accessPlan.size() && accessPlan.get(accessIndex)[3] < 0) {
            int[] cell = localRecoveryCell == null ? accessPlan.get(accessIndex) : localRecoveryCell; x = cell[0]; y = cell[1]; z = cell[2];
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
        if (buildingAccess || restockLadders > 0 || storageTask()) {
            requestedBlocks = Math.max(0,requiredSupports()-accessCount(false));
            requestedLadders = Math.max(0,requiredLadders()-accessCount(true));
        }
        int total = (plan[5] - plan[3] + 1) * (plan[6] - plan[4] + 1) * (plan[7] - plan[8] + 1);
        if (!blockWorkRemaining()) return;
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
        // An inaccessible/unloaded station is an access problem, not proof of missing supplies.
        if (!accessSupplyChecked) return;
        for (Depot depot : reachableSupplyDepots) {
            IInventory station=depotInventory(depot); if (station == null) continue;
            for (int i = firstSlot(station); i < station.getSizeInventory(); i++) {
                ItemStack stack = station.getStackInSlot(i);
                if (!tool && suitable(stack)) tool = true;
                if (isTorch(stack)) lights += stack.stackSize;
            }
        }
        requestedTools = tool ? 0 : 1;
        requestedTorches = needsLight && lights == 0 ? 1 : 0;
    }
    private void notifySupplies() {
        // A local collection/search/repair shortage is diagnostic, not a player
        // request. Notify only after recovery has exhausted its real options.
        if (!navigationBlocked && task != Task.RESTOCKING && !waitingForDepot && (requestedBlocks > 0 || requestedLadders > 0)) return;
        long now = System.nanoTime();
        if (running && restockLadders > 0 && requestedLadders > 0
                && (!sentLadderReminder || now-lastLadderReminder >= 60000000000L)) {
            EntityPlayerMP recipient = supplyRecipient();
            if (recipient != null) {
                recipient.addChatMessage(new ChatComponentTranslation(restockLadders > accessCount(true) ? "xf.miner.ladder_notice" : "xf.miner.escape_notice",
                        getCommandSenderName(),getUniqueID().toString().substring(0,8),
                        requestedLadders,Arrays.toString(depotDestination()),
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
            ChatComponentTranslation access = requestedBlocks > 0 ? new ChatComponentTranslation("xf.miner.need_blocks",requestedBlocks)
                    : new ChatComponentTranslation("xf.miner.need_ladders",requestedLadders);
            if (requestedBlocks > 0 && requestedLadders > 0) access=new ChatComponentTranslation("xf.miner.both_requests",access,
                    new ChatComponentTranslation("xf.miner.need_ladders",requestedLadders));
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
        if (!worldObj.isRemote) normalizeDepots();
        updateRequests();
        NBTTagCompound tag = new NBTTagCompound(); tag.setIntArray("Plan", plan); tag.setBoolean("Configured", configured); tag.setInteger("Dimension", jobDimension);
        tag.setBoolean("StationAssigned", stationAssigned);
        NBTTagList linked=new NBTTagList();
        for (Depot depot : depots) {
            NBTTagCompound entry=new NBTTagCompound(); entry.setInteger("Dimension",depot.dimension); entry.setIntArray("Position",depot.position()); linked.appendTag(entry);
        }
        tag.setTag("Depots",linked);
        if (activeDepot != null) tag.setIntArray("ActiveDepot",new int[] {activeDepot.dimension,activeDepot.x,activeDepot.y,activeDepot.z});
        tag.setBoolean("Down",installedDown);
        tag.setString("TemporaryActivity",temporaryActivity());
        tag.setString("TravelRecoveryPhase",movementRecovery.phase.name());
        int[] savedWork = configured ? workDestination() : null;
        if (savedWork != null) tag.setIntArray("SavedWorkObjective",savedWork);
        int[] taskGoal = configured ? taskDestination() : null;
        if (taskGoal != null) tag.setIntArray("TaskDestination",taskGoal);
        if (interactionTarget != null) tag.setIntArray("TemporaryObjective",interactionTarget);
        if (movementRecovery.goal != null) tag.setIntArray("RecoveryActionDestination",movementRecovery.goal);
        if (movementRecovery.localEndpoint != null) tag.setIntArray("RecoveryLanding",movementRecovery.localEndpoint);
        tag.setInteger("RetainedBlocks",retainedTargets.cardinality());
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
        tag.setString("RecoveryAction",recoveryAction); tag.setString("RecoveryBlocker",recoveryBlocker);
        tag.setInteger("RecoveryBlocksShort",Math.max(0,requiredSupports()-accessCount(false)));
        tag.setInteger("RecoveryLaddersShort",Math.max(0,requiredLadders()-accessCount(true)));
        tag.setInteger("SupportsRequired",requiredSupports()); tag.setInteger("LaddersRequired",requiredLadders());
        tag.setInteger("SupportsMissing",Math.max(0,requiredSupports()-accessCount(false)));
        tag.setInteger("LaddersMissing",Math.max(0,requiredLadders()-accessCount(true)));
        tag.setInteger("PlanSupports",supportRequirement()); tag.setInteger("PlanLadders",Math.max(restockLadders,ladderRequirement()));
        tag.setString("SupplyBlocker",supplyBlocker); tag.setString("DepotFailureDetail",depotFailureDetail);
        if (supplyOperation != null) tag.setIntArray("SupplyOperation",supplyOperation);
        if (localRecoveryCell != null) tag.setIntArray("RecoveryOperation",Arrays.copyOf(localRecoveryCell,3));
        tag.setInteger("RestockLadders",restockLadders); tag.setInteger("LaddersCarried",accessCount(true));
        tag.setInteger("RestockBlocks",restockBlocks);
        tag.setInteger("BlocksCarried",accessCount(false)); tag.setInteger("EmergencyBlocks",emergencyBlocks());
        tag.setBoolean("RepairAfterRestock",repairAfterRestock);
        tag.setBoolean("BuildingAccess",buildingAccess || !accessPlan.isEmpty() && (accessProposals != null || reconsideringLadder)); tag.setInteger("AccessIndex",accessIndex);
        tag.setBoolean("StaircaseAccess",staircaseAccess); tag.setBoolean("StaircaseInstalled",staircaseInstalled);
        tag.setBoolean("RepairingLadder",repairingLadder);
        tag.setInteger("AccessPolicyVersion",7);
        tag.setInteger("AccessTripFloor",accessTripFloor);
        tag.setByteArray("RetainedWork",retainedTargets.toByteArray());
        tag.setByteArray("ReleasedAccess",releasedTargets.toByteArray());
        tag.setInteger("RetainedCursor",retainedTarget);
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
        tag.setBoolean("AccessDirectDelivery",navigationBlocked && (buildingAccess) && accessSupplyChecked && !accessStationReachable);
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
        if (configured && cursor >= totalWork() && rampCleanup < 0 && !retainedTargets.isEmpty()) current = retainedWork();
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
        tag.setTag("MinerLadderEntrance",writeAccess(ladderEntrance));
    }
    private NBTTagList writeAccess(List<int[]> source) {
        NBTTagList list = new NBTTagList();
        for (int[] p : source) { NBTTagCompound entry = new NBTTagCompound(); entry.setIntArray("Position",p); list.appendTag(entry); }
        return list;
    }
    private void readAccess(NBTTagList saved,List<int[]> into,int limit) {
        into.clear();
        int[] fixed = ladderPosition == null ? planningLadder : ladderPosition;
        for (int i = 0; i < Math.min(limit,saved.tagCount()); i++) {
            int[] p = saved.getCompoundTagAt(i).getIntArray("Position");
            // Load geometry without querying unloaded chunks or faction startup state.
            // Outside records are bounded hints; collectLadderRequirements rebuilds
            // the authoritative footprint before any saved construction can execute.
            if (configured && p.length == 4 && p[1] >= 0 && p[1] < worldObj.getHeight()
                    && (excavationArea(p[0],p[1],p[2]) || fixed != null
                        && Math.abs(p[0]-fixed[0])+Math.abs(p[2]-fixed[1]) <= 7
                        && p[1] >= plan[8]-3 && p[1] <= plan[7]+2)
                    && (p[3] == -1 || p[3] == 0 || p[3] >= 2 && p[3] <= 5)) into.add(p);
        }
    }
    public void readEntityFromNBT(NBTTagCompound tag) {
        super.readEntityFromNBT(tag); actor = null;
        localRecoveryCell = localRecoveryMove = null; localRecoveryDrop = null; localRecoveryEdits.clear();
        gatheringSupport = false; supplySearch = 0; supplyOrigin = null; rejectedRecovery.clear(); unsuitableRecoveryDrops.clear();
        recoveryAction = recoveryBlocker = ""; settlingTerrain.clear(); gravityTarget = null; ladderTerrain.clear(); ladderEntrance.clear();
        supplyProtected = supplyWrongTool = supplyUnreachable = supplyUnsuitable = 0;
        try { factionId = UUID.fromString(tag.getString("FactionUUID")); employerId = UUID.fromString(tag.getString("EmployerUUID")); }
        catch (IllegalArgumentException absent) { factionId = employerId = null; }
        employerName = tag.getString("EmployerName"); NBTTagCompound job = tag.getCompoundTag("MiningJob");
        jobDimension = job.hasKey("Dimension") ? job.getInteger("Dimension") : dimension;
        int[] saved = job.getIntArray("Plan"); configured = saved.length == PLAN_SIZE && job.getBoolean("Configured");
        if (saved.length == PLAN_SIZE) plan = saved;
        stationAssigned = job.hasKey("StationAssigned") ? job.getBoolean("StationAssigned") : configured;
        depots.clear(); activeDepot=null; triedDepots.clear(); reachableSupplyDepots.clear(); waitingForDepot=false;
        stageSupports = stageLadders = -1; supplyOperation = null; supplyBlocker = depotFailureDetail = "";
        depotDecisionStamp = depotTraceStamp = Long.MIN_VALUE;
        if (job.hasKey("Depots",9)) {
            NBTTagList linked=job.getTagList("Depots",10);
            for (int i=0; i<Math.min(MAX_DEPOTS,linked.tagCount()); i++) {
                NBTTagCompound entry=linked.getCompoundTagAt(i); int[] p=entry.getIntArray("Position");
                if (p.length != 3 || Math.abs((long)p[0]) > 30000000 || Math.abs((long)p[2]) > 30000000 || p[1] < 0 || p[1] >= 256) continue;
                Depot depot=new Depot(entry.getInteger("Dimension"),p[0],p[1],p[2]);
                if (!depots.contains(depot)) depots.add(depot);
            }
        } else if (stationAssigned) depots.add(new Depot(jobDimension,plan[0],plan[1],plan[2]));
        // Do not inspect worlds/chunks during deserialization; loaded double chests normalize on use.
        stationAssigned=!depots.isEmpty();
        int[] active=job.getIntArray("ActiveDepot");
        if (active.length == 4) {
            Depot depot=new Depot(active[0],active[1],active[2],active[3]); if (depots.contains(depot)) activeDepot=depot;
        } else if (!job.hasKey("Depots",9) && stationAssigned) activeDepot=depots.get(0);
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
        watchedTerrain.clear(); terrainChecks = null; clearRouteSearch(); clearAccessSearch();
        torches = tag.hasKey("Torches") ? ItemStack.loadItemStackFromNBT(tag.getCompoundTag("Torches")) : null;
        requiredHarvest = tag.hasKey("RequiredHarvest") ? tag.getInteger("RequiredHarvest") : -1;
        cursor = Math.max(0, job.getInteger("Cursor")); running = false; status = "paused"; toolBroken = tag.getBoolean("ToolBroken");
        retainedTargets.clear(); releasedTargets.clear(); rejectedRetainedTargets.clear(); retainedTarget = -1;
        if (configured && totalWork() > 0) {
            retainedTargets.or(java.util.BitSet.valueOf(job.getByteArray("RetainedWork")));
            releasedTargets.or(java.util.BitSet.valueOf(job.getByteArray("ReleasedAccess")));
            if (retainedTargets.length() > totalWork()) retainedTargets.clear(totalWork(),retainedTargets.length());
            if (releasedTargets.length() > totalWork()) releasedTargets.clear(totalWork(),releasedTargets.length());
            int previousTarget = job.getInteger("RetainedCursor");
            if (previousTarget >= 0 && retainedTargets.get(previousTarget)) retainedTarget = previousTarget;
        }
        task = Task.TO_WORK; storageReason = StorageReason.NONE;
        if (job.getInteger("TaskVersion") == 1) {
            for (Task value : Task.values()) if (value.key.equals(job.getString("Task"))) task = value;
            for (StorageReason value : StorageReason.values()) if (value.key.equals(job.getString("StorageReason"))) storageReason = value;
            if (storageTask() && storageReason == StorageReason.NONE) task = Task.TO_WORK;
        }
        // Retire the old jump-pillar controller; placed blocks still load through MinerAccess.
        if ("escape".equals(job.getString("Task"))) task = storageReason == StorageReason.NONE ? Task.TO_WORK : Task.TO_STORAGE;
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
        ladderPosition = null;
        int[] fixed = job.getIntArray("LadderPosition");
        if (validLadderPosition(fixed)) ladderPosition = fixed;
        planningLadder = null;
        NBTTagList pendingAccess = tag.getTagList("MinerAccessPlan",10);
        for (int i = 0; ladderPosition == null && i < Math.min(8192,pendingAccess.tagCount()); i++) {
            int[] p = pendingAccess.getCompoundTagAt(i).getIntArray("Position");
            if (p.length == 4 && p[3] > 0 && excavationArea(p[0],p[1],p[2])
                    && validLadderPosition(new int[] {p[0],p[2],p[3]})) { planningLadder = new int[] {p[0],p[2],p[3]}; break; }
        }
        readAccess(tag.getTagList("MinerAccess",10),accessBlocks,4096);
        // A retained requirement can also have a clearance prerequisite at the
        // same coordinate. Do not truncate its replacement on reload.
        readAccess(tag.getTagList("MinerAccessPlan",10),accessPlan,8192);
        recoverRecordedLadder();
        readAccess(tag.getTagList("MinerLadderEntrance",10),ladderEntrance,18);
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
        // Migrate the old work-task-plus-return-flag representation to a real
        // depot task. The excavation cursor/target and cargo above are untouched.
        if (job.getBoolean("ResumeStorageAfterAccess")) {
            for (StorageReason reason : StorageReason.values()) if (reason != StorageReason.NONE && reason.key.equals(job.getString("AccessStorageReason"))) {
                storageReason = reason; task = Task.TO_STORAGE; accessDestination = depotDestination(); break;
            }
        }
        if (storageTask() && buildingAccess && job.getInteger("AccessPolicyVersion") < 7) accessDestination = depotDestination();
        accessTripFloor = job.hasKey("AccessTripFloor") ? job.getInteger("AccessTripFloor") : (int)Math.floor(boundingBox.minY+.001);
        if (!job.hasKey("RetainedWork") && configured) {
            for (List<int[]> cells : Arrays.asList(accessBlocks,ladderAccess,accessPlan)) for (int[] cell : cells)
                if (cell[3] >= 0 && excavationArea(cell[0],cell[1],cell[2]) && workIndex(cell[0],cell[1],cell[2]) < cursor)
                    retainedTargets.set(workIndex(cell[0],cell[1],cell[2]));
        }
        movementRecovery.clear();
        restockLadders = Math.max(0,Math.min(512,job.getInteger("RestockLadders")));
        restockBlocks = Math.max(0,Math.min(4096,job.getInteger("RestockBlocks")));
        repairAfterRestock = job.getBoolean("RepairAfterRestock");
        accessSupplies = Long.MIN_VALUE;
        if ("escape".equals(job.getString("Task"))) {
            buildingAccess = staircaseAccess = repairingLadder = false;
            accessPlan.clear(); accessIndex = 0; constructionRefuge.clear();
        }
        sentLadderReminder = false;
        if (ladderPosition != null && job.getInteger("AccessPolicyVersion") < 6) {
            // Saved spare-material requests and verification/staircase travel do
            // not survive as commands. Reevaluate the actual fixed route first.
            restockLadders = restockBlocks = 0;
            buildingAccess = staircaseAccess = repairingLadder = false;
            accessPlan.clear(); accessIndex = 0;
            constructionRefuge.clear();
        }
        descentLanding = null;
        planningLadder = null;
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
    public void markDirty() { supplyCheckTicks = 0; accessSupplyChecked = false; stageSupports = stageLadders = -1; }
    public boolean isUseableByPlayer(EntityPlayer player) { return mayManage(player); }
    public void openInventory() { }
    public void closeInventory() { }
    public boolean isItemValidForSlot(int slot, ItemStack stack) { return slot >= 0 && slot < 27 || slot == 27 && pickaxe(stack) || slot == 28 && isTorch(stack); }
}
