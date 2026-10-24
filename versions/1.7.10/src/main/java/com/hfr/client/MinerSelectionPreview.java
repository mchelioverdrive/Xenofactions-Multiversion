package com.hfr.client;

import com.hfr.client.render.RenderState;
import com.hfr.items.ModItems;
import com.hfr.items.ItemMinerWand;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderGlobal;
import net.minecraft.entity.Entity;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.AxisAlignedBB;
import net.minecraftforge.client.event.RenderWorldLastEvent;
import org.lwjgl.opengl.GL11;
import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;

@SideOnly(Side.CLIENT)
public final class MinerSelectionPreview {
    private MinerSelectionPreview() { }
    public static void render(RenderWorldLastEvent event) {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.thePlayer == null || mc.renderViewEntity == null) return;
        ItemStack stack = mc.thePlayer.getHeldItem();
        if (stack == null || stack.getItem() != ModItems.miner_area_wand || !stack.hasTagCompound()) return;
        NBTTagCompound tag = stack.getTagCompound();
        if (tag.getInteger("Dimension") != mc.thePlayer.dimension) return;
        Entity camera = mc.renderViewEntity;
        double x = camera.lastTickPosX+(camera.posX-camera.lastTickPosX)*event.partialTicks;
        double y = camera.lastTickPosY+(camera.posY-camera.lastTickPosY)*event.partialTicks;
        double z = camera.lastTickPosZ+(camera.posZ-camera.lastTickPosZ)*event.partialTicks;
        try (RenderState state = new RenderState()) {
            GL11.glTranslated(-x,-y,-z); GL11.glDisable(GL11.GL_TEXTURE_2D); GL11.glDisable(GL11.GL_LIGHTING);
            GL11.glEnable(GL11.GL_DEPTH_TEST); GL11.glDepthMask(false); GL11.glDisable(GL11.GL_BLEND); GL11.glLineWidth(2);
            for (String p : new String[] {"A","B"}) if (tag.getBoolean(p)) {
                int px=tag.getInteger(p+"X"), py=tag.getInteger(p+"Y"), pz=tag.getInteger(p+"Z");
                RenderGlobal.drawOutlinedBoundingBox(AxisAlignedBB.getBoundingBox(px-.003,py-.003,pz-.003,px+1.003,py+1.003,pz+1.003),0xffffaa);
            }
            if (tag.getBoolean("A") && tag.getBoolean("B")) {
                int[] p=ItemMinerWand.plan(ItemMinerWand.corners(tag));
                RenderGlobal.drawOutlinedBoundingBox(AxisAlignedBB.getBoundingBox(p[3]-.002,p[8]-.002,p[4]-.002,p[5]+1.002,p[7]+1.002,p[6]+1.002),0x66ccff);
            }
        }
    }
}
