package com.hfr.packet;

import com.hfr.daily.DailyRewardService;
import com.hfr.main.MainRegistry;
import com.hfr.tdm.TDMServerTaskQueue;
import cpw.mods.fml.common.network.simpleimpl.*;
import io.netty.buffer.ByteBuf;
import net.minecraft.entity.player.EntityPlayerMP;

/** Preference intents and server-confirmed guidance. No client-supplied progress or gameplay state. */
public final class XFOnboardingPacket {
    public static final int READY = 0, LEGACY_DONE = 1, COMPLETE = 2, REPLAY = 3,
            HIDE_SETUP = 4, SHOW_SETUP = 5, RESUME_SETUP = 6;
    private XFOnboardingPacket() { }
    public static final class Intent implements IMessage {
        private int action;
        public Intent() { }
        public Intent(int action) { this.action = action; }
        public void fromBytes(ByteBuf buf) {
            action = buf.readUnsignedByte();
            if (action > RESUME_SETUP || buf.isReadable()) throw new IllegalArgumentException("Invalid onboarding intent");
        }
        public void toBytes(ByteBuf buf) { buf.writeByte(action); }
    }
    public static final class State implements IMessage {
        private boolean show;
        private int step;
        private boolean hidden, open;
        private String feedback = "";
        public State() { }
        public State(boolean show, int step, boolean hidden, boolean open, String feedback) {
            this.show = show; this.step = step; this.hidden = hidden; this.open = open; this.feedback = feedback;
        }
        public void fromBytes(ByteBuf buf) {
            show = buf.readBoolean(); step = buf.readUnsignedByte(); hidden = buf.readBoolean(); open = buf.readBoolean();
            int length = buf.readUnsignedShort();
            if (length > 1024 || length > buf.readableBytes()) throw new IllegalArgumentException("Invalid setup feedback");
            byte[] bytes = new byte[length]; buf.readBytes(bytes); feedback = new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
            if (step > 4 || buf.isReadable()) throw new IllegalArgumentException("Invalid setup snapshot");
        }
        public void toBytes(ByteBuf buf) {
            buf.writeBoolean(show); buf.writeByte(step); buf.writeBoolean(hidden); buf.writeBoolean(open);
            byte[] bytes = feedback.getBytes(java.nio.charset.StandardCharsets.UTF_8); buf.writeShort(bytes.length); buf.writeBytes(bytes);
        }
    }
    public static final class ServerHandler implements IMessageHandler<Intent, IMessage> {
        public IMessage onMessage(final Intent message, MessageContext context) {
            final EntityPlayerMP player = context.getServerHandler().playerEntity;
            TDMServerTaskQueue.schedule(() -> {
                if (player.playerNetServerHandler == context.getServerHandler() && !player.isDead
                        && net.minecraft.server.MinecraftServer.getServer().getConfigurationManager().playerEntityList.contains(player))
                    DailyRewardService.onboarding(player, message.action);
            });
            return null;
        }
    }
    public static final class ClientHandler implements IMessageHandler<State, IMessage> {
        public IMessage onMessage(State message, MessageContext context) {
            MainRegistry.proxy.receiveOnboarding(context.netHandler, message.show, message.step, message.hidden, message.open, message.feedback);
            return null;
        }
    }
}
