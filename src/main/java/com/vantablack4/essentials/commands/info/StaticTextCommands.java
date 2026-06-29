package com.vantablack4.essentials.commands.info;

import static com.mojang.brigadier.arguments.IntegerArgumentType.getInteger;
import static com.mojang.brigadier.arguments.StringArgumentType.getString;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;
import java.util.stream.Stream;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import com.vantablack4.essentials.Messages;
import com.vantablack4.essentials.VantablackEssentialsMod;

import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.Component;

final class StaticTextCommands {
    private static final int PAGE_SIZE = 8;
    private static final String PAGE_ARGUMENT = "page";
    private static final String CHAPTER_ARGUMENT = "chapter";

    private final Path textDirectory;

    StaticTextCommands(Path configDirectory) {
        this.textDirectory = configDirectory.resolve("text");
    }

    void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        registerAll(dispatcher, name -> textCommand(name, "MOTD", "motd.txt"), "motd", "emotd");
        registerAll(dispatcher, name -> textCommand(name, "Rules", "rules.txt"), "rules", "erules");
        registerAll(
            dispatcher,
            this::infoCommand,
            "info",
            "about",
            "eabout",
            "ifo",
            "eifo",
            "einfo",
            "inform",
            "einform",
            "news",
            "enews"
        );
    }

    private void registerAll(
        CommandDispatcher<CommandSourceStack> dispatcher,
        Function<String, LiteralArgumentBuilder<CommandSourceStack>> factory,
        String... names
    ) {
        for (String name : names) {
            dispatcher.register(factory.apply(name));
        }
    }

    private LiteralArgumentBuilder<CommandSourceStack> textCommand(String name, String title, String fileName) {
        return Commands.literal(name)
            .executes(context -> showText(context, title, textDirectory.resolve(fileName), fileName, 1))
            .then(Commands.argument(PAGE_ARGUMENT, IntegerArgumentType.integer(1))
                .executes(context -> showText(context, title, textDirectory.resolve(fileName), fileName, getInteger(context, PAGE_ARGUMENT))));
    }

    private LiteralArgumentBuilder<CommandSourceStack> infoCommand(String name) {
        return Commands.literal(name)
            .executes(context -> showInfo(context, null, 1))
            .then(Commands.argument(CHAPTER_ARGUMENT, StringArgumentType.word())
                .suggests(this::suggestInfoChapters)
                .executes(context -> showInfo(context, getString(context, CHAPTER_ARGUMENT), 1))
                .then(Commands.argument(PAGE_ARGUMENT, IntegerArgumentType.integer(1))
                    .executes(context -> showInfo(context, getString(context, CHAPTER_ARGUMENT), getInteger(context, PAGE_ARGUMENT)))));
    }

    private int showInfo(CommandContext<CommandSourceStack> context, String chapterOrPage, int page) {
        if (chapterOrPage != null && isInteger(chapterOrPage)) {
            return showText(context, "Info", textDirectory.resolve("info.txt"), "info.txt", Integer.parseInt(chapterOrPage));
        }
        if (chapterOrPage == null || chapterOrPage.isBlank()) {
            Path defaultInfo = textDirectory.resolve("info.txt");
            if (Files.isRegularFile(defaultInfo)) {
                return showText(context, "Info", defaultInfo, "info.txt", page);
            }
            List<String> chapters = infoChapters();
            if (!chapters.isEmpty()) {
                context.getSource().sendSystemMessage(Messages.header("Info"));
                context.getSource().sendSystemMessage(Messages.line("Chapters", String.join(", ", chapters)));
                return 1;
            }
            return missingText(context, "info.txt");
        }

        String chapter = safeName(chapterOrPage);
        if (chapter.isBlank()) {
            context.getSource().sendSystemMessage(Messages.error("Invalid info chapter: " + chapterOrPage));
            return 0;
        }
        Path chapterFile = textDirectory.resolve("info").resolve(chapter + ".txt");
        return showText(context, "Info: " + chapter, chapterFile, "info/" + chapter + ".txt", page);
    }

    private int showText(CommandContext<CommandSourceStack> context, String title, Path file, String relativeFile, int requestedPage) {
        if (!Files.isRegularFile(file)) {
            return missingText(context, relativeFile);
        }

        List<String> lines;
        try {
            lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        } catch (IOException exception) {
            VantablackEssentialsMod.LOGGER.warn("Unable to read static Essentials text file: {}", file, exception);
            context.getSource().sendSystemMessage(Messages.error("Unable to read static text: " + relativeFile));
            return 0;
        }

        int totalPages = Math.max(1, (lines.size() + PAGE_SIZE - 1) / PAGE_SIZE);
        if (requestedPage > totalPages) {
            context.getSource().sendSystemMessage(Messages.error("Page " + requestedPage + " is outside " + title + " pages: " + totalPages));
            return 0;
        }

        context.getSource().sendSystemMessage(Messages.header(title + " " + requestedPage + "/" + totalPages));
        if (lines.isEmpty()) {
            context.getSource().sendSystemMessage(Component.literal("(empty)").withStyle(ChatFormatting.GRAY));
            return 0;
        }

        int start = (requestedPage - 1) * PAGE_SIZE;
        int end = Math.min(lines.size(), start + PAGE_SIZE);
        for (int index = start; index < end; index++) {
            context.getSource().sendSystemMessage(Component.literal(lines.get(index)).withStyle(ChatFormatting.WHITE));
        }
        return 1;
    }

    private int missingText(CommandContext<CommandSourceStack> context, String relativeFile) {
        context.getSource().sendSystemMessage(Messages.error("Static text is not configured: config/mod_essentials/text/" + relativeFile));
        return 0;
    }

    private CompletableFuture<Suggestions> suggestInfoChapters(CommandContext<CommandSourceStack> context, SuggestionsBuilder builder) {
        return SharedSuggestionProvider.suggest(infoChapters(), builder);
    }

    private List<String> infoChapters() {
        Path infoDirectory = textDirectory.resolve("info");
        if (!Files.isDirectory(infoDirectory)) {
            return List.of();
        }
        try (Stream<Path> files = Files.list(infoDirectory)) {
            return files
                .filter(Files::isRegularFile)
                .map(path -> path.getFileName().toString())
                .filter(name -> name.endsWith(".txt"))
                .map(name -> name.substring(0, name.length() - 4))
                .sorted(String.CASE_INSENSITIVE_ORDER)
                .toList();
        } catch (IOException exception) {
            VantablackEssentialsMod.LOGGER.warn("Unable to list Essentials info chapters", exception);
            return List.of();
        }
    }

    private static boolean isInteger(String value) {
        try {
            Integer.parseInt(value);
            return true;
        } catch (NumberFormatException exception) {
            return false;
        }
    }

    private static String safeName(String value) {
        return value.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_-]", "_").replaceAll("^_+|_+$", "");
    }
}
