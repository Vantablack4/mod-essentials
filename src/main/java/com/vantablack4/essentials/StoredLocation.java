package com.vantablack4.essentials;

import java.util.Optional;
import java.util.Properties;
import java.util.Set;

import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Relative;
import net.minecraft.world.level.Level;

public record StoredLocation(
    ResourceKey<Level> dimension,
    double x,
    double y,
    double z,
    float yaw,
    float pitch
) {
    public static StoredLocation capture(ServerPlayer player) {
        return new StoredLocation(
            player.level().dimension(),
            player.getX(),
            player.getY(),
            player.getZ(),
            player.getYRot(),
            player.getXRot()
        );
    }

    public void teleport(MinecraftServer server, ServerPlayer player) {
        ServerLevel level = server.getLevel(dimension);
        if (level == null) {
            throw new IllegalArgumentException("Dimension is not loaded: " + dimension.identifier());
        }
        player.teleportTo(level, x, y, z, Set.<Relative>of(), yaw, pitch, false);
    }

    public String display() {
        return dimension.identifier() + " " + blockX() + " " + blockY() + " " + blockZ();
    }

    public int blockX() {
        return (int) Math.floor(x);
    }

    public int blockY() {
        return (int) Math.floor(y);
    }

    public int blockZ() {
        return (int) Math.floor(z);
    }

    static void write(Properties properties, String prefix, StoredLocation location) {
        properties.setProperty(prefix + ".dimension", location.dimension().identifier().toString());
        properties.setProperty(prefix + ".x", Double.toString(location.x()));
        properties.setProperty(prefix + ".y", Double.toString(location.y()));
        properties.setProperty(prefix + ".z", Double.toString(location.z()));
        properties.setProperty(prefix + ".yaw", Float.toString(location.yaw()));
        properties.setProperty(prefix + ".pitch", Float.toString(location.pitch()));
    }

    static Optional<StoredLocation> read(Properties properties, String prefix) {
        String dimensionValue = properties.getProperty(prefix + ".dimension");
        Identifier dimensionId = dimensionValue == null ? null : Identifier.tryParse(dimensionValue);
        if (dimensionId == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(new StoredLocation(
                ResourceKey.create(Registries.DIMENSION, dimensionId),
                Double.parseDouble(properties.getProperty(prefix + ".x")),
                Double.parseDouble(properties.getProperty(prefix + ".y")),
                Double.parseDouble(properties.getProperty(prefix + ".z")),
                Float.parseFloat(properties.getProperty(prefix + ".yaw", "0")),
                Float.parseFloat(properties.getProperty(prefix + ".pitch", "0"))
            ));
        } catch (RuntimeException exception) {
            return Optional.empty();
        }
    }
}
