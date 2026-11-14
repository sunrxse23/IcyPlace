package dev.icyplace.client;

import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.minecraft.client.KeyMapping;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import org.lwjgl.glfw.GLFW;

public class IcyPlaceClient implements ClientModInitializer {

    private static boolean enabled = false;
    private static String lastTarget = "";

    private static final KeyMapping.Category CATEGORY =
            KeyMapping.Category.register(
                    Identifier.fromNamespaceAndPath("icyplace", "main")
            );

    private static final KeyMapping TOGGLE_KEY =
            KeyMappingHelper.registerKeyMapping(
                    new KeyMapping(
                            "key.icyplace.toggle",
                            InputConstants.Type.KEYSYM,
                            GLFW.GLFW_KEY_F10,
                            CATEGORY
                    )
            );

    @Override
    public void onInitializeClient() {
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            while (TOGGLE_KEY.consumeClick()) {
                enabled = !enabled;
                lastTarget = "";

                if (client.player != null) {
                    client.player.sendSystemMessage(
                            Component.literal("IcyPlace: " + (enabled ? "ON" : "OFF"))
                    );
                }
            }

            if (!enabled || client.player == null || client.level == null) {
                return;
            }

            if (client.hitResult instanceof BlockHitResult blockHit
                    && blockHit.getType() == HitResult.Type.BLOCK) {
                var pos = blockHit.getBlockPos();
                var state = client.level.getBlockState(pos);
                var placePos = pos.relative(blockHit.getDirection());
                var heldStack = client.player.getMainHandItem();

                String target = state.getBlock().getName().getString()
                        + " at " + pos.getX() + ", " + pos.getY() + ", " + pos.getZ()
                        + " | face " + blockHit.getDirection().getName()
                        + " | adjacent position " + placePos.getX() + ", "
                        + placePos.getY() + ", " + placePos.getZ()
                        + " | held: " + (heldStack.isEmpty()
                            ? "empty hand" : heldStack.getHoverName().getString());

                if (!target.equals(lastTarget)) {
                    client.player.sendSystemMessage(
                            Component.literal("IcyPlace: " + target)
                    );
                    lastTarget = target;
                }
            } else {
                lastTarget = "";
            }
        });
    }
}
