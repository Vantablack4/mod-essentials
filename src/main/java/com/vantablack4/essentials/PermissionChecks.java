package com.vantablack4.essentials;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.permissions.Permission;
import net.minecraft.server.permissions.PermissionLevel;

public final class PermissionChecks {
    private final EssentialsConfig config;

    public PermissionChecks(EssentialsConfig config) {
        this.config = config;
    }

    public boolean admin(CommandSourceStack source) {
        return source.permissions().hasPermission(
            new Permission.HasCommandLevel(PermissionLevel.byId(config.adminPermissionLevel()))
        );
    }
}
