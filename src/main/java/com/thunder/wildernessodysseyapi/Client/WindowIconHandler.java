package com.thunder.wildernessodysseyapi.Client;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.platform.Window;
import com.thunder.wildernessodysseyapi.Core.ModConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.glfw.GLFWImage;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static com.thunder.wildernessodysseyapi.Core.ModConstants.MOD_ID;

/**
 * Loads and applies the custom Wilderness Odyssey window icons during client setup.
 */
@EventBusSubscriber(modid = MOD_ID, value = Dist.CLIENT)
public final class WindowIconHandler {

    private static final ResourceLocation[] ICON_LOCATIONS = new ResourceLocation[] {
            ResourceLocation.fromNamespaceAndPath(MOD_ID, "icons/icon16.png"),
            ResourceLocation.fromNamespaceAndPath(MOD_ID, "icons/icon32.png")
    };

    private WindowIconHandler() {
    }

    @SubscribeEvent
    public static void onClientSetup(FMLClientSetupEvent event) {
        event.enqueueWork(WindowIconHandler::applyWindowIcons);
    }

    private static void applyWindowIcons() {
        Minecraft minecraft = Minecraft.getInstance();
        Window window = minecraft.getWindow();

        List<NativeImage> images = new ArrayList<>();
        List<ByteBuffer> pixelBuffers = new ArrayList<>();

        for (ResourceLocation iconLocation : ICON_LOCATIONS) {
            Optional<Resource> resourceOptional = minecraft.getResourceManager().getResource(iconLocation);
            if (resourceOptional.isEmpty()) {
                ModConstants.LOGGER.warn("Missing window icon resource: {}", iconLocation);
                continue;
            }

            try (InputStream stream = resourceOptional.get().open()) {
                NativeImage image = NativeImage.read(stream);
                images.add(image);
                ByteBuffer pixelBuffer = MemoryUtil.memAlloc(image.getWidth() * image.getHeight() * 4);
                pixelBuffer.asIntBuffer().put(image.getPixelsRGBA());
                pixelBuffers.add(pixelBuffer);
            } catch (IOException exception) {
                ModConstants.LOGGER.error("Failed to load window icon {}", iconLocation, exception);
            }
        }

        if (images.isEmpty()) {
            ModConstants.LOGGER.warn("No window icons were loaded; keeping default Minecraft icon.");
            return;
        }

        try (MemoryStack stack = MemoryStack.stackPush()) {
            GLFWImage.Buffer iconBuffer = GLFWImage.malloc(images.size(), stack);

            for (int i = 0; i < images.size(); i++) {
                NativeImage image = images.get(i);
                ByteBuffer pixels = pixelBuffers.get(i);
                iconBuffer.position(i);
                iconBuffer.width(image.getWidth());
                iconBuffer.height(image.getHeight());
                iconBuffer.pixels(pixels);
            }

            GLFW.glfwSetWindowIcon(window.getWindow(), iconBuffer.position(0));
        } finally {
            pixelBuffers.forEach(MemoryUtil::memFree);
        }

        for (NativeImage image : images) {
            image.close();
        }
    }
}
