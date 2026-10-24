package com.hfr.render.living;

import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;
import net.minecraft.client.model.ModelBiped;
import net.minecraft.client.renderer.entity.RenderBiped;
import net.minecraft.entity.Entity;
import net.minecraft.util.ResourceLocation;

/** Opaque vanilla biped pass, including normal lighting, damage flashes and held items. */
@SideOnly(Side.CLIENT)
public final class RenderFactionMiner extends RenderBiped {
    private static final ResourceLocation TEXTURE = new ResourceLocation("textures/entity/steve.png");
    public RenderFactionMiner() { super(new ModelBiped(), .5F); }
    protected ResourceLocation getEntityTexture(Entity entity) { return TEXTURE; }
}
