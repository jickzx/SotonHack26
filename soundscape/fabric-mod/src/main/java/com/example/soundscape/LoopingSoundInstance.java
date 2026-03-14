package com.example.soundscape;

import net.minecraft.client.sound.AbstractSoundInstance;
import net.minecraft.client.sound.SoundInstance;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvent;

public class LoopingSoundInstance extends AbstractSoundInstance {

    public LoopingSoundInstance(SoundEvent sound, float volume, float pitch) {
        super(sound, SoundCategory.MUSIC, SoundInstance.createRandom());
        this.volume = volume;
        this.pitch = pitch;
        this.repeat = true;      // Loop continuously
        this.repeatDelay = 0;    // No gap between loops
        this.relative = true;    // Non-positional — plays globally
    }
}
