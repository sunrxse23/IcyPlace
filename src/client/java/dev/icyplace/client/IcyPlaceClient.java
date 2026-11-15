package dev.icyplace.client;

import com.mojang.blaze3d.platform.InputConstants;
import fi.dy.masa.litematica.util.RayTraceUtils;
import fi.dy.masa.litematica.world.SchematicWorldHandler;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.minecraft.client.KeyMapping;
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

public class IcyPlaceClient {
    private static boolean enabled = true;
    private static String lastMissingBlock = "";

    private static final KeyMapping.Category CATEGORY =
            KeyMapping.Category.register(Identifier.fromNamespaceAndPath("icyplace", "main"));
    private static final KeyMapping TOGGLE_KEY = KeyMappingHelper.registerKeyMapping(
            new KeyMapping("key.icyplace.toggle", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_F10, CATEGORY));
    private static final KeyMapping AUTOPLACE_KEY = KeyMappingHelper.registerKeyMapping(
            new KeyMapping("key.icyplace.autoplace", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_G, CATEGORY));

    @Override
    public void onInitializeClient() {
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            while (TOGGLE_KEY.consumeClick()) {
                enabled = !enabled;
                lastMissingBlock = "";
                message(client, "Assistant " + (enabled ? "ON" : "OFF"));
            }

            if (!enabled || client.player == null || client.level == null || client.gui.screen() != null
                    || client.gameMode == null) {
                return;
            }

            // Use Litematica's own ray trace against its actual loaded schematic world.
            BlockPos targetPos = RayTraceUtils.getSchematicWorldTraceIfClosest(
                    client.level, client.player, 6.0);

            if (targetPos == null) {
                lastMissingBlock = "";
                return;
            }

            var schematicWorld = SchematicWorldHandler.getSchematicWorld();
            if (schematicWorld == null) {
                lastMissingBlock = "";
                return;
            }

            var requiredBlock = schematicWorld.getBlockState(targetPos).getBlock();
            if (requiredBlock == null || requiredBlock.defaultBlockState().isAir()) {
                lastMissingBlock = "";
                return;
            }

            String blockName = BuiltInRegistries.BLOCK.getKey(requiredBlock).toString();
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
                    // Server-synced swap from main inventory into the selected hotbar slot.
                    client.gameMode.handleContainerInput(
                            client.player.inventoryMenu.containerId,
                            matchingSlot,
                            hotbarSlot,
                            ContainerInput.SWAP,
                            client.player);
                } else {
                    client.player.getInventory().setSelectedSlot(matchingSlot);
                }

                lastMissingBlock = "";

                // One deliberate right-click action per G press; never auto-repeat.
                if (AUTOPLACE_KEY.consumeClick()
                        && client.player.getMainHandItem().getItem() instanceof BlockItem heldBlock
                        && heldBlock.getBlock() == requiredBlock
                        && client.hitResult instanceof BlockHitResult blockHit) {
                    client.gameMode.useItemOn(client.player, InteractionHand.MAIN_HAND, blockHit);
                }
            } else if (!blockName.equals(lastMissingBlock)) {
                message(client, "Missing from inventory: " + blockName);
                lastMissingBlock = blockName;
            }
        });
    }

    private static void message(net.minecraft.client.Minecraft client, String text) {
        if (client.player != null) {
            client.player.sendSystemMessage(Component.literal("IcyPlace: " + text));
        }
    }
}
