package com.hfr.main;

import com.hfr.render.util.RenderAccessoryUtility;

import java.util.HashMap;
import java.util.List;
import com.hfr.api.PublicChat;
import com.hfr.util.ChatTextFilter;

import org.lwjgl.input.Keyboard;
import org.lwjgl.opengl.GL11;

import com.hfr.command.CommandClowderChat;
import com.hfr.packet.PacketDispatcher;
import com.hfr.packet.client.ReseatRequestPacket;
import com.hfr.packet.effect.PlayerDataPacket;
import com.hfr.packet.client.TDMMenuActionPacket;
import com.hfr.inventory.gui.GUIXenofactionsMenu;
import com.hfr.inventory.gui.GUITDMMenu;
import com.hfr.inventory.gui.GUITDMKitSelect;
import com.hfr.inventory.gui.GUITDMMapVote;
import com.hfr.inventory.gui.GUIAdminEditor;
import com.hfr.render.hud.RenderFlagOverlay;
import com.hfr.tileentity.machine.MachineDisplaySnapshot;
import com.hfr.util.LoggingEngine;
import com.mojang.authlib.minecraft.MinecraftProfileTexture.Type;

import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.network.FMLNetworkEvent.ClientDisconnectionFromServerEvent;
import cpw.mods.fml.common.gameevent.TickEvent;
import cpw.mods.fml.common.gameevent.TickEvent.ClientTickEvent;
import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.AbstractClientPlayer;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.client.gui.GuiGameOver;
import net.minecraft.client.renderer.OpenGlHelper;
import net.minecraft.client.renderer.texture.TextureMap;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.entity.RenderManager;
import net.minecraft.client.renderer.entity.RenderPlayer;
import net.minecraft.client.renderer.entity.RendererLivingEntity;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.Item;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.ChatComponentTranslation;
import net.minecraft.util.EnumChatFormatting;
import net.minecraft.util.IChatComponent;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.Vec3;
import net.minecraft.world.World;
import net.minecraftforge.client.event.ClientChatReceivedEvent;
import net.minecraftforge.client.event.RenderGameOverlayEvent;
import net.minecraftforge.client.event.RenderGameOverlayEvent.ElementType;
import net.minecraftforge.client.event.RenderLivingEvent;
import net.minecraftforge.client.event.RenderPlayerEvent;
import net.minecraftforge.client.event.RenderWorldLastEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.world.WorldEvent;

public class EventHandlerClient {
    @SubscribeEvent
    public void activityMouse(net.minecraftforge.client.event.MouseEvent event) {
        com.hfr.client.PlayerActivityInput.mouse(event.dx, event.dy);
    }
    @SubscribeEvent
    public void structureTooltip(net.minecraftforge.event.entity.player.ItemTooltipEvent event) {
        String size = com.hfr.blocks.StructureInfo.size(event.itemStack);
        if (size != null) {
            event.toolTip.add(size);
            event.toolTip.add(com.hfr.blocks.StructureInfo.axes());
        }
    }
	
	boolean lock = false;
	
	public static boolean fps = false;
	public static boolean tilt = false;
	public static boolean shader = false;
	
	private static boolean tdmHudEnabled = false;
	private static GUITDMKitSelect mandatoryKitGui;

	public static void setMandatoryKitGui(GUITDMKitSelect gui) {
		mandatoryKitGui = gui;
		enforceMandatoryKitGui();
	}

	public static void clearMandatoryKitGui(boolean close) {
		mandatoryKitGui = null;
		Minecraft minecraft = Minecraft.getMinecraft();
		if (close && minecraft != null && minecraft.currentScreen instanceof GUITDMKitSelect) {
			minecraft.displayGuiScreen(null);
		}
	}

	private static void enforceMandatoryKitGui() {
		Minecraft minecraft = Minecraft.getMinecraft();
		if (mandatoryKitGui == null
				|| minecraft == null
				|| minecraft.theWorld == null
				|| minecraft.thePlayer == null
				|| minecraft.thePlayer.isDead
				|| minecraft.thePlayer.getHealth() <= 0
				|| minecraft.currentScreen instanceof GuiGameOver) {
			return;
		}
		if (minecraft.currentScreen != mandatoryKitGui) {
			minecraft.displayGuiScreen(mandatoryKitGui);
		}
	}
	private static boolean tdmVoting = false;
	private static int tdmRoundSeconds = 0;
	private static int tdmVoteSeconds = 0;
	private static int tdmRedScore = 0;
	private static int tdmBlueScore = 0;
	private static String tdmMapName = "";
	private static String tdmMode = "DEATHMATCH";
	private static String tdmBombState = "DISABLED";
	private static String tdmTerroristTeam = "red";
	private static String tdmBombsite = "";
	private static int tdmRedBombWins;
	private static int tdmBlueBombWins;
	private static int tdmBombSeconds;
	private static int tdmBuyScore;
	private static int tdmRedPlayerCount;
	private static int tdmBluePlayerCount;
	private static boolean tdmEconomy;
	
