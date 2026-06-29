package com.vantablack4.essentials.commands.integration;

import java.util.function.Predicate;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;

import com.vantablack4.essentials.Messages;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;

public final class IntegrationCommands {
    private static final String ARGUMENTS_ARGUMENT = "arguments";

    private IntegrationCommands() {
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher, Predicate<CommandSourceStack> admin) {
        Predicate<CommandSourceStack> any = source -> true;
        registerAll(
            dispatcher,
            name -> compatibilityCommand(name, admin, "EssentialsX Discord broadcast"),
            "discordbroadcast",
            "dbroadcast",
            "dbc",
            "dbcast",
            "ediscordbroadcast",
            "edbroadcast",
            "edbc",
            "edbcast"
        );
        registerAll(dispatcher, name -> compatibilityCommand(name, any, "EssentialsX Discord"), "discord", "ediscord");
        registerAll(dispatcher, name -> compatibilityCommand(name, any, "EssentialsX DiscordLink"), "link", "elink", "discordlink", "ediscordlink");
        registerAll(dispatcher, name -> compatibilityCommand(name, any, "EssentialsX DiscordLink"), "unlink", "eunlink", "discordunlink", "ediscordunlink");
        registerAll(dispatcher, name -> compatibilityCommand(name, any, "EssentialsXMPP"), "setxmpp", "xmpp");
        registerAll(dispatcher, name -> compatibilityCommand(name, admin, "EssentialsXMPP spy"), "xmppspy");
    }

    private static LiteralArgumentBuilder<CommandSourceStack> compatibilityCommand(
        String name,
        Predicate<CommandSourceStack> requirement,
        String integration
    ) {
        return Commands.literal(name)
            .requires(requirement)
            .executes(context -> notConfigured(context, integration))
            .then(Commands.argument(ARGUMENTS_ARGUMENT, StringArgumentType.greedyString())
                .executes(context -> notConfigured(context, integration)));
    }

    private static int notConfigured(CommandContext<CommandSourceStack> context, String integration) {
        context.getSource().sendSystemMessage(Messages.error(
            integration + " is not configured in this Fabric port. Install and wire a Fabric bridge module before enabling this command."
        ));
        return 0;
    }

    private static void registerAll(
        CommandDispatcher<CommandSourceStack> dispatcher,
        CommandFactory factory,
        String... names
    ) {
        for (String name : names) {
            dispatcher.register(factory.create(name));
        }
    }

    @FunctionalInterface
    private interface CommandFactory {
        LiteralArgumentBuilder<CommandSourceStack> create(String name);
    }
}
