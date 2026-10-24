package com.hfr.items;

import com.hfr.lib.RefStrings;
import com.hfr.main.MainRegistry;

import cpw.mods.fml.common.registry.GameRegistry;
import net.minecraft.item.Item;
import net.minecraft.item.ItemArmor.ArmorMaterial;
import net.minecraft.item.ItemFood;
import net.minecraftforge.common.util.EnumHelper;

public class ModItems {
	
	public static void mainRegistry()
	{
		initializeItem();
		registerItem();
	}

    public static Item builder_contract, farmer, miner;
    public static Item miner_station_wand, miner_area_wand;
	public static Item rice;
	public static Item rice_seeds;
	public static Item rice_bag;
	public static Item rice_sticky;

	public static Item squid_raw;
	public static Item squid_cooked;

	public static Item components_wood;
	public static Item components_scaffold;
	public static Item components_steel;
	public static Item components_mechanical;
	public static Item components_electronics;

	public static Item whale_meat;
	public static Item science;

	public static Item cog;

	public static Item scroll;
	
	public static Item province_point;
	public static Item flour;

	public static Item out_of_bounds_wand;
	public static Item world_border_wand;

	public static Item clowder_banner;

	public static Item hatter;

	public static Item mult_stoneage;
	public static Item mult_roman;
	public static Item mult_steel;
	public static Item mult_alloy;
	
	public static Item mace;
	public static Item hatchet;
	public static Item clowder_map;
	public static Item earth_map;

	public static Item debug;
	
	public static ArmorMaterial matGasMask = EnumHelper.addArmorMaterial("GASMASK", 10, new int[] {0, 0, 0, 0}, 0);

	public static void initializeItem()
	{
        builder_contract = new ItemBuilderContract().setUnlocalizedName("builder_contract").setMaxStackSize(1).setCreativeTab(MainRegistry.tab).setTextureName("hfr:farmer");
        farmer = new ItemWorkerContract(false).setUnlocalizedName("farmer").setMaxStackSize(1).setCreativeTab(MainRegistry.tab).setTextureName("hfr:farmer");
        miner = new ItemWorkerContract(true).setUnlocalizedName("miner").setMaxStackSize(1).setCreativeTab(MainRegistry.tab).setTextureName("hfr:miner");
        miner_station_wand = new ItemMinerWand(false).setUnlocalizedName("miner_station_wand").setFull3D().setTextureName("hfr:wand");
        miner_area_wand = new ItemMinerWand(true).setUnlocalizedName("miner_area_wand").setFull3D().setTextureName("hfr:wand");

		components_wood = new ItemLore().setUnlocalizedName("components_wood").setCreativeTab(MainRegistry.tab).setTextureName(RefStrings.MODID + ":components_wood");
		components_scaffold = new ItemLore().setUnlocalizedName("components_scaffold").setCreativeTab(MainRegistry.tab).setTextureName(RefStrings.MODID + ":components_scaffold");
		components_steel = new ItemLore().setUnlocalizedName("components_steel").setCreativeTab(MainRegistry.tab).setTextureName(RefStrings.MODID + ":components_steel");
		components_mechanical = new ItemLore().setUnlocalizedName("components_mechanical").setCreativeTab(MainRegistry.tab).setTextureName(RefStrings.MODID + ":components_mechanical");
		components_electronics = new ItemLore().setUnlocalizedName("components_electronics").setCreativeTab(MainRegistry.tab).setTextureName(RefStrings.MODID + ":components_electronics");
		
		rice = new ItemLore().setUnlocalizedName("rice").setCreativeTab(MainRegistry.tab).setTextureName(RefStrings.MODID + ":rice");
		rice_seeds = new ItemRice().setUnlocalizedName("rice_seeds").setCreativeTab(MainRegistry.tab).setTextureName(RefStrings.MODID + ":rice_seeds");
		rice_bag = new ItemLore().setUnlocalizedName("rice_bag").setCreativeTab(MainRegistry.tab).setTextureName(RefStrings.MODID + ":rice_bag");
		rice_sticky = new ItemFood(5, false).setUnlocalizedName("rice_sticky").setCreativeTab(MainRegistry.tab).setTextureName(RefStrings.MODID + ":rice_sticky");
		squid_raw = new ItemFood(2, 0.2F, false).setUnlocalizedName("squid_raw").setCreativeTab(MainRegistry.tab).setTextureName(RefStrings.MODID + ":squid_raw");
		squid_cooked = new ItemFood(6, 0.6F, false).setUnlocalizedName("squid_cooked").setCreativeTab(MainRegistry.tab).setTextureName(RefStrings.MODID + ":squid_cooked");
		
		whale_meat = new ItemMeat().setUnlocalizedName("whale_meat").setCreativeTab(MainRegistry.tab).setTextureName(RefStrings.MODID + ":whale_meat");
		science = new ItemLore().setUnlocalizedName("science").setCreativeTab(MainRegistry.tab).setTextureName(RefStrings.MODID + ":science");
		cog = new ItemLore().setUnlocalizedName("cog").setCreativeTab(MainRegistry.tab).setTextureName(RefStrings.MODID + ":cog");
		scroll = new ItemLore().setUnlocalizedName("scroll").setCreativeTab(MainRegistry.tab).setTextureName(RefStrings.MODID + ":scroll");

		province_point = new ItemLore().setUnlocalizedName("province_point").setCreativeTab(null).setTextureName(RefStrings.MODID + ":prestige");
		flour = new ItemLore().setUnlocalizedName("flour").setCreativeTab(MainRegistry.tab).setTextureName(RefStrings.MODID + ":flour");

		out_of_bounds_wand = new ItemOutOfBoundsWand().setUnlocalizedName("out_of_bounds_wand").setMaxStackSize(1).setFull3D().setCreativeTab(MainRegistry.tab).setTextureName(RefStrings.MODID + ":wand");
		world_border_wand = new ItemWorldBorderWand().setUnlocalizedName("world_border_wand").setFull3D().setTextureName(RefStrings.MODID + ":wand");

		clowder_banner = new ItemBanner(matGasMask, 5, 3).setUnlocalizedName("clowder_banner").setMaxStackSize(1).setCreativeTab(MainRegistry.tab).setTextureName(RefStrings.MODID + ":clowder_banner");
		hatter = new ItemHatter().setUnlocalizedName("hatter").setMaxStackSize(1).setCreativeTab(MainRegistry.tab).setTextureName(RefStrings.MODID + ":hat");

		mult_stoneage = new ItemMultitool().setUnlocalizedName("mult_stoneage").setMaxStackSize(1).setCreativeTab(MainRegistry.tab).setTextureName(RefStrings.MODID + ":mult_stoneage");
		mult_roman = new ItemMultitool().setUnlocalizedName("mult_roman").setMaxStackSize(1).setCreativeTab(MainRegistry.tab).setTextureName(RefStrings.MODID + ":mult_roman");
		mult_steel = new ItemMultitool().setUnlocalizedName("mult_steel").setMaxStackSize(1).setCreativeTab(MainRegistry.tab).setTextureName(RefStrings.MODID + ":mult_steel");
		mult_alloy = new ItemMultitool().setUnlocalizedName("mult_alloy").setMaxStackSize(1).setCreativeTab(MainRegistry.tab).setTextureName(RefStrings.MODID + ":mult_alloy");
		
		mace = new ItemMace().setUnlocalizedName("mace").setMaxStackSize(1).setCreativeTab(MainRegistry.tab).setTextureName(RefStrings.MODID + ":mace");
		hatchet = new ItemHatchet().setUnlocalizedName("hatchet").setMaxStackSize(1).setFull3D().setCreativeTab(MainRegistry.tab).setTextureName(RefStrings.MODID + ":hatchet");
		clowder_map = new ItemMap().setUnlocalizedName("clowder_map").setMaxStackSize(1).setCreativeTab(MainRegistry.tab).setTextureName(RefStrings.MODID + ":clowder_map");
		earth_map = new ItemEarthMap().setUnlocalizedName("earth_map").setMaxStackSize(1).setCreativeTab(MainRegistry.tab).setTextureName(RefStrings.MODID + ":clowder_map");
		
		debug = new ItemDebug().setUnlocalizedName("debug").setMaxStackSize(1).setCreativeTab(MainRegistry.tab).setTextureName(RefStrings.MODID + ":debug");
	}
	
