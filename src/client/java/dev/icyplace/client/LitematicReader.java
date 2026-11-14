package dev.icyplace.client;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPInputStream;

/**
 * Small reader for the common, gzip-compressed Litematica .litematic format.
 * It intentionally reads only the first region; multi-region support can follow.
 */
final class LitematicReader {
    private LitematicReader() {}

    record Schematic(int sizeX, int sizeY, int sizeZ, List<String> palette, long[] states) {
        String blockAt(int x, int y, int z) {
            if (x < 0 || y < 0 || z < 0 || x >= sizeX || y >= sizeY || z >= sizeZ) return null;
            int bits = Math.max(2, 32 - Integer.numberOfLeadingZeros(palette.size() - 1));
            long blockIndex = (long) y * sizeZ * sizeX + (long) z * sizeX + x;
            int valuesPerLong = 64 / bits;
            int word = (int) (blockIndex / valuesPerLong);
            int offset = (int) ((blockIndex % valuesPerLong) * bits);
            if (word >= states.length) return null;
            int paletteIndex = (int) ((states[word] >>> offset) & ((1L << bits) - 1));
            return paletteIndex >= 0 && paletteIndex < palette.size() ? palette.get(paletteIndex) : null;
        }
    }

    static Schematic load(Path path) throws IOException {
        try (DataInputStream in = new DataInputStream(
                new BufferedInputStream(new GZIPInputStream(Files.newInputStream(path))))) {
            int rootType = in.readUnsignedByte();
            if (rootType != 10) throw new IOException("Not an NBT compound");
            readString(in);
            Object rootValue = readPayload(in, 10, 0);
            Map<?, ?> root = asMap(rootValue);
            Map<?, ?> regions = asMap(root.get("Regions"));
            if (regions.isEmpty()) throw new IOException("The schematic contains no regions");

            Object firstRegion = regions.values().iterator().next();
            Map<?, ?> region = asMap(firstRegion);
            Map<?, ?> size = asMap(region.get("Size"));
            int sx = Math.abs(number(size.get("x")));
            int sy = Math.abs(number(size.get("y")));
            int sz = Math.abs(number(size.get("z")));
            if (sx < 1 || sy < 1 || sz < 1 || (long) sx * sy * sz > 16_000_000L) {
                throw new IOException("Unsupported schematic dimensions");
            }

            Object paletteValue = region.get("BlockStatePalette");
            if (!(paletteValue instanceof List<?> paletteTags) || paletteTags.isEmpty()) {
                throw new IOException("Missing block palette");
            }
            List<String> palette = new ArrayList<>();
            for (Object entry : paletteTags) {
                Object name = asMap(entry).get("Name");
                if (!(name instanceof String blockName)) throw new IOException("Invalid palette entry");
                palette.add(blockName);
            }
            Object stateValue = region.get("BlockStates");
            if (!(stateValue instanceof long[] states)) throw new IOException("Missing block-state data");
            return new Schematic(sx, sy, sz, List.copyOf(palette), states);
        } catch (RuntimeException exception) {
            throw new IOException("Could not read this Litematica schematic", exception);
        }
    }

    private static Object readPayload(DataInputStream in, int type, int depth) throws IOException {
        if (depth > 64) throw new IOException("NBT nesting too deep");
        return switch (type) {
            case 1 -> in.readByte();
            case 2 -> in.readShort();
            case 3 -> in.readInt();
            case 4 -> in.readLong();
            case 5 -> in.readFloat();
            case 6 -> in.readDouble();
            case 7 -> {
                int length = checkedLength(in.readInt());
                byte[] data = new byte[length];
                in.readFully(data);
                yield data;
            }
            case 8 -> readString(in);
            case 9 -> {
                int childType = in.readUnsignedByte();
                int length = checkedLength(in.readInt());
                List<Object> list = new ArrayList<>(length);
                for (int i = 0; i < length; i++) list.add(readPayload(in, childType, depth + 1));
                yield list;
            }
            case 10 -> {
                Map<String, Object> compound = new LinkedHashMap<>();
                while (true) {
                    int childType = in.readUnsignedByte();
                    if (childType == 0) break;
                    String name = readString(in);
                    compound.put(name, readPayload(in, childType, depth + 1));
                }
                yield compound;
            }
            case 11 -> {
                int length = checkedLength(in.readInt());
                int[] data = new int[length];
                for (int i = 0; i < length; i++) data[i] = in.readInt();
                yield data;
            }
            case 12 -> {
                int length = checkedLength(in.readInt());
                long[] data = new long[length];
                for (int i = 0; i < length; i++) data[i] = in.readLong();
                yield data;
            }
            default -> throw new IOException("Unknown NBT tag type " + type);
        };
    }

    private static int checkedLength(int length) throws IOException {
        if (length < 0 || length > 32_000_000) throw new IOException("Invalid NBT array length");
        return length;
    }

    private static String readString(DataInputStream in) throws IOException {
        return in.readUTF();
    }

    private static int number(Object value) throws IOException {
        if (value instanceof Number number) return number.intValue();
        throw new IOException("Invalid region size");
    }

    private static Map<?, ?> asMap(Object value) throws IOException {
        if (value instanceof Map<?, ?> map) return map;
        throw new IOException("Invalid NBT compound");
    }
}
