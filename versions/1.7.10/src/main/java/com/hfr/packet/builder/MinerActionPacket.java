package com.hfr.packet.builder;

import com.hfr.entity.EntityFactionMiner;
import com.hfr.inventory.container.ContainerMinerNPC;
import cpw.mods.fml.common.network.ByteBufUtils;
import cpw.mods.fml.common.network.simpleimpl.IMessage;
import cpw.mods.fml.common.network.simpleimpl.MessageContext;
import io.netty.buffer.ByteBuf;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayerMP;

public final class MinerActionPacket implements IMessage {
    private int id, action;
    private String uuid = "";
    private int[] plan = new int[EntityFactionMiner.PLAN_SIZE];
    public MinerActionPacket() { }
    public MinerActionPacket(EntityFactionMiner worker, int action, int[] plan) {
        id = worker.getEntityId(); uuid = worker.getUniqueID().toString(); this.action = action; this.plan = plan.clone();
    }
    public void fromBytes(ByteBuf buf) {
        id = buf.readInt(); uuid = ByteBufUtils.readUTF8String(buf); action = buf.readUnsignedByte();
        if (uuid.length() != 36 || action > 2) throw new IllegalArgumentException("Invalid miner action");
        for (int i = 0; i < plan.length; i++) plan[i] = buf.readInt();
        if (buf.isReadable()) throw new IllegalArgumentException("Trailing miner data");
    }
    public void toBytes(ByteBuf buf) {
        buf.writeInt(id); ByteBufUtils.writeUTF8String(buf, uuid); buf.writeByte(action);
        for (int value : plan) buf.writeInt(value);
    }
    public static final class Handler extends BuilderServerHandler<MinerActionPacket> {
        protected IMessage handle(MinerActionPacket message, MessageContext context) {
            EntityPlayerMP player = context.getServerHandler().playerEntity;
            Entity entity = player.worldObj.getEntityByID(message.id);
            if (!(entity instanceof EntityFactionMiner) || !entity.getUniqueID().toString().equals(message.uuid)
                    || !(player.openContainer instanceof ContainerMinerNPC)
                    || ((ContainerMinerNPC)player.openContainer).worker != entity) return null;
            EntityFactionMiner worker = (EntityFactionMiner)entity;
            if (!worker.mayManage(player)) return null;
            String feedback = "";
            if (message.action == 1) feedback = worker.configure(player, message.plan);
            else if (message.action == 2) worker.pause();
            return new MinerSnapshotPacket(worker, feedback);
        }
    }
}
