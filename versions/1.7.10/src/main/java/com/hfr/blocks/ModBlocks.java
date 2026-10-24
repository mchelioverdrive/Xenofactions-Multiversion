package com.hfr.blocks;

import com.hfr.blocks.clowder.*;
import com.hfr.blocks.machine.*;
import com.hfr.blocks.props.*;
import com.hfr.items.*;
import com.hfr.lib.RefStrings;
import com.hfr.main.MainRegistry;

import cpw.mods.fml.common.registry.GameRegistry;
import net.minecraft.block.Block;
import net.minecraft.block.material.Material;

import static net.minecraft.block.Block.soundTypeWood;

public class ModBlocks {
	
	public static void mainRegistry()
	{
		initializeBlock();
		registerBlock();
	}

	public static Block builder;
    public static final int guiID_builder = 14, guiID_builder_npc = 41;
	public static Block rice;

	public static Block uni_foundation;
	public static Block asphalt;

	public static Block machine_net;
	public static final int guiID_net = 10;
	public static Block machine_market;
    public static Block player_trader;
    public static Block faction_market;
	public static final int guiID_market = 11;

	public static Block machine_uni;

	public static final int guiID_uni = 15;

	public static Block clowder_flag;

	public static Block clowder_cap;

	public static Block clowder_flag_big;
	public static Block faction_banner;
	public static Block large_faction_flag;
	public static final int guiID_flag_big = 19;
	public static Block machine_grainmill;
	public static final int guiID_grainmill = 20;

	public static Block machine_factory;
	public static final int guiID_factory = 25;

	public static Block machine_temple;
	public static final int guiID_temple = 30;
	public static Block clowder_conquerer;
	public static Block officer_chest;
	public static final int guiID_chest = 36;

	public static Block barricade;

	public static Block wallImageBlock;
    public static Block factionLeaderboard;

	public static Block hesco_block;
	public static Block palisade;
	public static Block stone_wall;
	public static Block brick_wall;
	public static Block great_wall;
	public static Block berlin_wall;

	public static Block tp_tent;
	public static Block statue;

	public static Block debug;

	public static Block.SoundType soundTypeConcrete = new ModSoundType("concrete", 0.25F, 1.0F)
    {
        public String func_150496_b()
        {
            return Block.soundTypeStone.func_150496_b();
        }
    };
	public static Block.SoundType soundTypeMetal = new ModSoundType("metal", 0.5F, 1.0F)
    {
        public String func_150496_b()
        {
            return Block.soundTypeMetal.func_150496_b();
        }
    };

