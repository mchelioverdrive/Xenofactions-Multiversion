package com.hfr.main;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import net.minecraft.server.MinecraftServer;
import net.minecraft.creativetab.CreativeTabs;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityList;
import net.minecraft.init.Blocks;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.JsonToNBT;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.DamageSource;
import net.minecraft.world.World;
import net.minecraftforge.common.ForgeChunkManager;
import net.minecraftforge.common.ForgeChunkManager.LoadingCallback;
import net.minecraftforge.common.ForgeChunkManager.Ticket;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.common.config.Configuration;
import net.minecraftforge.common.config.Property;
import net.minecraft.item.Item;
import net.minecraftforge.common.util.EnumHelper;
import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.Mod;
import cpw.mods.fml.common.Mod.EventHandler;
import cpw.mods.fml.common.Mod.Instance;
import cpw.mods.fml.common.Mod.Metadata;
import cpw.mods.fml.common.ModMetadata;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Reader;
import java.io.Writer;
import java.lang.reflect.Type;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

import net.minecraftforge.oredict.OreDictionary;
import org.apache.logging.log4j.Logger;

import com.google.gson.JsonIOException;
import com.google.gson.JsonSyntaxException;
import com.hfr.blocks.*;
import com.hfr.blocks.machine.MachineMarket.TileEntityMarket;
import com.hfr.clowder.*;
import com.hfr.command.*;
import com.hfr.compat.HbmStoneDropIntegration;
import com.hfr.config.XFConfig;
import com.hfr.data.*;
import com.hfr.dynmap.XFDynmapIntegration;
//import com.hfr.dim.WorldGeneratorMoon;
//import com.hfr.dim.WorldProviderMoon;
import com.hfr.guide.XFGuideBook;
import com.hfr.handler.*;
import com.hfr.items.*;
import com.hfr.lib.*;
import com.hfr.packet.*;
import com.hfr.stonedrops.StoneDropSnapshotSync;
import com.hfr.tileentity.machine.MachineDisplaySnapshotSync;
import com.hfr.tdm.*;
import com.hfr.tileentity.*;
import com.hfr.tileentity.clowder.*;
import com.hfr.tileentity.machine.*;
import com.hfr.tileentity.prop.*;
import com.hfr.util.*;

import cpw.mods.fml.common.SidedProxy;
import cpw.mods.fml.common.event.FMLInitializationEvent;
import cpw.mods.fml.common.event.FMLPostInitializationEvent;
import cpw.mods.fml.common.event.FMLPreInitializationEvent;
import cpw.mods.fml.common.event.FMLServerStartingEvent;
import cpw.mods.fml.common.event.FMLServerAboutToStartEvent;
import cpw.mods.fml.common.event.FMLServerStoppingEvent;
import cpw.mods.fml.common.network.NetworkRegistry;
import cpw.mods.fml.common.registry.EntityRegistry;
import cpw.mods.fml.common.registry.GameRegistry;

import static com.hfr.clowder.Clowder.initializeDiplomacy;

@Mod(modid = RefStrings.MODID, name = RefStrings.NAME, version = RefStrings.VERSION, guiFactory = RefStrings.GUI_FACTORY, dependencies = "required-after:unimixins@[0.3.1,);after:guideapi")
public class MainRegistry
{
	// Packet layouts changed with the maintained content set; mixed builds cannot connect.
	@cpw.mods.fml.common.network.NetworkCheckHandler
	public boolean acceptsNetworkBuild(java.util.Map<String, String> remoteVersions, cpw.mods.fml.relauncher.Side side) {
		if (!RefStrings.VERSION.equals(remoteVersions.get(RefStrings.MODID))) return false;
		// The pinned coremod changes S01/S07/S23 globally, so even login requires matching native peers.
		String remote = remoteVersions.get("cubicchunks");
		boolean local = cpw.mods.fml.common.Loader.isModLoaded("cubicchunks");
		if (!local) return remote == null;
		return com.hfr.world.earth.storage.XFEarthStorageIdentity.CUBIC_VERSION.equals(remote)
				&& remote.equals(cpw.mods.fml.common.Loader.instance().getIndexedModList().get("cubicchunks").getVersion());
	}

	@Instance(RefStrings.MODID)
	public static MainRegistry instance;

	@SidedProxy(clientSide = RefStrings.CLIENTSIDE, serverSide = RefStrings.SERVERSIDE)
	public static ServerProxy proxy;

	@Metadata
	public static ModMetadata meta;

	public static Logger logger;
	public static double customDropChance = 0.0; // default off
	public static ItemStack customDropStack = null;
	public static final List<ItemStack> customDrops = new ArrayList<ItemStack>();
	public static final List<Double> customDropChances = new ArrayList<Double>();
	public static final List<Integer> customDropMinYs = new ArrayList<Integer>();
	public static final List<Integer> customDropMaxYs = new ArrayList<Integer>();

	//public static WorldGeneratorMoon worldGenMoon = new WorldGeneratorMoon();

	public static int superFishrate = 20;
	public static int goodFishrate = 40;
	public static int averageFishrate = 60;
	public static int crapFishrate = 1000000;
	public static int jamRate = 15 * 60;
	public static int whaleChance = 5;

	public static int uniRate = 60 * 3;
	public static int uniJamRate = 60 * 15;

	public static int temple = 10 * 60 * 3;

	public static int factoryRate = 60 * 3;

	public static int factoryJamRate = 60 * 15;

	public static int caveCap = -10;

	public static int warpCost = 125;
	public static int territoryDelay = 5;
	public static int territoryAmount = 50;
	public static int prestigeDelay = 60 * 60 * 20;

	public static boolean freeRaid = true;

	public static boolean chatfilter = true;

	public static boolean border = false;

	public static int borderPosX = 0;
	public static int borderNegX = 0;
	public static int borderPosZ = 0;
	public static int borderNegZ = 0;

	public static double coalChance = 0.04;
	public static double ironChance = 0.05;
	public static double goldChance = 0.01;

	Random rand = new Random();

	public static DamageSource blast = (new DamageSource("blast")).setExplosion().setDamageBypassesArmor().setDamageIsAbsolute();

	public static DamageSource wire = (new DamageSource("wire"));

	public static CreativeTabs tab = new CreativeTabHFR(CreativeTabs.getNextID(), "tabHFR");

	public static float smoothing = 0.0F;

	public static boolean hfr_powerlog = false;

	public static HashMap<String, String> sub = new HashMap();

	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static final File SAVE_FILE = new File("config/stonedrops.json");
	private static final Charset UTF_8 = Charset.forName("UTF-8");
	public static StoneDropLoadState stoneDropLoadState = StoneDropLoadState.MISSING;

	public enum StoneDropLoadState {
		MISSING, EMPTY_FILE, MALFORMED, EMPTY_LIST, LOADED
	}

