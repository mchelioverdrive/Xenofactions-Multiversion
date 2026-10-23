 package com.hfr.main;

import com.hfr.render.RenderWallImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.particle.EntityBreakingFX;
import net.minecraft.client.particle.EntityCloudFX;
import net.minecraft.client.particle.EntityFX;
import net.minecraft.client.particle.EntityFlameFX;
import net.minecraft.client.particle.EntityLavaFX;
import net.minecraft.client.particle.EntityReddustFX;
import net.minecraft.client.particle.EntitySmokeFX;
import net.minecraft.client.renderer.entity.RenderSnowball;
import net.minecraft.client.settings.KeyBinding;
import org.lwjgl.input.Keyboard;
import net.minecraft.entity.Entity;
import net.minecraft.entity.passive.EntityChicken;
import net.minecraft.entity.passive.EntityCow;
import net.minecraft.entity.passive.EntityPig;
import net.minecraft.entity.passive.EntitySheep;
import net.minecraft.init.Items;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.network.INetHandler;
import net.minecraft.network.PacketBuffer;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.Vec3;
import net.minecraft.world.World;
import net.minecraftforge.client.MinecraftForgeClient;
import net.minecraftforge.client.model.AdvancedModelLoader;

import java.awt.Desktop;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.List;

import com.hfr.handler.ExplosionSound;
import com.hfr.items.ModItems;
import com.hfr.render.block.*;
import com.hfr.render.hud.*;
import com.hfr.render.living.*;
import com.hfr.render.mob.*;
import com.hfr.render.mob.ModelSheep.ModelSheepWooly;
import com.hfr.render.tileentity.*;
import com.hfr.tileentity.*;
import com.hfr.tileentity.clowder.*;
import com.hfr.tileentity.machine.*;
import com.hfr.tileentity.prop.*;

import cpw.mods.fml.client.registry.ClientRegistry;
import cpw.mods.fml.client.registry.RenderingRegistry;
import cpw.mods.fml.relauncher.ReflectionHelper;

public class ClientProxy extends ServerProxy
{
    @Override public void receiveClowderBorder(final INetHandler handler, final long subscription, final int dimension,
            final int x1, final int z1, final int x2, final int z2, final int color) {
        com.hfr.client.render.ClientRenderSession.enqueue(handler, dimension, () -> {
            if (com.hfr.client.journeymap.ClientClaimOverlayCache.isCurrentSubscription(subscription, dimension))
                com.hfr.client.journeymap.ClientBorderParticles.enqueue(Minecraft.getMinecraft().theWorld,
                        x1, z1, x2, z2, color);
        });
    }

    @Override public void receiveFactionFlagBytes(final INetHandler handler, final String factionName,
            final String hash, final byte[] png) {
        final World receiving = Minecraft.getMinecraft().theWorld;
        if (receiving != null) com.hfr.client.render.ClientRenderSession.enqueue(handler, receiving.provider.dimensionId,
                () -> com.hfr.client.flag.FactionFlagTextureManager.handleBytes(factionName, hash, png));
    }

    @Override public void receiveFactionFlagMetadata(final INetHandler handler, final String factionName, final String hash) {
        final World receiving = Minecraft.getMinecraft().theWorld;
        if (receiving != null) com.hfr.client.render.ClientRenderSession.enqueue(handler, receiving.provider.dimensionId,
                () -> com.hfr.client.flag.FactionFlagTextureManager.handleMetadata(factionName, hash));
    }

