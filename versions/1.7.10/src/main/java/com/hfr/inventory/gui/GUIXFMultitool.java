package com.hfr.inventory.gui;

import java.util.List;
import com.hfr.client.XFFactionSetup;
import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.resources.I18n;

/** Guidance only: acquiring the tool remains an explicit regular-player command. */
@SideOnly(Side.CLIENT)
public final class GUIXFMultitool extends XFListMenu {
    public void initGui() {
        beginList(36, height - 40);
        button(0, left(), height - 32, contentWidth(), 26, I18n.format("xf.nav.play"));
        finishList();
    }
    protected void actionPerformed(GuiButton button) { back(); }
    protected void back() { mc.displayGuiScreen(null); }
    public void onGuiClosed() { XFFactionSetup.completeMultitool(); super.onGuiClosed(); }
    public void drawScreen(int mouseX, int mouseY, float ticks) {
        drawDefaultBackground();
        drawCenteredString(fontRendererObj, I18n.format("xf.setup.multitool.title"), width / 2, 12, 0xffffff);
        int y = 42;
        for (String key : new String[] {"xf.setup.multitool.instruction", "xf.setup.multitool.body"}) {
            List<String> lines = fontRendererObj.listFormattedStringToWidth(I18n.format(key), contentWidth() - 8);
            for (String line : lines) { drawString(fontRendererObj, line, left() + 4, y, 0xeeeeee); y += fontRendererObj.FONT_HEIGHT + 2; }
            y += 10;
        }
        super.drawScreen(mouseX, mouseY, ticks);
    }
}
