package com.hfr.inventory.gui;

import java.io.File;
import com.hfr.client.XFFactionSetup;
import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.resources.I18n;

/** Optional reference pages; action returns and later visits retain the selected page. */
@SideOnly(Side.CLIENT)
public class GUIXenofactionsTutorial extends XFListMenu {
    private static final String[][] ACTIONS = {
        {"c_list", "c_apply", "c_create"}, {"c_claim", "c_capital", "c_sethome"}, {"c_home", "c_balance", "c_info"}
    };
    private static int rememberedPage;
    private final GuiScreen parent;
    private int page = rememberedPage;
    public GUIXenofactionsTutorial() { this(new GUIXenofactionsHome()); }
    public GUIXenofactionsTutorial(GuiScreen parent) { this.parent = parent; }
    public void initGui() {
        beginList(46, height - (XFFactionSetup.active() ? 68 : 38));
        for (int i = 0; i < 3; i++) row(10 + i, I18n.format("xf.start." + ACTIONS[page][i]),
                I18n.format("xf.start." + ACTIONS[page][i] + ".desc"));
        if (XFFactionSetup.active()) {
            button(4, left(), height - 62, (contentWidth() - 6) / 2, 26, I18n.format("xf.setup.resume"));
            button(5, left() + (contentWidth() + 6) / 2, height - 62, (contentWidth() - 6) / 2, 26,
                    I18n.format(XFFactionSetup.hidden() ? "xf.setup.show" : "xf.setup.hide"));
        }
        int bw = (contentWidth() - 18) / 4, x = left();
        button(0, x, height - 29, bw, 26, I18n.format("xf.nav.back"));
        button(1, x + bw + 6, height - 29, bw, 26, I18n.format("xf.tutorial.back")).enabled = page > 0;
        button(2, x + (bw + 6) * 2, height - 29, bw, 26,
                I18n.format(page == 2 ? "xf.home.help" : "xf.tutorial.next"));
        button(3, x + (bw + 6) * 3, height - 29, bw, 26, I18n.format("xf.nav.play"));
        finishList();
    }
    protected void actionPerformed(GuiButton b) {
        if (scrollButton(b)) return;
        if (b.id >= 10) GUIXenofactionsMenu.requestAndOpen(this, ACTIONS[page][b.id - 10]);
        else if (b.id == 0) back();
        else if (b.id == 1) { rememberedPage = --page; firstRow = 0; initGui(); }
        else if (b.id == 2) {
            if (page < 2) { rememberedPage = ++page; firstRow = 0; initGui(); } else mc.displayGuiScreen(new GUIXFHelp(this));
        } else if (b.id == 4) XFFactionSetup.resume();
        else if (b.id == 5) XFFactionSetup.toggle();
        else mc.displayGuiScreen(null);
    }
    protected void back() { mc.displayGuiScreen(parent); }
    public void updateScreen() { if (XFFactionSetup.consumeMenuRefresh()) initGui(); }
    public void drawScreen(int mx, int my, float ticks) {
        drawDefaultBackground();
        drawCenteredString(fontRendererObj, I18n.format("xf.tutorial.title"), width / 2, 10, 0xffffff);
        drawCenteredString(fontRendererObj, I18n.format("xf.start.step" + page), width / 2, 28, 0xffdd88);
        super.drawScreen(mx, my, ticks);
    }
    /** Read-only migration of the former installation-wide dismissal marker. */
    public static File getFlagFile() { return new File(Minecraft.getMinecraft().mcDataDir, "tutorialflag"); }
}
