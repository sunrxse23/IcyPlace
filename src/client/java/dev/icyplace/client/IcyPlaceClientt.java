
package dev.icyplace.client;

import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.minecraft.client.KeyMapping;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import org.lwjgl.glfw.GLFW;

public class IcyPlaceClient implements ClientModInitializer {

    private static boolean enabled = false;

    private static final KeyMapping.Category CATEGORY =
            KeyMapping.Category.register(
                    Identifier.fromNamespaceAndPath("icyplace", "main")
            );

    private static final KeyMapping TOGGLE_KEY =
            KeyMappingHelper.registerKeyMapping(
                    new KeyMapping(
                            "key.icyplace.toggle",
                            InputConstants.Type.KEYSYM,
                            GLFW.GLFW_KEY_F8,
                            CATEGORY
                    )
            );

    @Override
    public void onInitializeClient() {
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            while (TOGGLE_KEY.consumeClick()) {
                enabled = !enabled;

                if (client.player != null) {
                    String status = enabled ? "ON" : "OFF";
                    client.player.sendSystemMessage(
                            Component.literal("IcyPlace: " + status)
                    );
                }
            }
        });
    }
}
