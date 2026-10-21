package com.hfr.util;

import java.util.regex.Pattern;

/** Formatting-code removal available on both physical sides. */
public final class ChatFormatting {
    private static final Pattern FORMATTING_CODES = Pattern.compile("(?i)\u00a7[0-9A-FK-OR]");

    private ChatFormatting() { }

    /** Matches 1.7.10 EnumChatFormatting's client-only stripper, including null input. */
    public static String stripFormatting(String text) {
        return text == null ? null : FORMATTING_CODES.matcher(text).replaceAll("");
    }
}