	boolean resources = false;
	
	public static void updateTDMStatus(boolean enabled, boolean voting, int roundSeconds, int voteSeconds, int redScore, int blueScore, String mapName) {
		updateTDMStatus(enabled, voting, roundSeconds, voteSeconds, redScore, blueScore, mapName,
				"DEATHMATCH", "DISABLED", 0, 0, "red", 0, "");
	}

	public static void updateTDMStatus(boolean enabled, boolean voting, int roundSeconds, int voteSeconds,
			int redScore, int blueScore, String mapName, String mode, String bombState,
			int redBombWins, int blueBombWins, String terroristTeam, int bombSeconds, String site) {
		updateTDMStatus(enabled, voting, roundSeconds, voteSeconds, redScore, blueScore, mapName,
				mode, bombState, redBombWins, blueBombWins, terroristTeam, bombSeconds, site, false, 0);
	}

	public static void updateTDMStatus(boolean enabled, boolean voting, int roundSeconds, int voteSeconds,
			int redScore, int blueScore, String mapName, String mode, String bombState,
			int redBombWins, int blueBombWins, String terroristTeam, int bombSeconds, String site,
			boolean economy, int buyScore) {
		updateTDMStatus(enabled, voting, roundSeconds, voteSeconds, redScore, blueScore, mapName,
				mode, bombState, redBombWins, blueBombWins, terroristTeam, bombSeconds, site,
				economy, buyScore, 0, 0);
	}

	public static void updateTDMStatus(boolean enabled, boolean voting, int roundSeconds, int voteSeconds,
			int redScore, int blueScore, String mapName, String mode, String bombState,
			int redBombWins, int blueBombWins, String terroristTeam, int bombSeconds, String site,
			boolean economy, int buyScore, int redPlayerCount, int bluePlayerCount) {
		if (!enabled) {
			resetTDMClientState();
			return;
		}
		tdmHudEnabled = enabled;
		tdmVoting = voting;
		tdmRoundSeconds = roundSeconds;
		tdmVoteSeconds = voteSeconds;
		tdmRedScore = redScore;
		tdmBlueScore = blueScore;
		tdmMapName = mapName == null ? "" : mapName;
		tdmMode = mode;
		tdmBombState = bombState;
		tdmRedBombWins = redBombWins;
		tdmBlueBombWins = blueBombWins;
		tdmTerroristTeam = terroristTeam;
		tdmBombSeconds = bombSeconds;
		tdmBombsite = site;
		tdmEconomy = economy;
		tdmBuyScore = buyScore;
		tdmRedPlayerCount = redPlayerCount;
		tdmBluePlayerCount = bluePlayerCount;
		if (!"BOMB".equals(mode)
				&& mandatoryKitGui != null
				&& mandatoryKitGui.isBombBuySelection()) {
			clearMandatoryKitGui(true);
		}
	}

	public static void resetTDMClientState() {
		mandatoryKitGui = null;
		tdmHudEnabled = false;
		tdmVoting = false;
		tdmRoundSeconds = 0;
		tdmVoteSeconds = 0;
		tdmRedScore = 0;
		tdmBlueScore = 0;
		tdmMapName = "";
		tdmMode = "DEATHMATCH";
		tdmBombState = "DISABLED";
		tdmTerroristTeam = "red";
		tdmBombsite = "";
		tdmRedBombWins = 0;
		tdmBlueBombWins = 0;
		tdmBombSeconds = 0;
		tdmBuyScore = 0;
		tdmRedPlayerCount = 0;
		tdmBluePlayerCount = 0;
		tdmEconomy = false;

		Minecraft minecraft = Minecraft.getMinecraft();
		if (minecraft == null) {
			return;
		}
		if (minecraft.currentScreen instanceof GUITDMMenu
				|| minecraft.currentScreen instanceof GUITDMKitSelect
				|| minecraft.currentScreen instanceof GUITDMMapVote) {
			minecraft.displayGuiScreen(null);
		}
	}

