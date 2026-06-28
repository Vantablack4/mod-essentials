package com.vantablack4.essentials;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;

public final class Messages {
    private Messages() {
    }

    public static Component header(String text) {
        return Component.literal("VANTABLACK ESSENTIALS: ")
            .withStyle(ChatFormatting.GOLD)
            .append(Component.literal(text).withStyle(ChatFormatting.WHITE));
    }

    public static Component line(String label, String value) {
        return Component.literal(label + ": ")
            .withStyle(ChatFormatting.GRAY)
            .append(Component.literal(value).withStyle(ChatFormatting.WHITE));
    }

    public static Component success(String value) {
        return Component.literal(value).withStyle(ChatFormatting.GREEN);
    }

    public static Component error(String value) {
        return Component.literal("HATA: ")
            .withStyle(ChatFormatting.RED)
            .append(Component.literal(value).withStyle(ChatFormatting.WHITE));
    }

    public static Component usage(String value) {
        return Component.literal("KULLANIM: ")
            .withStyle(ChatFormatting.YELLOW)
            .append(Component.literal(value).withStyle(ChatFormatting.WHITE));
    }
}