	private static void initializeBlock() {
        builder = new MachineBuilder(Material.iron).setStepSound(soundTypeMetal).setBlockName("builder").setHardness(5F).setResistance(10F).setCreativeTab(MainRegistry.tab);

        factionLeaderboard = new BlockFactionLeaderboard().setCreativeTab(MainRegistry.tab);

		barricade = new BlockSandbags(Material.rock).setStepSound(soundTypeConcrete).setBlockName("barricade").setHardness(10.0F).setResistance(10.0F).setCreativeTab(MainRegistry.tab).setBlockTextureName(RefStrings.MODID + ":sandbags");

		rice = new BlockRice().setBlockName("rice").setHardness(0.0F).setResistance(0.0F).setBlockTextureName(RefStrings.MODID + ":rice");

		uni_foundation = new BlockSpeedy(Material.rock, 0.15D).setStepSound(soundTypeConcrete).setBlockName("uni_foundation").setHardness(5.0F).setResistance(1.0F).setCreativeTab(MainRegistry.tab).setBlockTextureName(RefStrings.MODID + ":uni_foundation");
		asphalt = new BlockSpeedy(Material.rock, 0.25D).setStepSound(soundTypeConcrete).setBlockName("asphalt").setHardness(5.0F).setResistance(30.0F).setCreativeTab(MainRegistry.tab).setBlockTextureName(RefStrings.MODID + ":asphalt");

		wallImageBlock = new BlockWallImage()
				.setBlockName("wall_image_block")
				.setHardness(0.5F)
				.setResistance(1.0F)
				.setStepSound(soundTypeWood) // treat like wood; change if you want
				.setCreativeTab(MainRegistry.tab)

				.setBlockTextureName(RefStrings.MODID + ":concrete_flat");

        player_trader = new PlayerTrader().setStepSound(soundTypeMetal).setBlockName("player_trader")
            .setHardness(3F).setResistance(10F).setCreativeTab(MainRegistry.tab).setBlockTextureName(RefStrings.MODID + ":player_trader");
		machine_market = new MachineMarket(Material.iron).setStepSound(soundTypeMetal).setBlockName("machine_market").setHardness(Float.POSITIVE_INFINITY).setResistance(Float.POSITIVE_INFINITY).setCreativeTab(MainRegistry.tab).setBlockTextureName(RefStrings.MODID + ":market_side");
        faction_market = new MachineMarket(Material.iron, true).setStepSound(soundTypeMetal).setBlockName("faction_market")
            .setHardness(3F).setResistance(10F).setCreativeTab(MainRegistry.tab).setBlockTextureName(RefStrings.MODID + ":market_side");

		machine_net = new MachineNet(Material.cloth).setStepSound(Block.soundTypeCloth).setBlockName("machine_net").setHardness(0.0F).setResistance(0.0F).setCreativeTab(MainRegistry.tab).setBlockTextureName(RefStrings.MODID + ":machine_net");
		machine_uni = new MachineUni(Material.rock).setStepSound(Block.soundTypeStone).setBlockName("machine_uni").setHardness(5.0F).setResistance(0.0F).setCreativeTab(MainRegistry.tab).setBlockTextureName(RefStrings.MODID + ":machine_uni");

		machine_grainmill = new MachineGrainmill(Material.wood).setStepSound(soundTypeWood).setBlockName("machine_grainmill").setHardness(5.0F).setResistance(0.0F).setCreativeTab(MainRegistry.tab).setBlockTextureName(RefStrings.MODID + ":machine_grainmill");

		machine_factory = new MachineFactory(Material.iron).setStepSound(soundTypeConcrete).setBlockName("machine_factory").setHardness(5.0F).setResistance(0.0F).setCreativeTab(MainRegistry.tab).setBlockTextureName(RefStrings.MODID + ":machine_factory");

		machine_temple = new MachineTemple(Material.iron).setStepSound(soundTypeConcrete).setBlockName("machine_temple").setHardness(5.0F).setResistance(0.0F).setCreativeTab(MainRegistry.tab).setBlockTextureName(RefStrings.MODID + ":machine_temple");
		
		clowder_flag = new Flag(Material.iron).setStepSound(soundTypeMetal).setBlockName("clowder_flag").setHardness(5.0F).setResistance(Float.POSITIVE_INFINITY).setCreativeTab(MainRegistry.tab).setBlockTextureName(RefStrings.MODID + ":clowder_flag");
		clowder_cap = new Cap(Material.iron).setStepSound(soundTypeMetal).setBlockName("clowder_cap").setHardness(Float.POSITIVE_INFINITY).setResistance(Float.POSITIVE_INFINITY).setCreativeTab(null).setBlockTextureName(RefStrings.MODID + ":clowder_cap");
		clowder_flag_big = new FlagBig(Material.iron).setStepSound(soundTypeMetal).setBlockName("clowder_flag_big").setLightLevel(1F).setHardness(Float.POSITIVE_INFINITY).setResistance(Float.POSITIVE_INFINITY).setCreativeTab(MainRegistry.tab).setBlockTextureName(RefStrings.MODID + ":clowder_flag_big");
		faction_banner = new FactionBanner().setStepSound(Block.soundTypeCloth).setBlockName("faction_banner")
			.setHardness(.5F).setResistance(1F).setCreativeTab(MainRegistry.tab).setBlockTextureName(RefStrings.MODID + ":faction_banner");
		large_faction_flag = new LargeFactionFlag().setStepSound(soundTypeMetal).setBlockName("large_faction_flag")
			.setHardness(2F).setResistance(5F).setCreativeTab(MainRegistry.tab).setBlockTextureName(RefStrings.MODID + ":large_faction_flag");

		clowder_conquerer = new Conquerer(Material.cloth).setStepSound(Block.soundTypeCloth).setBlockName("clowder_conquerer").setHardness(0.8F).setResistance(4F).setCreativeTab(MainRegistry.tab).setBlockTextureName(RefStrings.MODID + ":clowder_conquerer");

		officer_chest = new OfficerChest().setStepSound(soundTypeWood).setBlockName("officer_chest").setResistance(10F).setHardness(5.0F).setCreativeTab(MainRegistry.tab).setBlockTextureName(RefStrings.MODID + ":officer_chest");

		hesco_block = new PropHesco(Material.rock).setStepSound(Block.soundTypeStone).setBlockName("hesco_block").setHardness(1F).setResistance(200F).setCreativeTab(MainRegistry.tab).setBlockTextureName(RefStrings.MODID + ":hesco_block");
		palisade = new PropPalisade(Material.wood).setStepSound(soundTypeWood).setBlockName("palisade").setHardness(2F).setResistance(5F).setCreativeTab(MainRegistry.tab);
		stone_wall = new PropWall(Material.rock).setStepSound(Block.soundTypeStone).setBlockName("stone_wall").setHardness(2.5F).setResistance(10F).setCreativeTab(MainRegistry.tab);
		brick_wall = new PropWall(Material.rock).setStepSound(Block.soundTypeStone).setBlockName("brick_wall").setHardness(2.5F).setResistance(25F).setCreativeTab(MainRegistry.tab);
		great_wall = new PropWall(Material.rock).setStepSound(Block.soundTypeStone).setBlockName("great_wall").setHardness(2.5F).setResistance(10F).setCreativeTab(MainRegistry.tab);
		berlin_wall = new PropBerlin(Material.rock).setStepSound(soundTypeConcrete).setBlockName("berlin_wall").setHardness(10.0F).setResistance(100F).setCreativeTab(MainRegistry.tab);
		tp_tent = new PropTent(Material.cloth).setStepSound(Block.soundTypeCloth).setBlockName("tp_tent").setHardness(1F).setResistance(1.5F).setCreativeTab(MainRegistry.tab).setBlockTextureName(RefStrings.MODID + ":tp_tent");
		statue = new PropStatue(Material.cloth).setStepSound(soundTypeConcrete).setBlockName("statue").setHardness(2.5F).setResistance(10F).setCreativeTab(MainRegistry.tab).setBlockTextureName(RefStrings.MODID + ":statue");

		debug = new BlockDebug(Material.cloth).setStepSound(soundTypeMetal).setBlockName("debug").setHardness(0.0F).setResistance(0.0F).setCreativeTab(null).setBlockTextureName(RefStrings.MODID + ":debug");
		
	}

