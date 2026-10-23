package com.hfr.inventory.gui;

import java.io.File;
import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.resources.I18n;

/** Three optional steps; returning from an action keeps the same step. */
@SideOnly(Side.CLIENT)
public class GUIXenofactionsTutorial extends GuiScreen {
    private static final String[][] ACTIONS = {
        {"c_list", "c_apply", "c_create"}, {"c_claim", "c_capital", "c_sethome"}, {"c_home", "c_balance", "c_info"}
    };
    private final GuiScreen parent;
    private int page;
    public GUIXenofactionsTutorial() { this(new GUIXenofactionsHome()); }
    public GUIXenofactionsTutorial(GuiScreen parent) { this.parent = parent; }
    public boolean doesGuiPauseGame() { return false; }
    public void initGui() {
        buttonList.clear();
        int w = Math.min(400, width - 20), x = (width - w) / 2;
        for (int i = 0; i < 3; i++) buttonList.add(new GuiButton(10 + i, x, 53 + i * stepHeight(), w, 18,
                I18n.format("xf.start." + ACTIONS[page][i])));
        int bw = Math.min(100, (width - 26) / 4), start = (width - (bw * 4 + 18)) / 2;
        buttonList.add(new GuiButton(0, start, height - 25, bw, 20, I18n.format("xf.nav.back")));
        GuiButton previous = new GuiButton(1, start + bw + 6, height - 25, bw, 20, I18n.format("xf.tutorial.back"));
        previous.enabled = page > 0; buttonList.add(previous);
        buttonList.add(new GuiButton(2, start + (bw + 6) * 2, height - 25, bw, 20,
                I18n.format(page == 2 ? "xf.home.help" : "xf.tutorial.next")));
        buttonList.add(new GuiButton(3, start + (bw + 6) * 3, height - 25, bw, 20, I18n.format("xf.nav.play")));
    }
    private int stepHeight() { return Math.max(43, (height - 90) / 3); }
    protected void actionPerformed(GuiButton button) {
        if (button.id >= 10) GUIXenofactionsMenu.requestAndOpen(this, ACTIONS[page][button.id - 10]);
        else if (button.id == 0) mc.displayGuiScreen(parent);
        else if (button.id == 1) { page--; initGui(); }
        else if (button.id == 2) {
            if (page < 2) { page++; initGui(); } else mc.displayGuiScreen(new GUIXFHelp(this));
        } else mc.displayGuiScreen(null);
    }
    protected void keyTyped(char c, int key) { if (key == 1) mc.displayGuiScreen(parent); }
    public void drawScreen(int mx, int my, float ticks) {
        drawDefaultBackground();
        drawCenteredString(fontRendererObj, I18n.format("xf.tutorial.title"), width / 2, 10, 0xffffff);
        drawCenteredString(fontRendererObj, I18n.format("xf.start.step" + page), width / 2, 28, 0xffdd88);
        int w = Math.min(400, width - 20), x = (width - w) / 2;
        for (int i = 0; i < 3; i++) fontRendererObj.drawSplitString(I18n.format("xf.start." + ACTIONS[page][i] + ".desc"),
                x + 3, 75 + i * stepHeight(), w - 6, 0xcccccc);
        super.drawScreen(mx, my, ticks);
    }
    /** Read-only migration of the former installation-wide dismissal marker. */
    public static File getFlagFile() { return new File(Minecraft.getMinecraft().mcDataDir, "tutorialflag"); }
}
