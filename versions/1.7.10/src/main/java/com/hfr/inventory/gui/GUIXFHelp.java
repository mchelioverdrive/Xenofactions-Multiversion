package com.hfr.inventory.gui;

import java.util.List;
import com.hfr.client.XFLoginFlow;
import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;
import net.minecraft.client.gui.*;
import net.minecraft.client.resources.I18n;

/** Optional reference pages, paginated after wrapping at the current GUI scale. */
@SideOnly(Side.CLIENT)
public final class GUIXFHelp extends GuiScreen implements GuiYesNoCallback {
    private static final String[] TOPICS = {"map", "factions", "cities", "prestige", "safety", "menus"};
    private final GuiScreen parent;
    private int topic = -1, page;
    public GUIXFHelp(GuiScreen parent) { this.parent = parent; }
    public boolean doesGuiPauseGame() { return false; }
    private int capacity() { return Math.max(1, (height - 90) / 12); }
    private List<String> lines() { return fontRendererObj.listFormattedStringToWidth(I18n.format("xf.help." + TOPICS[topic] + ".body").replace("\\n","\n"), Math.min(440, width - 28)); }
    public void initGui() {
        buttonList.clear();
        int w = Math.min(440, width - 20), x = (width - w) / 2;
        if (topic < 0) {
            for (int i = 0; i < TOPICS.length; i++) buttonList.add(new GuiButton(10 + i, x, 39 + i * 23, w, 20,
                    I18n.format("xf.help." + TOPICS[i])));
            buttonList.add(new GuiButton(3, x, 180, w, 20, I18n.format("xf.menu.reset_tutorial")));
        } else {
            page = Math.min(page, Math.max(0, (lines().size() - 1) / capacity()));
            GuiButton prev = new GuiButton(1, x, height - 50, 70, 20, I18n.format("xf.tutorial.back"));
            GuiButton next = new GuiButton(2, x + w - 70, height - 50, 70, 20, I18n.format("xf.tutorial.next"));
            prev.enabled = page > 0; next.enabled = (page + 1) * capacity() < lines().size();
            buttonList.add(prev); buttonList.add(next);
            if (topic == 0 && parent instanceof com.hfr.client.earth.GuiXFEarthMap)
                buttonList.add(new GuiButton(4,x,height-25,80,20,I18n.format("xf.map.sources")));
        }
        buttonList.add(new GuiButton(0, width / 2 - 50, height - 25, 100, 20, I18n.format("xf.nav.back")));
    }
    protected void actionPerformed(GuiButton b) {
        if (b.id >= 10) { topic = b.id - 10; page = 0; initGui(); }
        else if (b.id == 0) back();
        else if (b.id == 3) mc.displayGuiScreen(new GuiYesNo(this, I18n.format("xf.confirm.reset_tutorial"), I18n.format("xf.help.replay"), 3));
        else if (b.id == 4) ((com.hfr.client.earth.GuiXFEarthMap)parent).showInfo(this);
        else { page += b.id == 1 ? -1 : 1; initGui(); }
    }
    public void confirmClicked(boolean ok, int id) {
        if (ok) { XFLoginFlow.replay(); mc.displayGuiScreen(null); }
        else mc.displayGuiScreen(this);
    }
    private void back() { if (topic >= 0) { topic = -1; page = 0; initGui(); } else mc.displayGuiScreen(parent); }
    protected void keyTyped(char c, int key) { if (key == 1) back(); }
    public void drawScreen(int mx, int my, float ticks) {
        drawDefaultBackground();
        drawCenteredString(fontRendererObj, I18n.format(topic < 0 ? "xf.home.help" : "xf.help." + TOPICS[topic]), width / 2, 12, 0xffffff);
        if (topic >= 0) {
            List<String> text = lines(); int x = (width - Math.min(440, width - 28)) / 2;
            for (int i = page * capacity(), y = 38; i < Math.min(text.size(), (page + 1) * capacity()); i++, y += 12)
                drawString(fontRendererObj, text.get(i), x, y, 0xdddddd);
            drawCenteredString(fontRendererObj, (page + 1) + "/" + Math.max(1, (text.size() + capacity() - 1) / capacity()), width / 2, height - 44, 0xaaaaaa);
        }
        super.drawScreen(mx, my, ticks);
    }
}
