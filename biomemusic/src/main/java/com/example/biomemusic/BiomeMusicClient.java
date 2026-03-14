package com.example.biomemusic;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;

public class BiomeMusicClient implements ClientModInitializer {

    @Override
    public void onInitializeClient() {
        final int[] tickCounter = {0};

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (client.player == null || client.world == null) return;

            tickCounter[0]++;
            if (tickCounter[0] < 20) return;
            tickCounter[0] = 0;

            var biomeEntry = client.world.getBiome(client.player.getBlockPos());
            biomeEntry.getKey().ifPresent(biomeKey -> {
                BiomeMusicHandler.onBiomeChanged(client, biomeKey);
            });
        });
    }
}
