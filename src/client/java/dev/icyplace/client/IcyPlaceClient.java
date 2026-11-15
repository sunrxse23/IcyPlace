package dev.icyplace.client;

import com.mojang.blaze3d.platform.InputConstants;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.loader.api.FabricLoader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.zip.ZipFile;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
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

public class IcyPlaceClient implements ClientModInitializer {
    private static boolean enabled = true;
    private static boolean autoPlaceEnabled = false;
    private static int autoPlaceCooldown = 0;
    private static final int[] SPEED_OPTIONS = {1, 2, 4, 6, 10};
    private static int speedIndex = 2;
    private static int autoPlaceIntervalTicks = SPEED_OPTIONS[speedIndex];
    private static volatile Path pendingUpdateFile;
    private static volatile String pendingUpdateName;
    private static boolean shutdownHookRegistered = false;
    private static String lastMissingBlock = "";
    private static boolean updateCheckStarted = false;
    private static final ConcurrentLinkedQueue<String> UPDATE_MESSAGES = new ConcurrentLinkedQueue<>();
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

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
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (!updateCheckStarted) {
                updateCheckStarted = true;
                CompletableFuture.runAsync(IcyPlaceClient::checkForUpdates);
            }
            if (client.player != null) {
                String updateMessage;
                while ((updateMessage = UPDATE_MESSAGES.poll()) != null) {
                    message(client, updateMessage);
                }
            }
            while (TOGGLE_KEY.consumeClick()) {
                enabled = !enabled;
                lastMissingBlock = "";
                message(client, "Assistant " + (enabled ? "ON" : "OFF"));
            }

            while (AUTOPLACE_KEY.consumeClick()) {
                autoPlaceEnabled = !autoPlaceEnabled;
                autoPlaceCooldown = 0;
                message(client, "AutoPlace " + (autoPlaceEnabled ? "ON" : "OFF"));
            }

            while (SPEED_KEY.consumeClick()) {
                speedIndex = (speedIndex + 1) % SPEED_OPTIONS.length;
                autoPlaceIntervalTicks = SPEED_OPTIONS[speedIndex];
                autoPlaceCooldown = 0;
                message(client, "Placement speed: every " + autoPlaceIntervalTicks
                        + " tick" + (autoPlaceIntervalTicks == 1 ? "" : "s"));
            }

            if (client.player == null || client.level == null || client.gui.screen() != null
                    || client.gameMode == null) {
                return;
            }

            // Read the block at the active Litematica schematic target.
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

