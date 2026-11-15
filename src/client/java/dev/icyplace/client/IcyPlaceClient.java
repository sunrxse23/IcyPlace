package dev.icyplace.client;

import com.mojang.blaze3d.platform.InputConstants;
import fi.dy.masa.litematica.util.RayTraceUtils;
import fi.dy.masa.litematica.world.SchematicWorldHandler;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.BlockHitResult;
import org.lwjgl.glfw.GLFW;

public class IcyPlaceClient implements ClientModInitializer {
    private static boolean enabled = true;
    private static boolean autoPlaceEnabled = false;
    private static int autoPlaceCooldown = 0;
    private static final int[] SPEED_OPTIONS = {1, 2, 4, 6, 10};
    private static int speedIndex = 2;
    private static int autoPlaceIntervalTicks = SPEED_OPTIONS[speedIndex];

    private static String notification = "";
    private static long notificationUntil = 0L;

    private static final KeyMapping.Category CATEGORY =
            KeyMapping.Category.register(Identifier.fromNamespaceAndPath("icyplace", "main"));
    private static final KeyMapping TOGGLE_KEY = KeyMappingHelper.registerKeyMapping(
            new KeyMapping("key.icyplace.toggle", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_F10, CATEGORY));
    private static final KeyMapping AUTOPLACE_KEY = KeyMappingHelper.registerKeyMapping(
            new KeyMapping("key.icyplace.autoplace", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_G, CATEGORY));
    private static final KeyMapping SPEED_KEY = KeyMappingHelper.registerKeyMapping(
            new KeyMapping("key.icyplace.speed", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_H, CATEGORY));

    @Override
    public void onInitializeClient() {
        HudElementRegistry.addLast(
                Identifier.fromNamespaceAndPath("icyplace", "status_overlay"),
                IcyPlaceClient::renderOverlay
        );

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            while (TOGGLE_KEY.consumeClick()) {
                enabled = !enabled;
                notifyOverlay("IcyPlace " + (enabled ? "Activated" : "Deactivated"));
            }

            while (AUTOPLACE_KEY.consumeClick()) {
                autoPlaceEnabled = !autoPlaceEnabled;
                autoPlaceCooldown = 0;
                notifyOverlay("AutoPlace " + (autoPlaceEnabled ? "Activated" : "Deactivated"));
            }

            while (SPEED_KEY.consumeClick()) {
                speedIndex = (speedIndex + 1) % SPEED_OPTIONS.length;
                autoPlaceIntervalTicks = SPEED_OPTIONS[speedIndex];
                autoPlaceCooldown = 0;
                notifyOverlay("AutoPlace speed: " + autoPlaceIntervalTicks + " tick"
                        + (autoPlaceIntervalTicks == 1 ? "" : "s"));
            }

            if (client.player == null || client.level == null || client.gui.screen() != null
                    || client.gameMode == null) {
                return;
            }

            // Read the block at the active Litematica schematic target.
            BlockPos targetPos = RayTraceUtils.getSchematicWorldTraceIfClosest(
                    client.level, client.player, 6.0);

            if (targetPos == null) {
                return;
            }

            var schematicWorld = SchematicWorldHandler.getSchematicWorld();
            if (schematicWorld == null) {
                return;
            }

            var requiredBlock = schematicWorld.getBlockState(targetPos).getBlock();
            if (requiredBlock == null || requiredBlock.defaultBlockState().isAir()) {
                return;
            }

            // F10 controls automatic block selection. Missing-block chat messages are intentionally disabled.
            if (enabled) {
                int matchingSlot = -1;

                // Search all 36 normal inventory slots, not only the hotbar.
                for (int slot = 0; slot < 36; slot++) {
                    ItemStack stack = client.player.getInventory().getItem(slot);
                    if (!stack.isEmpty() && stack.getItem() instanceof BlockItem blockItem
                            && blockItem.getBlock() == requiredBlock) {
                        matchingSlot = slot;
                        break;
                    }
                }

                if (matchingSlot >= 0) {
                    int hotbarSlot = client.player.getInventory().getSelectedSlot();

                    if (matchingSlot >= 9) {
                        client.gameMode.handleContainerInput(
                                client.player.inventoryMenu.containerId,
                                matchingSlot,
                                hotbarSlot,
                                ContainerInput.SWAP,
                                client.player);
                    } else {
                        client.player.getInventory().setSelectedSlot(matchingSlot);
                    }
                }
            }

            // G controls AutoPlace independently. With F10 off, the required block
            // must already be in the player's hand.
            if (autoPlaceEnabled
                    && client.player.getMainHandItem().getItem() instanceof BlockItem heldBlock
                    && heldBlock.getBlock() == requiredBlock
                    && client.hitResult instanceof BlockHitResult blockHit) {
                if (autoPlaceCooldown <= 0) {
                    client.gameMode.useItemOn(client.player, InteractionHand.MAIN_HAND, blockHit);
                    autoPlaceCooldown = autoPlaceIntervalTicks - 1;
                } else {
                    autoPlaceCooldown--;
                }
            }
        });
    }

    private static void notifyOverlay(String text) {
        notification = text;
        notificationUntil = System.currentTimeMillis() + 2200L;
    }

    private static void renderOverlay(GuiGraphicsExtractor graphics, DeltaTracker deltaTracker) {
        Minecraft client = Minecraft.getInstance();
        if (client.player == null || client.gui.screen() != null
                || notification.isEmpty() || System.currentTimeMillis() >= notificationUntil) {
            return;
        }

        int width = client.font.width(notification) + 18;
        int height = 22;
        int x = 8;
        int y = graphics.guiHeight() - height - 8;

        // A temporary toast only: bottom-left, hidden unless a keybind was just used.
        graphics.fill(x, y, x + width, y + height, 0xD9000000);
        graphics.outline(x, y, width, height, 0x6655FFFF);
        graphics.text(client.font, notification, x + 9, y + 7, 0xFFFFFFFF, true);
    }
}
