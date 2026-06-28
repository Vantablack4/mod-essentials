package com.vantablack4.essentials;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class VantablackEssentialsMod implements ModInitializer {
    public static final String MOD_ID = "mod_essentials";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    @Override
    public void onInitialize() {
        LOGGER.info("Vantablack Essentials initialized");
        if (FabricLoader.getInstance().getEnvironmentType() != EnvType.SERVER) {
            return;
        }

        EssentialsConfig config = EssentialsConfig.load();
        EssentialsStorage storage = EssentialsStorage.load(config.configDirectory());
        BackService backService = new BackService();
        TpaService tpaService = new TpaService(config, backService);
        PlayerStateService playerStateService = new PlayerStateService(config);

        new EssentialsCommands(config, storage, backService, tpaService, playerStateService).register();
    }
}
