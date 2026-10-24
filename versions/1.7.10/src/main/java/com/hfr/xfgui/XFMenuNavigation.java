package com.hfr.xfgui;

import java.util.EnumMap;
import java.util.Map;
import java.util.UUID;

import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;
import net.minecraft.client.Minecraft;

/** Client-session navigation only: never retain screens, players or argument values. */
@SideOnly(Side.CLIENT)
public final class XFMenuNavigation {
    public static final class Location {
        public final String commandId;
        public final String search;
        public final int page;
        public final int argumentPage;

        public Location(String commandId, String search, int page, int argumentPage) {
            this.commandId = commandId;
            this.search = search;
            this.page = page;
            this.argumentPage = argumentPage;
        }
    }

    private static final Map<XFCommandDefinition.Category, Location> locations =
            new EnumMap<XFCommandDefinition.Category, Location>(XFCommandDefinition.Category.class);
    private static XFCommandDefinition.Category category = XFCommandDefinition.Category.FACTION;
    private static UUID playerId;
    private static int playerIdentity;
    private static int connectionIdentity;
    private static long session;
    private static volatile boolean resetRequested;

    private XFMenuNavigation() {}

    /** Connection events may run off the client thread; actual clearing happens on it. */
    public static void requestReset() { resetRequested = true; }

    public static boolean updateSession(Minecraft mc) {
        UUID currentPlayer = mc.thePlayer == null ? null : mc.thePlayer.getUniqueID();
        int currentIdentity = System.identityHashCode(mc.thePlayer);
        int currentConnection = System.identityHashCode(mc.getNetHandler());
        if (mc.theWorld == null) { currentPlayer = null; currentIdentity = 0; currentConnection = 0; }
        if (!resetRequested && java.util.Objects.equals(playerId, currentPlayer)
                && playerIdentity == currentIdentity && connectionIdentity == currentConnection) return false;
        resetRequested = false;
        playerId = currentPlayer;
        playerIdentity = currentIdentity;
        connectionIdentity = currentConnection;
        locations.clear();
        category = XFCommandDefinition.Category.FACTION;
        session++;
        return true;
    }

    public static long session() { return session; }
    public static XFCommandDefinition.Category category() { return category; }
    public static Location location(XFCommandDefinition.Category target) {
        Location location = locations.get(target);
        return location == null ? new Location(null, "", 0, 0) : location;
    }
    public static void remember(XFCommandDefinition.Category target, Location location) {
        category = target;
        locations.put(target, location);
    }
}
