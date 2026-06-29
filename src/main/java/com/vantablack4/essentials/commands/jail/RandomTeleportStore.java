package com.vantablack4.essentials.commands.jail;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Optional;
import java.util.Properties;

import com.vantablack4.essentials.VantablackEssentialsMod;

import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

final class RandomTeleportStore {
    private final Path file;
    private final Properties properties;

    RandomTeleportStore(Path file) {
        this.file = file;
        this.properties = load(file);
    }

    synchronized Optional<Area> area() {
        Identifier dimensionId = Identifier.tryParse(properties.getProperty("dimension", ""));
        if (dimensionId == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(new Area(
                ResourceKey.create(Registries.DIMENSION, dimensionId),
                Double.parseDouble(properties.getProperty("center.x")),
                Double.parseDouble(properties.getProperty("center.z")),
                Double.parseDouble(properties.getProperty("min-radius", "0")),
                Double.parseDouble(properties.getProperty("max-radius"))
            ));
        } catch (RuntimeException exception) {
            return Optional.empty();
        }
    }

    synchronized void set(Area area) {
        properties.setProperty("dimension", area.dimension().identifier().toString());
        properties.setProperty("center.x", Double.toString(area.centerX()));
        properties.setProperty("center.z", Double.toString(area.centerZ()));
        properties.setProperty("min-radius", Double.toString(area.minRadius()));
        properties.setProperty("max-radius", Double.toString(area.maxRadius()));
        save();
    }

    synchronized boolean clear() {
        boolean hadValues = !properties.isEmpty();
        properties.clear();
        if (hadValues) {
            save();
        }
        return hadValues;
    }

    private void save() {
        try {
            Files.createDirectories(file.getParent());
            Path temporaryFile = Files.createTempFile(file.getParent(), file.getFileName().toString(), ".tmp");
            try (Writer writer = Files.newBufferedWriter(temporaryFile, StandardCharsets.UTF_8)) {
                properties.store(writer, "Vantablack Essentials random teleport area");
            }
            try {
                Files.move(temporaryFile, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException exception) {
                Files.move(temporaryFile, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to save random teleport state: " + file, exception);
        }
    }

    private static Properties load(Path file) {
        Properties properties = new Properties();
        if (!Files.isRegularFile(file)) {
            return properties;
        }
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            properties.load(reader);
        } catch (IOException exception) {
            VantablackEssentialsMod.LOGGER.warn("Unable to load random teleport state: {}", file, exception);
        }
        return properties;
    }

    record Area(ResourceKey<Level> dimension, double centerX, double centerZ, double minRadius, double maxRadius) {
    }
}