	public static void saveCustomDrops() {
		Writer writer = null;
		File tempFile = new File(SAVE_FILE.getPath() + ".tmp");
		try {
			File parent = SAVE_FILE.getParentFile();
			if (parent != null && !parent.exists() && !parent.mkdirs()) {
				throw new java.io.IOException("could not create " + parent.getPath());
			}
			List<DropEntry> entries = new ArrayList<DropEntry>();
			int limit = getCustomDropListSize();
			for (int i = 0; i < limit; i++) {
				ItemStack stack = MainRegistry.customDrops.get(i);
				String itemName = Item.itemRegistry.getNameForObject(stack.getItem());
				String nbtData = stack.hasTagCompound() ? stack.getTagCompound().toString() : null;
				entries.add(new DropEntry(itemName, stack.getItemDamage(), stack.stackSize,
						MainRegistry.customDropChances.get(i), nbtData,
						MainRegistry.customDropMinYs.get(i), MainRegistry.customDropMaxYs.get(i)));
			}
			writer = new OutputStreamWriter(new FileOutputStream(tempFile), UTF_8);
			GSON.toJson(entries, writer);
			writer.close();
			writer = null;
			if (SAVE_FILE.exists() && !SAVE_FILE.delete()) {
				throw new java.io.IOException("could not replace " + SAVE_FILE.getPath());
			}
			if (!tempFile.renameTo(SAVE_FILE)) {
				throw new java.io.IOException("could not move " + tempFile.getPath() + " to " + SAVE_FILE.getPath());
			}
		} catch (Exception e) {
			logger.warn("[XF] Failed to save stone drops safely to " + SAVE_FILE.getPath() + ": " + e.getMessage());
		} finally {
			if (writer != null) {
				try { writer.close(); } catch (Exception e) { logger.warn("[XF] Failed to close stone-drop writer: " + e.getMessage()); }
			}
			if (tempFile.exists()) tempFile.delete();
		}
	}

	public static void loadCustomDrops() {
		if (!SAVE_FILE.exists()) {
			stoneDropLoadState = StoneDropLoadState.MISSING;
			return;
		}
		if (SAVE_FILE.length() == 0L) {
			stoneDropLoadState = StoneDropLoadState.EMPTY_FILE;
			logger.warn("[XF] config/stonedrops.json is empty; automatic HBM recovery may replace it if valid HBM drops load.");
			return;
		}
		Reader reader = null;
		try {
			reader = new InputStreamReader(new FileInputStream(SAVE_FILE), UTF_8);
			Type listType = new TypeToken<List<DropEntry>>() {}.getType();
			List<DropEntry> entries = GSON.fromJson(reader, listType);
			if (entries == null) {
				stoneDropLoadState = StoneDropLoadState.MALFORMED;
				logger.warn("[XF] config/stonedrops.json did not contain a stone-drop list; automatic HBM recovery may replace it if valid HBM drops load.");
				return;
			}
			if (entries.isEmpty()) {
				stoneDropLoadState = StoneDropLoadState.EMPTY_LIST;
				return;
			}
			MainRegistry.customDrops.clear();
			MainRegistry.customDropChances.clear();
			MainRegistry.customDropMinYs.clear();
			MainRegistry.customDropMaxYs.clear();
			for (DropEntry entry : entries) {
				Item item = (Item) Item.itemRegistry.getObject(entry.itemName);
				if (item != null) {
					ItemStack stack = new ItemStack(item, entry.count, entry.metadata);
					if (entry.nbtData != null) {
						try { stack.setTagCompound((NBTTagCompound) JsonToNBT.func_150315_a(entry.nbtData)); }
						catch (Exception e) { logger.warn("[XF] Failed to parse NBT for stone-drop item " + entry.itemName + ": " + e.getMessage()); }
					}
					MainRegistry.customDrops.add(stack);
					MainRegistry.customDropChances.add(entry.chance);
					MainRegistry.customDropMinYs.add(entry.minY);
					MainRegistry.customDropMaxYs.add(entry.maxY);
				} else {
					logger.warn("[XF] Stone-drop item not found while loading config/stonedrops.json: " + entry.itemName);
				}
			}
			stoneDropLoadState = StoneDropLoadState.LOADED;
		} catch (JsonSyntaxException e) {
			stoneDropLoadState = StoneDropLoadState.MALFORMED;
			logger.warn("[XF] Malformed config/stonedrops.json; keeping runtime stone drops empty and attempting HBM recovery later: " + e.getMessage());
		} catch (Exception e) {
			stoneDropLoadState = StoneDropLoadState.MALFORMED;
			logger.warn("[XF] Failed to load config/stonedrops.json; keeping runtime stone drops empty and attempting HBM recovery later: " + e.getMessage());
		} finally {
			if (reader != null) {
				try { reader.close(); } catch (Exception e) { logger.warn("[XF] Failed to close stone-drop reader: " + e.getMessage()); }
			}
		}
	}

	public static boolean hasCustomDrop(ItemStack candidate) {
		for (ItemStack existing : MainRegistry.customDrops) {
			if (existing != null && existing.getItem() == candidate.getItem()
					&& existing.getItemDamage() == candidate.getItemDamage()
					&& ItemStack.areItemStackTagsEqual(existing, candidate)) return true;
		}
		return false;
	}

	private static int getCustomDropListSize() {
		int size = Math.min(MainRegistry.customDrops.size(), MainRegistry.customDropChances.size());
		size = Math.min(size, MainRegistry.customDropMinYs.size());
		size = Math.min(size, MainRegistry.customDropMaxYs.size());
		if (size != MainRegistry.customDrops.size() || size != MainRegistry.customDropChances.size()
				|| size != MainRegistry.customDropMinYs.size() || size != MainRegistry.customDropMaxYs.size()) {
			logger.warn("[XF] Stone-drop runtime lists had mismatched sizes; saving only complete entries.");
		}
		return size;
	}

	public static void backupMalformedStoneDrops() {
		if (!SAVE_FILE.exists()) return;
		File backup = new File(SAVE_FILE.getPath() + ".malformed");
		if (backup.exists()) backup.delete();
		if (SAVE_FILE.renameTo(backup)) {
			logger.warn("[XF] Backed up malformed config/stonedrops.json to " + backup.getPath() + " before writing recovered HBM stone drops.");
		} else {
			logger.warn("[XF] Could not back up malformed config/stonedrops.json; safe replacement will still avoid partial writes.");
		}
	}

	private static class DropEntry {
		String itemName;
		int metadata;
		int count;
		double chance;
		String nbtData; // Store NBT as a string
		Integer minY;
		Integer maxY;

		DropEntry(String itemName, int metadata, int count, double chance, String nbtData, Integer minY, Integer maxY) {
			this.itemName = itemName;
			this.metadata = metadata;
			this.count = count;
			this.chance = chance;
			this.nbtData = nbtData;
			this.minY = minY;
			this.maxY = maxY;
		}
	}

    public static final java.util.List<com.hfr.schematic.Schematic> schems = new java.util.ArrayList<com.hfr.schematic.Schematic>();

