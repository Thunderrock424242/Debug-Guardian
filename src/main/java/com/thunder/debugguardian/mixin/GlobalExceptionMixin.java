package com.thunder.debugguardian.mixin;

import com.thunder.debugguardian.debug.monitor.LiveLogMonitor;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Minecraft.class)
public abstract class GlobalExceptionMixin {

    @Inject(method = "run", at = @At("HEAD"))
    private void onGameStart(CallbackInfo ci) {
        // Global uncaught exception handler
        Thread.setDefaultUncaughtExceptionHandler((thread, throwable) -> LiveLogMonitor.captureThrowable(throwable));
    }
}