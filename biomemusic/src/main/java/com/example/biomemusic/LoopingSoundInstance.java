package com.example.biomemusic;

import net.minecraft.client.sound.AbstractSoundInstance;
import net.minecraft.client.sound.SoundInstance;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvent;

public class LoopingSoundInstance extends AbstractSoundInstance {

    public LoopingSoundInstance(SoundEvent sound, float volume, float pitch) {
        super(sound, SoundCategory.MUSIC, SoundInstance.createRandom());
        this.volume = volume;
        this.pitch = pitch;
        this.repeat = true;       // Loop the track continuously
        this.repeatDelay = 0;     // No gap between loops
        this.relative = true;     // Not positional — plays globally
    }
}