	@EventHandler
	public void PreLoad(FMLPreInitializationEvent PreEvent)
	{
		if(logger == null)
			logger = PreEvent.getModLog();

		ModBlocks.mainRegistry();
		ModItems.mainRegistry();
		XFAchievements.register();
		loadConfig(PreEvent);
        com.hfr.schematic.SchematicLibrary.get().initialize(PreEvent.getModConfigurationDirectory());

		com.hfr.world.earth.XFEarthRegistry.register();
		MinecraftForge.EVENT_BUS.register(new com.hfr.world.earth.XFEarthWorldValidationHandler());
		MinecraftForge.EVENT_BUS.register(new com.hfr.world.earth.roads.XFEarthRoadState());
        MinecraftForge.EVENT_BUS.register(new com.hfr.clowder.ClaimTerrainService());
		FMLCommonHandler.instance().bus().register(com.hfr.world.earth.network.XFEarthHeightSync.events());
		CraftingManager.mainRegistry();
        cpw.mods.fml.common.registry.EntityRegistry.registerModEntity(com.hfr.entity.EntityFarmer.class, "entity_hfr_slave", 22, this, 80, 3, true);
        cpw.mods.fml.common.registry.EntityRegistry.registerModEntity(com.hfr.entity.EntityFactionBuilder.class, "entity_hfr_faction_builder", 23, this, 80, 3, true);
        cpw.mods.fml.common.registry.EntityRegistry.registerModEntity(com.hfr.entity.EntityFactionMiner.class, "entity_hfr_faction_miner", 27, this, 80, 3, true);
		proxy.registerRenderInfo();

		MainRegistry.loadCustomDrops();
		com.hfr.tdm.AdminEditorLifecycle adminEditorLifecycle = new com.hfr.tdm.AdminEditorLifecycle();
        com.hfr.daily.DailyRewardService dailyRewards = new com.hfr.daily.DailyRewardService();
        MinecraftForge.EVENT_BUS.register(dailyRewards);
        FMLCommonHandler.instance().bus().register(dailyRewards);
        com.hfr.rewards.ChatRewardService chatRewards = new com.hfr.rewards.ChatRewardService();
        MinecraftForge.EVENT_BUS.register(chatRewards);
        FMLCommonHandler.instance().bus().register(chatRewards);
		MinecraftForge.EVENT_BUS.register(adminEditorLifecycle);
		FMLCommonHandler.instance().bus().register(adminEditorLifecycle);
		if(XFConfig.enableTDM) {
			TDMManager.init();
			TDMHandler tdmHandler = new TDMHandler();
			MinecraftForge.EVENT_BUS.register(tdmHandler);
			FMLCommonHandler.instance().bus().register(tdmHandler);
		}

		//todone: make chat filter apply to faction names so we don't have edgelords putting swastikas and shit in their fac names
		//todone pick which name should be used for the faction name (so the censored one)
		sub.put(regexify("sex mod"), "funnies");
		sub.put(regexify("fuck"), "frick");
		sub.put(regexify("nigga"), "african american");
		sub.put(regexify("nigger"), "african american");
		sub.put(regexify("faggot"), "homosexual");
		sub.put(regexify("gay"), "homosexual");
		sub.put(regexify("fag"), "cigarette");
		sub.put(regexify("cunt"), "immanuel kant");
		sub.put(regexify("penis"), "pee pee");
		sub.put(regexify("cock"), "rooster");
		sub.put(regexify("dick"), "richard nixon");
		sub.put(regexify("shit"), "crap");
		sub.put(regexify("bitch"), "dog");
		sub.put(regexify("pussy"), "kitten");
		sub.put(regexify("bastard"), "illegitimate child");
		sub.put(regexify("goddamn"), "goshdarn");
		sub.put(regexify("damn"), "darn");
		sub.put(regexify("asshole"), "poohole");
		//sub.put(regexify("jew"), "יהודי");
		//the overton window shifted so this is like acceptable or something idk I mean it can just be a general term too so it's not derog
		sub.put(regexify("kike"), "יהודי");
		sub.put(regexify("kys"), "stop");
		sub.put(regexify("kill yourself"), "reconsider your life choices");
		sub.put(regexify("chink"), "eastern asian fellow");
		sub.put(regexify(" ass "), " butté ");
		sub.put(regexify("cracker"), "caucasian");
		sub.put(regexify("gook"), "vietnamese");
		sub.put(regexify("cuck"), "lad");
		sub.put(regexify("negro"), "melanine man");
		sub.put(regexify("negroid"), "african-like");
		sub.put(regexify("groid"), "OWO :3 haiiiii you like boys don't you :3");
		sub.put(regexify(" coon "), "overweight");
		//weirdly just decides to like replace unrelated shit
		sub.put(regexify("penis"), "junk");
		//sub.put(regexify("twat"), "天安门广场大屠杀");
		//sub.put(regexify("idiot"), "傻子");
		//unicode errors
		sub.put(regexify("whore"), "hussy");
		sub.put(regexify("pornography"), "naughty pics");
		sub.put(regexify("porn"), "cartoons");
		sub.put(regexify("hentai"), "naughty cartoons");
		sub.put(regexify("porch monkey"), "owo im a furry uwu");
		sub.put(regexify("woke"), "furry");
		sub.put(regexify("moon cricket"), "I own a fursuit");
		sub.put(regexify("50% 13%"), "*wags tail cutely OWO :3");
		sub.put(regexify("13% 50%"), "*wags tail cutely OWO :3");
		sub.put(regexify("13 50"), "*wags tail cutely OWO :3");
		sub.put(regexify("1350"), "*wags tail cutely OWO :3");
		sub.put(regexify("melon muncher"), "uwu x3");
		sub.put(regexify("beaner"), "I like beans :3");
		sub.put(regexify("sigma"), "fur suiter");
		sub.put(regexify("ragex chan"), "I'm a furry uwu");
		sub.put(regexify("gator bait"), "my fursona is an alligator :3");
		sub.put(regexify("be my girlfriend"), "I'm a lonely middle aged man");
		sub.put(regexify("rape"), "haiiii :3");
		sub.put(regexify("soylent"), "chad sauce");
		sub.put(regexify("xenobyte"), "I'm a furry uwu");
		sub.put(regexify("xradar"), "xenofactions");
		//the psyop is working
		sub.put(regexify("blackie"), "YES KING");
		sub.put(regexify("cotton picker"), "I'm from rust");
		sub.put(regexify("jiggabo"), "OWO maxxing rn");
		sub.put(regexify("ragex chan"), "I'm a furry uwu");
		sub.put(regexify("crow"), "I probably said something not nice >< uwu x3");
		sub.put(regexify("jungle bunny"), "I like bunny girls x3 rawr");
		sub.put(regexify("kaffir"), "I dont like yuuus x3");
		sub.put(regexify("mayonnaise monkey"), "I'm a furry x3");
		sub.put(regexify("mayo monkey"), "I wear thigh highs to bed :33");
		sub.put(regexify("niglet"), "UwU x3 haiiiiii");
		sub.put(regexify("niggerlet"), "UwU x3 haiiiiii");
		sub.put(regexify("nog"), "I'm a furry x333");
		sub.put(regexify("shitskin"), "What in the whiskers?! :3");
		sub.put(regexify("spade"), "What the actual fur?! OWO :3");
		sub.put(regexify("spook "), "OmO Pawsitively ridiculous! :3");
		sub.put(regexify("tar baby"), "Fuzz nuggets! :33");
		sub.put(regexify("my bad gang"), "Fluff me sideways!");
		sub.put(regexify("mb gng"), "Fluff me sideways!");
		sub.put(regexify("uncle tom"), "lawyer :3");
		sub.put(regexify("wigger"), "Paw-sitively shocking! OwO");
		sub.put(regexify("white trash"), "UwU have u heard of the boykisser meme x3");
		sub.put(regexify("pajeet"), "Fwend :33");
		sub.put(regexify("gang gang"), "guys how do I clean my fursuits ><");
		sub.put(regexify("raghead"), "Yiff maxxing rn x3 UwU");
		sub.put(regexify("buck "), "you like boyys dont you x333");
		sub.put(regexify("cracka"), "Fur-tastic! :3");
		sub.put(regexify("trenbolone acetate"), "turkesteron and go2 max");
		//sub.put(regexify("`1989`"), "please respect our chinese partners uwu");
		sub.put(regexify("tiannamen square massacre"), "never happened");
		sub.put(regexify(" mao "), "mandela");
		sub.put(regexify("hotdog"), "please respect our north korean partners");
		sub.put(regexify("janny"), "terrorist");
		sub.put(regexify("jannie"), "terrorist");
		sub.put(regexify("janitors"), "terrorist");
		sub.put(regexify("卍"), "furry");
		sub.put(regexify("卐"), "extreme furry");
		sub.put(regexify("tranny"), "yiff enjoyer");
		sub.put(regexify("trannies"), "yiff enjoyers");
		sub.put(regexify("troon"), "fwen");
		sub.put(regexify("trannyfag"), "furry");
		sub.put(regexify("trannyfaggot"), "furry");
		sub.put(regexify("intcraft"), "bad serb");
		//this '' doesn't actually work
		sub.put(regexify(" tap "), "turkish coding");
		sub.put(regexify("tyrants and plebians"), "turkish coding");
		sub.put(regexify("monorisu"), "turkish coding");
		sub.put(regexify("mono"), "turkish coding");
		sub.put(regexify("labjac"), "turkish coding");
		sub.put(regexify("bobcat"), "fat german");
		sub.put(regexify("atom_14_a"), "fat hispanic");
		sub.put(regexify("dr nostalgia"), "fat bronie");
		sub.put(regexify("drnostalgia"), "fat bronie");
		sub.put(regexify("hbm"), "hated by many");
		sub.put(regexify("duck "), "annoying redditor talk");
		sub.put(regexify("chungus"), "annoying redditor talk");
		sub.put(regexify("wholesome"), "annoying redditor talk");
		sub.put(regexify("job"), "pawj*b");
		sub.put(regexify("fuck dog"), "I have a bestiality fetish and I can't stop projecting");
		sub.put(regexify(" vic "), "model stealer");
		sub.put(regexify("vic's modern warfare"), "complete garbage");
		sub.put(regexify("event "), "iteration");
		sub.put(regexify("skibidi"), "iq reduction");
		sub.put(regexify("minors"), "./ban @whoeversaid this :3");
		//sub.put(regexify("'cp'"), "./ban @whoeversaid this :3");
		//I'm gonna be honest, airpiglet should just be banned.
		sub.put(regexify("children"), "I'm epstein");
		sub.put(regexify("kid "), "I'm epstein");
		sub.put(regexify(" kids "), "I'm epstein");
		sub.put(regexify("f4g"), "I'm in the closet");
		sub.put(regexify("trçnny"), "Obsessed");
		sub.put(regexify("kafir"), "Hey man that's like not nice or something");
		sub.put(regexify("hitler"), "coal mines");
		sub.put(regexify("piggie"), "large person");
		sub.put(regexify("code"), "I'm probably crying about something");
		sub.put(regexify("unmute "), "I'm definitely crying about something");
		sub.put(regexify("goy"), "clueless");
		sub.put(regexify("flans"), "-4 tps");
		//sub.put(regexify("mute "), "I'm probably bitching about something");
		//will conflict with /c mute...?
		sub.put(regexify("white power"), "I'm probably american and fat");
		sub.put(regexify("aryan"), "indian");
		sub.put(regexify("ngger"), "african american");
		//sub.put(regexify("server"), "I'm complaining");
		//possibly
		sub.put(regexify("nig "), "african american");
		sub.put(regexify("tr@ns"), "yiff enjoyer");
		sub.put(regexify("slave"), "helper");
		sub.put(regexify("n¡gger"), "african american");
		sub.put(regexify("rapist"), "someone who really likes rap music");
		sub.put(regexify("chatgpt"), "I can't code");
		sub.put(regexify("hoi4"), "not this server");
		sub.put(regexify("gaped"), "I am strange");
		sub.put(regexify("gaping"), "I am very strange");
		sub.put(regexify("jeet"), "indian");
		sub.put(regexify("impregnation"), "why am I talking about this on a minecraft server");
		sub.put(regexify("doxxed"), "I want my ip leaked");
		sub.put(regexify("kigger"), "african american");
		sub.put(regexify("klu klux"), "I'm a furry");
		sub.put(regexify("ai code"), "I can't code");
		sub.put(regexify("negar"), "african american");
		sub.put(regexify("ngger"), "african american");
		sub.put(regexify("doxxbin"), "I'm furry");
		sub.put(regexify("doxbin"), "I'm furry");
		sub.put(regexify("neegar"), "I'm furry");
		sub.put(regexify("peepee"), "I have a problem");
		sub.put(regexify("little girls"), "I'm epstein");
		sub.put(regexify("breed you"), "I'm furry");
		sub.put(regexify("white rights"), "I'm furry");
		sub.put(regexify("azov"), "larp");

		//1984

		NetworkRegistry.INSTANCE.registerGuiHandler(instance, new GUIHandler());

        GameRegistry.registerTileEntity(TileEntityMachineBuilder.class, "tileentity_hfr_builder");
		GameRegistry.registerTileEntity(TileEntityDebug.class, "tileentity_hfr_devon_truck");
		GameRegistry.registerTileEntity(TileEntityMachineNet.class, "tileentity_hfr_net");
		GameRegistry.registerTileEntity(TileEntityMachineUni.class, "tileentity_hfr_university");
		GameRegistry.registerTileEntity(TileEntityFlag.class, "tileentity_hfr_flag");
		GameRegistry.registerTileEntity(TileEntityCap.class, "tileentity_hfr_cap");
		GameRegistry.registerTileEntity(TileEntityFlagBig.class, "tileentity_hfr_flag_big");
		GameRegistry.registerTileEntity(com.hfr.tileentity.clowder.TileEntityFactionDecoration.class, "tileentity_hfr_faction_decoration");
		GameRegistry.registerTileEntity(TileEntityProp.class, "tileentity_hfr_prop");
		GameRegistry.registerTileEntity(TileEntityStatue.class, "tileentity_hfr_statue");
		GameRegistry.registerTileEntity(TileEntityMachineGrainmill.class, "tileentity_hfr_mill");
        GameRegistry.registerTileEntity(com.hfr.tileentity.machine.TileEntityGrainmillInputProxy.class, "tileentity_hfr_mill_input_proxy");
        GameRegistry.registerTileEntity(com.hfr.blocks.machine.PlayerTrader.Tile.class, "tileentity_hfr_player_trader");
		GameRegistry.registerTileEntity(TileEntityBerlin.class, "tileentity_hfr_berlin");
		GameRegistry.registerTileEntity(TileEntityMachineFactory.class, "tileentity_hfr_factory");
		GameRegistry.registerTileEntity(TileEntityMachineTemple.class, "tileentity_hfr_temple");
		GameRegistry.registerTileEntity(TileEntityConquerer.class, "tileentity_hfr_conquest_flag");
		GameRegistry.registerTileEntity(TileEntityOfficerChest.class, "tileentity_hfr_chest");
		GameRegistry.registerTileEntity(TileEntityMarket.class, "tileentity_hfr_shop");

		BlockWallImage.instance = new BlockWallImage();
		GameRegistry.registerTileEntity(TileEntityWallImage.class, "tile_wall_image");
        GameRegistry.registerTileEntity(com.hfr.tileentity.TileEntityFactionLeaderboard.class, "tile_faction_leaderboard");

		//e
		//GameRegistry.registerBlock(BlockWallImage.instance, "wall_image_block");
		//done somewhere else

		int id = 0;

		CommonEventHandler handler = new CommonEventHandler();
		ClowderEvents clowder = new ClowderEvents();
		com.hfr.journeymap.ClaimOverlaySync claimOverlaySync = new com.hfr.journeymap.ClaimOverlaySync();
		XFDynmapIntegration dynmap = new XFDynmapIntegration();

		FMLCommonHandler.instance().bus().register(handler);
		FMLCommonHandler.instance().bus().register(clowder);
		FMLCommonHandler.instance().bus().register(claimOverlaySync);
		FMLCommonHandler.instance().bus().register(new StoneDropSnapshotSync());
		FMLCommonHandler.instance().bus().register(new MachineDisplaySnapshotSync());
		FMLCommonHandler.instance().bus().register(new PlayerActivityTracker());
		if(XFConfig.enableDynmapIntegration) {
			FMLCommonHandler.instance().bus().register(dynmap);
			MinecraftForge.EVENT_BUS.register(dynmap);
		}
		//FMLCommonHandler.instance().bus().register(pon4);
		MinecraftForge.EVENT_BUS.register(handler);
		MinecraftForge.EVENT_BUS.register(clowder);
        MinecraftForge.EVENT_BUS.register(new com.hfr.ender.EnderChestInteraction());
		//MinecraftForge.EVENT_BUS.register(pon4);

		//GameRegistry.registerWorldGenerator(worldGenMoon, 0);
		//DimensionManager.registerProviderType(15, WorldProviderMoon.class, false);
	    //DimensionManager.registerDimension(15, 15);
	}

