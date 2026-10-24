package com.hfr.daily;

import com.hfr.packet.PacketDispatcher;
import com.hfr.packet.daily.DailyActionPacket;
import com.hfr.packet.daily.DailySnapshotPacket;
import com.hfr.tdm.TDMAdminKitEdit;
import com.hfr.tdm.TDMManager;
import com.hfr.util.XFLog;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.PlayerEvent;
import cpw.mods.fml.common.gameevent.TickEvent;
import java.io.IOException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.WeakHashMap;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.ChatComponentTranslation;

/** All state, selection and inventory mutation is confined to the owning server thread. */
public final class DailyRewardService {
    private static final Map<EntityPlayerMP, Session> SESSIONS = new WeakHashMap<EntityPlayerMP, Session>();
    private static final Map<EntityPlayer, Boolean> BLOCKED = new WeakHashMap<EntityPlayer, Boolean>();
    private static final Map<EntityPlayerMP, Completion> COMPLETIONS = new WeakHashMap<EntityPlayerMP, Completion>();
    private static final Map<EntityPlayer, Boolean> LOGIN_OPENED = new WeakHashMap<EntityPlayer, Boolean>();
    // UUIDs retain each online player's interval across respawns and dimension changes.
    private static final Map<UUID, Long> ACHIEVEMENT_REMINDERS = new HashMap<UUID, Long>();
    private static final long ACHIEVEMENT_REMINDER_MILLIS = 5 * 60 * 1000L;
    private static final Random RANDOM = new Random();
    private int ticks;
    private static final class Session {
        final String token = UUID.randomUUID().toString();
        long revision, day, unlockAt;
        String animationClaim = "";
        boolean animationFinished, resultAvailable, resultShown;
    }
    private static final class Completion {
        long checkAt;
        boolean failureReported;
        Completion(long checkAt) { this.checkAt = checkAt; }
    }
    private static boolean unfinished(NBTTagCompound saved) { return saved.hasKey("SpinItems"); }
    private static void schedule(EntityPlayerMP player, NBTTagCompound saved) {
        if ((unfinished(saved) || saved.getBoolean("DeliveryDue")) && !COMPLETIONS.containsKey(player))
            COMPLETIONS.put(player, new Completion(unfinished(saved) ? saved.getLong("CompleteAt") : 0));
    }
    private static long today() { return LocalDate.now(DailyRewardConfig.active().zone).toEpochDay(); }
    private static long availableAfter(NBTTagCompound state) {
        if (!state.hasKey("Day")) return 0;
        // Old claims have no instant. Retain the imported timezone permanently for offline legacy players.
        return state.hasKey("AvailableAfter") ? state.getLong("AvailableAfter") : LocalDate.ofEpochDay(state.getLong("Day"))
            .plusDays(1).atStartOfDay(DailyRewardConfig.active().legacyZone).toInstant().toEpochMilli();
    }
    private static boolean eligible(NBTTagCompound state, long day) {
        return state.getBoolean("ClaimReset") || !state.hasKey("Day")
            || state.getLong("Day") < day && System.currentTimeMillis() >= availableAfter(state);
    }
    private static int nextStreak(NBTTagCompound state, long day) {
        // A debug spin retains progress rather than farming or resetting the streak on the same date.
        if (state.getBoolean("ClaimReset")) return state.getInteger("Streak");
        return state.hasKey("Day") && state.getLong("Day") == day - 1
            ? state.getInteger("Streak") == Integer.MAX_VALUE ? Integer.MAX_VALUE : state.getInteger("Streak") + 1 : 1;
    }
    private static void say(EntityPlayer player, String key, Object... args) { player.addChatMessage(new ChatComponentTranslation("daily.hfr." + key, args)); }
    private static NBTTagCompound state(EntityPlayer player) {
        if (BLOCKED.containsKey(player)) throw new IllegalStateException("Daily player data failed to load");
        return DailyRewardStore.read(player);
    }
    public static void clear() { SESSIONS.clear(); BLOCKED.clear(); COMPLETIONS.clear(); LOGIN_OPENED.clear(); ACHIEVEMENT_REMINDERS.clear(); com.hfr.main.XFAchievements.clearTracking(); }
    public static NBTTagCompound achievementState(EntityPlayerMP player) { return state(player); }
    public static void achievementBalanceChanged(EntityPlayerMP player) {
        Session session = SESSIONS.get(player);
        if (session != null && online(player)) snapshot(player, session, false, false);
    }
    public static int reset(String administrator, EntityPlayerMP player) throws IOException {
        NBTTagCompound saved = state(player);
        if (saved.hasKey("Day")) saved.setBoolean("ClaimReset", true);
        DailyRewardStore.commit(player, saved);
        XFLog.info("Daily claim reset by " + administrator + " for " + player.getCommandSenderName() + " UUID=" + player.getUniqueID());
        Session session = SESSIONS.get(player);
        if (session != null) {
            // Rotate the token as well as the revision: even a queued close from the old GUI is stale.
            Session refreshed = new Session(); SESSIONS.put(player, refreshed);
            try { if (online(player)) snapshot(player, refreshed, true, false); }
            catch (RuntimeException e) { XFLog.error("Daily reset saved but GUI refresh failed for " + player.getUniqueID(), e); }
        }
        say(player, "claim_reset");
        return saved.getTagList("Pending", 10).tagCount();
    }
    /** Called only after durable publication; do not turn a notification failure into a failed save. */
    public static void definitionsChanged() {
        for (Map.Entry<EntityPlayerMP, Session> entry : SESSIONS.entrySet()) {
            entry.getValue().revision++; // Invalidate queued intents even if a snapshot cannot be sent.
            try { if (online(entry.getKey())) snapshot(entry.getKey(), entry.getValue(), false, false); }
            catch (RuntimeException e) { XFLog.error("Daily live GUI refresh failed for " + entry.getKey().getUniqueID(), e); }
        }
    }
    public static void open(EntityPlayerMP player) {
        open(player, true);
    }
    private static void open(EntityPlayerMP player, boolean closeContainer) {
        if (!online(player)) return;
        if (LOGIN_OPENED.containsKey(player)) LOGIN_OPENED.put(player, true);
        if (closeContainer) player.closeScreen();
        Session session = new Session(); SESSIONS.put(player, session);
        snapshot(player, session, true, false);
    }
    /** Readiness is only a hint: loaded server state authorizes this once-per-login opening. */
    public static void loginReady(EntityPlayerMP player) {
        if (!Boolean.FALSE.equals(LOGIN_OPENED.get(player)) || !online(player)) return;
        LOGIN_OPENED.put(player, true);
        try {
            NBTTagCompound saved = state(player);
            if (!unfinished(saved) && !saved.getBoolean("DeliveryDue")
                && DailyRewardConfig.ready() && eligible(saved, today())) open(player, false);
        } catch (IllegalStateException e) { say(player, "unavailable"); }
    }
    private static boolean online(EntityPlayerMP player) {
        return !player.isDead && MinecraftServer.getServer().getConfigurationManager().playerEntityList.contains(player)
            && player.playerNetServerHandler.netManager.isChannelOpen();
    }
    public static void action(EntityPlayerMP player, String token, long revision, int action, String claim) {
        Session session = SESSIONS.get(player);
        if (session == null || !session.token.equals(token) || !online(player)) return;
        if (action == 0) { SESSIONS.remove(player); return; }
        // An acknowledgement only releases this session's animation gate. The durable server
        // deadline remains mandatory; revisions may change while the same wheel is on screen.
        if (action == 3) {
            if (session.animationClaim.equals(claim)) session.animationFinished = true;
            return;
        }
        if (action == 4) {
            if (session.resultAvailable && session.animationClaim.equals(claim)) session.resultShown = true;
            return;
        }
        if (revision != session.revision) { snapshot(player, session, false, false); return; }
        session.revision++; // Every intent is single use, even a rejected claim or failed save.
        boolean spin = false;
        try {
            NBTTagCompound saved = state(player);
            if (action == 1 || action == 5) {
                boolean earned = action == 5;
                long day = today();
                DailyRewardConfig.Table table = DailyRewardConfig.active();
                if (table.rewards.isEmpty()) say(player, "empty");
                else if (!DailyRewardConfig.ready()) say(player, "disabled");
                else if (unfinished(saved) || saved.getBoolean("DeliveryDue")) say(player, "claimed");
                else if (earned ? saved.getInteger("AchievementSpins") <= 0 : !eligible(saved, day)) say(player, earned ? "no_achievement_spins" : "claimed");
                else if (saved.getTagList("Pending", 10).tagCount() > 0) say(player, "collect_first");
                else {
                    int chosen = RANDOM.nextInt(table.totalWeight), selected = 0;
                    for (; selected < table.rewards.size() - 1; selected++) {
                        chosen -= table.rewards.get(selected).weight;
                        if (chosen < 0) break;
                    }
                    DailyRewardConfig.Reward reward = table.rewards.get(selected);
                    NBTTagList pending = new NBTTagList(), wheel = new NBTTagList();
                    int streak = earned ? 0 : nextStreak(saved, day);
                    // Freeze this claim's amount, independently of later streak/reset/definition changes.
                    long multiplier = Math.max(1L, (long)streak);
                    for (DailyRewardConfig.Reward entry : table.rewards) wheel.appendTag(entry.preview(multiplier));
                    NBTTagCompound result = (NBTTagCompound)wheel.getCompoundTagAt(selected).copy();
                    reward.append(pending, result.getLong("amount"));
                    NBTTagCompound reached = saved.getCompoundTag("Milestones");
                    DailyRewardConfig.Reward bonus = !earned && table.milestones.containsKey(streak) ? table.reward(table.milestones.get(streak)) : null;
                    if (bonus != null && !reached.hasKey(Integer.toString(streak))) {
                        bonus.append(pending); reached.setBoolean(Integer.toString(streak), true);
                        saved.setTag("Bonus", bonus.preview());
                    } else saved.removeTag("Bonus");
                    if (pending.tagCount() > 8192) throw new IllegalStateException("Daily escrow capacity exceeded");
                    if (earned) saved.setInteger("AchievementSpins", saved.getInteger("AchievementSpins") - 1);
                    else {
                        saved.setLong("Day", day); saved.setInteger("Streak", streak);
                        saved.removeTag("ClaimReset");
                        saved.setLong("AvailableAfter", LocalDate.ofEpochDay(day).plusDays(1).atStartOfDay(table.zone).toInstant().toEpochMilli());
                    }
                    saved.setBoolean("AchievementSpin", earned);
                    saved.setString("Claim", UUID.randomUUID().toString()); saved.setTag("Result", result);
                    saved.setTag("Wheel", wheel); saved.setInteger("Selected", selected);
                    saved.setTag("SpinItems", pending); saved.setTag("Milestones", reached);
                    saved.setLong("CompleteAt", System.currentTimeMillis() + DailyActionPacket.SPIN_MILLIS);
                    // Acceptance is durable BEFORE any inventory grant or client animation.
                    DailyRewardStore.commit(player, saved); spin = true;
                    schedule(player, saved);
                }
            } else if (action == 2) deliver(player);
        } catch (IOException e) {
            XFLog.error("Daily reward save failed for " + player.getUniqueID() + "; uncommitted changes rolled back", e);
            say(player, "save_failed");
        } catch (IllegalStateException e) {
            XFLog.error("Daily rewards unavailable for " + player.getUniqueID(), e); say(player, "unavailable");
        } catch (ArithmeticException e) {
            XFLog.error("Daily reward quantity overflow for " + player.getUniqueID() + "; claim not accepted", e); say(player, "unavailable");
        }
        snapshot(player, session, false, spin);
    }
    /** Never use vanilla addItemStackToInventory: creative mode can consume leftovers there. */
    private static void deliver(EntityPlayerMP player) throws IOException {
        NBTTagCompound saved = state(player); NBTTagList pending = saved.getTagList("Pending", 10);
        boolean deliveryDue = saved.getBoolean("DeliveryDue");
        saved.removeTag("DeliveryDue");
        if (pending.tagCount() == 0) {
            if (deliveryDue) DailyRewardStore.commit(player, saved);
            return;
        }
        if (TDMAdminKitEdit.isEditing(player) || TDMManager.isEnabled(player.worldObj)
            || player.openContainer != player.inventoryContainer || player.inventory.getItemStack() != null) {
            if (deliveryDue) DailyRewardStore.commit(player, saved);
            say(player, "delivery_deferred"); return;
        }
        ItemStack[] before = new ItemStack[player.inventory.mainInventory.length];
        for (int i = 0; i < before.length; i++) before[i] = ItemStack.copyItemStack(player.inventory.mainInventory[i]);
        NBTTagList remaining = new NBTTagList(); boolean changed = false, missing = false;
        try {
            for (int i = 0; i < pending.tagCount(); i++) {
                NBTTagCompound raw = pending.getCompoundTagAt(i);
                String registry = raw.getString("Registry");
                if (registry.isEmpty() || !Item.itemRegistry.containsKey(registry)) {
                    remaining.appendTag(raw.copy()); missing = true; continue;
                }
                NBTTagCompound resolved = (NBTTagCompound)raw.copy();
                resolved.setShort("id", (short)Item.getIdFromItem((Item)Item.itemRegistry.getObject(registry)));
                ItemStack stack = ItemStack.loadItemStackFromNBT(resolved);
                if (stack == null) { remaining.appendTag(raw.copy()); missing = true; continue; }
                // Legacy stacks are fixed quantities too; neither path consults a streak or definition.
                long quantity = raw.hasKey("Remaining") ? raw.getLong("Remaining") : stack.stackSize;
                long original = quantity;
                stack.stackSize = 1;
                int limit = Math.min(64, Math.min(player.inventory.getInventoryStackLimit(), stack.getMaxStackSize()));
                for (int pass = 0; pass < 2 && quantity > 0; pass++) {
                    for (int slot = 0; slot < before.length && quantity > 0; slot++) {
                        ItemStack existing = player.inventory.mainInventory[slot];
                        if (pass == 0 && existing != null && existing.isItemEqual(stack) && ItemStack.areItemStackTagsEqual(existing, stack)) {
                            int capacity = Math.max(0, Math.min(limit, existing.getMaxStackSize()) - existing.stackSize);
                            int move = (int)Math.min(quantity, (long)capacity);
                            existing.stackSize += move; quantity -= move;
                        } else if (pass == 1 && existing == null) {
                            int move = (int)Math.min(quantity, (long)Math.max(0, limit));
                            if (move > 0) { ItemStack part = stack.copy(); part.stackSize = move; player.inventory.mainInventory[slot] = part; quantity -= move; }
                        }
                    }
                }
                changed |= quantity != original;
                if (quantity > 0) {
                    NBTTagCompound leftover = (NBTTagCompound)raw.copy();
                    // Preserve all item data, normalizing only the template count for compact storage.
                    leftover.setByte("Count", (byte)1); leftover.setLong("Remaining", quantity);
                    remaining.appendTag(leftover);
                }
            }
            if (changed || deliveryDue) {
                saved.setTag("Pending", remaining);
                // Both the inventory afterimage and reduced escrow become visible in one atomic file replacement.
                DailyRewardStore.commit(player, saved);
            }
        } catch (IOException | RuntimeException e) {
            for (int slot = 0; slot < before.length; slot++) player.inventory.mainInventory[slot] = before[slot];
            throw e;
        }
        if (changed) { player.inventory.markDirty(); player.inventoryContainer.detectAndSendChanges(); say(player, "delivered"); }
        if (remaining.tagCount() > 0) say(player, missing ? "missing_item" : "inventory_full");
    }
    private static void snapshot(EntityPlayerMP player, Session session, boolean opening, boolean spin) {
        NBTTagCompound out = new NBTTagCompound();
        out.setString("token", session.token); out.setLong("revision", ++session.revision);
        out.setBoolean("opening", opening); out.setBoolean("spin", spin);
        long day = today(); session.day = day; session.unlockAt = 0;
        DailyRewardConfig.Table table = DailyRewardConfig.active();
        long reset = LocalDate.ofEpochDay(day).plusDays(1).atStartOfDay(table.zone).toInstant().toEpochMilli();
        try {
            NBTTagCompound saved = state(player);
            boolean unfinished = unfinished(saved);
            schedule(player, saved);
            out.setBoolean("unfinished", unfinished);
            out.setBoolean("achievementSpin", saved.getBoolean("AchievementSpin"));
            out.setInteger("achievementSpins", saved.getInteger("AchievementSpins"));
            out.setBoolean("achievementEligible", !unfinished && !saved.getBoolean("DeliveryDue")
                && saved.getTagList("Pending", 10).tagCount() == 0 && saved.getInteger("AchievementSpins") > 0 && DailyRewardConfig.ready());
            out.setBoolean("deliveryDue", saved.getBoolean("DeliveryDue"));
            session.resultAvailable = !unfinished && saved.getBoolean("DeliveryDue");
            if (session.resultAvailable && session.animationClaim.isEmpty()) session.animationClaim = saved.getString("Claim");
            if (unfinished) {
                out.setLong("remainingMillis", Math.max(0, Math.min(DailyActionPacket.SPIN_MILLIS,
                    saved.getLong("CompleteAt") - System.currentTimeMillis())));
                if (!session.animationClaim.equals(saved.getString("Claim"))) {
                    session.animationClaim = saved.getString("Claim"); session.animationFinished = false; session.resultShown = false;
                }
            }
            long available = availableAfter(saved);
            if (!saved.getBoolean("ClaimReset") && available > System.currentTimeMillis()) session.unlockAt = available;
            boolean eligible = eligible(saved, day);
            if (saved.hasKey("Day") && !eligible) reset = Math.max(available, LocalDate.ofEpochDay(saved.getLong("Day"))
                .plusDays(1).atStartOfDay(table.zone).toInstant().toEpochMilli());
            int current = saved.hasKey("Day") && (saved.getBoolean("ClaimReset") || saved.getLong("Day") >= day - 1)
                ? saved.getInteger("Streak") : 0;
            out.setInteger("streak", current);
            out.setInteger("nextStreak", eligible ? nextStreak(saved, day) : current == Integer.MAX_VALUE ? current : current + 1);
            out.setLong("nextMultiplier", Math.max(1L, (long)out.getInteger("nextStreak")));
            out.setBoolean("eligible", !unfinished && !saved.getBoolean("DeliveryDue") && eligible && DailyRewardConfig.ready());
            out.setInteger("pending", saved.getTagList("Pending", 10).tagCount());
            out.setBoolean("disabled", !DailyRewardConfig.ready());
            out.setBoolean("empty", table.rewards.isEmpty());
            out.setString("claim", saved.getString("Claim"));
            if (!unfinished && saved.hasKey("Result")) out.setTag("result", saved.getTag("Result").copy());
            if (!unfinished && saved.hasKey("Bonus")) out.setTag("bonus", saved.getTag("Bonus").copy());
            NBTTagList wheel = new NBTTagList();
            if (saved.hasKey("Claim") && (unfinished || saved.getBoolean("DeliveryDue") || !eligible || saved.getTagList("Pending", 10).tagCount() > 0)) {
                wheel = saved.getTagList("Wheel", 10); out.setInteger("selected", saved.getInteger("Selected"));
            } else {
                for (DailyRewardConfig.Reward reward : table.rewards) wheel.appendTag(reward.preview(out.getLong("nextMultiplier")));
                out.setInteger("selected", -1);
            }
            // The saved wheel keeps its accepted multiplier, including during animation and resets.
            long multiplier = out.getInteger("selected") >= 0
                ? Math.max(1L, saved.getCompoundTag("Result").getLong("multiplier")) : out.getLong("nextMultiplier");
            out.setLong("wheelMultiplier", multiplier);
            out.setTag("wheel", wheel);
            for (Map.Entry<Integer, String> milestone : table.milestones.entrySet()) {
                if (milestone.getKey() > current && !saved.getCompoundTag("Milestones").hasKey(milestone.getKey().toString())) {
                    out.setInteger("milestone", milestone.getKey()); out.setTag("milestoneReward", table.reward(milestone.getValue()).preview()); break;
                }
            }
        } catch (IllegalStateException e) { out.setBoolean("unavailable", true); }
        out.setString("reset", Instant.ofEpochMilli(reset).atZone(table.zone).format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm z")));
        PacketDispatcher.wrapper.sendTo(new DailySnapshotPacket(out), player);
    }
    @SubscribeEvent public void load(net.minecraftforge.event.entity.player.PlayerEvent.LoadFromFile event) {
        if (event.entityPlayer.worldObj.isRemote) return;
        try { DailyRewardStore.reconcileLoad(event.entityPlayer); state(event.entityPlayer); }
        catch (Exception e) { BLOCKED.put(event.entityPlayer, true); XFLog.error("Daily player data could not be validated; claims disabled for " + event.playerUUID, e); }
    }
    @SubscribeEvent public void login(PlayerEvent.PlayerLoggedInEvent event) {
        if (!(event.player instanceof EntityPlayerMP)) return;
        ACHIEVEMENT_REMINDERS.putIfAbsent(event.player.getUniqueID(), System.currentTimeMillis() + ACHIEVEMENT_REMINDER_MILLIS);
        try {
            NBTTagCompound saved = state(event.player);
            if (!LOGIN_OPENED.containsKey(event.player)) LOGIN_OPENED.put(event.player, false);
            schedule((EntityPlayerMP)event.player, saved);
            if (!unfinished(saved) && DailyRewardConfig.ready() && eligible(saved, today())) say(event.player, "login");
            if (saved.getTagList("Pending", 10).tagCount() > 0) say(event.player, "pending");
        } catch (IllegalStateException e) { say(event.player, "unavailable"); }
    }
    @SubscribeEvent public void logout(PlayerEvent.PlayerLoggedOutEvent event) {
        SESSIONS.remove(event.player); BLOCKED.remove(event.player); COMPLETIONS.remove(event.player); LOGIN_OPENED.remove(event.player);
        ACHIEVEMENT_REMINDERS.remove(event.player.getUniqueID());
    }
    @SubscribeEvent public void dimension(PlayerEvent.PlayerChangedDimensionEvent event) { SESSIONS.remove(event.player); }
    @SubscribeEvent public void clone(net.minecraftforge.event.entity.player.PlayerEvent.Clone event) {
        SESSIONS.remove(event.original);
        COMPLETIONS.remove(event.original);
        Boolean opened = LOGIN_OPENED.remove(event.original);
        if (opened != null) LOGIN_OPENED.put(event.entityPlayer, opened);
        if (BLOCKED.containsKey(event.original)) BLOCKED.put(event.entityPlayer, true);
        // Forge clonePlayer already carries PlayerPersisted, including escrow and milestone receipts.
        if (event.entityPlayer instanceof EntityPlayerMP && !BLOCKED.containsKey(event.entityPlayer))
            schedule((EntityPlayerMP)event.entityPlayer, state(event.entityPlayer));
    }
    /** Move escrow exactly once before attempting the existing atomic inventory/escrow transaction. */
    private static boolean complete(EntityPlayerMP player, long now) throws IOException {
        NBTTagCompound saved = state(player);
        Session session = SESSIONS.get(player);
        if (unfinished(saved)) {
            if (now < saved.getLong("CompleteAt")) return false;
            if (session != null && session.animationClaim.equals(saved.getString("Claim")) && !session.animationFinished) return false;
            NBTTagList pending = saved.getTagList("Pending", 10), spinning = saved.getTagList("SpinItems", 10);
            for (int i = 0; i < spinning.tagCount(); i++) pending.appendTag(spinning.getCompoundTagAt(i).copy());
            saved.setTag("Pending", pending); saved.removeTag("SpinItems"); saved.removeTag("CompleteAt");
            saved.setBoolean("DeliveryDue", true);
            DailyRewardStore.commit(player, saved);
            // Wait for a rendered result before sending inventory/message packets to an open wheel.
            if (session != null) snapshot(player, session, false, false);
        }
        if (!saved.getBoolean("DeliveryDue")) return true;
        if (session != null && session.animationClaim.equals(saved.getString("Claim")) && !session.resultShown) return false;
        // Failure here leaves completed, collectible items; it must never recreate the spin escrow.
        try { deliver(player); }
        finally { if (session != null) snapshot(player, session, false, false); }
        return true;
    }
    private static void remindAchievementSpins(long now) {
        for (Object raw : MinecraftServer.getServer().getConfigurationManager().playerEntityList) {
            EntityPlayerMP player = (EntityPlayerMP)raw;
            if (!online(player)) continue;
            UUID id = player.getUniqueID();
            Long checkAt = ACHIEVEMENT_REMINDERS.get(id);
            if (checkAt == null) {
                ACHIEVEMENT_REMINDERS.put(id, now + ACHIEVEMENT_REMINDER_MILLIS);
            } else if (now >= checkAt) {
                // Advance before checking/sending so repeated callbacks cannot duplicate reminders.
                ACHIEVEMENT_REMINDERS.put(id, now + ACHIEVEMENT_REMINDER_MILLIS);
                try {
                    if (state(player).getInteger("AchievementSpins") > 0)
                        player.addChatMessage(new ChatComponentText("You have unclaimed achievement spin rewards! Run /xdaily to use them."));
                } catch (IllegalStateException e) {
                    // Invalid or blocked data already has load diagnostics; do not infer a balance.
                }
            }
        }
    }
    @SubscribeEvent public void tick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        long now = System.currentTimeMillis();
        Iterator<Map.Entry<EntityPlayerMP, Completion>> iterator = COMPLETIONS.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<EntityPlayerMP, Completion> entry = iterator.next();
            EntityPlayerMP player = entry.getKey(); Completion completion = entry.getValue();
            if (now < completion.checkAt || !online(player)) continue;
            try { if (complete(player, now)) iterator.remove(); }
            catch (IOException | RuntimeException e) {
                completion.checkAt = now + 5000;
                if (!completion.failureReported) {
                    completion.failureReported = true;
                    XFLog.error("Daily completion/delivery failed for " + player.getUniqueID() + "; saved escrow retained", e);
                    say(player, e instanceof IOException ? "save_failed" : "unavailable");
                }
            }
        }
        if (++ticks % 20 != 0) return;
        remindAchievementSpins(now);
        if (SESSIONS.isEmpty()) return;
        long day = today();
        for (Map.Entry<EntityPlayerMP, Session> entry : SESSIONS.entrySet())
            if ((entry.getValue().day != day || entry.getValue().unlockAt > 0 && entry.getValue().unlockAt <= System.currentTimeMillis())
                && online(entry.getKey())) snapshot(entry.getKey(), entry.getValue(), false, false);
    }
}
