package com.hfr.inventory.gui;

import java.util.ArrayList;
import java.util.List;
import org.lwjgl.input.Mouse;
import com.hfr.entity.EntityFactionMiner;
import com.hfr.inventory.container.ContainerMinerNPC;
import com.hfr.items.ItemMinerWand;
import com.hfr.packet.PacketDispatcher;
import com.hfr.packet.builder.MinerActionPacket;
import cpw.mods.fml.client.config.GuiButtonExt;
import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.inventory.GuiContainer;
import net.minecraft.client.resources.I18n;
import net.minecraft.entity.player.InventoryPlayer;
import net.minecraft.inventory.Slot;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;

@SideOnly(Side.CLIENT)
public final class GUIMinerNPC extends GuiContainer {
    private final EntityFactionMiner worker;
    private NBTTagCompound state;
    private String feedback = "";
    private boolean confirm;
    private GuiButton startButton;
    private int screenTicks, lastSnapshotTick = -1, scroll, detailLines;
    public GUIMinerNPC(InventoryPlayer player, EntityFactionMiner worker) {
        super(new ContainerMinerNPC(player,worker)); this.worker = worker; xSize = 320; ySize = 232;
    }
    public boolean matches(int id, String uuid) { return worker.getEntityId() == id && worker.getUniqueID().toString().equals(uuid); }
    public void receive(NBTTagCompound state, String feedback) {
        if (this.state != null && state.getLong("Sequence") <= this.state.getLong("Sequence")) return;
        this.state = state; lastSnapshotTick = screenTicks;
        if (state.getBoolean("ActionReply") || !feedback.isEmpty()) { this.feedback = feedback; scroll = 0; }
        confirm = state.getBoolean("ConfirmReplacement");
        if (startButton != null) startButton.displayString = I18n.format(confirm ? "xf.miner.confirm" : "xf.miner.start");
    }
    public void initGui() {
        super.initGui(); buttonList.clear();
        startButton = new GuiButtonExt(1,guiLeft+184,guiTop+158,128,20,I18n.format(confirm ? "xf.miner.confirm" : "xf.miner.start"));
        buttonList.add(startButton);
        buttonList.add(new GuiButtonExt(2,guiLeft+184,guiTop+182,128,20,I18n.format("gui.builder.pause")));
        buttonList.add(new GuiButtonExt(3,guiLeft+184,guiTop+206,128,20,I18n.format("xf.miner.wands")));
        buttonList.add(new GuiButtonExt(5,guiLeft+272,guiTop+5,19,14,"\u25b2"));
        buttonList.add(new GuiButtonExt(6,guiLeft+293,guiTop+5,19,14,"\u25bc"));
        request(0);
    }
    private void request(int action) { PacketDispatcher.wrapper.sendToServer(new MinerActionPacket(worker,inventorySlots.windowId,action)); }
    protected void actionPerformed(GuiButton button) {
        if (button.id == 5 || button.id == 6) { scroll = Math.max(0, Math.min(Math.max(0,detailLines-13),scroll+(button.id == 5 ? -5 : 5))); return; }
        request(button.id == 1 && confirm ? 4 : button.id);
    }
    public void updateScreen() {
        super.updateScreen();
        if (++screenTicks % 20 == 0) request(0);
        if (lastSnapshotTick >= 0 && screenTicks-lastSnapshotTick == 101) scroll = 0;
    }
    @Override public void handleMouseInput() {
        super.handleMouseInput();
        int wheel = Mouse.getEventDWheel();
        int x = Mouse.getEventX() * width / mc.displayWidth - guiLeft;
        int y = height - Mouse.getEventY() * height / mc.displayHeight - 1 - guiTop;
        if (wheel != 0 && x >= 181 && x < xSize && y >= 20 && y < 153)
            scroll = Math.max(0,Math.min(Math.max(0,detailLines-13),scroll+(wheel > 0 ? -3 : 3)));
    }
    private void line(String text, int x, int y, int width, int color) {
        fontRendererObj.drawString(fontRendererObj.trimStringToWidth(text,width),x,y,color);
    }
    private static String coord(int x,int y,int z) { return x+", "+y+", "+z; }
    private void detail(List<String> lines, String text) { lines.addAll(fontRendererObj.listFormattedStringToWidth(text,128)); }
    protected void drawGuiContainerBackgroundLayer(float ticks,int mouseX,int mouseY) {
        drawRect(guiLeft,guiTop,guiLeft+xSize,guiTop+ySize,0xee182532);
        drawRect(guiLeft+177,guiTop+6,guiLeft+178,guiTop+ySize-6,0xff405467);
        for (Object value : inventorySlots.inventorySlots) {
            Slot slot = (Slot)value; int x = guiLeft+slot.xDisplayPosition, y = guiTop+slot.yDisplayPosition;
            drawRect(x-1,y-1,x+17,y+17,0xff526576); drawRect(x,y,x+16,y+16,0xff101923);
        }
    }
    protected void drawGuiContainerForegroundLayer(int mouseX,int mouseY) {
        line(I18n.format("xf.miner.title"),8,8,304,0xffffff);
        line(I18n.format("xf.miner.tool"),8,32,100,0xdddddd);
        line(I18n.format("xf.miner.torches"),116,32,54,0xdddddd);
        ItemStack tool = ((Slot)inventorySlots.inventorySlots.get(27)).getStack();
        if (tool != null) {
            String durability = tool.isItemStackDamageable() ? (tool.getMaxDamage()-tool.getItemDamage())+"/"+tool.getMaxDamage() : I18n.format("xf.miner.no_wear");
            line(durability,29,48,83,0xdddddd);
        }
        line(I18n.format("xf.miner.cargo_label"),8,69,162,0xffffff);
        line(I18n.format("container.inventory"),8,137,162,0xffffff);
        line(I18n.format("xf.miner.details"),184,8,84,0xffffff);
        List<String> lines = new ArrayList<String>();
        if (lastSnapshotTick < 0 || screenTicks-lastSnapshotTick > 100)
            detail(lines,I18n.format(screenTicks > 100 ? "xf.miner.sync_failed" : "xf.miner.loading"));
        if (!feedback.isEmpty()) detail(lines,"\u00a7e"+I18n.format(feedback));
        if (state != null) {
            detail(lines,I18n.format("xf.miner.running",I18n.format(state.getBoolean("Running") ? "xf.miner.active" : "xf.miner.inactive")));
            detail(lines,"\u00a7e"+I18n.format("xf.miner.status",I18n.format("xf.miner.state."+state.getString("Status"))));
            int[] p = state.getIntArray("Plan"), target = state.getIntArray("Target");
            if (target.length == 3) detail(lines,I18n.format("xf.miner.work_target",coord(target[0],target[1],target[2])));
            if (state.getInteger("AccessProposals") > 0)
                detail(lines,I18n.format("xf.miner.access_proposal",state.getInteger("AccessProposal"),state.getInteger("AccessProposals"),state.getInteger("AccessEndpointsRemaining")));
            if (!state.getString("AccessStage").isEmpty())
                detail(lines,I18n.format("xf.miner.access_stage",I18n.format("xf.miner.access_stage."+state.getString("AccessStage"))));
            if (!state.getString("NavigationReason").isEmpty()) {
                detail(lines,I18n.format("xf.miner.nav."+state.getString("NavigationReason")));
                if (state.getBoolean("NavigationBlocked")) detail(lines,I18n.format("xf.miner.blocked_action"));
            }
            int[] recoveryStart = state.getIntArray("RecoveryOrigin"), recoveryEnd = state.getIntArray("RecoveryDestination");
            if (recoveryStart.length == 3) {
                detail(lines,I18n.format("xf.miner.recovery_origin",coord(recoveryStart[0],recoveryStart[1],recoveryStart[2])));
                detail(lines,recoveryEnd.length == 3 ? I18n.format("xf.miner.recovery_destination",coord(recoveryEnd[0],recoveryEnd[1],recoveryEnd[2]))
                        : I18n.format("xf.miner.recovery_no_destination"));
                detail(lines,I18n.format("xf.miner.recovery_attempts",state.getInteger("RecoveryAttempts")));
            }
            detail(lines,I18n.format("xf.miner.station")+":");
            detail(lines,state.getBoolean("StationAssigned") ? "D"+state.getInteger("Dimension")+": "+coord(p[0],p[1],p[2]) : I18n.format("xf.miner.no_chest"));
            detail(lines,I18n.format("xf.miner.area")+":");
            if (state.getBoolean("Configured")) {
                detail(lines,coord(p[3],p[8],p[4])+" >"); detail(lines,coord(p[5],p[7],p[6]));
                bounds(lines,p,state.getBoolean("Down"));
            } else detail(lines,I18n.format("xf.miner.no_area"));
            int[] proposed = state.getIntArray("ProposedPlan");
            if (confirm && proposed.length == EntityFactionMiner.PLAN_SIZE) {
                detail(lines,I18n.format("xf.miner.proposed"));
                detail(lines,coord(proposed[3],proposed[8],proposed[4])+" > "+coord(proposed[5],proposed[7],proposed[6]));
                bounds(lines,proposed,state.getBoolean("ProposedDown"));
            }
            if (recoveryStart.length != 3 && !state.getString("NavigationReason").isEmpty()) {
                if (!state.getString("ArrivalFailure").isEmpty()) detail(lines,I18n.format("xf.miner.arrival",I18n.format("xf.miner.nav."+state.getString("ArrivalFailure"))));
                detail(lines,I18n.format("xf.miner.actual",state.getString("ActualPosition")));
            }
            if (!state.getString("NavigationDetail").isEmpty()) detail(lines,state.getString("NavigationDetail"));
            int[] standing = state.getIntArray("StandingPosition"), access = state.getIntArray("AccessDestination");
            int[] entrance = state.getIntArray("Entrance");
            if (entrance.length == 3) detail(lines,I18n.format("xf.miner.entrance",coord(entrance[0],entrance[1],entrance[2])));
            if (standing.length == 3) detail(lines,I18n.format("xf.miner.standing_position",coord(standing[0],standing[1],standing[2])));
            if (state.getBoolean("BuildingAccess") && access.length == 3) detail(lines,I18n.format("xf.miner.access_destination",coord(access[0],access[1],access[2])));
            int[] dest = state.getIntArray("Destination"), waypoint = state.getIntArray("Waypoint");
            if (dest.length == 3) detail(lines,I18n.format("xf.miner.destination",coord(dest[0],dest[1],dest[2])));
            if (waypoint.length == 3) detail(lines,I18n.format("xf.miner.waypoint",coord(waypoint[0],waypoint[1],waypoint[2])));
            int tools = state.getInteger("PickaxesRequested"), level = state.getInteger("HarvestLevel"), lights = state.getInteger("TorchesRequested");
            detail(lines,I18n.format("xf.miner.requests"));
            if (tools > 0) detail(lines,I18n.format(level >= 0 ? "xf.miner.request_tier" : "xf.miner.request_pick",level));
            if (lights > 0) detail(lines,I18n.format("xf.miner.need_torches",lights));
            int blocks = state.getInteger("BlocksRequested"), ladders = state.getInteger("LaddersRequested");
            if (blocks > 0 || ladders > 0) detail(lines,I18n.format("xf.miner.need_access",blocks,ladders));
            if ((blocks > 0 || ladders > 0) && state.getBoolean("AccessDirectDelivery")) detail(lines,I18n.format("xf.miner.direct_supplies"));
            if (tools == 0 && lights == 0 && blocks == 0 && ladders == 0) detail(lines,I18n.format("xf.miner.no_requests"));
            detail(lines,I18n.format("xf.miner.task",I18n.format("xf.miner.task."+state.getString("Task"))));
            if (!"none".equals(state.getString("StorageReason")))
                detail(lines,I18n.format("xf.miner.storage_reason",I18n.format("xf.miner.reason."+state.getString("StorageReason"))));
            if (state.getInteger("Overflow") > 0) detail(lines,I18n.format("xf.miner.overflow",state.getInteger("Overflow")));
            int[] c = state.getIntArray("Selection");
            detail(lines,I18n.format("xf.miner.pending")+":");
            if (c.length == 7) {
                detail(lines,"A: "+coord(c[0],c[1],c[2])); detail(lines,"B: "+coord(c[3],c[4],c[5]));
                bounds(lines,ItemMinerWand.plan(c),c[6] == 1);
                detail(lines,I18n.format("xf.miner.pending_help"));
            } else detail(lines,I18n.format(!state.getString("SelectionError").isEmpty() ? state.getString("SelectionError")
                    : state.getBoolean("Configured") ? "xf.miner.resume_saved" : "xf.miner.use_wands"));
            detail(lines,I18n.format("xf.miner.optional_torches",state.getInteger("TorchRestock")));
            detail(lines,I18n.format("xf.miner.supplies"));
        }
        detailLines = lines.size(); scroll = Math.min(scroll,Math.max(0,detailLines-13));
        for (int i = 0; i < 13 && scroll+i < detailLines; i++) fontRendererObj.drawString(lines.get(scroll+i),184,23+i*10,0xdddddd);
        ((GuiButton)buttonList.get(3)).enabled = scroll > 0;
        ((GuiButton)buttonList.get(4)).enabled = scroll+13 < detailLines;
        if (mouseX-guiLeft >= 184 && mouseY-guiTop >= 206 && mouseY-guiTop < 226)
            drawHoveringText(fontRendererObj.listFormattedStringToWidth(I18n.format("xf.miner.station_controls")+" "+I18n.format("xf.miner.area_controls"),260),mouseX-guiLeft,mouseY-guiTop,fontRendererObj);
    }
    private void bounds(List<String> lines, int[] p, boolean down) {
        detail(lines,I18n.format(down ? "xf.miner.depth_down" : "xf.miner.depth_corners"));
        long volume = ((long)p[5]-p[3]+1)*((long)p[6]-p[4]+1)*((long)p[7]-p[8]+1);
        detail(lines,I18n.format("xf.miner.depth_bounds",p[7],p[8],volume));
    }
}
