package dev.icyplace.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Consumer;
import java.util.stream.Stream;

/**
 * A Minecraft-native schematic picker. Avoids opening an AWT window,
 * which can freeze some custom clients.
 */
final class SchematicPickerScreen extends Screen {
    private final Minecraft client;
    private final Screen parent;
    private final Consumer<LitematicReader.Schematic> onLoaded;
    private String status = "Choose a .litematic file from your schematics folder.";

    SchematicPickerScreen(Minecraft client, Screen parent,
                          Consumer<LitematicReader.Schematic> onLoaded) {
        super(Component.literal("IcyPlace - Select schematic"));
        this.client = client;
        this.parent = parent;
        this.onLoaded = onLoaded;
    }

    @Override
    protected void init() {
        super.init();
        clearWidgets();

        int buttonWidth = Math.min(360, this.width - 40);
        int left = (this.width - buttonWidth) / 2;
        int y = 78;
        int shown = 0;

        List<Path> files = findSchematics();
        for (Path file : files) {
            if (shown >= 10) break;
            Path selected = file;
            addRenderableWidget(Button.builder(
                    Component.literal(file.getFileName().toString()),
                    button -> load(selected))
                    .bounds(left, y, buttonWidth, 20).build());
            y += 24;
            shown++;
        }

        if (files.isEmpty()) {
            status = "No .litematic files found. Put them in .minecraft/schematics";
        } else if (files.size() > 10) {
            status = "Showing first 10 files in .minecraft/schematics";
        } else {
            status = "Found " + files.size() + " schematic file(s).";
        }

        addRenderableWidget(Button.builder(Component.literal("Close"), button -> closePicker())
                .bounds(left, this.height - 36, buttonWidth, 20).build());
    }

    private List<Path> findSchematics() {
        List<Path> files = new ArrayList<>();
        Path gameDir = client.gameDirectory.toPath();
        Path[] folders = {
                gameDir.resolve("schematics"),
                gameDir.resolve("config").resolve("icyplace")
        };

        for (Path folder : folders) {
            try {
                Files.createDirectories(folder);
                try (Stream<Path> stream = Files.list(folder)) {
                    stream.filter(Files::isRegularFile)
                            .filter(path -> path.getFileName().toString().toLowerCase().endsWith(".litematic"))
                            .forEach(files::add);
                }
            } catch (IOException ignored) {
                // Keep scanning the other supported folder.
            }
        }

        files.sort(Comparator.comparing(path -> path.getFileName().toString().toLowerCase()));
        return files;
    }

    private void load(Path file) {
        try {
            LitematicReader.Schematic schematic = LitematicReader.load(file);
            onLoaded.accept(schematic);
            closePicker();
        } catch (Exception exception) {
            status = "Could not load file: " + exception.getMessage();
        }
    }

    private void closePicker() {
        client.gui.setScreen(parent);
    }

    @Override
    public void onClose() {
        closePicker();
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        super.extractRenderState(graphics, mouseX, mouseY, partialTick);
        graphics.centeredText(font, Component.literal("IcyPlace schematic picker"),
                this.width / 2, 38, 0xFFFFFFFF);
        graphics.centeredText(font, Component.literal(status),
                this.width / 2, 58, 0xFFFFFFFF);
    }
}