	private static void registerItem() {
        GameRegistry.registerItem(miner_station_wand, miner_station_wand.getUnlocalizedName());
        GameRegistry.registerItem(miner_area_wand, miner_area_wand.getUnlocalizedName());
		GameRegistry.registerItem(out_of_bounds_wand, out_of_bounds_wand.getUnlocalizedName());
		GameRegistry.registerItem(world_border_wand, world_border_wand.getUnlocalizedName());

		GameRegistry.registerItem(clowder_banner, clowder_banner.getUnlocalizedName());
		
		GameRegistry.registerItem(hatter, hatter.getUnlocalizedName());

		GameRegistry.registerItem(components_wood, components_wood.getUnlocalizedName());
		GameRegistry.registerItem(components_scaffold, components_scaffold.getUnlocalizedName());
		GameRegistry.registerItem(components_steel, components_steel.getUnlocalizedName());
		GameRegistry.registerItem(components_mechanical, components_mechanical.getUnlocalizedName());
		GameRegistry.registerItem(components_electronics, components_electronics.getUnlocalizedName());

		GameRegistry.registerItem(builder_contract, builder_contract.getUnlocalizedName());
        GameRegistry.registerItem(farmer, farmer.getUnlocalizedName());
        GameRegistry.registerItem(miner, miner.getUnlocalizedName());
        GameRegistry.registerItem(rice, rice.getUnlocalizedName());
		GameRegistry.registerItem(rice_seeds, rice_seeds.getUnlocalizedName());
		GameRegistry.registerItem(rice_sticky, rice_sticky.getUnlocalizedName());
		GameRegistry.registerItem(rice_bag, rice_bag.getUnlocalizedName());
		GameRegistry.registerItem(squid_raw, squid_raw.getUnlocalizedName());
		GameRegistry.registerItem(squid_cooked, squid_cooked.getUnlocalizedName());

		GameRegistry.registerItem(whale_meat, whale_meat.getUnlocalizedName());
		GameRegistry.registerItem(science, science.getUnlocalizedName());
		GameRegistry.registerItem(cog, cog.getUnlocalizedName());
		GameRegistry.registerItem(scroll, scroll.getUnlocalizedName());
		GameRegistry.registerItem(province_point, province_point.getUnlocalizedName());
		GameRegistry.registerItem(flour, flour.getUnlocalizedName());

		GameRegistry.registerItem(mace, mace.getUnlocalizedName());
		GameRegistry.registerItem(hatchet, hatchet.getUnlocalizedName());
		GameRegistry.registerItem(clowder_map, clowder_map.getUnlocalizedName());
		GameRegistry.registerItem(earth_map, "earth_map");

		GameRegistry.registerItem(mult_stoneage, mult_stoneage.getUnlocalizedName());
		GameRegistry.registerItem(mult_roman, mult_roman.getUnlocalizedName());
		GameRegistry.registerItem(mult_steel, mult_steel.getUnlocalizedName());
		GameRegistry.registerItem(mult_alloy, mult_alloy.getUnlocalizedName());
		
		GameRegistry.registerItem(debug, debug.getUnlocalizedName());
	}
}
