package com.hfr.inventory.gui;

import java.util.ArrayList;
import java.util.List;
import cpw.mods.fml.client.config.GuiButtonExt;
import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;

/** Compact action groups. Forge's continuous button slices stay inside the 200x20 texture. */
@SideOnly(Side.CLIENT)
public abstract class XFListMenu extends GuiScreen {
    private final List<Row> rows = new ArrayList<Row>();
    protected int firstRow;
    private int top, bottom, focus = -1;
    private static final class Row {
        GuiButton button;
        List<String> description;
        int height;
    }
    protected int contentWidth() { return Math.max(80, Math.min(420, width - 24)); }
    protected int left() { return (width - contentWidth()) / 2; }
    protected void beginList(int top, int bottom) {
        buttonList.clear(); rows.clear(); this.top = top; this.bottom = bottom;
    }
    protected GuiButton button(int id, int x, int y, int w, int h, String text) {
        GuiButton b = new GuiButtonExt(id, x, y, w, h, text);
        buttonList.add(b); return b;
    }
    protected void row(int id, String label, String description) {
        Row row = new Row();
        row.button = button(id, left(), top, contentWidth(), 28, label);
        row.description = new ArrayList<String>(fontRendererObj.listFormattedStringToWidth(description, contentWidth() - 8));
        if (description.isEmpty()) row.description.clear();
        row.height = 28 + (row.description.isEmpty() ? 0 : 4 + row.description.size() * fontRendererObj.FONT_HEIGHT) + 8;
        rows.add(row);
    }
    protected void finishList() {
        firstRow = Math.max(0, Math.min(firstRow, lastStart()));
        layoutRows();
        if (!rows.isEmpty() && (firstRow > 0 || !rows.get(rows.size() - 1).button.visible)) {
            button(9000, left(), top - 24, 22, 20, "^").enabled = firstRow > 0;
            button(9001, left() + contentWidth() - 22, top - 24, 22, 20, "v").enabled = !rows.get(rows.size() - 1).button.visible;
        }
    }
    private void layoutRows() {
        firstRow = Math.max(0, Math.min(firstRow, lastStart()));
        int y = top;
        for (int i = 0; i < rows.size(); i++) {
            Row row = rows.get(i);
            row.button.visible = i >= firstRow && y + row.height - 8 <= bottom;
            if (i >= firstRow) { row.button.yPosition = y; y += row.height; }
        }
        for (Object raw : buttonList) {
            GuiButton b = (GuiButton)raw;
            if (b.id == 9000) b.enabled = firstRow > 0;
            if (b.id == 9001) b.enabled = !rows.isEmpty() && !rows.get(rows.size() - 1).button.visible;
        }
    }
    private int lastStart() {
        if (rows.isEmpty()) return 0;
        int first = rows.size() - 1, used = rows.get(first).height - 8;
        while (first > 0 && used + rows.get(first - 1).height <= bottom - top) used += rows.get(--first).height;
        return first;
    }
    protected void scroll(int delta) {
        if (rows.isEmpty()) return;
        if (delta > 0 && rows.get(rows.size() - 1).button.visible) return;
        firstRow = Math.max(0, Math.min(rows.size() - 1, firstRow + delta)); initGui();
    }
    protected boolean scrollButton(GuiButton button) {
        if (button.id != 9000 && button.id != 9001) return false;
        scroll(button.id == 9000 ? -1 : 1); return true;
    }
    public void handleMouseInput() {
        super.handleMouseInput(); int wheel = Mouse.getEventDWheel();
        if (wheel != 0) scroll(wheel < 0 ? 1 : -1);
    }
    protected void mouseClicked(int x, int y, int mouse) {
        for (Object o : buttonList) { GuiButton b = (GuiButton)o; if (b.mousePressed(mc, x, y)) focus = b.id; }
        super.mouseClicked(x, y, mouse);
    }
    /** Tab/arrows include off-screen actions and reveal the focused group. */
    protected void keyTyped(char c, int key) {
        if (key == Keyboard.KEY_ESCAPE) { back(); return; }
        if (key == Keyboard.KEY_RETURN || key == Keyboard.KEY_NUMPADENTER || key == Keyboard.KEY_SPACE) {
            for (Object o : buttonList) { GuiButton b = (GuiButton)o;
                if (b.id == focus && b.enabled && b.visible) { b.func_146113_a(mc.getSoundHandler()); actionPerformed(b); return; }
            }
        }
        int direction = key == Keyboard.KEY_UP || key == Keyboard.KEY_LEFT || key == Keyboard.KEY_TAB && isShiftKeyDown() ? -1
                : key == Keyboard.KEY_DOWN || key == Keyboard.KEY_RIGHT || key == Keyboard.KEY_TAB ? 1 : 0;
        if (direction == 0 || buttonList.isEmpty()) return;
        int index = -1;
        for (int i = 0; i < buttonList.size(); i++) if (((GuiButton)buttonList.get(i)).id == focus) index = i;
        if (index < 0 && direction < 0) index = 0;
        for (int n = 0; n < buttonList.size(); n++) {
            index = (index + direction + buttonList.size()) % buttonList.size();
            GuiButton b = (GuiButton)buttonList.get(index);
            if (!b.enabled) continue;
            focus = b.id;
            for (int i = 0; i < rows.size(); i++) if (rows.get(i).button == b && !b.visible) { firstRow = i; layoutRows(); break; }
            return;
        }
    }
    protected abstract void back();
    public boolean doesGuiPauseGame() { return false; }
    public void drawScreen(int x, int y, float ticks) {
        for (Row row : rows) if (row.button.visible) {
            int lineY = row.button.yPosition + row.button.height + 4;
            for (String line : row.description) {
                drawString(fontRendererObj, line, left() + 4, lineY, 0xcccccc); lineY += fontRendererObj.FONT_HEIGHT;
            }
        }
        super.drawScreen(x, y, ticks);
        for (Object o : buttonList) {
            GuiButton b = (GuiButton)o;
            if (b.id == focus && b.enabled && b.visible) {
                drawRect(b.xPosition, b.yPosition, b.xPosition + b.width, b.yPosition + 1, 0xffffdd88);
                drawRect(b.xPosition, b.yPosition + b.height - 1, b.xPosition + b.width, b.yPosition + b.height, 0xffffdd88);
            }
        }
    }
}