    @Override public void receiveParticleEffect(final INetHandler handler, final PacketBuffer buffer) {
        if (Minecraft.getMinecraft().theWorld == null) return;
        try {
            final NBTTagCompound nbt = buffer.readNBTTagCompoundFromBuffer();
            if (nbt != null) {
                final World receiving = Minecraft.getMinecraft().theWorld;
                if (receiving != null) com.hfr.client.render.ClientRenderSession.enqueue(handler, receiving.provider.dimensionId,
                        () -> effectNT(nbt));
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    @Override public void recordMillStructureCheck() { if(com.hfr.client.render.RenderMetrics.ENABLED)com.hfr.client.render.RenderMetrics.structures++; }
    @Override public net.minecraft.util.AxisAlignedBB modelBounds(net.minecraft.tileentity.TileEntity tile) { return com.hfr.client.render.ModelBounds.forTile(tile); }
    @Override public void receiveDecoration(final TileEntityFactionDecoration tile, final NBTTagCompound data) {
        final Minecraft mc = Minecraft.getMinecraft();
        mc.func_152344_a(() -> {
            if(mc.theWorld != tile.getWorldObj() || tile.isInvalid()
                || mc.theWorld.getTileEntity(tile.xCoord, tile.yCoord, tile.zCoord) != tile) return;
            tile.readFromNBT(data);
            if(!tile.getCustomFlagHash().isEmpty())
                com.hfr.client.flag.FactionFlagTextureManager.handleMetadata(tile.getFactionName(), tile.getCustomFlagHash());
            updateDecorationTexture(tile);
        });
    }

    @Override public void updateDecorationTexture(TileEntityFactionDecoration tile) {
        if(!tile.getCustomFlagHash().isEmpty()) {
            com.hfr.client.flag.FactionFlagTextureManager.requestFlagIfMissing(tile.getFactionName());
        }
    }
    @Override public void receiveDailyReward(final NBTTagCompound data) {
        final Minecraft mc = Minecraft.getMinecraft();
        final World receiving = mc.theWorld;
        mc.func_152344_a(new Runnable() { public void run() {
            if (data == null || mc.thePlayer == null || receiving == null || mc.theWorld != receiving) return;
            if (data.getBoolean("achievementProgress")) { XFAchievements.receiveClient(mc.thePlayer, data); return; }
            com.hfr.client.XFLoginFlow.daily(data);
        } });
    }

    @Override public void receiveOnboarding(final INetHandler handler, final boolean show) {
        final Minecraft mc = Minecraft.getMinecraft();
        mc.func_152344_a(() -> {
            if (mc.getNetHandler() == handler && mc.thePlayer != null && mc.theWorld != null)
                com.hfr.client.XFLoginFlow.state(show);
        });
    }
	@Override public com.hfr.world.earth.XFEarthProfile getEarthHeightProfile(World world) {
		return com.hfr.client.earth.XFEarthHeightClient.getActiveProfile(world);
	}
	@Override public void receiveEarthHeight(com.hfr.world.earth.network.XFEarthHeightPacket packet, net.minecraft.network.INetHandler handler) {
		com.hfr.client.earth.XFEarthHeightClient.receive(packet,handler);
	}
	@Override public void openEarthMap() { Minecraft.getMinecraft().displayGuiScreen(new com.hfr.client.earth.GuiXFEarthMap()); }
    @Override public void receiveEarthLayers(final com.hfr.packet.effect.EarthRoadSnapshotPacket packet){
        final Minecraft mc=Minecraft.getMinecraft();final World receiving=mc.theWorld;
        mc.func_152344_a(new Runnable(){public void run(){if(receiving==null||mc.theWorld!=receiving)return;
            if(packet.dimension==receiving.provider.dimensionId&&com.hfr.packet.effect.EarthRoadSnapshotPacket.SELECTION_INVALIDATED.equals(packet.view)){
                com.hfr.client.earth.EarthMapLayerCache.clear();
                if(mc.currentScreen instanceof com.hfr.client.earth.GuiXFEarthMap)((com.hfr.client.earth.GuiXFEarthMap)mc.currentScreen).invalidateLayers();return;
            }
            com.hfr.client.earth.EarthMapLayerCache.Frame frame=com.hfr.client.earth.EarthMapLayerCache.receive(packet);
            if(frame!=null&&mc.currentScreen instanceof com.hfr.client.earth.GuiXFEarthMap)((com.hfr.client.earth.GuiXFEarthMap)mc.currentScreen).receiveLayers(frame);
        }});
    }
	@Override public void receiveEarthMap(final int request, final int dimension, final String profile, final String identity, final String error) {
		final Minecraft mc = Minecraft.getMinecraft();
		final World receivingWorld = mc.theWorld;
		mc.func_152344_a(new Runnable() { @Override public void run() {
			if (receivingWorld != null && mc.theWorld == receivingWorld)
				com.hfr.client.journeymap.XFEarthJourneyMapImport.receive(request, dimension, profile, identity, error);
			if (receivingWorld != null && mc.theWorld == receivingWorld && mc.currentScreen instanceof com.hfr.client.earth.GuiXFEarthMap)
				((com.hfr.client.earth.GuiXFEarthMap)mc.currentScreen).receive(request, dimension, profile, identity, error);
		} });
	}
	@Override public void openWallArtGui(TileEntityWallImage tile) { Minecraft.getMinecraft().displayGuiScreen(new com.hfr.inventory.gui.GuiWallArt(tile)); }
	@Override public void acceptWallArtChunk(String hash, long token, int dimension, int index, int total, int fullLength, byte[] bytes) { com.hfr.client.wallart.WallArtTextureCache.accept(hash,token,dimension,index,total,fullLength,bytes); }
	@Override
	public void receiveClaimOverlay(final long subscription, final int dimension, final int generation, final int part, final int parts, final List<com.hfr.clowder.ClaimOverlayData.Claim> claims) {
		final Minecraft mc = Minecraft.getMinecraft();
		final World receiving = mc.theWorld;
		mc.func_152344_a(new Runnable() { @Override public void run() {
			if(receiving == null || mc.theWorld != receiving || receiving.provider.dimensionId != dimension) return;
			com.hfr.client.journeymap.ClientClaimOverlayCache.accept(subscription, dimension, generation, part, parts, claims);
		} });
	}

	public static KeyBinding filter = new KeyBinding("Toggle chat filter", 62, "xRadar");

	public static KeyBinding flushLog = new KeyBinding("Flush extended debugging log", 0, "xRadar");
	public static KeyBinding tdmMenu = new KeyBinding("key.hfr.tdmmenu", Keyboard.KEY_L, "key.categories.hfr.xenofactions");
	public static KeyBinding tdmBuyMenu = new KeyBinding("key.hfr.tdmbuy", Keyboard.KEY_B, "key.categories.hfr.xenofactions");
	public static KeyBinding xenofactionsMenu = new KeyBinding("key.hfr.xfmenu", Keyboard.KEY_K, "key.categories.hfr.xenofactions");
	public static KeyBinding adminEditor = new KeyBinding("key.hfr.admineditor", Keyboard.KEY_F10, "key.categories.hfr.xenofactions");
	public static KeyBinding borderVisuals = new KeyBinding("key.hfr.bordervisuals", Keyboard.KEY_RMENU, "key.categories.hfr.xenofactions");
	
	@Override
	public void registerRenderInfo()
	{
		com.hfr.client.render.GpuModelManager.register();
        ((net.minecraft.client.resources.IReloadableResourceManager)Minecraft.getMinecraft().getResourceManager()).registerReloadListener(manager -> {
            ResourceManager.reloadBlockModels();
            com.hfr.client.flag.FactionFlagTextureManager.reload();
            com.hfr.tmt.ModelRendererTurbo.reloadGeometry();
            com.hfr.client.wallart.WallArtTextureCache.clear();
        });
        com.hfr.client.earth.XFEarthHeightClient.register();
		net.minecraftforge.common.MinecraftForge.EVENT_BUS.register(new com.hfr.client.earth.XFEarthCreateWorldHandler());
		new EventHandlerClient().register();
		com.hfr.client.earth.EarthMapTileCache.registerReloadListener();
		com.hfr.client.journeymap.XFJourneyMapIntegration.register();
		com.hfr.client.journeymap.XFEarthJourneyMapImport.register();
		
		RenderingRegistry.addNewArmourRendererPrefix("5");

		RenderingRegistry.registerBlockHandler(new RenderPalisade());
		RenderingRegistry.registerBlockHandler(new RenderWall());
		RenderingRegistry.registerBlockHandler(new RenderBerliner());

		RenderingRegistry.registerBlockHandler(new RenderSandbags());

		RenderingRegistry.registerBlockHandler(new RenderWallArtBlock());

		ClientRegistry.bindTileEntitySpecialRenderer(TileEntityWallImage.class, new RenderWallImage());
        ClientRegistry.bindTileEntitySpecialRenderer(TileEntityFactionLeaderboard.class, new com.hfr.render.RenderFactionLeaderboard());

		ClientRegistry.registerKeyBinding(filter);

		ClientRegistry.registerKeyBinding(flushLog);
		ClientRegistry.registerKeyBinding(tdmMenu);
		ClientRegistry.registerKeyBinding(tdmBuyMenu);
		ClientRegistry.registerKeyBinding(xenofactionsMenu);
		ClientRegistry.registerKeyBinding(adminEditor);
		ClientRegistry.registerKeyBinding(borderVisuals);
		
		ClientRegistry.bindTileEntitySpecialRenderer(TileEntityDebug.class, new RenderDebug());
		ClientRegistry.bindTileEntitySpecialRenderer(TileEntityMachineNet.class, new RenderNet());
		ClientRegistry.bindTileEntitySpecialRenderer(TileEntityMachineUni.class, new RenderUni());
		ClientRegistry.bindTileEntitySpecialRenderer(TileEntityFlag.class, new RenderFlag());
		ClientRegistry.bindTileEntitySpecialRenderer(TileEntityCap.class, new RenderCap());
		ClientRegistry.bindTileEntitySpecialRenderer(TileEntityFlagBig.class, new RenderFlagBig());
		ClientRegistry.bindTileEntitySpecialRenderer(TileEntityFactionDecoration.class, new RenderFactionDecoration());
		ClientRegistry.bindTileEntitySpecialRenderer(TileEntityProp.class, new RenderProp());
		ClientRegistry.bindTileEntitySpecialRenderer(TileEntityStatue.class, new RenderProp());
		ClientRegistry.bindTileEntitySpecialRenderer(TileEntityMachineGrainmill.class, new RenderMill());
		ClientRegistry.bindTileEntitySpecialRenderer(TileEntityMachineFactory.class, new RenderFactory());
		ClientRegistry.bindTileEntitySpecialRenderer(TileEntityMachineTemple.class, new RenderTemple());
		ClientRegistry.bindTileEntitySpecialRenderer(TileEntityConquerer.class, new RenderConquerer());
		ClientRegistry.bindTileEntitySpecialRenderer(TileEntityOfficerChest.class, new RenderChest());

		RenderingRegistry.registerEntityRenderingHandler(EntityCow.class, new RenderCow(new ModelBuffalo(), 1.0F));
		RenderingRegistry.registerEntityRenderingHandler(EntityPig.class, new RenderPig(new ModelPig(), 1.0F));
		RenderingRegistry.registerEntityRenderingHandler(EntitySheep.class, new RenderSheep(new ModelSheep(), new ModelSheepWooly(), 1.0F));
		RenderingRegistry.registerEntityRenderingHandler(EntityChicken.class, new RenderChicken(new ModelChicken(), 0.5F));
		//todo villager -> human

	}
	
	@Override
	public void registerTileEntitySpecialRenderer() { }
	
	//please fucking end my life i'm begging you
	//everything is pain
	//make it stop
	
	//sfx stands for special effects, not sound effects
	@Override
	public void spawnSFX(World world, double posX, double posY, double posZ, int type, Object payload) {
		
		switch(type) {
			
		/// CLOWDER BORDER ///
		case SFX_BORDER:
			if(payload instanceof int[] && ((int[])payload).length == 5) {
				int[] edge = (int[])payload;
				com.hfr.client.journeymap.ClientBorderParticles.enqueue(world, edge[0], edge[1], edge[2], edge[3], edge[4]);
			}
			break;

			/// CUMMIES ///
			case 3:
				
				EntityBreakingFX cum = new EntityBreakingFX(world, posX, posY, posZ, Items.snowball);
				cum.motionX = ((double[])payload)[0];
				cum.motionY = ((double[])payload)[1];
				cum.motionZ = ((double[])payload)[2];
				Minecraft.getMinecraft().effectRenderer.addEffect(cum);
				break;
			
		default: break;
		
		}
	}
	
	@Override
	public void updateFlag(ResourceLocation flag, ResourceLocation overlay, int color, String name, String title) {
		RenderFlagOverlay.flag = flag;
		RenderFlagOverlay.overlay = overlay;
		RenderFlagOverlay.color = color;
		RenderFlagOverlay.tintFlag = true;
		RenderFlagOverlay.customFlagName = "";
		RenderFlagOverlay.title = name;
		RenderFlagOverlay.zone = title;
		RenderFlagOverlay.startingTime = System.currentTimeMillis();
	}
	




	public void effectNT(NBTTagCompound nbt) {
		
		String type = nbt.getString("type");
		World world = Minecraft.getMinecraft().theWorld;
		
		if(type.equals("clowderNotif")) {
			
			EventHandlerClient.updateFactionName(nbt.getString("player"), nbt.getString("clowder"));
			return;
		}
		
		 if(type.equals("explosion")) {
			
			try {
				float strength = nbt.getFloat("strength");
				double x = nbt.getDouble("posX");
				double y = nbt.getDouble("posY") + 0.5;
				double z = nbt.getDouble("posZ");
				
				for(int i = 0; i < strength * 10; i++) {
					
					EntityFlameFX flames = new EntityFlameFX(world, x, y, z,
							world.rand.nextGaussian() * 0.1 * strength,
							world.rand.nextGaussian() * 0.1 * strength,
							world.rand.nextGaussian() * 0.1 * strength);
					
					ReflectionHelper.setPrivateValue(EntityFX.class, flames, 100, "particleMaxAge", "field_70547_e");
					
					Minecraft.getMinecraft().effectRenderer.addEffect(flames);
				}
				
				for(int i = 0; i < strength * 25; i++) {
					
					EntityLavaFX flames = new EntityLavaFX(world, x, y, z);
	
					ReflectionHelper.setPrivateValue(EntityFX.class, flames, 200, "particleMaxAge", "field_70547_e");
					ReflectionHelper.setPrivateValue(Entity.class, flames, world.rand.nextGaussian() * 0.1 * strength, "motionX", "field_70159_w");
					ReflectionHelper.setPrivateValue(Entity.class, flames, world.rand.nextDouble() * 0.25 * strength, "motionY", "field_70181_x");
					ReflectionHelper.setPrivateValue(Entity.class, flames, world.rand.nextGaussian() * 0.1 * strength, "motionZ", "field_70179_y");
					
					Minecraft.getMinecraft().effectRenderer.addEffect(flames);
				}
				
				for(int i = 0; i < strength * 50; i++) {
					
					EntityCloudFX flames = new EntityCloudFX(world, x, y, z,
							world.rand.nextGaussian() * 0.025 * strength,
							world.rand.nextGaussian() * 0.025 * strength,
							world.rand.nextGaussian() * 0.025 * strength);
	
					ReflectionHelper.setPrivateValue(EntityFX.class, flames, 100, "particleMaxAge", "field_70547_e");
					ReflectionHelper.setPrivateValue(EntityFX.class, flames, 10, "particleScale", "field_70544_f");
					ReflectionHelper.setPrivateValue(EntityCloudFX.class, flames, 10, "field_70569_a");
					
					Minecraft.getMinecraft().effectRenderer.addEffect(flames);
				}
			} catch(Exception ex) {}
		}
	}

	public void spawnEFX(double posX, double posY, double posZ, float pow) {
		ExplosionSound.handleClient(Minecraft.getMinecraft().thePlayer, (int)posX, (int)posY, (int)posZ, pow);
}
}
