package com.hfr.daily;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.nbt.CompressedStreamTools;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.server.MinecraftServer;

/** Inventory and escrow share the native UUID player file: no cross-file grant/receipt gap. */
public final class DailyRewardStore {
    public static final String KEY = "xfDailyRewards";
    private DailyRewardStore() { }
    public static NBTTagCompound read(EntityPlayer player) {
        NBTTagCompound persisted = player.getEntityData().getCompoundTag(EntityPlayer.PERSISTED_NBT_TAG);
        if (persisted.hasKey(KEY)) {
            if (!persisted.hasKey(KEY, 10)) throw new IllegalStateException("Malformed daily compound");
            NBTTagCompound tag = persisted.getCompoundTag(KEY);
            validate(tag); return (NBTTagCompound)tag.copy();
        }
        NBTTagCompound tag = new NBTTagCompound();
        tag.setInteger("Version", 1); tag.setLong("Revision", 0);
        tag.setInteger("Streak", 0); tag.setTag("Pending", new NBTTagList());
        tag.setTag("Milestones", new NBTTagCompound());
        return tag;
    }
    private static void validate(NBTTagCompound tag) {
        if (tag.hasKey("FactionSetup")) {
            NBTTagCompound guide = tag.getCompoundTag("FactionSetup");
            if (!tag.hasKey("FactionSetup", 10) || !guide.hasKey("Faction", 8) || guide.getString("Faction").length() != 36
                    || !guide.hasKey("Step", 3) || guide.getInteger("Step") < 1 || guide.getInteger("Step") > 5
                    || !guide.hasKey("Hidden", 1))
                throw new IllegalStateException("Malformed faction setup state");
        }
        int version = tag.getInteger("Version");
        if ((version < 1 || version > 4) || !tag.hasKey("Revision", 4) || tag.getLong("Revision") < 0
            || !tag.hasKey("Streak", 3) || tag.getInteger("Streak") < 0 || !tag.hasKey("Pending", 9)
            || !tag.hasKey("Milestones", 10)) throw new IllegalStateException("Malformed daily state");
        if (tag.hasKey("AchievementSpins") && (version < 4 || !tag.hasKey("AchievementSpins", 3)
            || tag.getInteger("AchievementSpins") < 0)) throw new IllegalStateException("Malformed achievement spin balance");
        if (tag.hasKey("AchievementSpin") && (version < 4 || !tag.hasKey("AchievementSpin", 1) || !tag.hasKey("Claim", 8)))
            throw new IllegalStateException("Malformed achievement spin kind");
        if (tag.hasKey("Achievements")) {
            if (version < 4 || !tag.hasKey("Achievements", 10)) throw new IllegalStateException("Malformed achievements");
            NBTTagCompound achievements = tag.getCompoundTag("Achievements");
            for (String group : new String[] {"Progress", "Completed", "Rewarded"}) {
                if (!achievements.hasKey(group, 10)) throw new IllegalStateException("Malformed achievement group");
                NBTTagCompound values = achievements.getCompoundTag(group);
                for (Object raw : values.func_150296_c()) {
                    String id = (String)raw;
                    if (group.equals("Progress") ? !values.hasKey(id, 4) || values.getLong(id) < 0 || values.getLong(id) > 1
                        : !values.hasKey(id, 1)) throw new IllegalStateException("Malformed achievement progress/receipt");
                    if (group.equals("Rewarded") && values.getBoolean(id) && !achievements.getCompoundTag("Completed").getBoolean(id))
                        throw new IllegalStateException("Achievement reward without completion");
                }
            }
        }
        if (tag.hasKey("ClaimReset") && (!tag.hasKey("ClaimReset", 1) || !tag.hasKey("Day", 4)))
            throw new IllegalStateException("Malformed daily claim reset");
        if (tag.hasKey("DeliveryDue") && (version < 2 || !tag.hasKey("DeliveryDue", 1)
            || !tag.hasKey("Claim", 8) || tag.hasKey("SpinItems"))) throw new IllegalStateException("Malformed daily delivery state");
        validateItems((NBTTagList)tag.getTag("Pending"), version);
        // Version 1 claims are already complete: never recreate escrow from their result previews.
        if (tag.hasKey("SpinItems") || tag.hasKey("CompleteAt")) {
            if (version < 2 || !tag.hasKey("Claim", 8) || !tag.hasKey("SpinItems", 9)
                || !tag.hasKey("CompleteAt", 4) || tag.getLong("CompleteAt") <= 0)
                throw new IllegalStateException("Malformed unfinished daily spin");
            NBTTagList spinning = (NBTTagList)tag.getTag("SpinItems");
            validateItems(spinning, version);
            if (spinning.tagCount() == 0 || spinning.tagCount() + tag.getTagList("Pending", 10).tagCount() > 8192)
                throw new IllegalStateException("Invalid unfinished daily escrow");
        }
        if (tag.hasKey("Day") || tag.hasKey("Claim")) {
            // Retain zero progress if present; debug claims floor only their multiplier at one.
            if ((tag.hasKey("Day") ? !tag.hasKey("Day", 4) : !tag.getBoolean("AchievementSpin")) || !tag.hasKey("Claim", 8)
                || !tag.hasKey("Result", 10) || !tag.hasKey("Wheel", 9)) throw new IllegalStateException("Incomplete daily claim");
            NBTTagList wheel = tag.getTagList("Wheel", 10);
            if (wheel.tagCount() < 1 || wheel.tagCount() > 16 || tag.getInteger("Selected") < 0 || tag.getInteger("Selected") >= wheel.tagCount())
                throw new IllegalStateException("Invalid saved wheel");
            if (tag.hasKey("AvailableAfter") && (!tag.hasKey("AvailableAfter", 4) || tag.getLong("AvailableAfter") <= 0))
                throw new IllegalStateException("Invalid daily reset instant");
            // Absent multiplier fields mean a legacy fixed amount, even after a schema upgrade.
            NBTTagCompound result = tag.getCompoundTag("Result");
            if (result.hasKey("baseCount") || result.hasKey("multiplier")) {
                if (version < 3 || !result.hasKey("baseCount", 4) || !result.hasKey("multiplier", 4)
                    || !result.hasKey("amount", 4) || result.getLong("baseCount") < 1 || result.getLong("multiplier") < 1)
                    throw new IllegalStateException("Malformed daily multiplied result");
                try {
                    if (Math.multiplyExact(result.getLong("baseCount"), result.getLong("multiplier")) != result.getLong("amount"))
                        throw new IllegalStateException("Inconsistent daily multiplied result");
                } catch (ArithmeticException e) { throw new IllegalStateException("Daily quantity overflow", e); }
            }
        }
        if (!tag.hasKey("Day") && tag.getInteger("Streak") != 0) throw new IllegalStateException("Missing claim date");
    }
    private static void validateItems(NBTTagList pending, int version) {
        if (pending.tagCount() > 8192 || pending.tagCount() > 0 && pending.func_150303_d() != 10)
            throw new IllegalStateException("Malformed daily escrow");
        for (int i = 0; i < pending.tagCount(); i++) {
            NBTTagCompound item = pending.getCompoundTagAt(i);
            if (!item.hasKey("id", 2) || item.getByte("Count") <= 0 || !item.hasKey("Damage", 2) || item.getString("Registry").isEmpty())
                throw new IllegalStateException("Malformed pending item");
            if (item.hasKey("Remaining") && (version < 3 || !item.hasKey("Remaining", 4)
                || item.getLong("Remaining") < 1 || item.getByte("Count") != 1))
                throw new IllegalStateException("Malformed compact daily quantity");
        }
    }
    private static void install(EntityPlayer player, NBTTagCompound tag) {
        NBTTagCompound persisted = player.getEntityData().getCompoundTag(EntityPlayer.PERSISTED_NBT_TAG);
        persisted.setTag(KEY, tag); player.getEntityData().setTag(EntityPlayer.PERSISTED_NBT_TAG, persisted);
    }
    private static Path file(EntityPlayer player) {
        return new File(MinecraftServer.getServer().worldServerForDimension(0).getSaveHandler().getWorldDirectory(),
            "playerdata/" + player.getUniqueID() + ".dat").toPath();
    }
    /** No operation after the atomic move can fail: callers may safely roll back on exception. */
    public static void commit(EntityPlayerMP player, NBTTagCompound next) throws IOException {
        NBTTagCompound persisted = player.getEntityData().getCompoundTag(EntityPlayer.PERSISTED_NBT_TAG);
        NBTTagCompound old = persisted.hasKey(KEY) ? (NBTTagCompound)persisted.getCompoundTag(KEY).copy() : null;
        next.setLong("Revision", read(player).getLong("Revision") + 1);
        next.setInteger("Version", 4);
        validate(next); install(player, next);
        try {
            NBTTagCompound full = new NBTTagCompound(); player.writeToNBT(full);
            Path target = file(player), temporary = target.resolveSibling(player.getUniqueID() + ".daily.tmp");
            player.worldObj.getSaveHandler().checkSessionLock();
            Files.createDirectories(target.getParent());
            try (FileOutputStream out = new FileOutputStream(temporary.toFile())) { CompressedStreamTools.writeCompressed(full, out); }
            try (FileOutputStream out = new FileOutputStream(temporary.toFile(), true)) { out.getFD().sync(); }
            // No non-atomic delete/rename fallback; failure must retain the previous complete player state.
            Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (Exception e) {
            if (old == null) player.getEntityData().getCompoundTag(EntityPlayer.PERSISTED_NBT_TAG).removeTag(KEY);
            else install(player, old);
            throw new IOException("Daily player save failed", e);
        }
    }
    /** Integrated servers may load level.dat's older Player tag instead of the newer UUID file. */
    public static void reconcileLoad(EntityPlayer player) throws IOException {
        Path path = file(player);
        if (!Files.exists(path)) return;
        NBTTagCompound full;
        try (FileInputStream in = new FileInputStream(path.toFile())) { full = CompressedStreamTools.readCompressed(in); }
        NBTTagCompound persisted = full.getCompoundTag("ForgeData").getCompoundTag(EntityPlayer.PERSISTED_NBT_TAG);
        if (!persisted.hasKey(KEY)) return;
        NBTTagCompound disk = persisted.getCompoundTag(KEY); validate(disk);
        if (disk.getLong("Revision") > read(player).getLong("Revision")) {
            // Restore the complete committed snapshot, including its matching inventory, before login.
            if (full.getLong("UUIDMost") != player.getUniqueID().getMostSignificantBits()
                || full.getLong("UUIDLeast") != player.getUniqueID().getLeastSignificantBits()) throw new IOException("Daily UUID mismatch");
            player.readFromNBT(full);
        }
    }
}
