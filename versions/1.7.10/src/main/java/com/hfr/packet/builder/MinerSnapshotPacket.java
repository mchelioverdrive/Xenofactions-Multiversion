package com.hfr.packet.builder;

import com.hfr.entity.EntityFactionMiner;
import com.hfr.main.MainRegistry;
import cpw.mods.fml.common.network.ByteBufUtils;
import cpw.mods.fml.common.network.simpleimpl.IMessage;
import cpw.mods.fml.common.network.simpleimpl.IMessageHandler;
import cpw.mods.fml.common.network.simpleimpl.MessageContext;
import io.netty.buffer.ByteBuf;
import net.minecraft.nbt.NBTTagCompound;

public final class MinerSnapshotPacket implements IMessage {
    private int id;
    private String uuid = "", feedback = "";
    private NBTTagCompound state;
    public MinerSnapshotPacket() { }
    public MinerSnapshotPacket(EntityFactionMiner worker, net.minecraft.entity.player.EntityPlayer player, String feedback) {
        id = worker.getEntityId(); uuid = worker.getUniqueID().toString(); state = worker.snapshot(); this.feedback = feedback;
        state.setIntArray("Selection", com.hfr.items.ItemMinerWand.selection(player, worker));
        state.setInteger("Window", player.openContainer.windowId);
    }
    public void fromBytes(ByteBuf buf) {
        id = buf.readInt(); uuid = ByteBufUtils.readUTF8String(buf); feedback = ByteBufUtils.readUTF8String(buf); state = ByteBufUtils.readTag(buf);
        if (uuid.length() != 36 || state == null || state.getIntArray("Plan").length != EntityFactionMiner.PLAN_SIZE || buf.isReadable()) throw new IllegalArgumentException("Invalid miner snapshot");
    }
    public void toBytes(ByteBuf buf) { buf.writeInt(id); ByteBufUtils.writeUTF8String(buf, uuid); ByteBufUtils.writeUTF8String(buf, feedback); ByteBufUtils.writeTag(buf, state); }
    public static final class Handler implements IMessageHandler<MinerSnapshotPacket, IMessage> {
        public IMessage onMessage(MinerSnapshotPacket message, MessageContext context) {
            MainRegistry.proxy.receiveMiner(context.netHandler, message.id, message.uuid, message.state, message.feedback); return null;
        }
    }
}
