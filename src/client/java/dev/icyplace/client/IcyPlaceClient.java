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
import net.minecraft.world.phys.Vec3;
import org.lwjgl.glfw.GLFW;

public class IcyPlaceClient implements ClientModInitializer {
    private static boolean enabled = false;
    private static LitematicReader.Schematic schematic;
    private static int originX, originY, originZ;
    private static boolean originSet = false;
    private static String lastMissingBlock = "";
    private static int rotation = 0;

    private static final KeyMapping.Category CATEGORY =
            KeyMapping.Category.register(Identifier.fromNamespaceAndPath("icyplace", "main"));
    private static final KeyMapping TOGGLE_KEY = KeyMappingHelper.registerKeyMapping(
            new KeyMapping("key.icyplace.toggle", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_F10, CATEGORY));
    private static final KeyMapping LOAD_KEY = KeyMappingHelper.registerKeyMapping(
            new KeyMapping("key.icyplace.load", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_F12, CATEGORY));
    private static final KeyMapping ORIGIN_KEY = KeyMappingHelper.registerKeyMapping(
            new KeyMapping("key.icyplace.origin", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_F8, CATEGORY));
    private static final KeyMapping ROTATE_KEY = KeyMappingHelper.registerKeyMapping(
            new KeyMapping("key.icyplace.rotate", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_F7, CATEGORY));

    @Override
    public void onInitializeClient() {
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            while (TOGGLE_KEY.consumeClick()) {
                enabled = !enabled;
                lastMissingBlock = "";
                message(client, "Assistant " + (enabled ? "ON" : "OFF"));
            }

            while (LOAD_KEY.consumeClick()) {
                if (client.player != null) {
                    client.gui.setScreen(new SchematicPickerScreen(client, client.gui.screen(), loaded -> {
                        schematic = loaded;
                        originSet = false;
                        lastMissingBlock = "";
                        message(client, "Loaded " + schematic.sizeX() + "x" + schematic.sizeY()
                                + "x" + schematic.sizeZ()
                                + ". Put Litematica's hologram in place, aim at its bottom corner, then press F8.");
                    }));
                }
            }

            while (ROTATE_KEY.consumeClick()) {
                rotation = (rotation + 1) & 3;
                lastMissingBlock = "";
                message(client, "Rotation set to " + (rotation * 90)
                        + " degrees. Match this to Litematica's schematic rotation.");
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
                    lastMissingBlock = "";
                    message(client, "Anchor set at " + originX + ", " + originY + ", " + originZ
                            + ". This must match the bottom corner of Litematica's hologram.");
                } else {
                    message(client, "Aim at a block face beside the hologram's bottom corner and press F8.");
                }
            }

            if (!enabled || schematic == null || !originSet || client.player == null) return;

            String blockName = findSchematicBlockOnLookRay(
                    schematic, client.player.getEyePosition(), client.player.getViewVector(1.0F));
            if (blockName == null) {
                lastMissingBlock = "";
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

            if (matchingSlot >= 0) {
                // Silently select the needed block. Successful selection never sends chat messages.
                client.player.getInventory().setSelectedSlot(matchingSlot);
                lastMissingBlock = "";
            } else if (!blockName.equals(lastMissingBlock)) {
                // Warn only once when the required missing block changes.
                message(client, "Missing from hotbar: " + blockName);
                lastMissingBlock = blockName;
            }
        });
    }

    /*
     * Walks the player's view ray through world-space voxels and checks the
     * matching schematic cell. Unlike Minecraft's normal hitResult, this can
     * detect Litematica ghost blocks because they are not real world blocks.
     */
    private static String findSchematicBlockOnLookRay(
            LitematicReader.Schematic schematic, Vec3 eye, Vec3 direction) {
        int x = floor(eye.x);
        int y = floor(eye.y);
        int z = floor(eye.z);

        int stepX = direction.x > 0 ? 1 : direction.x < 0 ? -1 : 0;
        int stepY = direction.y > 0 ? 1 : direction.y < 0 ? -1 : 0;
        int stepZ = direction.z > 0 ? 1 : direction.z < 0 ? -1 : 0;

        double deltaX = stepX == 0 ? Double.POSITIVE_INFINITY : Math.abs(1.0 / direction.x);
        double deltaY = stepY == 0 ? Double.POSITIVE_INFINITY : Math.abs(1.0 / direction.y);
        double deltaZ = stepZ == 0 ? Double.POSITIVE_INFINITY : Math.abs(1.0 / direction.z);

        double maxX = stepX > 0 ? (x + 1.0 - eye.x) / direction.x
                : stepX < 0 ? (eye.x - x) / -direction.x : Double.POSITIVE_INFINITY;
        double maxY = stepY > 0 ? (y + 1.0 - eye.y) / direction.y
                : stepY < 0 ? (eye.y - y) / -direction.y : Double.POSITIVE_INFINITY;
        double maxZ = stepZ > 0 ? (z + 1.0 - eye.z) / direction.z
                : stepZ < 0 ? (eye.z - z) / -direction.z : Double.POSITIVE_INFINITY;

        for (int visited = 0; visited < 128; visited++) {
            int dx = x - originX;
            int dy = y - originY;
            int dz = z - originZ;
            int localX;
            int localZ;

            switch (rotation) {
                case 1 -> {
                    localX = dz;
                    localZ = schematic.sizeZ() - 1 - dx;
                }
                case 2 -> {
                    localX = schematic.sizeX() - 1 - dx;
                    localZ = schematic.sizeZ() - 1 - dz;
                }
                case 3 -> {
                    localX = schematic.sizeX() - 1 - dz;
                    localZ = dx;
                }
                default -> {
                    localX = dx;
                    localZ = dz;
                }
            }

            String block = schematic.blockAt(localX, dy, localZ);
            if (block != null && !block.endsWith(":air")
                    && !block.endsWith(":cave_air") && !block.endsWith(":void_air")) {
                return block;
            }

            double next = Math.min(maxX, Math.min(maxY, maxZ));
            if (next > 8.0 || Double.isInfinite(next)) return null;
            double epsilon = 1.0E-10;
            if (maxX <= next + epsilon) {
                x += stepX;
                maxX += deltaX;
            }
            if (maxY <= next + epsilon) {
                y += stepY;
                maxY += deltaY;
            }
            if (maxZ <= next + epsilon) {
                z += stepZ;
                maxZ += deltaZ;
            }
        }
        return null;
    }

    private static int floor(double value) {
        return (int) Math.floor(value);
    }

    private static void message(net.minecraft.client.Minecraft client, String text) {
        if (client.player != null) {
            client.player.sendSystemMessage(Component.literal("IcyPlace: " + text));
        }
    }
}
