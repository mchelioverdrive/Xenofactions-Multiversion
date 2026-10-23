package com.hfr.client;

import com.hfr.client.earth.GuiXFEarthMap;
import com.hfr.inventory.gui.GUIDailyReward;
import com.hfr.inventory.gui.GUIXenofactionsTutorial;
import com.hfr.packet.PacketDispatcher;
import com.hfr.packet.XFOnboardingPacket;
import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiDownloadTerrain;
import net.minecraft.client.network.NetHandlerPlayClient;
import net.minecraft.nbt.NBTTagCompound;

/** Client-thread automatic openings: only use an idle screen, with onboarding first. */
@SideOnly(Side.CLIENT)
public final class XFLoginFlow {
    private static NetHandlerPlayClient connection;
    private static boolean requested, resolved, mapPending;
    private static NBTTagCompound daily;
    private static net.minecraft.world.World dailyWorld;
    private XFLoginFlow() { }

    public static void tick() {
        Minecraft mc = Minecraft.getMinecraft();
        NetHandlerPlayClient current = mc.getNetHandler();
        if (current != connection || current == null) {
            connection = current; requested = resolved = mapPending = false; daily = null; dailyWorld = null;
            XFFactionSetup.reset();
        }
        if (current == null || mc.thePlayer == null || mc.theWorld == null
                || mc.currentScreen instanceof GuiDownloadTerrain) return;
        if (!requested) {
            requested = true;
            // Import the old opt-out once; never delete or reset it for existing installations.
            PacketDispatcher.wrapper.sendToServer(new XFOnboardingPacket.Intent(
                    GUIXenofactionsTutorial.getFlagFile().exists() ? XFOnboardingPacket.LEGACY_DONE : XFOnboardingPacket.READY));
            PacketDispatcher.wrapper.sendToServer(com.hfr.packet.daily.DailyActionPacket.loginReady());
        }
        if (!resolved || mc.thePlayer.isDead) return;
        XFFactionSetup.tick();
        if (XFFactionSetup.blocksAutomaticScreens() || mc.currentScreen != null) return;
        if (mapPending) {
            mapPending = false;
            mc.displayGuiScreen(new GuiXFEarthMap(null, true));
        } else if (daily != null) {
            NBTTagCompound next = daily; daily = null;
            // Dimension changes/respawns retire server wheel sessions. Fetch a fresh one.
            if (dailyWorld != mc.theWorld) mc.thePlayer.sendChatMessage("/xdaily");
            else mc.displayGuiScreen(new GUIDailyReward(next));
        }
    }
    public static void state(boolean show) {
        if (!requested) return;
        resolved = true;
        if (show) mapPending = true;
    }
    public static void complete() {
        mapPending = false;
        PacketDispatcher.wrapper.sendToServer(new XFOnboardingPacket.Intent(XFOnboardingPacket.COMPLETE));
    }
    public static void replay() {
        PacketDispatcher.wrapper.sendToServer(new XFOnboardingPacket.Intent(XFOnboardingPacket.REPLAY));
    }
    public static void daily(NBTTagCompound next) {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.currentScreen instanceof GUIDailyReward
                && ((GUIDailyReward)mc.currentScreen).token().equals(next.getString("token"))) {
            ((GUIDailyReward)mc.currentScreen).receive(next);
            return;
        }
        if (daily != null && daily.getString("token").equals(next.getString("token"))) {
            if (next.getLong("revision") > daily.getLong("revision")) daily = next;
        } else if (next.getBoolean("opening")) { daily = next; dailyWorld = mc.theWorld; }
    }
}