            // F10 controls only automatic block selection and missing-block warnings.
            if (enabled) {
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
                } else if (!blockName.equals(lastMissingBlock)) {
                    message(client, "Missing from inventory: " + blockName);
                    lastMissingBlock = blockName;
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

    private static void checkForUpdates() {
        try {
            HttpRequest releaseRequest = HttpRequest.newBuilder()
                    .uri(URI.create("https://api.github.com/repos/sunrxse23/IcyPlace/releases/latest"))
                    .timeout(Duration.ofSeconds(20))
                    .header("Accept", "application/vnd.github+json")
                    .header("User-Agent", "IcyPlace-Updater")
                    .GET()
                    .build();
            HttpResponse<String> releaseResponse = HTTP.send(releaseRequest, HttpResponse.BodyHandlers.ofString());
            if (releaseResponse.statusCode() != 200) return;

            JsonObject release = JsonParser.parseString(releaseResponse.body()).getAsJsonObject();
            String tag = release.get("tag_name").getAsString();
            String latestVersion = tag.startsWith("v") ? tag.substring(1) : tag;
            String installedVersion = FabricLoader.getInstance().getModContainer("icyplace")
                    .map(container -> container.getMetadata().getVersion().getFriendlyString()).orElse("0");
            if (latestVersion.equals(installedVersion)) return;

            JsonArray assets = release.getAsJsonArray("assets");
            if (assets == null) return;
            String downloadUrl = null;
            String assetName = null;
            String expectedDigest = null;
            for (int i = 0; i < assets.size(); i++) {
                JsonObject asset = assets.get(i).getAsJsonObject();
                String name = asset.get("name").getAsString();
                if (name.equals("icyplace-" + latestVersion + ".jar")) {
                    assetName = name;
                    downloadUrl = asset.get("browser_download_url").getAsString();
                    if (asset.has("digest") && !asset.get("digest").isJsonNull()) {
                        expectedDigest = asset.get("digest").getAsString();
                    }
                    break;
                }
            }
            if (downloadUrl == null) return;

            Path updateDirectory = FabricLoader.getInstance().getGameDir()
                    .resolve("config").resolve("icyplace").resolve("updates");
            Files.createDirectories(updateDirectory);
            Path updateFile = updateDirectory.resolve(assetName);

            if (!isValidModJar(updateFile)) {
                Files.deleteIfExists(updateFile);
                HttpRequest downloadRequest = HttpRequest.newBuilder().uri(URI.create(downloadUrl))
                        .timeout(Duration.ofMinutes(2))
                        .header("User-Agent", "IcyPlace-Updater")
                        .GET().build();
                HttpResponse<byte[]> downloadResponse = HTTP.send(downloadRequest, HttpResponse.BodyHandlers.ofByteArray());
                if (downloadResponse.statusCode() != 200 || downloadResponse.body().length == 0) return;

                if (expectedDigest != null && expectedDigest.startsWith("sha256:")) {
                    String actualDigest = "sha256:" + HexFormat.of().formatHex(
                            MessageDigest.getInstance("SHA-256").digest(downloadResponse.body()));
                    if (!actualDigest.equalsIgnoreCase(expectedDigest)) return;
                }

                Path temporaryFile = updateDirectory.resolve(assetName + ".download");
                Files.write(temporaryFile, downloadResponse.body());
                if (!isValidModJar(temporaryFile)) {
                    Files.deleteIfExists(temporaryFile);
                    return;
                }
                Files.move(temporaryFile, updateFile, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }

            pendingUpdateFile = updateFile;
            pendingUpdateName = assetName;
            registerAutomaticInstall();
            UPDATE_MESSAGES.add("Update " + latestVersion
                    + " downloaded. It will install automatically when you close Minecraft.");
        } catch (Exception ignored) {
            // Network or GitHub errors must never prevent Minecraft from starting.
        }
    }

    private static boolean isValidModJar(Path file) {
        if (!Files.isRegularFile(file)) return false;
        try (ZipFile jar = new ZipFile(file.toFile())) {
            return jar.getEntry("fabric.mod.json") != null;
        } catch (Exception ignored) {
            return false;
        }
    }

    private static synchronized void registerAutomaticInstall() {
        if (shutdownHookRegistered) return;
        shutdownHookRegistered = true;
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try {
                Path source = pendingUpdateFile;
                if (source == null || !isValidModJar(source)) return;

                if (!System.getProperty("os.name", "").toLowerCase().contains("windows")) {
                    UPDATE_MESSAGES.add("Update " + pendingUpdateName
                            + " is ready in config/icyplace/updates. Replace the IcyPlace JAR in mods after closing Minecraft.");
                    return;
                }

                Path installedJar = findInstalledJar();
                if (installedJar == null) {
                    UPDATE_MESSAGES.add("Update downloaded, but I couldn't locate the installed JAR to replace automatically.");
                    return;
                }

                Path modsDirectory = installedJar.getParent();
                Path newJar = modsDirectory.resolve(pendingUpdateName);
                Path script = source.getParent().resolve("install-update.ps1");
                String ps = "$ErrorActionPreference = 'Stop'\n"
                        + "$gamePid = " + ProcessHandle.current().pid() + "\n"
                        + "while (Get-Process -Id $gamePid -ErrorAction SilentlyContinue) { Start-Sleep -Milliseconds 500 }\n"
                        + "$source = '" + psQuote(source.toAbsolutePath().toString()) + "'\n"
                        + "$target = '" + psQuote(newJar.toAbsolutePath().toString()) + "'\n"
                        + "$old = '" + psQuote(installedJar.toAbsolutePath().toString()) + "'\n"
                        + "$temp = $target + '.installing'\n"
                        + "Copy-Item -LiteralPath $source -Destination $temp -Force\n"
                        + "Move-Item -LiteralPath $temp -Destination $target -Force\n"
                        + "if ($old -ne $target -and (Test-Path -LiteralPath $old)) { Remove-Item -LiteralPath $old -Force }\n"
                        + "Remove-Item -LiteralPath '" + psQuote(script.toAbsolutePath().toString()) + "' -Force -ErrorAction SilentlyContinue\n";
                Files.writeString(script, ps, StandardCharsets.UTF_8);
                new ProcessBuilder("powershell.exe", "-NoProfile", "-ExecutionPolicy", "Bypass",
                        "-WindowStyle", "Hidden", "-File", script.toAbsolutePath().toString())
                        .redirectErrorStream(true)
                        .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                        .start();
                UPDATE_MESSAGES.add("Update " + pendingUpdateName
                        + " will install automatically after Minecraft closes. Restart Minecraft to use it.");
            } catch (Exception ignored) {
                // Never interrupt Minecraft shutdown if automatic installation cannot be scheduled.
            }
        }, "IcyPlace-AutoUpdater"));
    }

    private static Path findInstalledJar() {
        var modContainer = FabricLoader.getInstance().getModContainer("icyplace").orElse(null);
        if (modContainer == null) return null;
        for (Path path : modContainer.getOrigin().getPaths()) {
            if (Files.isRegularFile(path) && path.toString().toLowerCase().endsWith(".jar")) {
                return path.toAbsolutePath();
            }
        }
        return null;
    }

    private static String psQuote(String value) {
        return value.replace("'", "''");
    }

    private static void message(net.minecraft.client.Minecraft client, String text) {
        if (client.player != null) {
            client.player.sendSystemMessage(Component.literal("IcyPlace: " + text));
        }
    }
}