	private static void drawTDMHud() {
		if (!tdmHudEnabled) {
			return;
		}

		Minecraft mc = Minecraft.getMinecraft();
		if (mc == null || mc.fontRenderer == null) {
			return;
		}

		FontRenderer font = mc.fontRenderer;
		boolean bombMode = "BOMB".equals(tdmMode);
		String timer;
		if (tdmVoting) {
			timer = "Map vote: " + formatSeconds(tdmVoteSeconds);
		} else if (bombMode) {
			timer = bombStateText();
		} else {
			timer = "Round: " + formatSeconds(tdmRoundSeconds);
		}
		String modeLabel = bombMode ? "Search and Destroy" : "FFA".equals(tdmMode) ? "FFA" : "TDM";
		String map = tdmMapName.length() > 0 ? " Map: " + tdmMapName + " \u2014 " + modeLabel : "";
		boolean waitingForTeams = bombMode && "WAITING_FOR_TEAMS".equals(tdmBombState);
		String redRole = "red".equals(tdmTerroristTeam) ? "T" : "CT";
		String blueRole = "blue".equals(tdmTerroristTeam) ? "T" : "CT";
		int redDisplayScore = waitingForTeams ? tdmRedPlayerCount : (bombMode ? tdmRedBombWins : tdmRedScore);
		int blueDisplayScore = waitingForTeams ? tdmBluePlayerCount : (bombMode ? tdmBlueBombWins : tdmBlueScore);
		String redRoleText = bombMode && !waitingForTeams ? " (" + redRole + ")" : "";
		String blueRoleText = bombMode && !waitingForTeams ? " (" + blueRole + ")" : "";
		String buyScoreText = bombMode && tdmEconomy ? "  Buy score: " + tdmBuyScore : "";
		String text = timer
				+ "  Red" + redRoleText + ": " + redDisplayScore
				+ "  Blue" + blueRoleText + ": " + blueDisplayScore
				+ map
				+ buyScoreText;
		ScaledResolution resolution = new ScaledResolution(mc, mc.displayWidth, mc.displayHeight);
		int x = (resolution.getScaledWidth() - font.getStringWidth(text)) / 2;
		font.drawStringWithShadow(text, Math.max(2, x), 6, tdmVoting ? 0xFFFF55 : 0xFFFFFF);
	}

	private static String bombStateText() {
		if ("WAITING_FOR_TEAMS".equals(tdmBombState)) {
			return "Waiting for teams";
		}
		if ("PRE_ROUND".equals(tdmBombState)) {
			return "Buy time: " + formatSeconds(tdmBombSeconds);
		}
		if ("LIVE".equals(tdmBombState)) {
			return "Round: " + formatSeconds(tdmBombSeconds);
		}
		if ("BOMB_PLANTED".equals(tdmBombState)) {
			return "Bomb planted" + (tdmBombsite.length() > 0 ? " " + tdmBombsite : "");
		}
		if ("ROUND_END".equals(tdmBombState)) {
			return "Round over";
		}
		return "";
	}

	public static void openTDMMenu(String currentTeam, int cooldownSeconds, String[] friendlyLines, String[] enemyLines,boolean canOpenBuyMenu,boolean ffa,boolean showUtility,boolean showKillstreaks,int killScore) {
		Minecraft mc = Minecraft.getMinecraft();
		if (mc != null) {
			mc.displayGuiScreen(new GUITDMMenu(currentTeam, cooldownSeconds, friendlyLines, enemyLines,canOpenBuyMenu,ffa,showUtility,showKillstreaks,killScore));
		}
	}

	private static String formatSeconds(int seconds) {
		if (seconds < 0) {
			seconds = 0;
		}
		int minutes = seconds / 60;
		int secs = seconds % 60;
		return minutes + ":" + (secs < 10 ? "0" : "") + secs;
	}

	public void register() {

		MinecraftForge.EVENT_BUS.register(this);
		FMLCommonHandler.instance().bus().register(this);
	}
	