	private String regexify(String string) {

		String neue = "(?i)";
		boolean first = true;

		for(char c : string.toCharArray()) {

			if(!first)
				neue += "[ \\.\\-_@$!#:;&\\(\\)\\-¶,\\.\\?+×÷=%/*€£￦¥¿¡^\\[\\]<>~`§μ¬Г´·\\{\\}©|¤Ωθฯ]{0,3}";

			first = false;

			if(c == 'a') {
				neue += "[aäáàâåǎ]";
			} else if(c == 'c') {
				neue += "[cĉčćç]";
			} else if(c == 'e') {
				neue += "[eëéèêě]";
			} else if(c == 'i') {
				neue += "[iịǐíìîï]";
			} else if(c == 'j') {
				neue += "[jĵǰ]";
			} else if(c == 'm') {
				neue += "[mṃ]";
			} else if(c == 'n') {
				neue += "[nňṇńņ]";
			} else if(c == 'o') {
				neue += "[oöóòôǒọ]";
			} else if(c == 's') {
				neue += "[sŝšṣśşŝ]";
			} else if(c == 'u') {
				neue += "[uüúùûůǔụ]";
			} else {
				neue += c;
			}
		}

		return neue;
	}

	@EventHandler
	public static void load(FMLInitializationEvent event)
	{

	}

