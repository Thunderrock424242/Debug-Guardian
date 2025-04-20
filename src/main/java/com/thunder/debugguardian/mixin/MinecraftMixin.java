package com.thunder.debugguardian.mixin;

import com.thunder.debugguardian.debug.CrashHelper.CrashHandler;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Minecraft.class)
public abstract class MinecraftMixin {
    @Inject(method = "shutdown", at = @At("HEAD"))
    private void onShutdown(CallbackInfo ci) {
        if (CrashHandler.isCrashed()) {
            CrashHandler.logCrashDetails();
        } else {
            CrashHandler.logNormalExit();
        }
    }
}