	@SubscribeEvent
	public void drawHUD(RenderGameOverlayEvent.Post event) {
		
		EntityPlayer player = Minecraft.getMinecraft().thePlayer;
		
		if(event.type == ElementType.TEXT) {
			drawTDMHud();
			GUIAdminEditor.drawSelectionPreview();
			com.hfr.client.journeymap.ClientTDMMapOverlay.drawHud(event);
			if (Minecraft.getMinecraft().currentScreen == null && !Minecraft.getMinecraft().gameSettings.hideGUI)
				com.hfr.client.XFFactionSetup.draw(event.resolution.getScaledWidth(), event.resolution.getScaledHeight(), false, 0);
		}

		if(event.type == ElementType.CROSSHAIRS)
		{
			//anti-ragex mechanism, do not delete - oops i deleted it
			//if(player.getUniqueID().toString().equals("c874fd4e-5841-42e4-8f77-70efd5881bc1"))
			//	if(player.ticksExisted > 5 * 60 * 20) //<- time til the autism kicks in
			//		Minecraft.getMinecraft().entityRenderer.debugViewDirection = 5;

			//cope artistry led to this, now we must cope with the consequences.
			
			/// START KEYBINDS ///
			if(/*!FMLClientHandler.instance().isGUIOpen(GuiChat.class)*/ Minecraft.getMinecraft().currentScreen == null) {
				
				//chat filter
				if(Keyboard.isKeyDown(ClientProxy.filter.getKeyCode())/* && Minecraft.getMinecraft().currentScreen != null*/) {
					
					if(!lock) {
						MainRegistry.chatfilter = !MainRegistry.chatfilter;
						Minecraft.getMinecraft().thePlayer.playSound("hfr:item.toggle", 0.25F, 0.5F);
					}
					
					lock = true;

				//tdm menu
				} else if(Keyboard.isKeyDown(ClientProxy.tdmMenu.getKeyCode())) {
					
					if(!lock) {
						PacketDispatcher.wrapper.sendToServer(new TDMMenuActionPacket(false));
						Minecraft.getMinecraft().thePlayer.playSound("hfr:item.toggle", 0.25F, 1.0F);
					}
					
					lock = true;
				} else if(Keyboard.isKeyDown(ClientProxy.tdmBuyMenu.getKeyCode())) {
					if(!lock) PacketDispatcher.wrapper.sendToServer(new TDMMenuActionPacket(false, true));
					lock = true;
				
				//logger
				} else if(Keyboard.isKeyDown(ClientProxy.flushLog.getKeyCode())) {
					
					if(!lock) {
						LoggingEngine.flush();
						Minecraft.getMinecraft().thePlayer.playSound("hfr:item.toggle", 0.25F, 0.5F);
					}
					
					lock = true;
					
				} else {
					
					lock = false;
				}
			}
			/// END KEYBINDS ///
			
			/// CLOWDER TRASH ///
			//todo fix with HMG
			RenderFlagOverlay.drawFlag();
		}
		
		if(fps) {
			Minecraft.getMinecraft().gameSettings.limitFramerate = 5;
		}
		
		if(tilt) {
			Minecraft.getMinecraft().entityRenderer.debugViewDirection = 5;
		}
		
		if(shader && player.getRNG().nextInt(500) == 0) {
			Minecraft.getMinecraft().entityRenderer.activateNextShader();
		}
	}
	
	/** Render plans in world space, independently of the Depot TESR's frustum. */
	
	@SubscribeEvent
	public void preRenderEvent(RenderPlayerEvent.Pre event) {
		
		RenderPlayer renderer = event.renderer;
		AbstractClientPlayer player = (AbstractClientPlayer)event.entityPlayer;
		
		ResourceLocation cloak = RenderAccessoryUtility.getCloakFromPlayer(player);
		
		if(cloak != null)
			player.func_152121_a(Type.CAPE, cloak);
		
		if(player.getHeldItem() != null) {
			Item item = player.getHeldItem().getItem();
			
		}
	}
	
	public static HashMap<String, String> lookup = new HashMap();
    private static final java.util.Map<java.util.UUID,String> renderedNames = new java.util.HashMap<java.util.UUID,String>();
    public static void updateFactionName(String player,String value) {
        lookup.put(player,value);
        java.util.UUID id;
        try { id=java.util.UUID.fromString(player); } catch(IllegalArgumentException invalid) { return; }
        if(value==null || value.equals("###")) { renderedNames.remove(id); return; }
        String[] relation=value.split("\\|",2);String name=relation[0].replace('_',' ');
        String kind=relation.length>1?relation[1]:"neutral";
        renderedNames.put(id,("own".equals(kind)?EnumChatFormatting.GREEN:"ally".equals(kind)?EnumChatFormatting.BLUE:"enemy".equals(kind)?EnumChatFormatting.RED:EnumChatFormatting.YELLOW)+name);
    }

	@SubscribeEvent
	public void preRenderEvent(RenderLivingEvent.Pre event) {

		if(event.entity instanceof EntityPlayer) {
			//allahu bookmark note important - this is the part where player name changes based on clowder
            String name=renderedNames.get(event.entity.getUniqueID());
            if(name!=null)renderTag((EntityPlayer)event.entity,event.x,event.y,event.z,event.renderer,name);
		}
	}
	
	@SubscribeEvent
	public void onClientDisconnect(ClientDisconnectionFromServerEvent event) {
		com.hfr.client.journeymap.ClientBorderParticles.requestClear();
        com.hfr.client.render.ClientRenderSession.invalidate();
		GUIXenofactionsMenu.requestSessionReset();
		com.hfr.client.journeymap.ClientClaimOverlayCache.requestClear();
		com.hfr.client.flag.FactionFlagTextureManager.requestClear();
		com.hfr.client.earth.EarthMapTileCache.requestClear();
        disconnectCleanup=true;

	}

