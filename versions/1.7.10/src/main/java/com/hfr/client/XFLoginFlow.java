package com.hfr.client;

import com.hfr.client.earth.GuiXFEarthMap;
import com.hfr.inventory.gui.GUIDailyReward;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.attribute.BasicFileAttributes;
import com.hfr.util.XFLog;
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
            mapPending = !welcomeDismissed();
            // Synchronize server-owned faction setup regardless of the local welcome preference.
            PacketDispatcher.wrapper.sendToServer(new XFOnboardingPacket.Intent(XFOnboardingPacket.READY));
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
    public static void state() {
        if (!requested) return;
        resolved = true;
    }
    private static File flagFile() { return new File(Minecraft.getMinecraft().mcDataDir, "tutorialflag"); }
    private static boolean welcomeDismissed() {
        try {
            Files.readAttributes(flagFile().toPath(), BasicFileAttributes.class);
            return true;
        } catch (NoSuchFileException absent) { return false; }
        catch (IOException | SecurityException e) {
            XFLog.error("Could not read tutorialflag; queuing the welcome for this connection", e);
            return false;
        }
    }
    public static void complete() {
        mapPending = false;
        try {
            File flag = flagFile();
            if (!flag.createNewFile() && !flag.isFile()) throw new IOException("tutorialflag is not a file");
        } catch (IOException | SecurityException e) { XFLog.error("Could not create tutorialflag; the welcome may appear next connection", e); }
    }
    public static void replay() {
        try { Files.deleteIfExists(flagFile().toPath()); }
        catch (IOException | SecurityException e) { XFLog.error("Could not delete tutorialflag for welcome replay", e); }
        mapPending = true;
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