	@EventHandler
	public static void PostLoad(FMLPostInitializationEvent event)
	{
		//in postload, long after all blocks have been registered, the buffered config is being evaluated and processed.

		com.hfr.ender.EnderChestRestrictions.validateRegistry();
		HbmStoneDropIntegration.seedDefaultsIfAvailable(stoneDropLoadState);
		XFGuideBook.register();
		StoneDropSnapshotSync.sendToAll();

	}

	public class CustomDrop {
		public ItemStack stack;
		public double chance;

		public CustomDrop(ItemStack stack, double chance) {
			this.stack = stack;
			this.chance = chance;
		}
	}

	@Mod.EventHandler
	public void serverStarting(FMLServerStartingEvent event) {
        PlayerActivityTracker.clear();
        com.hfr.rewards.ChatRewardService.clear();
        com.hfr.rewards.ChatRewardConfig.load();
        event.registerServerCommand(new com.hfr.command.CommandXRewards());
        com.hfr.daily.DailyRewardService.clear();
        com.hfr.daily.DailyRewardConfig.load();
        event.registerServerCommand(new com.hfr.command.CommandXDaily());
		XFDynmapIntegration.resetServerSession();
		com.hfr.clowder.PlayerIdentityService.initialize(MinecraftServer.getServer());
		com.hfr.clowder.flag.CustomFlagService.startServerSession(MinecraftServer.getServer());
		// Main faction system commands
		event.registerServerCommand(new CommandClowder());
		event.registerServerCommand(new CommandClowderChat());
		event.registerServerCommand(new CommandClowderAdmin());

		// Other utility commands
		event.registerServerCommand(new CommandXShop());
		event.registerServerCommand(new CommandXPlayer());
		event.registerServerCommand(new CommandXDebug());
        event.registerServerCommand(new com.hfr.command.CommandXMinerDebug());

		event.registerServerCommand(new CommandStoneDrop());
		MuteManager.init();
		if(XFConfig.enableTDM)
			TDMKitManager.init();
		if(XFConfig.enableTDM)
			com.hfr.tdm.TDMPurchasableManager.init();
		IgnoreManager.init();
		event.registerServerCommand(new CommandMute());
		event.registerServerCommand(new CommandUnmute());
		event.registerServerCommand(new CommandIgnore());
		event.registerServerCommand(new CommandXmap());
		event.registerServerCommand(new CommandXFlags());
		event.registerServerCommand(new CommandXMulti());
		event.registerServerCommand(new CommandXWallArt());
		event.registerServerCommand(new CommandInvSee());
		if(XFConfig.enableTDM)
			event.registerServerCommand(new CommandTDM());
		MarketData.loadMarketData();

		//and now we just pray that the market data does not cope over serverside

		//I'm flying too high
	}

	@Mod.EventHandler
	public void validateEarthStorageBeforeWorldLoad(FMLServerAboutToStartEvent event) throws java.io.IOException {
		com.hfr.world.earth.storage.XFEarthStorageIdentity.preflight(event.getServer());
	}