	@SubscribeEvent
	public void onClientConnect(cpw.mods.fml.common.network.FMLNetworkEvent.ClientConnectedToServerEvent event) {
		com.hfr.client.journeymap.ClientBorderParticles.requestClear();
        com.hfr.client.render.ClientRenderSession.invalidate();
		GUIXenofactionsMenu.requestSessionReset();
	}

	@SubscribeEvent
	public void onClientWorldUnload(WorldEvent.Unload event) {
		if(event.world != null && event.world.isRemote) com.hfr.client.journeymap.ClientBorderParticles.worldUnloaded(event.world);
        if(event.world != null && event.world.isRemote) {
            com.hfr.client.render.ClientRenderSession.invalidate();
            com.hfr.client.wallart.WallArtTextureCache.requestClear();
            com.hfr.client.flag.FactionFlagTextureManager.requestClear();
            com.hfr.client.render.GpuModelManager.release();
        }
		if(event.world != null && event.world.isRemote) com.hfr.client.journeymap.ClientClaimOverlayCache.requestClear();
		if(event.world != null && event.world.isRemote) com.hfr.client.earth.EarthMapTileCache.requestClear();
		if(event.world!=null&&event.world.isRemote) { resetTDMClientState(); GUIAdminEditor.clearSelectionPreview(); com.hfr.client.journeymap.ClientTDMMapOverlay.clear(); }
	}

	@SubscribeEvent
	public void onBorderChunkLoad(net.minecraftforge.event.world.ChunkEvent.Load event) {
		if(event.world.isRemote) com.hfr.client.journeymap.ClientBorderParticles.loaded(event.getChunk());
	}

	@SubscribeEvent
	public void onBorderChunkUnload(net.minecraftforge.event.world.ChunkEvent.Unload event) {
		if(event.world.isRemote) com.hfr.client.journeymap.ClientBorderParticles.unloaded(event.getChunk());
	}

	private void renderTag(EntityPlayer player, double x, double y, double z, RendererLivingEntity renderer, String name) {
		
		EntityPlayer thePlayer = Minecraft.getMinecraft().thePlayer;
		
		GL11.glAlphaFunc(GL11.GL_GREATER, 0.1F);

        if (this.func_110813_b(player))
        {
            float f = 1.6F;
            float f1 = 0.016666668F * f;
            double d3 = player.getDistanceSqToEntity(thePlayer);
            float f2 = player.isSneaking() ? renderer.NAME_TAG_RANGE_SNEAK : renderer.NAME_TAG_RANGE;

            if (d3 < (double)(f2 * f2))
            {
                String s = name;

				if (player.isSneaking()) {
					return; // Do not render nameplate at all when sneaking
				}
                else
                {
                	func_96449_a(player, x, y, z, name, f1, d3);
                }
            }
        }
	}

    protected boolean func_110813_b(EntityLivingBase p_110813_1_)
    {
        return Minecraft.isGuiEnabled() && p_110813_1_ != RenderManager.instance.livingPlayer && !p_110813_1_.isInvisibleToPlayer(Minecraft.getMinecraft().thePlayer) && p_110813_1_.riddenByEntity == null;
    }

    protected void func_96449_a(EntityLivingBase p_96449_1_, double p_96449_2_, double p_96449_4_, double p_96449_6_, String p_96449_8_, float p_96449_9_, double p_96449_10_)
    {
        if (p_96449_1_.isPlayerSleeping())
        {
            this.func_147906_a(p_96449_1_, p_96449_8_, p_96449_2_, p_96449_4_ - 1.5D, p_96449_6_, 64);
        }
        else
        {
            this.func_147906_a(p_96449_1_, p_96449_8_, p_96449_2_, p_96449_4_, p_96449_6_, 64);
        }
    }

