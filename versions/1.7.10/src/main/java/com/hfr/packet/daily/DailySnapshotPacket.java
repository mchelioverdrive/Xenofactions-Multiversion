package com.hfr.packet.daily;

import cpw.mods.fml.common.network.ByteBufUtils;
import cpw.mods.fml.common.network.simpleimpl.IMessage;
import cpw.mods.fml.common.network.simpleimpl.IMessageHandler;
import cpw.mods.fml.common.network.simpleimpl.MessageContext;
import io.netty.buffer.ByteBuf;
import net.minecraft.nbt.NBTTagCompound;

public final class DailySnapshotPacket implements IMessage {
    private NBTTagCompound data;
    public DailySnapshotPacket() { }
    public DailySnapshotPacket(NBTTagCompound data) { this.data = data; }
    public void fromBytes(ByteBuf buf) { data = ByteBufUtils.readTag(buf); }
    public void toBytes(ByteBuf buf) { ByteBufUtils.writeTag(buf, data); }
    public static final class Handler implements IMessageHandler<DailySnapshotPacket, IMessage> {
        public IMessage onMessage(DailySnapshotPacket message, MessageContext context) {
            com.hfr.main.MainRegistry.proxy.receiveDailyReward(context.netHandler, message.data); return null;
        }
    }
}
