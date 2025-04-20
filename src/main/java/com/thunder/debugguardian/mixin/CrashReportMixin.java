package com.thunder.debugguardian.mixin;

import com.thunder.debugguardian.debug.CrashHelper.CrashHandler;
import net.minecraft.CrashReport;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;


@Mixin(CrashReport.class)
public abstract class CrashReportMixin {
    @Inject(method = "getCompleteReport", at = @At("RETURN"), cancellable = true)
    private void enhanceCrashReport(CallbackInfoReturnable<String> cir) {
        String report = cir.getReturnValue();
        String enhancedInfo = "\n========== Enhanced Debug Info ==========\n";
        enhancedInfo += "Potential Mod Causing Crash: " + CrashHandler.getModCausingCrash() + "\n";
        enhancedInfo += "Active Thread at Crash: " + Thread.currentThread().getName() + "\n";
        enhancedInfo += "=========================================\n";
        cir.setReturnValue(report + enhancedInfo);
    }
}