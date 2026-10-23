package com.hfr.main;

import java.util.List;

import com.hfr.clowder.ClaimOverlayData.Claim;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.network.INetHandler;
import net.minecraft.network.PacketBuffer;
import net.minecraft.util.ResourceLocation;
import net.minecraft.world.World;
import com.hfr.tileentity.TileEntityWallImage;

public class ServerProxy
{
    public void recordMillStructureCheck() { }
    public net.minecraft.util.AxisAlignedBB modelBounds(net.minecraft.tileentity.TileEntity tile) { return net.minecraft.util.AxisAlignedBB.getBoundingBox(tile.xCoord,tile.yCoord,tile.zCoord,tile.xCoord+1,tile.yCoord+1,tile.zCoord+1); }
	public void registerRenderInfo() { }
	
	public void registerTileEntitySpecialRenderer() { }
	public void receiveDecoration(com.hfr.tileentity.clowder.TileEntityFactionDecoration tile, NBTTagCompound data) { }
	public void updateDecorationTexture(com.hfr.tileentity.clowder.TileEntityFactionDecoration tile) { }
	
	public static final int SFX_BORDER = 1;
	public void spawnSFX(World world, double posX, double posY, double posZ, int type, Object payload) { }
	
	public void updateFlag(ResourceLocation flag, ResourceLocation overlay, int color, String name, String title) { }



	public void effectNT(NBTTagCompound nbt) { }
	// Clientbound handlers are also constructed during dedicated-server registration.
	public void receiveClowderBorder(INetHandler handler, long subscription, int dimension, int x1, int z1, int x2, int z2, int color) { }
	public void receiveFactionFlagBytes(INetHandler handler, String factionName, String hash, byte[] png) { }
	public void receiveFactionFlagMetadata(INetHandler handler, String factionName, String hash) { }
	public void receiveParticleEffect(INetHandler handler, PacketBuffer buffer) { }
    public void receiveDailyReward(INetHandler handler, NBTTagCompound data) { }
    public void receiveOnboarding(INetHandler handler, boolean show, int step, boolean hidden, boolean open, String feedback) { }
	public void receiveClaimOverlay(long subscription, int dimension, int generation, int part, int parts, List<Claim> claims) { }
	public void openWallArtGui(TileEntityWallImage tile) { }
	public void openEarthMap() { }
    public void receiveEarthLayers(com.hfr.packet.effect.EarthRoadSnapshotPacket packet) { }
	public void receiveEarthMap(int request, int dimension, String profile, String identity, String error) { }
	public void receiveEarthHeight(com.hfr.world.earth.network.XFEarthHeightPacket packet, net.minecraft.network.INetHandler handler) { }
	public com.hfr.world.earth.XFEarthProfile getEarthHeightProfile(World world) { return null; }
	public void acceptWallArtChunk(String hash, long token, int dimension, int index, int total, int fullLength, byte[] bytes) { }

	public void spawnEFX(double posX, double posY, double posZ, float pow) { }
}
