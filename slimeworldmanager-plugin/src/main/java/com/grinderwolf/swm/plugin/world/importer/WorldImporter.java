package com.grinderwolf.swm.plugin.world.importer;

import com.flowpowered.nbt.*;
import com.flowpowered.nbt.stream.NBTInputStream;
import com.grinderwolf.swm.api.exceptions.InvalidWorldException;
import com.grinderwolf.swm.api.utils.NibbleArray;
import com.grinderwolf.swm.api.world.SlimeChunk;
import com.grinderwolf.swm.api.world.SlimeChunkSection;
import com.grinderwolf.swm.api.world.properties.SlimeProperties;
import com.grinderwolf.swm.api.world.properties.SlimePropertyMap;
import com.grinderwolf.swm.nms.*;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.zip.GZIPInputStream;
import java.util.zip.InflaterInputStream;

public class WorldImporter {

    private static final Pattern MAP_FILE_PATTERN = Pattern.compile("^(?:map_([0-9]*).dat)$");
    private static final int SECTOR_SIZE = 4096;

    public static CraftSlimeWorld readFromDirectory(File worldDir) throws InvalidWorldException, IOException {
        File levelFile = new File(worldDir, "level.dat");

        if (!levelFile.exists() || !levelFile.isFile()) {
            throw new InvalidWorldException(worldDir);
        }

        LevelData data = readLevelData(levelFile);

        // World version
        byte worldVersion;

        if (data.getVersion() == -1) { // DataVersion tag was added in 1.9
            worldVersion = 0x01;
        } else if (data.getVersion() < 818) {
            worldVersion = 0x02; // 1.9 world
        } else if (data.getVersion() < 1501) {
            worldVersion = 0x03; // 1.11 world
        } else if (data.getVersion() < 1517) {
            worldVersion = 0x04; // 1.13 world
        } else if (data.getVersion() < 2556) {
            worldVersion = 0x04; // 1.14 world
        } else {
            worldVersion = 0x06; // 1.16 world
        }

        // Chunks
        File regionDir = new File(worldDir, "region");

        if (!regionDir.exists() || !regionDir.isDirectory()) {
            throw new InvalidWorldException(worldDir);
        }

        Map<Long, SlimeChunk> chunks = new HashMap<>();

        for (File file : regionDir.listFiles((dir, name) -> name.endsWith(".mca"))) {
            chunks.putAll(loadChunks(file, worldVersion).stream().collect(Collectors.toMap((chunk) -> ((long) chunk.getZ()) * Integer.MAX_VALUE + ((long) chunk.getX()), (chunk) -> chunk)));
        }

        if (chunks.isEmpty()) {
            throw new InvalidWorldException(worldDir);
        }

        // World maps
        File dataDir = new File(worldDir, "data");
        List<CompoundTag> maps = new ArrayList<>();

        if (dataDir.exists()) {
            if (!dataDir.isDirectory()) {
                throw new InvalidWorldException(worldDir);
            }

            for (File mapFile : dataDir.listFiles((dir, name) -> MAP_FILE_PATTERN.matcher(name).matches())) {
                maps.add(loadMap(mapFile));
            }
        }

        // Extra Data
        CompoundMap extraData = new CompoundMap();

        if (!data.getGameRules().isEmpty()) {
            CompoundMap gamerules = new CompoundMap();
            data.getGameRules().forEach((rule, value) -> gamerules.put(rule, new StringTag(rule, value)));

            extraData.put("gamerules", new CompoundTag("gamerules", gamerules));
        }

        SlimePropertyMap propertyMap = new SlimePropertyMap();

        propertyMap.setInt(SlimeProperties.SPAWN_X, data.getSpawnX());
        propertyMap.setInt(SlimeProperties.SPAWN_Y, data.getSpawnY());
        propertyMap.setInt(SlimeProperties.SPAWN_Z, data.getSpawnZ());

        return new CraftSlimeWorld(null, worldDir.getName(), chunks, new CompoundTag("", extraData),
                maps, worldVersion, propertyMap, false, true);
    }

