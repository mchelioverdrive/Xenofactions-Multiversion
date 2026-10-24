package com.hfr.worker;

import java.util.UUID;
import com.hfr.clowder.Clowder;
import com.hfr.clowder.ClowderTerritory;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.world.World;

/** Workers never acquire authority from an item, a GUI, or a client-supplied faction. */
public final class WorkerAccess {
    private WorkerAccess() { }
    public static boolean ownsLand(World world, int x, int z, UUID factionId) {
        if (factionId == null) return false;
        Clowder faction = Clowder.getClowderFromUUID(factionId.toString());
        ClowderTerritory.Ownership owner = ClowderTerritory.getOwnerFromInts(world, x, z);
        return faction != null && !faction.isInfrastructureDisabled() && owner != null
                && owner.zone == ClowderTerritory.Zone.FACTION && owner.owner == faction;
    }
    public static boolean member(EntityPlayer player, UUID factionId) {
        Clowder faction = Clowder.getClowderFromPlayer(player);
        return faction != null && factionId != null && faction.uuid.equals(factionId.toString())
                && faction.getRole(player) != null && !faction.isInfrastructureDisabled();
    }
}