	private static void registerBlock() {

        GameRegistry.registerBlock(factionLeaderboard, "faction_leaderboard");

		GameRegistry.registerBlock(wallImageBlock, "wall_image_block");

		GameRegistry.registerBlock(barricade, ItemBlockLore.class, barricade.getUnlocalizedName());
		
		GameRegistry.registerBlock(builder, ItemBlockUnstackable.class, builder.getUnlocalizedName());
        GameRegistry.registerBlock(rice, rice.getUnlocalizedName());

		GameRegistry.registerBlock(uni_foundation, ItemBlockLore.class, uni_foundation.getUnlocalizedName());
		GameRegistry.registerBlock(asphalt, ItemBlockLore.class, asphalt.getUnlocalizedName());

		GameRegistry.registerBlock(machine_net, ItemBlockLore.class, machine_net.getUnlocalizedName());
		GameRegistry.registerBlock(machine_uni, ItemBlockLore.class, machine_uni.getUnlocalizedName());
		GameRegistry.registerBlock(machine_factory, ItemBlockLore.class, machine_factory.getUnlocalizedName());

		GameRegistry.registerBlock(machine_temple, ItemBlockLore.class, machine_temple.getUnlocalizedName());
		GameRegistry.registerBlock(machine_grainmill, ItemBlockLore.class, machine_grainmill.getUnlocalizedName());

		GameRegistry.registerBlock(player_trader, player_trader.getUnlocalizedName());
		GameRegistry.registerBlock(machine_market, ItemBlockUnstackable.class, machine_market.getUnlocalizedName());
        GameRegistry.registerBlock(faction_market, ItemBlockFactionMarket.class, faction_market.getUnlocalizedName());

		GameRegistry.registerBlock(clowder_flag, com.hfr.items.ItemBlockCityCenter.class, clowder_flag.getUnlocalizedName());
		GameRegistry.registerBlock(clowder_flag_big, ItemBlockUnstackable.class, clowder_flag_big.getUnlocalizedName());
		GameRegistry.registerBlock(faction_banner, ItemBlockFactionDecoration.class, "faction_banner");
		GameRegistry.registerBlock(large_faction_flag, ItemBlockFactionDecoration.class, "large_faction_flag");
		GameRegistry.registerBlock(clowder_cap, ItemBlockUnstackable.class, clowder_cap.getUnlocalizedName());
		GameRegistry.registerBlock(clowder_conquerer, ItemBlockConqueror.class, clowder_conquerer.getUnlocalizedName());
		GameRegistry.registerBlock(officer_chest, ItemBlockLore.class, officer_chest.getUnlocalizedName());
		
		GameRegistry.registerBlock(hesco_block, ItemBlockLore.class, hesco_block.getUnlocalizedName());
		GameRegistry.registerBlock(palisade, ItemBlockLore.class, palisade.getUnlocalizedName());
		GameRegistry.registerBlock(stone_wall, ItemBlockLore.class, stone_wall.getUnlocalizedName());
		GameRegistry.registerBlock(brick_wall, ItemBlockLore.class, brick_wall.getUnlocalizedName());
		GameRegistry.registerBlock(great_wall, ItemBlockLore.class, great_wall.getUnlocalizedName());
		GameRegistry.registerBlock(berlin_wall, ItemBlockLore.class, berlin_wall.getUnlocalizedName());
		GameRegistry.registerBlock(tp_tent, ItemBlockLore.class, tp_tent.getUnlocalizedName());
		GameRegistry.registerBlock(statue, ItemBlockLore.class, statue.getUnlocalizedName());

		GameRegistry.registerBlock(debug, ItemBlockUnstackable.class, debug.getUnlocalizedName());

	}
}