    private static CompoundTag loadMap(File mapFile) throws IOException {
        String fileName = mapFile.getName();
        int mapId = Integer.parseInt(fileName.substring(4, fileName.length() - 4));
        CompoundTag tag;

        try (NBTInputStream nbtStream = new NBTInputStream(new FileInputStream(mapFile),
                NBTInputStream.GZIP_COMPRESSION, ByteOrder.BIG_ENDIAN)) {
            tag = nbtStream.readTag().getAsCompoundTag().get().getAsCompoundTag("data").get();
        }

        tag.getValue().put("id", new IntTag("id", mapId));

        return tag;
    }

    private static LevelData readLevelData(File file) throws IOException, InvalidWorldException {
        Optional<CompoundTag> tag;

        try (NBTInputStream nbtStream = new NBTInputStream(new FileInputStream(file))) {
            tag = nbtStream.readTag().getAsCompoundTag();
        }

        if (tag.isPresent()) {
            Optional<CompoundTag> dataTag = tag.get().getAsCompoundTag("Data");

            if (dataTag.isPresent()) {
                // Data version
                int dataVersion = dataTag.get().getIntValue("DataVersion").orElse(-1);

                // Game rules
                Map<String, String> gameRules = new HashMap<>();
                Optional<CompoundTag> rulesList = dataTag.get().getAsCompoundTag("GameRules");

                rulesList.ifPresent(compoundTag -> compoundTag.getValue().forEach((ruleName, ruleTag) ->
                        gameRules.put(ruleName, ruleTag.getAsStringTag().get().getValue())));

                int spawnX = dataTag.get().getIntValue("SpawnX").orElse(0);
                int spawnY = dataTag.get().getIntValue("SpawnY").orElse(255);
                int spawnZ = dataTag.get().getIntValue("SpawnZ").orElse(0);

                return new LevelData(dataVersion, gameRules, spawnX, spawnY, spawnZ);
            }
        }

        throw new InvalidWorldException(file.getParentFile());
    }

    private static List<SlimeChunk> loadChunks(File file, byte worldVersion) throws IOException {
        byte[] regionByteArray = Files.readAllBytes(file.toPath());
        DataInputStream inputStream = new DataInputStream(new ByteArrayInputStream(regionByteArray));

        List<ChunkEntry> chunks = new ArrayList<>(1024);

        for (int i = 0; i < 1024; i++) {
            int entry = inputStream.readInt();
            int chunkOffset = entry >>> 8;
            int chunkSize = entry & 15;

            if (entry != 0) {
                ChunkEntry chunkEntry = new ChunkEntry(chunkOffset * SECTOR_SIZE, chunkSize * SECTOR_SIZE);
                chunks.add(chunkEntry);
            }
        }

        List<SlimeChunk> loadedChunks = chunks.stream().map((entry) -> {

            try {
                DataInputStream headerStream = new DataInputStream(new ByteArrayInputStream(regionByteArray, entry.getOffset(), entry.getPaddedSize()));

                int chunkSize = headerStream.readInt() - 1;
                int compressionScheme = headerStream.readByte();

                DataInputStream chunkStream = new DataInputStream(new ByteArrayInputStream(regionByteArray, entry.getOffset() + 5, chunkSize));
                InputStream decompressorStream = compressionScheme == 1 ? new GZIPInputStream(chunkStream) : new InflaterInputStream(chunkStream);
                NBTInputStream nbtStream = new NBTInputStream(decompressorStream, NBTInputStream.NO_COMPRESSION, ByteOrder.BIG_ENDIAN);
                CompoundTag globalCompound = (CompoundTag) nbtStream.readTag();
                CompoundMap globalMap = globalCompound.getValue();

                if (!globalMap.containsKey("Level")) {
                    throw new RuntimeException("Missing Level tag?");
                }

                CompoundTag levelCompound = (CompoundTag) globalMap.get("Level");

                return readChunk(levelCompound, worldVersion);
            } catch (IOException ex) {
                throw new RuntimeException(ex);
            }

        }).filter(Objects::nonNull).collect(Collectors.toList());

        return loadedChunks;
    }

