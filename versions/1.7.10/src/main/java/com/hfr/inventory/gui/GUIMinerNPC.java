package com.hfr.inventory.gui;

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
    private int feedbackTicks;
    public GUIMinerNPC(InventoryPlayer player, EntityFactionMiner worker) {
        super(new ContainerMinerNPC(player,worker)); this.worker = worker; xSize = 320; ySize = 232;
    }
    public boolean matches(int id, String uuid) { return worker.getEntityId() == id && worker.getUniqueID().toString().equals(uuid); }
    public void receive(NBTTagCompound state, String feedback) {
        this.state = state; if (!feedback.isEmpty()) { this.feedback = feedback; feedbackTicks = 160; }
    }
    public void initGui() {
        super.initGui(); buttonList.clear(); confirm = false;
        buttonList.add(new GuiButtonExt(1,guiLeft+184,guiTop+158,61,20,I18n.format("xf.miner.start")));
        buttonList.add(new GuiButtonExt(2,guiLeft+251,guiTop+158,61,20,I18n.format("gui.builder.pause")));
        buttonList.add(new GuiButtonExt(3,guiLeft+184,guiTop+182,128,20,I18n.format("xf.miner.wands")));
        buttonList.add(new GuiButtonExt(4,guiLeft+184,guiTop+206,128,20,I18n.format("xf.miner.assign")));
        request(0);
    }
    private void request(int action) { PacketDispatcher.wrapper.sendToServer(new MinerActionPacket(worker,action)); }
    protected void actionPerformed(GuiButton button) {
        if (button.id == 4 && !confirm) {
            confirm = true; button.displayString = I18n.format("xf.miner.confirm"); return;
        }
        request(button.id); confirm = false;
        ((GuiButton)buttonList.get(3)).displayString = I18n.format("xf.miner.assign");
    }
    public void updateScreen() { super.updateScreen(); if (feedbackTicks > 0) feedbackTicks--; if (mc.thePlayer.ticksExisted % 20 == 0) request(0); }
    private void line(String text, int x, int y, int width, int color) {
        fontRendererObj.drawString(fontRendererObj.trimStringToWidth(text,width),x,y,color);
    }
    private static String coord(int x,int y,int z) { return x+", "+y+", "+z; }
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
        if (state == null) return;
        String status = I18n.format("xf.miner.status",I18n.format("xf.miner.state."+state.getString("Status")));
        line(status,8,20,304,0xffdd88);
        int[] p = state.getIntArray("Plan");
        line(I18n.format("xf.miner.station"),184,33,128,0xffffff);
        line(state.getBoolean("StationAssigned") ? "D"+state.getInteger("Dimension")+": "+coord(p[0],p[1],p[2]) : I18n.format("gui.builder.none"),184,44,128,0xdddddd);
        line(I18n.format("xf.miner.area"),184,57,128,0xffffff);
        line(state.getBoolean("Configured") ? coord(p[3],p[8],p[4])+" > "+coord(p[5],p[7],p[6]) : I18n.format("gui.builder.none"),184,68,128,0xdddddd);
        int[] c = state.getIntArray("Selection");
        line(I18n.format("xf.miner.pending"),184,81,128,0xffffff);
        if (c.length == 7) {
            line("A: "+coord(c[0],c[1],c[2])+" B: "+coord(c[3],c[4],c[5]),184,92,128,0x88ddff);
            line(I18n.format("xf.miner.volume",ItemMinerWand.volume(c))+(c[6] == 1 ? " (Y:0)" : ""),184,103,128,0x88ddff);
        } else line(I18n.format("xf.miner.use_wands"),184,92,128,0xdddddd);
        line(I18n.format("xf.miner.requests"),184,116,128,0xffffff);
        int tools = state.getInteger("PickaxesRequested"), level = state.getInteger("HarvestLevel");
        line(tools > 0 ? I18n.format(level >= 0 ? "xf.miner.request_tier" : "xf.miner.request_pick",level) : I18n.format("xf.miner.tool_ready"),184,127,128,0xffdd88);
        line(I18n.format("xf.miner.request_torches",state.getInteger("TorchesRequested")),184,138,128,0xffdd88);
        if (feedbackTicks > 0) {
            drawRect(181,31,315,153,0xff182532);
            fontRendererObj.drawSplitString(I18n.format(feedback),184,34,128,0xffdd88);
        }
        if (mouseY-guiTop < 31) drawHoveringText(fontRendererObj.listFormattedStringToWidth(status,300),mouseX-guiLeft,mouseY-guiTop,fontRendererObj);
        if (mouseX-guiLeft >= 184 && mouseY-guiTop >= 33 && mouseY-guiTop < 116) {
            String details = I18n.format("xf.miner.station")+": D"+state.getInteger("Dimension")+" "+coord(p[0],p[1],p[2])+"\n";
            if (state.getBoolean("Configured")) details += I18n.format("xf.miner.area")+": "+coord(p[3],p[8],p[4])+" > "+coord(p[5],p[7],p[6])+"\n";
            if (c.length == 7) details += "A: "+coord(c[0],c[1],c[2])+" B: "+coord(c[3],c[4],c[5])+"\n"+I18n.format("xf.miner.volume",ItemMinerWand.volume(c));
            drawHoveringText(fontRendererObj.listFormattedStringToWidth(details,280),mouseX-guiLeft,mouseY-guiTop,fontRendererObj);
        }
        if (mouseX-guiLeft >= 184 && mouseY-guiTop >= 182 && mouseY-guiTop < 202)
            drawHoveringText(fontRendererObj.listFormattedStringToWidth(I18n.format("xf.miner.station_controls")+" "+I18n.format("xf.miner.area_controls"),260),mouseX-guiLeft,mouseY-guiTop,fontRendererObj);
    }
}
