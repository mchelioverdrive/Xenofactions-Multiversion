package com.hfr.client;

import java.util.List;
import com.hfr.client.render.RenderState;
import com.hfr.inventory.gui.GUIAdminEditor;
import com.hfr.inventory.gui.GUIXenofactionsHome;
import com.hfr.inventory.gui.GUIXenofactionsMenu;
import com.hfr.inventory.gui.GUIXenofactionsTutorial;
import com.hfr.inventory.gui.GUIXFHelp;
import com.hfr.packet.PacketDispatcher;
import com.hfr.packet.XFOnboardingPacket;
import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiChat;
import net.minecraft.client.resources.I18n;
import org.lwjgl.opengl.GL11;

/** Presentation of confirmed server progress; closing a screen never changes the step. */
@SideOnly(Side.CLIENT)
public final class XFFactionSetup {
    private static int step, completionTicks;
    private static boolean hidden, pending, refresh;
    private static String waiting = "";
    private XFFactionSetup() { }
    public static void reset() { step = completionTicks = 0; hidden = pending = refresh = false; waiting = ""; }
    public static boolean active() { return step > 0 && step < 4; }
    public static boolean hidden() { return hidden; }
    public static boolean visible() { return !hidden && (active() || completionTicks > 0); }
    public static boolean blocksAutomaticScreens() { return active() && !hidden || pending || completionTicks > 0; }
    public static boolean consumeMenuRefresh() { boolean result = refresh; refresh = false; return result; }
    public static void toggle() { intent(hidden ? XFOnboardingPacket.SHOW_SETUP : XFOnboardingPacket.HIDE_SETUP); }
    public static void resume() { intent(XFOnboardingPacket.RESUME_SETUP); }
    private static void intent(int action) { PacketDispatcher.wrapper.sendToServer(new XFOnboardingPacket.Intent(action)); }
    public static boolean waitForResult(String action) {
        if ("c_create".equals(action) || active() && ("c_claim".equals(action) || "c_sethome".equals(action))) {
            waiting = action; return true;
        }
        return false;
    }
    public static void receive(int next, boolean hide, boolean open, String feedback) {
        Minecraft mc = Minecraft.getMinecraft();
        int previous = step;
        boolean wasWaiting = !waiting.isEmpty();
        step = next; hidden = hide; refresh = true;
        if (next != 4) completionTicks = 0;
        if (next == 4 && previous > 0 && previous < 4 && !hidden) completionTicks = 100;
        if (next != 1 && next != 3 || hidden) pending = false;
        else if ((open || next != previous) && (next == 1 || next == 3)) pending = true;
        if (wasWaiting && (!feedback.isEmpty() || next != previous) && mc.currentScreen instanceof GUIXenofactionsMenu) {
            GUIXenofactionsMenu menu = (GUIXenofactionsMenu)mc.currentScreen;
            if (menu.isAction(waiting)) {
                menu.setupResult(!feedback.isEmpty() ? feedback.startsWith("xf.setup.") ? I18n.format(feedback) : feedback
                        : next == previous ? I18n.format("xf.setup.retry") : "");
                if (next == 2 && "c_claim".equals(waiting) || next == 4 && "c_sethome".equals(waiting)) mc.displayGuiScreen(null);
            }
        }
        if (!feedback.isEmpty() || next != previous) waiting = "";
        if (open && next == 2 && !hidden && (mc.currentScreen instanceof GUIXenofactionsTutorial
                || mc.currentScreen instanceof GUIXFHelp || mc.currentScreen instanceof GUIXenofactionsHome)) mc.displayGuiScreen(null);
    }
    /** Priority opening only once per confirmed transition/resume, never once per idle tick. */
    public static void tick() {
        if (completionTicks > 0) completionTicks--;
        if (!pending || hidden) return;
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.thePlayer == null || mc.thePlayer.isDead) return;
        if (mc.currentScreen != null && !(mc.currentScreen instanceof GuiChat)
                && !(mc.currentScreen instanceof GUIXenofactionsHome) && !(mc.currentScreen instanceof GUIXenofactionsTutorial)
                && !(mc.currentScreen instanceof GUIXFHelp)
                && !(mc.currentScreen instanceof GUIXenofactionsMenu && ((GUIXenofactionsMenu)mc.currentScreen).isSetupForm())) return;
        pending = false;
        // Retire only the previous setup form, so requestAndOpen can use its normal dispatch path.
        if (mc.currentScreen instanceof GUIXenofactionsMenu) mc.displayGuiScreen(null);
        GUIXenofactionsMenu.requestAndOpen(new GUIXenofactionsTutorial(), step == 1 ? "c_claim" : "c_sethome");
    }
    public static void draw(int screenWidth, int screenHeight, boolean inMenu, int gutter) {
        Minecraft mc = Minecraft.getMinecraft();
        if (!visible() || mc.thePlayer == null || mc.gameSettings.showDebugInfo) return;
        int x = 8, y = 8;
        int w = Math.min(186, screenWidth - 16);
        if (inMenu) w = gutter >= 150 ? Math.min(w, gutter - 16) : Math.min(420, screenWidth - 24);
        else if (GUIAdminEditor.hasActiveSelectionPreview()) y = 80;
        String text = I18n.format("xf.setup.step" + step);
        List<String> lines = mc.fontRenderer.listFormattedStringToWidth(text, w - 12);
        String hint = inMenu ? "xf.setup.menu_hint" : "xf.setup.hud_hint";
        List<String> hints = mc.fontRenderer.listFormattedStringToWidth(I18n.format(hint), w - 12);
        int h = 20 + (lines.size() + hints.size()) * mc.fontRenderer.FONT_HEIGHT + 4;
        try (RenderState state = new RenderState()) {
            GL11.glDisable(GL11.GL_DEPTH_TEST); GL11.glDisable(GL11.GL_LIGHTING);
            GL11.glColor4f(1, 1, 1, 1);
            Gui.drawRect(x, y, x + w, y + h, 0xdd172532);
            mc.fontRenderer.drawString(I18n.format("xf.setup.title"), x + 6, y + 6, 0xffdd88);
            int lineY = y + 20;
            for (String line : lines) { mc.fontRenderer.drawString(line, x + 6, lineY, 0xffffff); lineY += mc.fontRenderer.FONT_HEIGHT; }
            for (String line : hints) { mc.fontRenderer.drawString(line, x + 6, lineY, 0xaaaaaa); lineY += mc.fontRenderer.FONT_HEIGHT; }
        }
    }
}