	@Mod.EventHandler
	public void serverStopping(FMLServerStoppingEvent event) {
        PlayerActivityTracker.clear();
        XFAchievements.flushActions();
        com.hfr.rewards.ChatRewardService.clear();
        com.hfr.daily.DailyRewardService.clear();
        com.hfr.clowder.ClaimTerrainService.clear();
		com.hfr.world.earth.geo.XFEarthGeoImportJob.shutdown();
		com.hfr.world.earth.roads.XFEarthRoadImport.cancelAll();
		com.hfr.world.earth.roads.XFEarthRoadMap.clear();
		com.hfr.world.earth.roads.XFEarthWorkQueue.clear();
		com.hfr.world.earth.pregen.XFEarthPregenerator.shutdown();
		com.hfr.world.earth.XFEarthProfileLoader.clear();
		com.hfr.world.earth.network.XFEarthHeightSync.clear();
		com.hfr.clowder.flag.CustomFlagService.shutdown();
		XFDynmapIntegration.resetServerSession();
	}
	@Mod.EventHandler
	public void clearRoadStateAfterFinalSave(cpw.mods.fml.common.event.FMLServerStoppedEvent event) {
		com.hfr.world.earth.roads.XFEarthRoadState.clear();
	}

	//only one thing

	//@EventHandler
	//public void serverStarting(FMLServerStartingEvent event) {
	//	event.registerServerCommand(new CommandClowder());
	//	//command ore wand

	//	//custom stone drops
	//	event.registerServerCommand(new CommandStoneDrop());
	//	//MarketData.loadMarketData();
	//	//depricated schizophrenia and unhinged gpt pasting
	//	event.registerServerCommand(new CommandXShop());
	//}

	//@EventHandler
	//public void ServerLoad(FMLServerStartingEvent event)
	//{
	//	event.registerServerCommand(new CommandXPlayer());
	//	event.registerServerCommand(new CommandXDebug());

	//	event.registerServerCommand(new CommandClowder());
	//	event.registerServerCommand(new CommandClowderChat());
	//	event.registerServerCommand(new CommandClowderAdmin());
	//	//event.registerServerCommand(new CommandXShop());
	//	//event.loadMarketData()
	//	//MarketData.loadMarketData();
	//	//OfferPacket
	//	//OfferPacket
	//}

	public static List<ControlEntry> controlList = new ArrayList();
	public static List<PotionEntry> potionList = new ArrayList();
	public static List<ImmunityEntry> immunityList = new ArrayList();
	public static String[] twilightBuffer;

	public static boolean skeletonAIDS = false;
	public static float skeletonHIV = 2.5F;

	public static Configuration config;

