package com.hfr.packet.builder;

import cpw.mods.fml.common.network.simpleimpl.IMessage;
import cpw.mods.fml.common.network.simpleimpl.MessageContext;
import io.netty.buffer.ByteBuf;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.item.ItemStack;
import net.minecraftforge.event.ForgeEventFactory;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import com.hfr.items.ItemMinerWand;

/** Click intent only. Binding, sneak mode and permissions come from current server state. */
public final class MinerWandPacket implements IMessage {
    private int x,y,z,side,slot,dimension;
    private boolean first;
    public MinerWandPacket() { }
    public MinerWandPacket(int x,int y,int z,int side,int slot,int dimension,boolean first) {
        this.x=x; this.y=y; this.z=z; this.side=side; this.slot=slot; this.dimension=dimension; this.first=first;
    }
    public void fromBytes(ByteBuf buf) {
        x=buf.readInt(); y=buf.readInt(); z=buf.readInt(); side=buf.readUnsignedByte(); slot=buf.readUnsignedByte(); dimension=buf.readInt(); first=buf.readBoolean();
        if (Math.abs((long)x)>30000000 || Math.abs((long)z)>30000000 || y<0 || side>5 || slot>8 || buf.isReadable()) throw new IllegalArgumentException("Invalid miner wand click");
    }
    public void toBytes(ByteBuf buf) { buf.writeInt(x); buf.writeInt(y); buf.writeInt(z); buf.writeByte(side); buf.writeByte(slot); buf.writeInt(dimension); buf.writeBoolean(first); }
    public static final class Handler extends BuilderServerHandler<MinerWandPacket> {
        protected IMessage handle(MinerWandPacket message,MessageContext context) {
            EntityPlayerMP player=context.getServerHandler().playerEntity;
            ItemStack held=player.getHeldItem();
            if (player.dimension!=message.dimension || player.inventory.currentItem!=message.slot || held==null || !(held.getItem() instanceof ItemMinerWand)
                    || message.y>=player.worldObj.getHeight() || !player.worldObj.blockExists(message.x,message.y,message.z)
                    || player.getDistanceSq(message.x+.5,message.y+.5,message.z+.5)>64) return null;
            // Uses the same server selection event path as native clicks.
            ForgeEventFactory.onPlayerInteract(player,message.first ? PlayerInteractEvent.Action.LEFT_CLICK_BLOCK : PlayerInteractEvent.Action.RIGHT_CLICK_BLOCK,
                    message.x,message.y,message.z,message.side,player.worldObj);
            return null;
        }
    }
}
