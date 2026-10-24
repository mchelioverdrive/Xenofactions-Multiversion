package com.hfr.packet.builder;

import cpw.mods.fml.common.network.simpleimpl.IMessage;
import cpw.mods.fml.common.network.simpleimpl.MessageContext;
import cpw.mods.fml.common.network.ByteBufUtils;
import io.netty.buffer.ByteBuf;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.item.ItemStack;
import com.hfr.items.ItemMinerWand;

/** Click-time depth intent; identity, held item, bounds and authority are checked on the server. */
public final class MinerWandPacket implements IMessage {
    private int x,y,z,side,slot,dimension;
    private boolean first, down;
    private String miner = "";
    public MinerWandPacket() { }
    public MinerWandPacket(int x,int y,int z,int side,int slot,int dimension,boolean first,boolean down,String miner) {
        this.x=x; this.y=y; this.z=z; this.side=side; this.slot=slot; this.dimension=dimension; this.first=first;
        this.down=down; this.miner=miner;
    }
    public void fromBytes(ByteBuf buf) {
        x=buf.readInt(); y=buf.readInt(); z=buf.readInt(); side=buf.readUnsignedByte(); slot=buf.readUnsignedByte(); dimension=buf.readInt(); first=buf.readBoolean();
        down=buf.readBoolean(); miner=ByteBufUtils.readUTF8String(buf);
        if (miner.length()!=36 || Math.abs((long)x)>30000000 || Math.abs((long)z)>30000000 || y<0 || side>5 || slot>8 || buf.isReadable()) throw new IllegalArgumentException("Invalid miner wand click");
    }
    public void toBytes(ByteBuf buf) { buf.writeInt(x); buf.writeInt(y); buf.writeInt(z); buf.writeByte(side); buf.writeByte(slot); buf.writeInt(dimension); buf.writeBoolean(first); buf.writeBoolean(down); ByteBufUtils.writeUTF8String(buf,miner); }
    public static final class Handler extends BuilderServerHandler<MinerWandPacket> {
        protected IMessage handle(MinerWandPacket message,MessageContext context) {
            EntityPlayerMP player=context.getServerHandler().playerEntity;
            ItemStack held=player.getHeldItem();
            if (player.dimension!=message.dimension || player.inventory.currentItem!=message.slot || held==null || !(held.getItem() instanceof ItemMinerWand)
                    || !held.hasTagCompound() || !message.miner.equals(held.getTagCompound().getString("Miner"))
                    || message.y>=player.worldObj.getHeight() || !player.worldObj.blockExists(message.x,message.y,message.z)
                    || player.getDistanceSq(message.x+.5,message.y+.5,message.z+.5)>64) return null;
            ItemMinerWand.select(player,message.x,message.y,message.z,message.first,message.down);
            return null;
        }
    }
}
