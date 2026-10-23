package com.hfr.inventory.gui;

import java.util.List;
import com.hfr.client.XFLoginFlow;
import com.hfr.client.XFFactionSetup;
import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;
import net.minecraft.client.gui.*;
import net.minecraft.client.resources.I18n;

/** Optional reference pages, wrapped and paginated at the current GUI scale. */
@SideOnly(Side.CLIENT)
public final class GUIXFHelp extends XFListMenu implements GuiYesNoCallback {
    private static final String[] TOPICS = {"map", "factions", "cities", "prestige", "safety", "menus"};
    private static int rememberedTopic = -1, rememberedPage, rememberedRow;
    private final GuiScreen parent;
    private int topic = rememberedTopic, page = rememberedPage;
    public GUIXFHelp(GuiScreen parent) { this.parent = parent; firstRow = rememberedRow; }
    private int capacity() { return Math.max(1, (height - 106) / 12); }
    private List<String> lines() { return fontRendererObj.listFormattedStringToWidth(I18n.format("xf.help." + TOPICS[topic] + ".body").replace("\\n","\n"), contentWidth() - 8); }
    public void initGui() {
        beginList(36, height - 36);
        int x = left(), w = contentWidth();
        if (topic < 0) {
            for (int i = 0; i < TOPICS.length; i++) row(10 + i, I18n.format("xf.help." + TOPICS[i]), "");
            if (XFFactionSetup.active()) {
                row(5, I18n.format("xf.setup.resume"), "");
                row(6, I18n.format(XFFactionSetup.hidden() ? "xf.setup.show" : "xf.setup.hide"), "");
            }
            row(3, I18n.format("xf.menu.reset_tutorial"), "");
        } else {
            page = Math.min(page, Math.max(0, (lines().size() - 1) / capacity()));
            button(1, x, height - 59, 88, 26, I18n.format("xf.tutorial.back")).enabled = page > 0;
            button(2, x + w - 88, height - 59, 88, 26, I18n.format("xf.tutorial.next")).enabled = (page + 1) * capacity() < lines().size();
            if (topic == 0 && parent instanceof com.hfr.client.earth.GuiXFEarthMap)
                button(4, width / 2 - 40, height - 29, 80, 26, I18n.format("xf.map.sources"));
        }
        button(0, x, height - 29, 90, 26, I18n.format("xf.nav.back"));
        button(7, x + w - 90, height - 29, 90, 26, I18n.format("xf.nav.play"));
        finishList();
    }
    protected void actionPerformed(GuiButton b) {
        if (scrollButton(b)) return;
        if (b.id >= 10) { topic = b.id - 10; page = 0; initGui(); }
        else if (b.id == 0) back();
        else if (b.id == 3) mc.displayGuiScreen(new GuiYesNo(this, I18n.format("xf.confirm.reset_tutorial"), I18n.format("xf.help.replay"), 3));
        else if (b.id == 4) ((com.hfr.client.earth.GuiXFEarthMap)parent).showInfo(this);
        else if (b.id == 5) XFFactionSetup.resume();
        else if (b.id == 6) XFFactionSetup.toggle();
        else if (b.id == 7) mc.displayGuiScreen(null);
        else { page += b.id == 1 ? -1 : 1; initGui(); }
    }
    protected void scroll(int delta) {
        if (topic < 0) super.scroll(delta);
        else { page = Math.max(0, Math.min((lines().size() - 1) / capacity(), page + delta)); initGui(); }
    }
    public void confirmClicked(boolean ok, int id) {
        if (ok) { XFLoginFlow.replay(); mc.displayGuiScreen(null); }
        else mc.displayGuiScreen(this);
    }
    protected void back() { if (topic >= 0) { topic = -1; page = 0; initGui(); } else mc.displayGuiScreen(parent); }
    public void onGuiClosed() { rememberedTopic = topic; rememberedPage = page; rememberedRow = firstRow; super.onGuiClosed(); }
    public void updateScreen() { if (XFFactionSetup.consumeMenuRefresh()) initGui(); }
    public void drawScreen(int mx, int my, float ticks) {
        drawDefaultBackground();
        drawCenteredString(fontRendererObj, I18n.format(topic < 0 ? "xf.home.help" : "xf.help." + TOPICS[topic]), width / 2, 12, 0xffffff);
        if (topic >= 0) {
            List<String> text = lines();
            for (int i = page * capacity(), y = 38; i < Math.min(text.size(), (page + 1) * capacity()); i++, y += 12)
                drawString(fontRendererObj, text.get(i), left() + 4, y, 0xdddddd);
            drawCenteredString(fontRendererObj, (page + 1) + "/" + Math.max(1, (text.size() + capacity() - 1) / capacity()), width / 2, height - 50, 0xaaaaaa);
        }
        super.drawScreen(mx, my, ticks);
    }
}
