package dev.icyplace.client;

import com.mojang.blaze3d.PrimitiveTopology;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelExtractionContext;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelExtractionEvents;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.StagedVertexBuffer;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.resources.Identifier;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Vector3f;
import org.joml.Vector4f;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalDouble;

final class SchematicHologramRenderer implements ClientModInitializer {
    private static final RenderPipeline PIPELINE = RenderPipelines.register(
            RenderPipeline.builder(RenderPipelines.DEBUG_FILLED_SNIPPET)
                    .withLocation(Identifier.fromNamespaceAndPath("icyplace", "pipeline/schematic_hologram"))
                    .withDepthStencilState(Optional.empty())
                    .build()
    );
    private static final Vector4f COLOR_MODULATOR = new Vector4f(1f, 1f, 1f, 1f);
    private static final Vector3f MODEL_OFFSET = new Vector3f();
    private static final Matrix4f TEXTURE_MATRIX = new Matrix4f();
    private static final StagedVertexBuffer BUFFER =
            new StagedVertexBuffer(() -> "IcyPlace hologram", RenderType.SMALL_BUFFER_SIZE);

    private volatile List<GhostBlock> blocks = List.of();

    private record GhostBlock(int x, int y, int z, float r, float g, float b, float a) { }

    @Override
    public void onInitializeClient() {
        LevelExtractionEvents.END_EXTRACTION.register(this::extract);
        LevelRenderEvents.AFTER_TRANSLUCENT_TERRAIN.register(this::draw);
    }

    private void extract(LevelExtractionContext context) {
        LitematicReader.Schematic schematic = IcyPlaceClient.getSchematic();
        Minecraft client = Minecraft.getInstance();
        if (schematic == null || !IcyPlaceClient.isOriginSet() || client.level == null) {
            blocks = List.of();
            return;
        }

        int sx = schematic.sizeX();
        int sy = schematic.sizeY();
        int sz = schematic.sizeZ();
        long volume = (long) sx * sy * sz;
        if (volume > 250_000L) {
            blocks = List.of();
            return;
        }

        List<GhostBlock> next = new ArrayList<>();
        int rotation = IcyPlaceClient.getRotation();
        int ox = IcyPlaceClient.getOriginX();
        int oy = IcyPlaceClient.getOriginY();
        int oz = IcyPlaceClient.getOriginZ();

        for (int y = 0; y < sy; y++) {
            for (int z = 0; z < sz; z++) {
                for (int x = 0; x < sx; x++) {
                    String block = schematic.blockAt(x, y, z);
                    if (block == null || block.endsWith(":air") || block.endsWith(":cave_air")
                            || block.endsWith(":void_air")) continue;

                    int rx = x;
                    int rz = z;
                    if (rotation == 1) { rx = sz - 1 - z; rz = x; }
                    else if (rotation == 2) { rx = sx - 1 - x; rz = sz - 1 - z; }
                    else if (rotation == 3) { rx = z; rz = sx - 1 - x; }

                    next.add(new GhostBlock(ox + rx, oy + y, oz + rz, 0.25f, 0.85f, 1f, 0.28f));
                }
            }
        }
        blocks = List.copyOf(next);
    }

    private void draw(LevelRenderContext context) {
        List<GhostBlock> snapshot = blocks;
        if (snapshot.isEmpty()) return;

        VertexFormat format = PIPELINE.getVertexFormatBinding(0);
        if (format == null) return;
        PrimitiveTopology topology = PIPELINE.getPrimitiveTopology();
        StagedVertexBuffer.Draw draw = BUFFER.appendDraw(format, topology,
                topology == PrimitiveTopology.QUADS ? RenderSystem.getProjectionType().vertexSorting() : null);

        PoseStack poses = context.poseStack();
        Vec3 camera = context.levelState().cameraRenderState.pos;
        poses.pushPose();
        poses.translate(-camera.x, -camera.y, -camera.z);
        Matrix4fc matrix = poses.last().pose();
        VertexConsumer builder = BUFFER.getVertexBuilder(draw);
        for (GhostBlock block : snapshot) {
            filledBox(matrix, builder, block.x(), block.y(), block.z(),
                    block.x() + 1, block.y() + 1, block.z() + 1,
                    block.r(), block.g(), block.b(), block.a());
        }
        poses.popPose();

        BUFFER.upload();
        StagedVertexBuffer.ExecuteInfo info = BUFFER.getExecuteInfo(draw);
        if (info != null) draw(Minecraft.getInstance(), info);
        BUFFER.endFrame();
    }

    private static void draw(Minecraft client, StagedVertexBuffer.ExecuteInfo info) {
        GpuBufferSlice transforms = RenderSystem.getDynamicUniforms()
                .writeTransform(RenderSystem.getModelViewMatrixCopy(), COLOR_MODULATOR, MODEL_OFFSET, TEXTURE_MATRIX);
        RenderTarget target = client.gameRenderer.mainRenderTarget();
        GpuTextureView color = target.getColorTextureView();
        if (color == null) return;
        try (RenderPass pass = RenderSystem.getDevice().createCommandEncoder()
                .createRenderPass(() -> "IcyPlace hologram", color, Optional.empty(),
                        target.getDepthTextureView(), OptionalDouble.empty())) {
            pass.setPipeline(PIPELINE);
            RenderSystem.bindDefaultUniforms(pass);
            pass.setUniform("DynamicTransforms", transforms);
            pass.setVertexBuffer(0, info.vertexBuffer().slice());
            pass.setIndexBuffer(info.indexBuffer(), info.indexType());
            pass.drawIndexed(info.indexCount(), 1, info.firstIndex(), info.baseVertex(), 0);
        }
    }

    private static void filledBox(Matrix4fc m, VertexConsumer b,
                                  float x1, float y1, float z1, float x2, float y2, float z2,
                                  float r, float g, float blue, float a) {
        quad(m,b,x1,y1,z2,x2,y1,z2,x2,y2,z2,x1,y2,z2,r,g,blue,a);
        quad(m,b,x2,y1,z1,x1,y1,z1,x1,y2,z1,x2,y2,z1,r,g,blue,a);
        quad(m,b,x1,y1,z1,x1,y1,z2,x1,y2,z2,x1,y2,z1,r,g,blue,a);
        quad(m,b,x2,y1,z2,x2,y1,z1,x2,y2,z1,x2,y2,z2,r,g,blue,a);
        quad(m,b,x1,y2,z2,x2,y2,z2,x2,y2,z1,x1,y2,z1,r,g,blue,a);
        quad(m,b,x1,y1,z1,x2,y1,z1,x2,y1,z2,x1,y1,z2,r,g,blue,a);
    }

    private static void quad(Matrix4fc m, VertexConsumer b,
                             float ax,float ay,float az,float bx,float by,float bz,
                             float cx,float cy,float cz,float dx,float dy,float dz,
                             float r,float g,float blue,float a) {
        b.addVertex(m,ax,ay,az).setColor(r,g,blue,a);
        b.addVertex(m,bx,by,bz).setColor(r,g,blue,a);
        b.addVertex(m,cx,cy,cz).setColor(r,g,blue,a);
        b.addVertex(m,dx,dy,dz).setColor(r,g,blue,a);
    }
}
