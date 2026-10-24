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
    private int id, action, window;
    private String uuid = "";
    public MinerActionPacket() { }
    public MinerActionPacket(EntityFactionMiner worker, int window, int action) {
        id = worker.getEntityId(); uuid = worker.getUniqueID().toString(); this.window = window; this.action = action;
    }
    public void fromBytes(ByteBuf buf) {
        id = buf.readInt(); uuid = ByteBufUtils.readUTF8String(buf); window = buf.readInt(); action = buf.readUnsignedByte();
        if (uuid.length() != 36 || action > 4) throw new IllegalArgumentException("Invalid miner action");
        if (buf.isReadable()) throw new IllegalArgumentException("Trailing miner data");
    }
    public void toBytes(ByteBuf buf) {
        buf.writeInt(id); ByteBufUtils.writeUTF8String(buf, uuid); buf.writeInt(window); buf.writeByte(action);
    }
    public static final class Handler extends BuilderServerHandler<MinerActionPacket> {
        protected IMessage handle(MinerActionPacket message, MessageContext context) {
            EntityPlayerMP player = context.getServerHandler().playerEntity;
            Entity entity = player.worldObj.getEntityByID(message.id);
            if (!(entity instanceof EntityFactionMiner) || !entity.getUniqueID().toString().equals(message.uuid)
                    || !(player.openContainer instanceof ContainerMinerNPC)
                    || player.openContainer.windowId != message.window
                    || ((ContainerMinerNPC)player.openContainer).worker != entity) return null;
            EntityFactionMiner worker = (EntityFactionMiner)entity;
            ContainerMinerNPC container = (ContainerMinerNPC)player.openContainer;
            if (!worker.mayManage(player)) {
                container.replacement = null;
                return new MinerSnapshotPacket(worker, player, "xf.worker.denied", true);
            }
            String feedback = "";
            if (message.action == 1 || message.action == 4) {
                if (message.action == 4 && container.replacement == null)
                    return new MinerSnapshotPacket(worker,player,"xf.miner.selection_changed",true);
                EntityFactionMiner.Activation result = worker.start(player, message.action == 4 ? container.replacement : null);
                container.replacement = result.needsConfirmation() ? result : null;
                feedback = result.feedback;
            } else if (message.action != 0) {
                container.replacement = null;
                if (message.action == 2) worker.pause();
                else if (message.action == 3) feedback = com.hfr.items.ItemMinerWand.give(player, worker, true);
            }
            return new MinerSnapshotPacket(worker, player, feedback, message.action != 0);
        }
    }
}
