package com.musicworld.worldgen;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import com.musicworld.data.WorldGenConfig;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.util.Identifier;
import net.minecraft.world.biome.Biome;
import net.minecraft.world.biome.source.BiomeSource;
import net.minecraft.world.biome.source.util.MultiNoiseUtil;
import net.minecraft.registry.RegistryKey;

import java.util.List;
import java.util.stream.Stream;

/**
 * Genre-aware BiomeSource.
 *
 * biomeStream() returns ALL 5 possible genre biomes so the StructurePlacementCalculator
 * (built once at world load) enables all relevant structure sets.
 *
 * getBiome() returns the single biome for the current genre — this is what Minecraft checks
 * when deciding which structure variant to place (plains village vs savanna village, etc).
 */
public class MusicBiomeSource extends BiomeSource {

    public static final Codec<MusicBiomeSource> CODEC = RecordCodecBuilder.create(instance ->
        instance.group(
            Biome.REGISTRY_CODEC.listOf().fieldOf("biomes")
                .forGetter(s -> s.allEntries)
        ).apply(instance, MusicBiomeSource::new)
    );

    private final List<RegistryEntry<Biome>> allEntries;

    public MusicBiomeSource(List<RegistryEntry<Biome>> allEntries) {
        this.allEntries = allEntries;
    }

    @Override
    protected Codec<? extends BiomeSource> getCodec() {
        return CODEC;
    }

    @Override
    protected Stream<RegistryEntry<Biome>> biomeStream() {
        // Return ALL genre biomes — this set is cached by StructurePlacementCalculator
        // and determines which structure sets are eligible for this world.
        return allEntries.stream();
    }

    @Override
    public RegistryEntry<Biome> getBiome(int x, int y, int z,
            MultiNoiseUtil.MultiNoiseSampler noise) {
        // Map active genre to the biome that unlocks the right structure variants.
        String structureType = WorldGenConfig.getActive().structureType;
        String biomeId = switch (structureType) {
            case "PLATFORMS" -> "savanna";    // hiphop: savanna village
            case "RUINS"     -> "swamp";      // ambient: swamp hut
            case "BUILDINGS" -> "dark_forest"; // jazz: woodland mansion
            default          -> "plains";     // metal/classical/electronic/pop: plains village + ruined portal
        };
        RegistryKey<Biome> target = RegistryKey.of(RegistryKeys.BIOME,
            new Identifier("minecraft", biomeId));
        return allEntries.stream()
            .filter(e -> e.matchesKey(target))
            .findFirst()
            .orElse(allEntries.get(0));
    }
}
