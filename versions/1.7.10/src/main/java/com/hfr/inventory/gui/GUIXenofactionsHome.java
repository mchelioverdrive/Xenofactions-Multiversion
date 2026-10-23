package com.hfr.inventory.gui;

import com.hfr.client.earth.GuiXFEarthMap;
import cpw.mods.fml.client.config.GuiButtonExt;
import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.resources.I18n;

@SideOnly(Side.CLIENT)
public final class GUIXenofactionsHome extends XFListMenu {
    private static final String[] ACTIONS = {"map", "start", "actions", "help", "rewards"};
    public boolean doesGuiPauseGame() { return false; }
    public void initGui() {
        buttonList.clear();
        int w = Math.min(360, width - 20), x = (width - w) / 2;
        for (int i = 0; i < ACTIONS.length; i++)
            buttonList.add(new GuiButtonExt(i, x, 42 + i * 32, w, 18, I18n.format("xf.home." + ACTIONS[i])));
        buttonList.add(new GuiButtonExt(9, width / 2 - 60, height - 25, 120, 20, I18n.format("xf.nav.play")));
    }
    protected void actionPerformed(GuiButton button) {
        switch (button.id) {
            case 0: mc.displayGuiScreen(new GuiXFEarthMap(this, false)); break;
            case 1: mc.displayGuiScreen(new GUIXenofactionsTutorial(this)); break;
            case 2: GUIXenofactionsMenu.requestAndOpen(this, null); break;
            case 3: mc.displayGuiScreen(new GUIXFHelp(this)); break;
            case 4: mc.thePlayer.sendChatMessage("/xdaily"); mc.displayGuiScreen(null); break;
            default: mc.displayGuiScreen(null);
        }
    }
    protected void back() { mc.displayGuiScreen(null); }
    public void drawScreen(int mx, int my, float ticks) {
        drawDefaultBackground();
        drawCenteredString(fontRendererObj, I18n.format("xf.home.title"), width / 2, 10, 0xffffff);
        drawCenteredString(fontRendererObj, I18n.format("xf.home.subtitle"), width / 2, 26, 0xcccccc);
        int w = Math.min(360, width - 20);
        for (int i = 0; i < ACTIONS.length; i++)
            drawCenteredString(fontRendererObj, fontRendererObj.trimStringToWidth(I18n.format("xf.home." + ACTIONS[i] + ".desc"), w),
                    width / 2, 63 + i * 32, 0xcccccc);
        super.drawScreen(mx, my, ticks);
    }
}
