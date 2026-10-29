package com.hfr.compat;

import java.lang.reflect.Method;
import net.minecraft.item.ItemStack;

/** Miner-only eligibility for HBM powered ability tools; no HBM linkage or NBT guesses. */
public final class HbmMiningToolEnergy {
    private HbmMiningToolEnergy() { }

    private static final class Check {
        final boolean powered;
        final Method canOperate;
        Check(boolean powered, Method canOperate) { this.powered = powered; this.canOperate = canOperate; }
    }

    private static final ClassValue<Check> CHECKS = new ClassValue<Check>() {
        protected Check computeValue(Class<?> itemType) {
            for (Class<?> type = itemType; type != null; type = type.getSuperclass()) {
                if (!"com.hbm.items.tool.ItemToolAbilityPower".equals(type.getName())) continue;
                try { return new Check(true, itemType.getMethod("canOperate", ItemStack.class)); }
                catch (ReflectiveOperationException | SecurityException error) { return new Check(true, null); }
            }
            return new Check(false, null);
        }
    };

    /** Empty means no energy rejection. Never changes the supplied stack or spends charge. */
    public static String problem(ItemStack stack) {
        if (stack == null || stack.stackSize <= 0) return "";
        Check check = CHECKS.get(stack.getItem().getClass());
        if (!check.powered) return "";
        if (check.canOperate != null) try {
            // HBM canOperate compares getCharge with consumption. getCharge initializes
            // untagged stacks, so query a copy to preserve even that native default safely.
            return Boolean.TRUE.equals(check.canOperate.invoke(stack.getItem(), stack.copy())) ? "" : "tool_depleted";
        } catch (ReflectiveOperationException | RuntimeException | LinkageError error) {
            // A recognized powered tool with an unreadable API must not mine for free.
        }
        return "tool_energy_error";
    }
}
