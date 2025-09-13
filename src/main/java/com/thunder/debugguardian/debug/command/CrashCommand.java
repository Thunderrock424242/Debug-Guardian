package com.thunder.debugguardian.debug.command;

import net.minecraft.commands.Commands;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

import static com.thunder.debugguardian.DebugGuardian.MOD_ID;

/**
 * Registers a simple command that intentionally crashes the game.
 * Useful for verifying debugging and crash handling tools.
 */
@EventBusSubscriber(modid = MOD_ID)
public class CrashCommand {
    private CrashCommand() {
    }

    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        event.getDispatcher().register(
            Commands.literal("crash")
                .requires(source -> source.hasPermission(2))
                .executes(ctx -> {
                    new Thread(() -> {
                        try {
                            Thread.sleep(100);
                        } catch (InterruptedException ignored) {
                        }
                        System.exit(-1);
                    }).start();
                    throw new RuntimeException("Intentional crash triggered by /crash command");
                })
        );
    }
}