    protected void func_147906_a(Entity p_147906_1_, String name, double p_147906_3_, double p_147906_5_, double p_147906_7_, int p_147906_9_)
    {
        double d3 = p_147906_1_.getDistanceSqToEntity(RenderManager.instance.livingPlayer);

        if (d3 <= (double)(p_147906_9_ * p_147906_9_))
        {
            FontRenderer fontrenderer = Minecraft.getMinecraft().fontRenderer;
            float f = 1.6F;
            float f1 = 0.016666668F * f;
            GL11.glPushMatrix();
            GL11.glTranslatef((float)p_147906_3_ + 0.0F, (float)p_147906_5_ + p_147906_1_.height + 0.75F, (float)p_147906_7_);
            GL11.glNormal3f(0.0F, 1.0F, 0.0F);
            GL11.glRotatef(-RenderManager.instance.playerViewY, 0.0F, 1.0F, 0.0F);
            GL11.glRotatef(RenderManager.instance.playerViewX, 1.0F, 0.0F, 0.0F);
            GL11.glScalef(-f1, -f1, f1);
            GL11.glDisable(GL11.GL_LIGHTING);
            GL11.glDepthMask(false);
            GL11.glDisable(GL11.GL_DEPTH_TEST);
            GL11.glEnable(GL11.GL_BLEND);
            OpenGlHelper.glBlendFunc(770, 771, 1, 0);
            Tessellator tessellator = Tessellator.instance;
            byte b0 = 0;

            if (name.equals("deadmau5"))
            {
                b0 = -10;
            }

            GL11.glDisable(GL11.GL_TEXTURE_2D);
            tessellator.startDrawingQuads();
            int j = fontrenderer.getStringWidth(name) / 2;
            tessellator.setColorRGBA_F(0.0F, 0.0F, 0.0F, 0.25F);
            tessellator.addVertex((double)(-j - 1), (double)(-1 + b0), 0.0D);
            tessellator.addVertex((double)(-j - 1), (double)(8 + b0), 0.0D);
            tessellator.addVertex((double)(j + 1), (double)(8 + b0), 0.0D);
            tessellator.addVertex((double)(j + 1), (double)(-1 + b0), 0.0D);
            tessellator.draw();
            GL11.glEnable(GL11.GL_TEXTURE_2D);
            fontrenderer.drawString(name, -fontrenderer.getStringWidth(name) / 2, b0, 553648127);
            GL11.glEnable(GL11.GL_DEPTH_TEST);
            GL11.glDepthMask(true);
            fontrenderer.drawString(name, -fontrenderer.getStringWidth(name) / 2, b0, -1);
            GL11.glEnable(GL11.GL_LIGHTING);
            GL11.glDisable(GL11.GL_BLEND);
            GL11.glColor4f(1.0F, 1.0F, 1.0F, 1.0F);
            GL11.glPopMatrix();
        }
    }
	
	@SubscribeEvent
	public void renderTick(TickEvent.RenderTickEvent event) {
		
		if(event.phase == TickEvent.Phase.START) {
			MainRegistry.smoothing = event.renderTickTime;
			
			if(MainRegistry.hfr_powerlog)
				LoggingEngine.survey();
		}
	}

