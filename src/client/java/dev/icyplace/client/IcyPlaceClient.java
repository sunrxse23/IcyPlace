package dev.icyplace.client;

import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.minecraft.client.KeyMapping;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import org.lwjgl.glfw.GLFW;


public class IcyPlaceClient implements ClientModInitializer {
    private static boolean enabled = false;
    private static LitematicReader.Schematic schematic;
    private static int originX, originY, originZ;
    private static boolean originSet = false;
    private static String lastTarget = "";

    private static final KeyMapping.Category CATEGORY =
            KeyMapping.Category.register(Identifier.fromNamespaceAndPath("icyplace", "main"));
    private static final KeyMapping TOGGLE_KEY = KeyMappingHelper.registerKeyMapping(
            new KeyMapping("key.icyplace.toggle", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_F10, CATEGORY));
    private static final KeyMapping LOAD_KEY = KeyMappingHelper.registerKeyMapping(
            new KeyMapping("key.icyplace.load", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_F12, CATEGORY));
    private static final KeyMapping ORIGIN_KEY = KeyMappingHelper.registerKeyMapping(
            new KeyMapping("key.icyplace.origin", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_F8, CATEGORY));

    @Override
    public void onInitializeClient() {
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            while (TOGGLE_KEY.consumeClick()) {
                enabled = !enabled;
                lastTarget = "";
                message(client, "IcyPlace " + (enabled ? "ON" : "OFF"));
            }

            while (LOAD_KEY.consumeClick()) {
                if (client.player != null) {
                    client.gui.setScreen(new SchematicPickerScreen(client, client.gui.screen(), loaded -> {
                        schematic = loaded;
                        originSet = false;
                        lastTarget = "";
                        message(client, "Loaded schematic (" + schematic.sizeX() + "x"
                                + schematic.sizeY() + "x" + schematic.sizeZ()
                                + "). Aim at the schematic's bottom corner and press F8.");
                    }));
                }
            }

            while (ORIGIN_KEY.consumeClick()) {
                if (schematic == null) {
                    message(client, "Load a .litematic first with F12.");
                } else if (client.hitResult instanceof BlockHitResult hit
                        && hit.getType() == HitResult.Type.BLOCK) {
                    var pos = hit.getBlockPos().relative(hit.getDirection());
                    originX = pos.getX();
                    originY = pos.getY();
                    originZ = pos.getZ();
                    originSet = true;
                    message(client, "Schematic origin set to " + originX + ", " + originY + ", " + originZ);
                } else {
                    message(client, "Look at a block face where the schematic's bottom corner should start.");
                }
            }

            if (!enabled || schematic == null || !originSet || client.player == null
                    || !(client.hitResult instanceof BlockHitResult hit)
                    || hit.getType() != HitResult.Type.BLOCK) return;

            var target = hit.getBlockPos().relative(hit.getDirection());
            int x = target.getX() - originX;
            int y = target.getY() - originY;
            int z = target.getZ() - originZ;
            String blockName = schematic.blockAt(x, y, z);
            if (blockName == null || blockName.equals("minecraft:air")
                    || blockName.equals("minecraft:cave_air")
                    || blockName.equals("minecraft:void_air")) {
                lastTarget = "";
                return;
            }

            Identifier blockId = Identifier.tryParse(blockName);
            if (blockId == null) return;
            var requiredBlock = BuiltInRegistries.BLOCK.getValue(blockId);
            int matchingSlot = -1;
            for (int slot = 0; slot < 9; slot++) {
                ItemStack stack = client.player.getInventory().getItem(slot);
                if (!stack.isEmpty() && stack.getItem() instanceof BlockItem blockItem
                        && blockItem.getBlock() == requiredBlock) {
                    matchingSlot = slot;
                    break;
                }
            }

            String status;
            if (matchingSlot >= 0) {
                client.player.getInventory().setSelectedSlot(matchingSlot);
                status = "Need " + blockName + " — selected hotbar slot " + (matchingSlot + 1);
            } else {
                status = "Need " + blockName + " — NOT IN HOTBAR";
            }
            if (!status.equals(lastTarget)) {
                message(client, status);
                lastTarget = status;
            }
        });
    }

    private static void message(net.minecraft.client.Minecraft client, String text) {
        if (client.player != null) client.player.sendSystemMessage(Component.literal("IcyPlace: " + text));
    }
}
