package com.example.soundscape.mixin;

import com.example.soundscape.BiomeMusicHandler;
import net.minecraft.client.sound.MusicTracker;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(MusicTracker.class)
public class MusicTrackerMixin {

    // Suppress Minecraft's built-in music while Soundscape is active
    @Inject(method = "tick", at = @At("HEAD"), cancellable = true)
    private void suppressVanillaMusic(CallbackInfo ci) {
        if (BiomeMusicHandler.isPlayingCustomMusic()) {
            ci.cancel();
        }
    }
}
