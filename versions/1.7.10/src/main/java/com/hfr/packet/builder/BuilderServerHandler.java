package com.hfr.packet.builder;

import com.hfr.packet.PacketDispatcher;
import com.hfr.tdm.TDMServerTaskQueue;
import cpw.mods.fml.common.network.simpleimpl.IMessage;
import cpw.mods.fml.common.network.simpleimpl.IMessageHandler;
import cpw.mods.fml.common.network.simpleimpl.MessageContext;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.server.MinecraftServer;

/** The recovered worker handlers must never touch worlds on a Netty thread. */
public abstract class BuilderServerHandler<M extends IMessage> implements IMessageHandler<M, IMessage> {
    public final IMessage onMessage(final M message, final MessageContext context) {
        final EntityPlayerMP player = context.getServerHandler().playerEntity;
        TDMServerTaskQueue.schedule(() -> {
            if (player == null || player.isDead || player.playerNetServerHandler != context.getServerHandler()
                    || !player.playerNetServerHandler.netManager.isChannelOpen()
                    || !MinecraftServer.getServer().getConfigurationManager().playerEntityList.contains(player)) return;
            IMessage reply = handle(message, context);
            if (reply != null) PacketDispatcher.wrapper.sendTo(reply, player);
        });
        return null;
    }
    protected abstract IMessage handle(M message, MessageContext context);
}
