package com.hfr.command;

import java.util.ArrayList;
import java.util.List;
import com.hfr.entity.EntityFactionMiner;
import net.minecraft.command.CommandBase;
import net.minecraft.command.ICommandSender;
import net.minecraft.entity.Entity;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.ChatComponentTranslation;

/** Diagnostics operate on loaded miners in the sender's dimension, on the server thread. */
public final class CommandXMinerDebug extends CommandBase {
    @Override public String getCommandName() { return "xminerdebug"; }
    @Override public int getRequiredPermissionLevel() { return 3; }
    @Override public String getCommandUsage(ICommandSender sender) { return "xf.miner.debug.usage"; }

    @Override public void processCommand(ICommandSender sender,String[] args) {
        if (!canCommandSenderUseCommand(sender)) {
            sender.addChatMessage(new ChatComponentTranslation("commands.generic.permission")); return;
        }
        if (args.length < 2 || !("inspect".equals(args[1]) ? args.length == 5 : args.length == 2)) {
            sender.addChatMessage(new ChatComponentTranslation(getCommandUsage(sender))); return;
        }
        EntityFactionMiner miner = null;
        // UUID is the existing wand identifier; entity ID is a convenient session-local alias.
        for (Object value : sender.getEntityWorld().loadedEntityList) if (value instanceof EntityFactionMiner) {
            EntityFactionMiner candidate = (EntityFactionMiner)value;
            if (!candidate.isDead && (args[0].equals(candidate.getUniqueID().toString()) || args[0].equals(Integer.toString(candidate.getEntityId())))) {
                miner = candidate; break;
            }
        }
        if (miner == null) { sender.addChatMessage(new ChatComponentTranslation("xf.miner.debug.missing")); return; }
        List<String> lines;
        switch (args[1]) {
        case "on":
        case "off":
            miner.setDebugTracing("on".equals(args[1]));
            sender.addChatMessage(new ChatComponentText("Miner " + miner.getEntityId() + " UUID=" + miner.getUniqueID() + " tracing " + args[1]));
            return;
        case "report": lines = miner.minerDebugReport(false); break;
        case "movement": lines = miner.minerDebugReport(true); break;
        case "inspect":
            lines = miner.inspectMinerCell(parseInt(sender,args[2]),parseInt(sender,args[3]),parseInt(sender,args[4])); break;
        default: sender.addChatMessage(new ChatComponentTranslation(getCommandUsage(sender))); return;
        }
        for (String line : lines) sender.addChatMessage(new ChatComponentText(line));
    }

    @Override public List addTabCompletionOptions(ICommandSender sender,String[] args) {
        if (!canCommandSenderUseCommand(sender)) return null;
        if (args.length == 2) return getListOfStringsMatchingLastWord(args,"on","off","report","movement","inspect");
        if (args.length != 1) return null;
        List<String> ids = new ArrayList<String>();
        for (Object value : sender.getEntityWorld().loadedEntityList) if (value instanceof EntityFactionMiner && !((Entity)value).isDead)
            ids.add(((Entity)value).getUniqueID().toString());
        return getListOfStringsMatchingLastWord(args,ids.toArray(new String[ids.size()]));
    }
}