    @SubscribeEvent
    public void renderWorkerPlans(RenderWorldLastEvent event) {
        com.hfr.client.MinerSelectionPreview.render(event);
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.theWorld == null || mc.renderViewEntity == null) return;
        Entity camera = mc.renderViewEntity;
        double x = camera.lastTickPosX + (camera.posX - camera.lastTickPosX) * event.partialTicks;
        double y = camera.lastTickPosY + (camera.posY - camera.lastTickPosY) * event.partialTicks;
        double z = camera.lastTickPosZ + (camera.posZ - camera.lastTickPosZ) * event.partialTicks;
        try (com.hfr.client.render.RenderState state = new com.hfr.client.render.RenderState()) {
            for (Object value : mc.theWorld.loadedTileEntityList) if (value instanceof com.hfr.tileentity.machine.TileEntityMachineBuilder) {
                com.hfr.tileentity.machine.TileEntityMachineBuilder depot = (com.hfr.tileentity.machine.TileEntityMachineBuilder)value;
                if (depot.preview == null) continue;
                GL11.glPushMatrix();
                GL11.glTranslated(depot.previewX - x, depot.previewY - y, depot.previewZ - z);
                mc.getTextureManager().bindTexture(net.minecraft.client.renderer.texture.TextureMap.locationBlocksTexture);
                com.hfr.schematic.SchematicRenderer.render(depot.preview, 1F, 0, 0, 0, 0, depot.previewRotation, depot.previewMirrored);
                GL11.glPopMatrix();
            }
        }
    }

    @SubscribeEvent
    public void minerWandMouse(net.minecraftforge.client.event.MouseEvent event) {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.currentScreen != null || mc.thePlayer == null || mc.objectMouseOver == null
                || mc.objectMouseOver.typeOfHit != net.minecraft.util.MovingObjectPosition.MovingObjectType.BLOCK) return;
        net.minecraft.item.ItemStack held = mc.thePlayer.getHeldItem();
        if (held == null || !(held.getItem() instanceof com.hfr.items.ItemMinerWand)) return;
        boolean first = event.button == mc.gameSettings.keyBindAttack.getKeyCode() + 100;
        boolean second = event.button == mc.gameSettings.keyBindUseItem.getKeyCode() + 100;
        if ((!first && !second) || !event.buttonstate) return;
        event.setCanceled(true);
        sendMinerWandClick(mc, first);
    }

    // Keyboard-bound attack/use follows the same click path as mouse-bound controls.
    @SubscribeEvent
    public void minerWandKey(cpw.mods.fml.common.gameevent.InputEvent.KeyInputEvent event) {
        Minecraft mc = Minecraft.getMinecraft();
        if (!Keyboard.getEventKeyState() || Keyboard.isRepeatEvent()) return;
        int key = Keyboard.getEventKey() == 0 ? Keyboard.getEventCharacter()+256 : Keyboard.getEventKey();
        boolean first = key == mc.gameSettings.keyBindAttack.getKeyCode();
        if (!first && key != mc.gameSettings.keyBindUseItem.getKeyCode()) return;
        if (sendMinerWandClick(mc,first)) {
            net.minecraft.client.settings.KeyBinding.setKeyBindState(key,false);
            while ((first ? mc.gameSettings.keyBindAttack : mc.gameSettings.keyBindUseItem).isPressed()) { }
        }
    }
    private boolean sendMinerWandClick(Minecraft mc, boolean first) {
        if (mc.currentScreen != null || mc.thePlayer == null || mc.objectMouseOver == null
                || mc.objectMouseOver.typeOfHit != net.minecraft.util.MovingObjectPosition.MovingObjectType.BLOCK) return false;
        net.minecraft.item.ItemStack held = mc.thePlayer.getHeldItem();
        if (held == null || !(held.getItem() instanceof com.hfr.items.ItemMinerWand)) return false;
        if (held.hasTagCompound()) {
            int sneak = mc.gameSettings.keyBindSneak.getKeyCode();
            boolean down = sneak < 0 ? org.lwjgl.input.Mouse.isButtonDown(sneak+100)
                    : sneak > 0 && sneak < Keyboard.KEYBOARD_SIZE && Keyboard.isKeyDown(sneak);
            net.minecraft.util.MovingObjectPosition hit = mc.objectMouseOver;
            PacketDispatcher.wrapper.sendToServer(new com.hfr.packet.builder.MinerWandPacket(hit.blockX,hit.blockY,hit.blockZ,hit.sideHit,
                    mc.thePlayer.inventory.currentItem,mc.thePlayer.dimension,first,down,held.getTagCompound().getString("Miner")));
        }
        return true;
    }
	
	private volatile boolean disconnectCleanup;
    private boolean xfHadWorld = false;

	@SubscribeEvent
	public void xenofactionsClientTick(ClientTickEvent event) {
        if (event.phase == TickEvent.Phase.END) com.hfr.client.PlayerActivityInput.tick();
		if(event.phase == TickEvent.Phase.START) GUIXenofactionsMenu.updateSession();
		if(event.phase == TickEvent.Phase.START) com.hfr.client.journeymap.ClientClaimOverlayCache.tick();
		if(event.phase == TickEvent.Phase.START) {
            com.hfr.client.journeymap.ClientBorderParticles.beginTick();
            if(disconnectCleanup) {
                disconnectCleanup=false;
                resetTDMClientState();
                GUIAdminEditor.clearSelectionPreview();
                com.hfr.client.journeymap.ClientTDMMapOverlay.clear();
                lookup.clear();renderedNames.clear();
                com.hfr.client.wallart.WallArtTextureCache.requestClear();
                com.hfr.stonedrops.StoneDropDisplaySnapshot.clearClientSnapshot();
                MachineDisplaySnapshot.clearClientSnapshot();
                com.hfr.client.render.GpuModelManager.release();
            }
            com.hfr.client.wallart.WallArtTextureCache.tick();
            com.hfr.client.flag.FactionFlagTextureManager.tick();
            com.hfr.client.render.ClientPresentation.tick();
            if(Minecraft.getMinecraft().theWorld != null) com.hfr.client.render.GpuModelManager.resume();
            com.hfr.client.render.RenderMetrics.tick(com.hfr.client.flag.FactionFlagTextureManager.size(),
                com.hfr.client.wallart.WallArtTextureCache.size(),com.hfr.client.wallart.WallArtTextureCache.pending());
        }
		if(event.phase==TickEvent.Phase.END)enforceMandatoryKitGui();
		if(event.phase==TickEvent.Phase.END)com.hfr.client.journeymap.ClientTDMMapOverlay.emitWorldBorder();
		if(event.phase == TickEvent.Phase.END) com.hfr.client.journeymap.ClientBorderParticles.endTick();
		Minecraft xfMc = Minecraft.getMinecraft();
		boolean xfHasWorld = xfMc.theWorld != null;
		if(xfHadWorld&&!xfHasWorld) { com.hfr.client.render.GpuModelManager.release(); resetTDMClientState(); GUIAdminEditor.clearSelectionPreview(); com.hfr.client.journeymap.ClientTDMMapOverlay.clear(); }
		xfHadWorld = xfHasWorld;
		if(xfHasWorld && xfMc.thePlayer != null && xfMc.currentScreen == null) {
			while(ClientProxy.borderVisuals.isPressed()) com.hfr.client.journeymap.ClientBorderVisuals.toggle();
			while(ClientProxy.adminEditor.isPressed()) xfMc.thePlayer.sendChatMessage("/tdm editor gui");
			while(ClientProxy.xenofactionsMenu.isPressed()) xfMc.displayGuiScreen(new com.hfr.inventory.gui.GUIXenofactionsHome());
			while(ClientProxy.xenofactionsMap.isPressed()) xfMc.displayGuiScreen(new com.hfr.client.earth.GuiXFEarthMap());
		}
		if(event.phase == TickEvent.Phase.END) com.hfr.client.XFLoginFlow.tick();
	}

	long time = -1;
	int oldEnt = 0;
	
	/*@SubscribeEvent
	EntityPlayer player = Minecraft.getMinecraft().thePlayer;
		
		if(player == null || player.worldObj == null)
			return;
		
		long now = System.currentTimeMillis();
		
		if(player.isRiding()) {
			time = now;
			oldEnt = player.ridingEntity.getEntityId();
			return;
		}
		
		if(now > time + 150 && now < time + 15000) {
			
			int shift = Minecraft.getMinecraft().gameSettings.keyBindSneak.getKeyCode();
			
			if(Keyboard.isKeyDown(shift)) {
				time = -1;
				oldEnt = 0;
				player.addChatComponentMessage(new ChatComponentText("Vehicle dismount detected: Shift key is pressed!"));
				return;
			} else {
				
				Entity reseat = player.worldObj.getEntityByID(oldEnt);
				
				if(reseat != null) {
					player.mountEntity(reseat);
					PacketDispatcher.wrapper.sendToServer(new ReseatRequestPacket());
					time = -1;
					oldEnt = 0;
					player.addChatComponentMessage(new ChatComponentText("Vehicle dismount detected: Attempting to remount!"));
					return;
				} else {
					player.addChatComponentMessage(new ChatComponentText("Vehicle dismount detected: Mounting entity not found!"));
				}
			}
		}
	}*/
	
	@SubscribeEvent
	public void chatReceivedEvent(ClientChatReceivedEvent event) {
		
		IChatComponent component = event.message;
		String raw = component.getUnformattedText();
		
		Integer i = PlayerDataPacket.vals.get(CommandClowderChat.MUTE_KEY);
		
		if(i != null && i == 1) {
			
			System.out.println(raw);
			
			if(raw.startsWith("§") && (raw.endsWith("Citizen ]") || raw.endsWith("Officer ]") || raw.endsWith("Leader ]"))) {
				event.setCanceled(true);
				return;
			}
			
			if(raw.startsWith("§2<")) {
				return;
			}
			
			if(raw.startsWith("<")) {
				event.setCanceled(true);
				return;
			}
			
			return;
		}
		
		// Server-filtered public bodies must not run through substitutions a second time.
		if(component instanceof ChatComponentTranslation &&
				PublicChat.FILTERED_CHAT_KEY.equals(((ChatComponentTranslation) component).getKey())) return;

		if(!MainRegistry.chatfilter)
			return;

		// Let Forge deliver the styled component to vanilla chat normally.
		event.message = filterChatComponent(component);
	}

	private static IChatComponent filterChatComponent(IChatComponent component) {
		IChatComponent filtered;
		if(component instanceof ChatComponentText) {
			filtered = new ChatComponentText(filterChatText(((ChatComponentText) component).getChatComponentText_TextValue()));
		} else if(component instanceof ChatComponentTranslation) {
			ChatComponentTranslation translation = (ChatComponentTranslation) component;
			Object[] args = translation.getFormatArgs().clone();
			for(int index = 0; index < args.length; index++) {
				if(args[index] instanceof IChatComponent) {
					args[index] = filterChatComponent((IChatComponent) args[index]);
				} else if(args[index] instanceof String) {
					args[index] = filterChatText((String) args[index]);
				}
			}
			filtered = new ChatComponentTranslation(translation.getKey(), args);
		} else {
			// Keep optional mods' component types rather than reducing them to text.
			filtered = component.createCopy();
			filtered.getSiblings().clear();
		}
		filtered.setChatStyle(component.getChatStyle().createShallowCopy());
		for(Object sibling : component.getSiblings()) {
			filtered.appendSibling(filterChatComponent((IChatComponent) sibling));
		}
		return filtered;
	}

	private static String filterChatText(String msg) {
		return ChatTextFilter.filter(msg);
	}
}
