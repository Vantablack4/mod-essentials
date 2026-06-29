package com.vantablack4.essentials.commands.gui;

import java.util.List;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;

import com.vantablack4.essentials.Messages;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.inventory.AnvilMenu;
import net.minecraft.world.inventory.CartographyTableMenu;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.CraftingMenu;
import net.minecraft.world.inventory.GrindstoneMenu;
import net.minecraft.world.inventory.LoomMenu;
import net.minecraft.world.inventory.MenuConstructor;
import net.minecraft.world.inventory.SmithingMenu;
import net.minecraft.world.inventory.StonecutterMenu;

public final class GuiUtilityCommands {
    private static final List<GuiCommand> COMMANDS = List.of(
        new GuiCommand(
            "workbench",
            "Workbench",
            (syncId, inventory, player) -> new CraftingMenu(syncId, inventory),
            "craft", "ecraft", "wb", "ewb", "wbench", "ewbench", "eworkbench"
        ),
        new GuiCommand("anvil", "Anvil", (syncId, inventory, player) -> new AnvilMenu(syncId, inventory), "eanvil"),
        new GuiCommand(
            "cartographytable",
            "Cartography Table",
            (syncId, inventory, player) -> new CartographyTableMenu(syncId, inventory),
            "ecartographytable", "carttable", "ecarttable"
        ),
        new GuiCommand("grindstone", "Grindstone", (syncId, inventory, player) -> new GrindstoneMenu(syncId, inventory), "egrindstone"),
        new GuiCommand("loom", "Loom", (syncId, inventory, player) -> new LoomMenu(syncId, inventory), "eloom"),
        new GuiCommand(
            "smithingtable",
            "Smithing Table",
            (syncId, inventory, player) -> new SmithingMenu(syncId, inventory),
            "esmithingtable", "smithtable", "esmithtable"
        ),
        new GuiCommand("stonecutter", "Stonecutter", (syncId, inventory, player) -> new StonecutterMenu(syncId, inventory), "estonecutter"),
        new GuiCommand(
            "disposal",
            "Disposal",
            (syncId, inventory, player) -> ChestMenu.fourRows(syncId, inventory),
            "edisposal", "trash", "etrash"
        )
    );

    private GuiUtilityCommands() {
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        for (GuiCommand command : COMMANDS) {
            dispatcher.register(command(command.name(), command));
            for (String alias : command.aliases()) {
                dispatcher.register(command(alias, command));
            }
        }
    }

    private static LiteralArgumentBuilder<CommandSourceStack> command(String name, GuiCommand command) {
        return Commands.literal(name)
            .executes(context -> open(context, command));
    }

    private static int open(CommandContext<CommandSourceStack> context, GuiCommand command) throws CommandSyntaxException {
        ServerPlayer player = context.getSource().getPlayerOrException();
        if (player.openMenu(new SimpleMenuProvider(command.menuConstructor(), Component.literal(command.title()))).isEmpty()) {
            player.sendSystemMessage(Messages.error("Could not open menu: " + command.title()));
            return 0;
        }
        return 1;
    }

    private record GuiCommand(
        String name,
        String title,
        MenuConstructor menuConstructor,
        String... aliases
    ) {
    }
}
