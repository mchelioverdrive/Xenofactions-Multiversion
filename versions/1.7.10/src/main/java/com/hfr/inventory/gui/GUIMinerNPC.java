package com.hfr.inventory.gui;

import com.hfr.entity.EntityFactionMiner;
import com.hfr.inventory.container.ContainerMinerNPC;
import com.hfr.packet.PacketDispatcher;
import com.hfr.packet.builder.MinerActionPacket;
import cpw.mods.fml.client.config.GuiButtonExt;
import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiTextField;
import net.minecraft.client.gui.inventory.GuiContainer;
import net.minecraft.client.resources.I18n;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import org.lwjgl.input.Keyboard;

@SideOnly(Side.CLIENT)
public final class GUIMinerNPC extends GuiContainer {
    private static final String[] LABELS = {"chest_x", "chest_y", "chest_z", "min_x", "min_z", "top_y", "max_x", "max_z", "bottom_y"};
    private static final int[] ORDER = {0, 1, 2, 3, 4, 7, 5, 6, 8};
    private final EntityFactionMiner worker;
    private final GuiTextField[] fields = new GuiTextField[9];
    private NBTTagCompound state;
    private String feedback = "";
    private boolean loaded;
    public GUIMinerNPC(EntityFactionMiner worker) { super(new ContainerMinerNPC(worker)); this.worker = worker; xSize = 302; ySize = 224; }
    public boolean matches(int id, String uuid) { return worker.getEntityId() == id && worker.getUniqueID().toString().equals(uuid); }
    public void receive(NBTTagCompound state, String feedback) {
        this.state = state; this.feedback = feedback;
        if (!loaded && fields[0] != null) {
            loaded = true; int[] plan = state.getIntArray("Plan");
            if (!state.getBoolean("Configured")) {
                int x = (int)Math.floor(worker.posX), y = (int)Math.floor(worker.posY), z = (int)Math.floor(worker.posZ);
                plan = new int[] {x, y - 1, z, x + 2, z + 2, x + 2, z + 2, y - 1, Math.max(1, y - 4)};
            }
            for (int i = 0; i < fields.length; i++) fields[i].setText(String.valueOf(plan[ORDER[i]]));
        }
    }
    public void initGui() {
        super.initGui(); Keyboard.enableRepeatEvents(true);
        for (int i = 0; i < fields.length; i++) {
            int x = guiLeft + 10 + (i % 3) * 96, y = guiTop + 62 + (i / 3) * 34;
            if (fields[i] == null) { fields[i] = new GuiTextField(fontRendererObj, x, y, 86, 18); fields[i].setMaxStringLength(9); }
            else { fields[i].xPosition = x; fields[i].yPosition = y; }
        }
        buttonList.clear();
        buttonList.add(new GuiButtonExt(1, guiLeft + 10, guiTop + 164, 90, 24, I18n.format("xf.miner.start")));
        buttonList.add(new GuiButtonExt(2, guiLeft + 106, guiTop + 164, 90, 24, I18n.format("gui.builder.pause")));
        buttonList.add(new GuiButtonExt(3, guiLeft + 202, guiTop + 164, 90, 24, I18n.format("xf.nav.play")));
        request(0);
    }
    private void request(int action) {
        int[] plan = new int[9];
        if (action == 1) try { for (int i = 0; i < fields.length; i++) plan[ORDER[i]] = Integer.parseInt(fields[i].getText()); }
        catch (NumberFormatException invalid) { feedback = "xf.miner.invalid_area"; return; }
        PacketDispatcher.wrapper.sendToServer(new MinerActionPacket(worker, action, plan));
    }
    protected void actionPerformed(GuiButton button) { if (button.id == 3) mc.displayGuiScreen(null); else request(button.id); }
    public void updateScreen() { super.updateScreen(); for (GuiTextField field : fields) field.updateCursorCounter(); if (mc.thePlayer.ticksExisted % 20 == 0) request(0); }
    public void onGuiClosed() { Keyboard.enableRepeatEvents(false); super.onGuiClosed(); }
    protected void mouseClicked(int x, int y, int button) { super.mouseClicked(x, y, button); for (GuiTextField field : fields) field.mouseClicked(x, y, button); }
    protected void keyTyped(char character, int key) {
        if (key == Keyboard.KEY_ESCAPE || key == mc.gameSettings.keyBindInventory.getKeyCode()) { mc.displayGuiScreen(null); return; }
        if (key == Keyboard.KEY_TAB) {
            int current = -1; for (int i = 0; i < fields.length; i++) if (fields[i].isFocused()) current = i;
            for (GuiTextField field : fields) field.setFocused(false);
            fields[current < 0 ? (isShiftKeyDown() ? 8 : 0) : (current + (isShiftKeyDown() ? 8 : 1)) % 9].setFocused(true); return;
        }
        for (GuiTextField field : fields) if (field.textboxKeyTyped(character, key)) return;
        super.keyTyped(character, key);
    }
    protected void drawGuiContainerBackgroundLayer(float ticks, int mouseX, int mouseY) { drawRect(guiLeft, guiTop, guiLeft + xSize, guiTop + ySize, 0xee182532); }
    protected void drawGuiContainerForegroundLayer(int mouseX, int mouseY) {
        fontRendererObj.drawString(I18n.format("xf.miner.title"), 10, 8, 0xffffff);
        if (state != null) {
            fontRendererObj.drawString(fontRendererObj.trimStringToWidth(I18n.format("xf.miner.status", I18n.format("xf.miner.state." + state.getString("Status"))), xSize - 20), 10, 23, 0xffdd88);
            ItemStack tool = state.hasKey("Tool") ? ItemStack.loadItemStackFromNBT(state.getCompoundTag("Tool")) : null;
            String toolName = tool == null ? I18n.format("gui.builder.none") : tool.getDisplayName() + (tool.isItemStackDamageable() ? " " + (tool.getMaxDamage() - tool.getItemDamage()) + "/" + tool.getMaxDamage() : "");
            fontRendererObj.drawString(fontRendererObj.trimStringToWidth(I18n.format("xf.miner.cargo", state.getInteger("Cargo"), toolName), xSize - 20), 10, 37, 0xdddddd);
        }
        for (int i = 0; i < fields.length; i++) fontRendererObj.drawString(I18n.format("xf.miner." + LABELS[i]), 10 + i % 3 * 96, 51 + i / 3 * 34, 0xffffff);
        fontRendererObj.drawSplitString(I18n.format(feedback.isEmpty() ? "xf.miner.supplies" : feedback), 10, 194, xSize - 20, 0xdddddd);
    }
    public void drawScreen(int x, int y, float ticks) { super.drawScreen(x, y, ticks); for (GuiTextField field : fields) field.drawTextBox(); }
}