    private static SlimeChunk readChunk(CompoundTag compound, byte worldVersion) {
        int chunkX = compound.getAsIntTag("xPos").get().getValue();
        int chunkZ = compound.getAsIntTag("zPos").get().getValue();
        Optional<String> status = compound.getStringValue("Status");

        if (status.isPresent() && !status.get().equals("postprocessed") && !status.get().startsWith("full")) {
            // It's a protochunk
            return null;
        }

        int[] biomes;
        Tag biomesTag = compound.getValue().get("Biomes");

        if (biomesTag instanceof IntArrayTag) {
            biomes = ((IntArrayTag) biomesTag).getValue();
        } else if (biomesTag instanceof ByteArrayTag) {
            byte[] byteBiomes = ((ByteArrayTag) biomesTag).getValue();
            biomes = toIntArray(byteBiomes);
        } else {
            biomes = null;
        }

        Optional<CompoundTag> optionalHeightMaps = compound.getAsCompoundTag("Heightmaps");
        CompoundTag heightMapsCompound;

        if (worldVersion >= 0x04) {
            heightMapsCompound = optionalHeightMaps.orElse(new CompoundTag("", new CompoundMap()));
        } else {
            // Pre 1.13 world

            int[] heightMap = compound.getIntArrayValue("HeightMap").orElse(new int[256]);
            heightMapsCompound = new CompoundTag("", new CompoundMap());
            heightMapsCompound.getValue().put("heightMap", new IntArrayTag("heightMap", heightMap));
        }

        List<CompoundTag> tileEntities = ((ListTag<CompoundTag>) compound.getAsListTag("TileEntities")
                .orElse(new ListTag<>("TileEntities", TagType.TAG_COMPOUND, new ArrayList<>()))).getValue();
        List<CompoundTag> entities = ((ListTag<CompoundTag>) compound.getAsListTag("Entities")
                .orElse(new ListTag<>("Entities", TagType.TAG_COMPOUND, new ArrayList<>()))).getValue();
        ListTag<CompoundTag> sectionsTag = (ListTag<CompoundTag>) compound.getAsListTag("Sections").get();
        SlimeChunkSection[] sectionArray = new SlimeChunkSection[16];

        for (CompoundTag sectionTag : sectionsTag.getValue()) {
            int index = sectionTag.getByteValue("Y").get();

            if (index < 0) {
                // For some reason MC 1.14 worlds contain an empty section with Y = -1.
                continue;
            }

            byte[] blocks = sectionTag.getByteArrayValue("Blocks").orElse(null);
            NibbleArray dataArray;
            ListTag<CompoundTag> paletteTag;
            long[] blockStatesArray;

            if (worldVersion < 0x04) {
                dataArray = new NibbleArray(sectionTag.getByteArrayValue("Data").get());

                if (isEmpty(blocks)) { // Just skip it
                    continue;
                }

                paletteTag = null;
                blockStatesArray = null;
            } else {
                dataArray = null;

                paletteTag = (ListTag<CompoundTag>) sectionTag.getAsListTag("Palette").orElse(null);
                blockStatesArray = sectionTag.getLongArrayValue("BlockStates").orElse(null);

                if (paletteTag == null || blockStatesArray == null || isEmpty(blockStatesArray)) { // Skip it
                    continue;
                }
            }

            NibbleArray blockLightArray = sectionTag.getValue().containsKey("BlockLight") ? new NibbleArray(sectionTag.getByteArrayValue("BlockLight").get()) : null;
            NibbleArray skyLightArray = sectionTag.getValue().containsKey("SkyLight") ? new NibbleArray(sectionTag.getByteArrayValue("SkyLight").get()) : null;

            sectionArray[index] = new CraftSlimeChunkSection(blocks, dataArray, paletteTag, blockStatesArray, blockLightArray, skyLightArray);
        }

        for (SlimeChunkSection section : sectionArray) {
            if (section != null) { // Chunk isn't empty
                return new CraftSlimeChunk(null, chunkX, chunkZ, sectionArray, heightMapsCompound, biomes, tileEntities, entities);
            }
        }

        // Chunk is empty
        return null;
    }

    private static int[] toIntArray(byte[] buf) {
        ByteBuffer buffer = ByteBuffer.wrap(buf).order(ByteOrder.BIG_ENDIAN);
        int[] ret = new int[buf.length / 4];

        buffer.asIntBuffer().get(ret);

        return ret;
    }

    private static boolean isEmpty(byte[] array) {
        for (byte b : array) {
            if (b != 0) {
                return false;
            }
        }

        return true;
    }

    private static boolean isEmpty(long[] array) {
        for (long b : array) {
            if (b != 0L) {
                return false;
            }
        }

        return true;
    }

    @Getter
    @RequiredArgsConstructor
    private static class ChunkEntry {

        private final int offset;
        private final int paddedSize;

    }
}
