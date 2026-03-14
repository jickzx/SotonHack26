package com.example.biomemusic.mixin;

import com.example.biomemusic.BiomeMusicHandler;
import net.minecraft.client.sound.MusicTracker;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(MusicTracker.class)
public class MusicTrackerMixin {

    // Suppress vanilla music while our mod is managing playback
    @Inject(method = "tick", at = @At("HEAD"), cancellable = true)
    private void suppressVanillaMusic(CallbackInfo ci) {
        if (BiomeMusicHandler.isPlayingCustomMusic()) {
            ci.cancel();
        }
    }
}
