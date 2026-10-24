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
            detail(lines,I18n.format("xf.miner.station")+":");
            detail(lines,state.getBoolean("StationAssigned") ? "D"+state.getInteger("Dimension")+": "+coord(p[0],p[1],p[2]) : I18n.format("xf.miner.no_chest"));
            detail(lines,I18n.format("xf.miner.area")+":");
            if (state.getBoolean("Configured")) {
                detail(lines,coord(p[3],p[8],p[4])+" >"); detail(lines,coord(p[5],p[7],p[6]));
            } else detail(lines,I18n.format("xf.miner.no_area"));
            int tools = state.getInteger("PickaxesRequested"), level = state.getInteger("HarvestLevel"), lights = state.getInteger("TorchesRequested");
            detail(lines,I18n.format("xf.miner.requests"));
            if (tools > 0) detail(lines,I18n.format(level >= 0 ? "xf.miner.request_tier" : "xf.miner.request_pick",level));
            if (lights > 0) detail(lines,I18n.format("xf.miner.need_torches",lights));
            if (tools == 0 && lights == 0) detail(lines,I18n.format("xf.miner.no_requests"));
            detail(lines,I18n.format("xf.miner.task",I18n.format("xf.miner.task."+state.getString("Task"))));
            if (target.length == 3 && "excavate".equals(state.getString("Task"))) detail(lines,coord(target[0],target[1],target[2]));
            int[] c = state.getIntArray("Selection");
            detail(lines,I18n.format("xf.miner.pending")+":");
            if (c.length == 7) {
                detail(lines,"A: "+coord(c[0],c[1],c[2])); detail(lines,"B: "+coord(c[3],c[4],c[5]));
                detail(lines,I18n.format("xf.miner.volume",ItemMinerWand.volume(c))+(c[6] == 1 ? " (Y:0)" : ""));
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
}