	public void loadConfig(FMLPreInitializationEvent event)
	{
		if(logger == null)
			logger = event.getModLog();

		PacketDispatcher.registerPackets();

		config = new Configuration(event.getSuggestedConfigurationFile());
        com.hfr.rewards.ChatRewardConfig.configure(event.getModConfigurationDirectory());
        com.hfr.daily.DailyRewardConfig.configure(event.getModConfigurationDirectory());

		config.load();
		XFConfig.load(config);

        superFishrate = createConfigInt(config, XFConfig.CAT_LEGACY_MACHINES, "superFishrate", "Average amount of seconds for fish in oceans", 20);
        goodFishrate = createConfigInt(config, XFConfig.CAT_LEGACY_MACHINES, "goodFishrate", "Average amount of seconds for fish in rivers", 40);
        averageFishrate = createConfigInt(config, XFConfig.CAT_LEGACY_MACHINES, "averageFishrate", "Average amount of seconds for fish in most biomes", 60);
        crapFishrate = createConfigInt(config, XFConfig.CAT_LEGACY_MACHINES, "crapFishrate", "Average amount of seconds for fish in hills, deserts and savannas", 1000000);
        jamRate = createConfigInt(config, XFConfig.CAT_LEGACY_MACHINES, "jamRate", "Average amount of seconds for net to get jammed", 15 * 60);
        whaleChance = createConfigInt(config, XFConfig.CAT_LEGACY_MACHINES, "whaleChance", "Chance in percent of chatching whale meat", 5);

        uniRate = createConfigInt(config, XFConfig.CAT_LEGACY_MACHINES, "uniRate", "Average amount of seconds for uni to generate research", 60 * 3);
        uniJamRate = createConfigInt(config, XFConfig.CAT_LEGACY_MACHINES, "uniJamRate", "Average amount of seconds for uni to get jammed", 60 * 15);

        temple = createConfigInt(config, XFConfig.CAT_LEGACY_MACHINES, "templeRate", "Average amount of seconds for temple to generate scrolls", 10 * 60 * 3);

        factoryRate = createConfigInt(config, XFConfig.CAT_LEGACY_MACHINES, "factoryRate", "Average amount of seconds for factory to generate cogs", 60 * 3);

        factoryJamRate = createConfigInt(config, XFConfig.CAT_LEGACY_MACHINES, "factoryJamRate", "Average amount of seconds for actory to get jammed", 60 * 15);

        Property pAids = config.get(XFConfig.CAT_LEGACY_WORLD, "explosiveArrows", false).setDefaultValue(false);
        pAids.comment = "Whether or not skeleton arrows should be explosive";
        skeletonAIDS = pAids.getBoolean();

        Property pHiv = config.get(XFConfig.CAT_LEGACY_WORLD, "arrowStrength", 1.5).setDefaultValue(1.5);
        pHiv.comment = "How powerful exploding arrows are";
        skeletonHIV = (float)pHiv.getDouble();

        /////////////////////////////////////////////////////////////////////////
        Property entcontrol = config.get(XFConfig.CAT_LEGACY_WORLD, "entityRestrictions", new String[] { "" });
        entcontrol.comment = "What entities should be regulated (syntax: [entity name]:[new spawn chance])";
        String[] ec = entcontrol.getStringList();

        for(String val : ec) {

        	try {

	        	String s = val.split(":")[0];
	        	int chance = Integer.valueOf(val.split(":")[1]);

	        	controlList.add(new ControlEntry(s, chance));

        	} catch(Exception ex) {
        		logger.error("Invalid config entry '" + val + "'");
        	}
        }
        /////////////////////////////////////////////////////////////////////////
        Property entfx = config.get(XFConfig.CAT_LEGACY_WORLD, "entityEffects", new String[] { "" });
        entfx.comment = "What entities should receive effects (syntax: [entity name]:[potion id]:[level, 0=I, 1=II, 2=III, etc.]:[duration])";
        String[] fx = entfx.getStringList();

        for(String val : fx) {

        	try {

	        	String s = val.split(":")[0];
	        	int id = Integer.valueOf(val.split(":")[1]);
	        	int level = Integer.valueOf(val.split(":")[2]);
	        	int dura = Integer.valueOf(val.split(":")[3]);

	        	potionList.add(new PotionEntry(s, id, dura, level));

        	} catch(Exception ex) {
        		logger.error("Invalid config entry '" + val + "'");
        	}
        }
        /////////////////////////////////////////////////////////////////////////
        Property entimm = config.get(XFConfig.CAT_LEGACY_WORLD, "entityImmunity", new String[] { "" });
        entimm.comment = "What entities should receive damage immunity (syntax: [entity name]:[damage source name])";
        String[] imm = entimm.getStringList();

        for(String val : imm) {

        	try {

	        	String s = val.split(":")[0];
	        	String d = val.split(":")[1];

	        	immunityList.add(new ImmunityEntry(s, d));

        	} catch(Exception ex) {
        		logger.error("Invalid config entry '" + val + "'");
        	}
        }
        /////////////////////////////////////////////////////////////////////////
        String[] flags = createConfigStringList(config, XFConfig.CAT_CLAIMS, "flags", "[name of the flag]:[whether it's shown in the listing]:[whether it has a tintable base]:[whether it has a static overlay]",
				new String[] {
						"AFR-1:true:true:true",
						"AFR-2EGY:true:true:true",
						"AFR-3PYR:true:true:true",
						"AFR-4:true:true:true",
						"AFR-5:true:true:true",
						"AFR-6AFRUN:true:true:true",
						"AFR-7ZA:true:true:true",
						"AFR_southafrica1994:true:true:true",
						"AME-10HUR:true:true:true",
						"AME-11ARG:true:true:true",
						"AME-12TEX:true:true:true",
						"AME-1:true:true:true",
						"AME-2CUBA:true:true:true",
						"AME-3inca:true:true:true",
						"AME-4VEN:true:true:true",
						"AME-5BR:true:true:true",
						"AME-6CALI:true:true:true",
						"AME-7CALI2:true:true:true",
						"AME-8MEX:true:true:true",
						"AME-9IRO:true:true:true",
						"AME-NAT1:true:true:true",
						"AME-NAT2:true:true:true",
						"AME-NAT3:true:true:true",
						"AME-NAT4:true:true:true",
						"AME-NAT6:true:true:true",
						"AME-NAT7:true:true:true",
						"ARAB-2:true:true:true",
						"ARAB-3:true:true:true",
						"ARAB-egypt:true:true:true",
						"ARAB-syria:true:true:true",
						"ARAB-:true:true:true",
						"ASI-10:true:true:true",
						"ASI-9:true:true:true",
						"ASI-malaysia:true:true:true",
						"ASI-modernindonesia:true:true:true",
						"ASI-modernphilippines:true:true:true",
						"ASI-modernsingapore:true:true:true",
						"ASI-modernthailand:true:true:true",
						"ASI-nepal:true:true:true",
						"ASI-nightdragon:true:true:true",
						"ASI-pakistan:true:true:true",
						"ASI-thailand1:true:true:true",
						"ASI-thailand2:true:true:true",
						"AUS-aborigineflag:true:true:true",
						"AUS-modernaustralia:true:true:true",
						"BEL-commie:true:true:true",
						"BEL-modernbelgium:true:true:true",
						"BHU-1:true:true:true",
						"CAN-CAN1:true:true:true",
						"CAN-CAN2:true:true:true",
						"CAN-QC:true:true:true",
						"CHN-1:true:true:true",
						"CHN-2:true:true:true",
						"CHN-fengtian:true:true:true",
						"CHN-handynasty:true:true:true",
						"CHN-macau:true:true:true",
						"CHN-mingdynasty:true:true:true",
						"CHN-modernhongkong:true:true:true",
						"CHN-prcmodern:true:true:true",
						"CHN-qingdynasty1:true:true:true",
						"CHN-qingdynasty2:true:true:true",
						"CHN-republicofchinaold:true:true:true",
						"CHN-songdynasty:true:true:true",
						"CHNgdynasty:true:true:true",
						"CHNTAI-roc:true:true:true",
						"CHNTIB-tibet:true:true:true",
						"ESP-1700:true:true:true",
						"ESP-modernspain:true:true:true",
						"EUR-altgreece:true:true:true",
						"EUR-altsweden:true:true:true",
						"EUR-austriahung:true:true:true",
						"EUR-austria:true:true:true",
						"EUR-bulgaria:true:true:true",
						"EUR-czech:true:true:true",
						"EUR-dutch1:true:true:true",
						"EUR-dutch2:true:true:true",
						"EUR-finland:true:true:true",
						"EUR-hapsburg:true:true:true",
						"EUR-holysee:true:true:true",
						"EUR-ireland:true:true:true",
						"EUR-italybad:true:true:true",
						"EUR-italy:true:true:true",
						"EUR-moderngreece:true:true:true",
						"EUR-modernserbia:true:true:true",
						"EUR-modernturkey:true:true:true",
						"EUR-poland:true:true:true",
						"EUR-roman:true:true:true",
						"EUR-SWE:true:true:true",
						"EUR-swiss:true:true:true",
						"EU:true:true:true",
						"femboyhooters:false:true:false",
						"FRA-1700:true:true:true",
						"FRA-fascist:true:true:true",
						"FRA-modernfrance:true:true:true",
						"gawr:false:true:false",
						"gaypride:true:true:true",
						"GER-communist:true:true:true",
						"GER-empire:true:true:true",
						"GER-fascist:true:true:true",
						"GER-hannover:true:true:true",
						"GER-moderngermany:true:true:true",
						"gigacat:false:true:false",
						"gigachad:false:true:false",
						"IND-1:true:true:true",
						"IND-modernindia:true:true:true",
						"islammoon:true:true:true",
						"ISR-modernisrael:true:true:true",
						"JAP-empire1:true:true:true",
						"JAP-empire2:true:true:true",
						"JAP-hojo:true:true:true",
						"JAP-modernjapan:true:true:true",
						"JAP-oda:true:true:true",
						"JAP-otomo:true:true:true",
						"JAP-risingmoon:true:true:true",
						"JAP-takeda:true:true:true",
						"KOR-1888:true:true:true",
						"KOR-3:true:true:true",
						"KOR-banner:true:true:true",
						"KOR-joseon:true:true:true",
						"KOR-modernnorthkorea:true:true:true",
						"KOR-modernsouthkorea:true:true:true",
						"KOR-unified1:true:true:true",
						"KUR-kurdsiraq:true:true:true",
						"KUR-kurdssyria:true:true:true",
						"MONG-goldenhordemongols:true:true:true",
						"MONG-modernmongolia:true:true:true",
						"nato:true:true:true",
						"nevergoon:false:true:false",
						"NOR-modernnorway:true:true:true",
						"northpacifictreatyorganization:true:true:true",
						"NZ-betternz:true:true:true",
						"ohio:false:true:false",
						"PER-iran1:true:true:true",
						"PER-moderniran:true:true:true",
						"pogs:false:true:false",
						"POR-1700:true:true:true",
						"POR-modernportugal:true:true:true",
						"redcross:true:true:true",
						"RUS-communist:true:true:true",
						"RUS-modernrussia:true:true:true",
						"scatcat:false:true:false",
						"spam:false:true:false",
						"swagminion:false:true:false",
						"TAI-moderntaiwanroc:true:true:true",
						"UK-1700:true:true:true",
						"UK-colonydefault:true:true:true",
						"UK-england:true:true:true",
						"UK-modernuk:true:true:true",
						"UK-scotland:true:true:true",
						"UK-wales:true:true:true",
						"UKJAP-britishjapan:true:true:true",
						"UKR-modernukraine:true:true:true",
						"UNFLAG:true:true:true",
						"USA-13:true:true:true",
						"USA-48:true:true:true",
						"USA-50:true:true:true",
						"usa:true:true:true",
						"VIET-capitalist:true:true:true",
						"VIET-communist:true:true:true",
						"warsawpact:true:true:true",
						"youngboy:false:true:false",
						"YUG-communist:true:true:true",
						"YUG-kingdom:true:true:true",
						"YUG-yugoslavia:true:true:true"
				});

        for(String val : flags) {

        	try {
	        	String fname = val.split(":")[0];
	        	boolean vis = Boolean.parseBoolean(val.split(":")[1]);
	        	boolean base = Boolean.parseBoolean(val.split(":")[2]);
	        	boolean over = Boolean.parseBoolean(val.split(":")[3]);

	        	EnumHelper.addEnum(ClowderFlag.class, fname, new Class[] { String.class, boolean.class, boolean.class, boolean.class }, new Object[] {fname, vis, base, over} );
	        XFLog.debug("Successfully added flag " + fname);
        	} catch(Exception ex) {
        		logger.error("Invalid config entry '" + val + "'");
        	}
        }

    	EnumHelper.addEnum(ClowderFlag.class, "GETTY", new Class[] { String.class, boolean.class }, new Object[] {"getty", false} );
    	EnumHelper.addEnum(ClowderFlag.class, "COMRADES", new Class[] { String.class, boolean.class }, new Object[] {"comrades", false} );

        warpCost = createConfigInt(config, XFConfig.CAT_CLAIMS, "warpCost", "How much prestige a warp point costs to create", 125);
        territoryDelay = createConfigInt(config, XFConfig.CAT_CLAIMS, "territoryDelay", "How many ticks inbetween territory validation operations", 5);
        territoryAmount = createConfigInt(config, XFConfig.CAT_CLAIMS, "territoryAmount", "How many chunks are checked each operation", 50);
        prestigeDelay = createConfigInt(config, XFConfig.CAT_CLAIMS, "prestigeDelay", "How many ticks inbetween prestige updates (1h per default)", 60 * 60 * 20);

        freeRaid = createConfigBool(config, XFConfig.CAT_CLAIMS, "freeRaid", "Enabling this will cause all raidability checks to be ignored, everyone will alway be raidable", true);

        Property prop_chatfilter = config.get(XFConfig.CAT_LEGACY_CHAT, "enableChatFilter", true);
        prop_chatfilter.comment = "Enables server public chat filtering and client display filtering";
        prop_chatfilter.setRequiresMcRestart(false);
        prop_chatfilter.setRequiresWorldRestart(false);
        chatfilter = prop_chatfilter.getBoolean(true);
		//sometimes it doesn't like being turned off for some reason (I think)

		//there shouldn't be a cap lol wtf??

        /////////////////////////////////////////////////////////////////////////

        caveCap = createConfigInt(config, XFConfig.CAT_LEGACY_WORLD, "caveCap_New", "Sets the maximum Y-coord where cave sickness kick in", -20);

        border = createConfigBool(config, XFConfig.CAT_LEGACY_WORLD, "enableBorder", "Toggles the world border", false);

        borderPosX = createConfigInt(config, XFConfig.CAT_LEGACY_WORLD, "borderPosX", "World border in the positive X direction", 10000);
        borderNegX = createConfigInt(config, XFConfig.CAT_LEGACY_WORLD, "borderNegX", "World border in the negative X direction", -10000);
        borderPosZ = createConfigInt(config, XFConfig.CAT_LEGACY_WORLD, "borderPosZ", "World border in the positive Z direction", 10000);
        borderNegZ = createConfigInt(config, XFConfig.CAT_LEGACY_WORLD, "borderNegZ", "World border in the negative Z direction", -10000);

        // Stable names used by the Earth boundary runtime layer. The legacy endpoint
        // properties remain authoritative so existing server configurations are unchanged.
        XFConfig.earthBoundaryEnabled = border;
        XFConfig.earthBoundaryCenterX = borderNegX + (borderPosX - borderNegX) / 2;
        XFConfig.earthBoundaryCenterZ = borderNegZ + (borderPosZ - borderNegZ) / 2;
        XFConfig.earthBoundaryRadiusX = Math.max(borderPosX - XFConfig.earthBoundaryCenterX, XFConfig.earthBoundaryCenterX - borderNegX);
        XFConfig.earthBoundaryRadiusZ = Math.max(borderPosZ - XFConfig.earthBoundaryCenterZ, XFConfig.earthBoundaryCenterZ - borderNegZ);

        coalChance = createConfigDouble(config, XFConfig.CAT_LEGACY_WORLD, "coalChance", "Chance for coal when stone is mined", 0.04);
        ironChance = createConfigDouble(config, XFConfig.CAT_LEGACY_WORLD, "ironChance", "Chance for iron when stone is mined", 0.05);
        goldChance = createConfigDouble(config, XFConfig.CAT_LEGACY_WORLD, "goldChance", "Chance for gold when stone is mined", 0.01);

        hfr_powerlog = createConfigBool(config, XFConfig.CAT_LEGACY_DEBUG, "hfrExtendedLogging", "Enables the HFR powerlogging(tm) feature which prints a shitton of debugging information", false);

        config.save();

	}

