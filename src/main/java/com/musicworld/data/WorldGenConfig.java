package com.musicworld.data;

/**
 * Singleton holding the currently active GenreProfile used during world generation.
 * Defaults to "classical". Thread-safe via volatile.
 *
 * On genre switch, remembers the previous profile and the chunk position (X+Z)
 * where the switch happened so MusicChunkGenerator can blend terrain radially
 * outward from that point.
 */
public final class WorldGenConfig {

    private WorldGenConfig() {}

    private static volatile GenreProfile activeProfile   = GenreProfile.GENRES.get("classical");
    private static volatile GenreProfile previousProfile = GenreProfile.GENRES.get("classical");

    /**
     * Chunk X/Z where the most recent genre switch occurred.
     * Integer.MIN_VALUE means "no switch yet" — blend factor returns 1 everywhere
     * so the initial world has no transition zone.
     */
    private static volatile int switchChunkX = Integer.MIN_VALUE;
    private static volatile int switchChunkZ = Integer.MIN_VALUE;

    /**
     * Sets the active genre and records the player's chunk position for radial
     * terrain blending. If the genre key is unknown, returns false (no-op).
     */
    public static boolean setGenre(String genre, int currentChunkX, int currentChunkZ) {
        GenreProfile profile = GenreProfile.GENRES.get(genre.toLowerCase());
        if (profile == null) return false;
        previousProfile = activeProfile;
        activeProfile   = profile;
        switchChunkX    = currentChunkX;
        switchChunkZ    = currentChunkZ;
        return true;
    }

    /** Backwards-compatible overload — disables blending (no switch position). */
    public static boolean setGenre(String genre) {
        return setGenre(genre, Integer.MIN_VALUE, Integer.MIN_VALUE);
    }

    public static GenreProfile getActive()   { return activeProfile; }
    public static GenreProfile getPrevious() { return previousProfile; }
    public static int getSwitchChunkX()      { return switchChunkX; }
    public static int getSwitchChunkZ()      { return switchChunkZ; }
}
