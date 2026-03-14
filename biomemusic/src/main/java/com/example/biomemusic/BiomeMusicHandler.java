package com.example.biomemusic;

import net.minecraft.client.MinecraftClient;
import net.minecraft.registry.RegistryKey;
import net.minecraft.sound.SoundEvent;
import net.minecraft.util.Identifier;
import net.minecraft.world.biome.Biome;
import net.minecraft.world.biome.BiomeKeys;

import java.util.HashMap;
import java.util.Map;
import java.util.Random;

public class BiomeMusicHandler {

    private static String currentGenre = null;
    private static LoopingSoundInstance currentSound = null;
    private static final Random RANDOM = new Random();

    // --- Genre → list of sound IDs ---
    private static final Map<String, String[]> GENRE_TRACKS = new HashMap<>();

    static {
        GENRE_TRACKS.put("country", new String[]{
            "biomemusic:country.track1",
            "biomemusic:country.track2"
        });

        GENRE_TRACKS.put("indipop", new String[]{
            "biomemusic:indipop.track1",
            "biomemusic:indipop.track2"
        });

        GENRE_TRACKS.put("metal", new String[]{
            "biomemusic:metal.track1",
            "biomemusic:metal.track2"
        });

        GENRE_TRACKS.put("ambient", new String[]{
            "biomemusic:ambient.track1"
        });

        GENRE_TRACKS.put("default", new String[]{
            "biomemusic:default.track1"
        });
    }

    // --- Biome → genre mapping ---
    private static final Map<RegistryKey<Biome>, String> BIOME_GENRE_MAP = new HashMap<>();

    static {
        // Overworld
        BIOME_GENRE_MAP.put(BiomeKeys.PALE_GARDEN,      "country");
        BIOME_GENRE_MAP.put(BiomeKeys.CHERRY_GROVE,     "indipop");
        BIOME_GENRE_MAP.put(BiomeKeys.DEEP_DARK,        "ambient");

        // Nether (all → metal)
        BIOME_GENRE_MAP.put(BiomeKeys.NETHER_WASTES,    "metal");
        BIOME_GENRE_MAP.put(BiomeKeys.SOUL_SAND_VALLEY, "metal");
        BIOME_GENRE_MAP.put(BiomeKeys.CRIMSON_FOREST,   "metal");
        BIOME_GENRE_MAP.put(BiomeKeys.WARPED_FOREST,    "metal");
        BIOME_GENRE_MAP.put(BiomeKeys.BASALT_DELTAS,    "metal");
    }

    public static void onBiomeChanged(MinecraftClient client, RegistryKey<Biome> biomeKey) {
        String genre = BIOME_GENRE_MAP.getOrDefault(biomeKey, "default");

        // Same genre already playing — don't restart
        if (genre.equals(currentGenre) && currentSound != null && !currentSound.isDone()) {
            return;
        }

        stopCurrentMusic(client);

        String[] tracks = GENRE_TRACKS.getOrDefault(genre, GENRE_TRACKS.get("default"));
        String chosenTrack = tracks[RANDOM.nextInt(tracks.length)];

        SoundEvent soundEvent = SoundEvent.of(Identifier.of(chosenTrack));
        currentSound = new LoopingSoundInstance(soundEvent, 0.5f, 1.0f);
        client.getSoundManager().play(currentSound);

        currentGenre = genre;
        System.out.println("[BiomeMusic] Playing genre: " + genre + " | track: " + chosenTrack);
    }

    public static void stopCurrentMusic(MinecraftClient client) {
        if (currentSound != null) {
            client.getSoundManager().stop(currentSound);
            currentSound = null;
        }
        currentGenre = null;
    }

    public static boolean isPlayingCustomMusic() {
        return currentSound != null && !currentSound.isDone();
    }
}
