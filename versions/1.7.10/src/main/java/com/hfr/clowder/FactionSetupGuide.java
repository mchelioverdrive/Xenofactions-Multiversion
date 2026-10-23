package com.hfr.clowder;

import java.io.IOException;
import com.hfr.daily.DailyRewardService;
import com.hfr.daily.DailyRewardStore;
import com.hfr.packet.PacketDispatcher;
import com.hfr.packet.XFOnboardingPacket;
import com.hfr.util.XFLog;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.ChatComponentTranslation;

/** Server-thread results only. The guide never performs a claim or sets a home itself. */
public final class FactionSetupGuide {
    public static final int CLAIM = 1, PLACE = 2, HOME = 3, DONE = 4;
    private static final String KEY = "FactionSetup";
    private FactionSetupGuide() { }

    private static NBTTagCompound read(EntityPlayerMP player) { return DailyRewardService.achievementState(player); }
    private static boolean matches(EntityPlayer player, NBTTagCompound guide) {
        Clowder faction = Clowder.getClowderFromPlayer(player);
        return faction != null && faction.uuid.equals(guide.getString("Faction"));
    }
    private static int step(EntityPlayer player) {
        if (!(player instanceof EntityPlayerMP)) return 0;
        NBTTagCompound guide = read((EntityPlayerMP)player).getCompoundTag(KEY);
        return matches(player, guide) ? guide.getInteger("Step") : 0;
    }
    private static void failed(EntityPlayerMP player, Exception e) {
        XFLog.error("Could not save faction setup for " + player.getUniqueID(), e);
        player.addChatMessage(new ChatComponentTranslation("xf.setup.save_failed"));
        // Release an awaiting form even when the save fails. Report only the previous saved step.
        int previousStep = 0; boolean hidden = false;
        try {
            NBTTagCompound guide = read(player).getCompoundTag(KEY);
            previousStep = matches(player, guide) ? guide.getInteger("Step") : 0; hidden = guide.getBoolean("Hidden");
        } catch (IllegalStateException unavailable) { /* A blocked/corrupt record authorizes no progress. */ }
        try { PacketDispatcher.wrapper.sendTo(new XFOnboardingPacket.State(false, previousStep, hidden, false, "xf.setup.save_failed"), player); }
        catch (RuntimeException notificationFailure) { XFLog.error("Could not send setup failure", notificationFailure); }
    }
    public static void created(EntityPlayer player) {
        if (!(player instanceof EntityPlayerMP)) return;
        EntityPlayerMP mp = (EntityPlayerMP)player;
        try {
            NBTTagCompound saved = read(mp), guide = new NBTTagCompound();
            guide.setString("Faction", Clowder.getClowderFromPlayer(player).uuid);
            guide.setInteger("Step", CLAIM); guide.setBoolean("Hidden", false);
            saved.setTag(KEY, guide); DailyRewardStore.commit(mp, saved);
            sync(mp, false, true);
        } catch (IOException | IllegalStateException e) { failed(mp, e); }
    }
    /** Same player-file transaction as the inventory: reconnect/replay cannot repeat this delivery. */
    public static boolean deliver(EntityPlayerMP player, ItemStack stack) {
        int slot = player.inventory.getFirstEmptyStack();
        if (slot < 0) {
            player.addChatMessage(new ChatComponentTranslation("xf.setup.inventory_full"));
            rejected(player, "xf.setup.inventory_full"); return false;
        }
        NBTTagCompound saved;
        try { saved = read(player); }
        catch (IllegalStateException e) { failed(player, e); return false; }
        NBTTagCompound guide = saved.getCompoundTag(KEY);
        boolean guided = matches(player, guide) && guide.getInteger("Step") == CLAIM;
        if (guided) guide.setInteger("Step", PLACE);
        // Named City Centers are unstackable. Retain the native insertion path and forbid creative
        // inventory overflow from reporting delivery when it silently discards the stack.
        if (!player.inventory.addItemStackToInventory(stack)) return false;
        try { DailyRewardStore.commit(player, saved); }
        catch (IOException | IllegalStateException e) {
            player.inventory.mainInventory[slot] = null;
            player.inventoryContainer.detectAndSendChanges(); failed(player, e); return false;
        }
        player.inventoryContainer.detectAndSendChanges();
        if (guided) sync(player, false, false);
        return true;
    }
    /** A receipt blocks reissuing the first flag even if it is dropped, stored, or a menu is reopened. */
    public static boolean awaitingFlag(EntityPlayer player) {
        try { if (step(player) != PLACE) return false; }
        catch (IllegalStateException e) { failed((EntityPlayerMP)player, e); return true; }
        player.addChatMessage(new ChatComponentTranslation("xf.setup.already_delivered"));
        rejected(player, "xf.setup.already_delivered"); return true;
    }
    /** Finish Forge's item bookkeeping (including creative restoration) before saving progress/inventory. */
    public static void cityFounded(EntityPlayer player) {
        Clowder faction = Clowder.getClowderFromPlayer(player);
        if (faction == null) return;
        final String factionId = faction.uuid;
        com.hfr.tdm.TDMServerTaskQueue.schedule(() -> refreshFaction(factionId));
    }
    public static void homeSet(EntityPlayer player) { refreshFaction(player); }
    private static void refreshFaction(EntityPlayer player) {
        Clowder faction = Clowder.getClowderFromPlayer(player);
        if (faction != null) refreshFaction(faction.uuid);
    }
    private static void refreshFaction(String factionId) {
        for (Object raw : MinecraftServer.getServer().getConfigurationManager().playerEntityList) {
            EntityPlayerMP recipient = (EntityPlayerMP)raw;
            Clowder faction = Clowder.getClowderFromPlayer(recipient);
            if (faction != null && factionId.equals(faction.uuid)) sync(recipient, false, false);
        }
    }
    public static void commandFinished(net.minecraft.command.ICommandSender sender, String[] args) {
        if (!(sender instanceof EntityPlayerMP) || args.length == 0) return;
        if ("create".equalsIgnoreCase(args[0]) || "claim".equalsIgnoreCase(args[0]) || "sethome".equalsIgnoreCase(args[0]))
            sync((EntityPlayerMP)sender, false, false, "xf.setup.retry");
        else if ("leave".equalsIgnoreCase(args[0]) || "join".equalsIgnoreCase(args[0]) || "disband".equalsIgnoreCase(args[0]))
            sync((EntityPlayerMP)sender, false, false);
    }
    public static void intent(EntityPlayerMP player, int action) {
        try {
            NBTTagCompound saved = read(player), guide = saved.getCompoundTag(KEY);
            if (matches(player, guide) && guide.getInteger("Step") < DONE) {
                boolean hidden = action == XFOnboardingPacket.HIDE_SETUP;
                if (guide.getBoolean("Hidden") != hidden) {
                    guide.setBoolean("Hidden", hidden); DailyRewardStore.commit(player, saved);
                }
            }
            sync(player, false, action == XFOnboardingPacket.RESUME_SETUP);
        } catch (IOException | IllegalStateException e) { failed(player, e); }
    }
    public static void sync(EntityPlayerMP player, boolean welcome, boolean open) {
        sync(player, welcome, open, "");
    }
    public static void rejected(EntityPlayer player, String reason) {
        if (player instanceof EntityPlayerMP) sync((EntityPlayerMP)player, false, false, reason);
    }
    private static void sync(EntityPlayerMP player, boolean welcome, boolean open, String feedback) {
        XFOnboardingPacket.State snapshot;
        try {
            NBTTagCompound saved = read(player), guide = saved.getCompoundTag(KEY);
            int step = matches(player, guide) ? guide.getInteger("Step") : 0;
            Clowder faction = Clowder.getClowderFromPlayer(player);
            // Only an explicitly started guide can resume. Existing faction membership never starts one.
            if (step > 0 && step < DONE && (faction.citiesFounded > 0 || faction.getOwnedCityCount() > 0)) {
                int confirmed = faction.homeSet && faction.hasValidBuildGraceHome() ? DONE : HOME;
                if (confirmed > step) {
                    guide.setInteger("Step", confirmed); DailyRewardStore.commit(player, saved); step = confirmed;
                    if (step == DONE) player.addChatMessage(new ChatComponentTranslation("xf.setup.step4"));
                }
            }
            snapshot = new XFOnboardingPacket.State(welcome, step, guide.getBoolean("Hidden"), open,
                    feedback.length() > 240 ? feedback.substring(0, 240) : feedback);
        } catch (IOException | IllegalStateException e) { failed(player, e); return; }
        // Sending is outside the save transaction; a network failure cannot undo committed delivery.
        try { PacketDispatcher.wrapper.sendTo(snapshot, player); }
        catch (RuntimeException e) { XFLog.error("Faction setup saved but synchronization failed for " + player.getUniqueID(), e); }
    }
}