	private static int createConfigInt(Configuration config, String category, String name, String comment, int def) {

        Property prop = config.get(category, name, def);
        prop.comment = comment;
        return prop.getInt();
	}

	private static boolean createConfigBool(Configuration config, String category, String name, String comment, boolean def) {

        Property prop = config.get(category, name, def);
        prop.comment = comment;
        return prop.getBoolean();
	}

	private static double createConfigDouble(Configuration config, String category, String name, String comment, double def) {

        Property prop = config.get(category, name, def);
        prop.comment = comment;
        return prop.getDouble();
	}

	private static String[] createConfigStringList(Configuration config, String category, String name, String comment, String[] def) {

        Property prop = config.get(category, name, def);
        prop.comment = comment;
        return prop.getStringList();
	}

	public static class ControlEntry {

		int chance;
		String entity;

		public ControlEntry(String e, int chance) {
			this.entity = e;
			this.chance = chance;
		}

		public static int getEntry(Entity e) {

			for(ControlEntry ent : controlList) {
				if(ent.entity.equals(EntityList.getEntityString(e)))
					return ent.chance;
			}

			return -1;
		}
	}

	public static class PotionEntry {

		int potion;
		int duration;
		int amplifier;
		String entity;

		public PotionEntry(String e, int potion, int duration, int amplifier) {
			this.entity = e;
			this.potion = potion;
			this.duration = duration;
			this.amplifier = amplifier;
		}

		public static int[] getEntry(Entity e) {

			for(PotionEntry ent : potionList) {
				if(ent.entity.equals(EntityList.getEntityString(e)))
					return new int[] {ent.potion, ent.duration, ent.amplifier};
			}

			return null;
		}
	}

	public static class ImmunityEntry {

		String entity;
		String damage;

		public ImmunityEntry(String e, String atk) {
			this.entity = e;
			this.damage = atk;
		}

		public static List<String> getEntry(Entity e) {

			List<String> list = new ArrayList();

			for(ImmunityEntry ent : immunityList) {
				if(ent.entity.equals(EntityList.getEntityString(e)))
					list.add(ent.damage);
			}

			return list;
		}
	}
}
