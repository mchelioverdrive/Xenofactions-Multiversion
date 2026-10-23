package com.hfr.packet;

import com.hfr.daily.DailyRewardService;
import com.hfr.main.MainRegistry;
import com.hfr.tdm.TDMServerTaskQueue;
import cpw.mods.fml.common.network.simpleimpl.*;
import io.netty.buffer.ByteBuf;
import net.minecraft.entity.player.EntityPlayerMP;

/** Cosmetic preferences only. No client-supplied reward or gameplay state. */
public final class XFOnboardingPacket {
    public static final int READY = 0, LEGACY_DONE = 1, COMPLETE = 2, REPLAY = 3;
    private XFOnboardingPacket() { }
    public static final class Intent implements IMessage {
        private int action;
        public Intent() { }
        public Intent(int action) { this.action = action; }
        public void fromBytes(ByteBuf buf) {
            action = buf.readUnsignedByte();
            if (action > REPLAY) throw new IllegalArgumentException("Invalid onboarding intent");
        }
        public void toBytes(ByteBuf buf) { buf.writeByte(action); }
    }
    public static final class State implements IMessage {
        private boolean show;
        public State() { }
        public State(boolean show) { this.show = show; }
        public void fromBytes(ByteBuf buf) { show = buf.readBoolean(); }
        public void toBytes(ByteBuf buf) { buf.writeBoolean(show); }
    }
    public static final class ServerHandler implements IMessageHandler<Intent, IMessage> {
        public IMessage onMessage(final Intent message, MessageContext context) {
            final EntityPlayerMP player = context.getServerHandler().playerEntity;
            TDMServerTaskQueue.schedule(() -> DailyRewardService.onboarding(player, message.action));
            return null;
        }
    }
    public static final class ClientHandler implements IMessageHandler<State, IMessage> {
        public IMessage onMessage(State message, MessageContext context) {
            MainRegistry.proxy.receiveOnboarding(context.netHandler, message.show);
            return null;
        }
    }
}
