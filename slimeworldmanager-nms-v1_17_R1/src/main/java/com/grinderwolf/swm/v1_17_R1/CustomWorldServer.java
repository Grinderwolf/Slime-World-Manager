package com.grinderwolf.swm.v1_17_R1;

import com.flowpowered.nbt.CompoundMap;
import com.flowpowered.nbt.CompoundTag;
import com.flowpowered.nbt.LongArrayTag;
import com.google.common.util.concurrent.ThreadFactoryBuilder;
import com.grinderwolf.swm.api.exceptions.UnknownWorldException;
import com.grinderwolf.swm.api.world.SlimeChunk;
import com.grinderwolf.swm.api.world.SlimeChunkSection;
import com.grinderwolf.swm.api.world.properties.SlimeProperties;
import com.grinderwolf.swm.api.world.properties.SlimePropertyMap;
import com.grinderwolf.swm.nms.CraftSlimeChunk;
import com.grinderwolf.swm.nms.CraftSlimeWorld;
import lombok.Getter;
import lombok.Setter;
import net.minecraft.core.BlockPosition;
import net.minecraft.core.IRegistry;
import net.minecraft.core.SectionPosition;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ChunkProviderServer;
import net.minecraft.server.level.TicketType;
import net.minecraft.server.level.WorldServer;
import net.minecraft.util.IProgressUpdate;
import net.minecraft.util.Unit;
import net.minecraft.world.EnumDifficulty;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.level.ChunkCoordIntPair;
import net.minecraft.world.level.EnumSkyBlock;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.World;
import net.minecraft.world.level.biome.WorldChunkManager;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.TileEntity;
import net.minecraft.world.level.block.state.IBlockData;
import net.minecraft.world.level.chunk.*;
import net.minecraft.world.level.dimension.DimensionManager;
import net.minecraft.world.level.dimension.WorldDimension;
import net.minecraft.world.level.levelgen.HeightMap;
import net.minecraft.world.level.lighting.LightEngine;
import net.minecraft.world.level.material.FluidType;
import net.minecraft.world.level.material.FluidTypes;
import net.minecraft.world.level.storage.IWorldDataServer;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.bukkit.Bukkit;
import org.bukkit.event.world.WorldSaveEvent;

import java.io.IOException;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

public class CustomWorldServer extends WorldServer {

    private static final Logger LOGGER = LogManager.getLogger("SWM World");
    private static final ExecutorService WORLD_SAVER_SERVICE = Executors.newFixedThreadPool(4, new ThreadFactoryBuilder()
            .setNameFormat("SWM Pool Thread #%1$d").build());
    private static final TicketType<Unit> SWM_TICKET = TicketType.a("swm-chunk", (a, b) -> 0);

    @Getter
    private final CraftSlimeWorld slimeWorld;
    private final Object saveLock = new Object();

    @Getter
    @Setter
    private boolean ready = false;

    public CustomWorldServer(CraftSlimeWorld world, IWorldDataServer worldData,
                             ResourceKey<World> worldKey, ResourceKey<WorldDimension> dimensionKey,
                             DimensionManager dimensionManager, ChunkGenerator chunkGenerator,
                             org.bukkit.World.Environment environment) throws IOException {
        super(MinecraftServer.getServer(), MinecraftServer.getServer().aA,
                v1_17_R1SlimeNMS.CONVERTABLE.c(world.getName(), dimensionKey),
                worldData, worldKey, dimensionManager, MinecraftServer.getServer().L.create(11),
                chunkGenerator, false, 0, new ArrayList<>(), true, environment, null);

        this.slimeWorld = world;

        SlimePropertyMap propertyMap = world.getPropertyMap();

        this.E.setDifficulty(EnumDifficulty.valueOf(propertyMap.getString(SlimeProperties.DIFFICULTY).toUpperCase()));
        this.E.setSpawn(new BlockPosition(propertyMap.getInt(SlimeProperties.SPAWN_X), propertyMap.getInt(SlimeProperties.SPAWN_Y), propertyMap.getInt(SlimeProperties.SPAWN_Z)), 0);
        super.setSpawnFlags(propertyMap.getBoolean(SlimeProperties.ALLOW_MONSTERS), propertyMap.getBoolean(SlimeProperties.ALLOW_ANIMALS));

        this.pvpMode = propertyMap.getBoolean(SlimeProperties.PVP);
    }

    @Override
    public void save(IProgressUpdate progressUpdate, boolean forceSave, boolean flag1) {
        if (!slimeWorld.isReadOnly() && !flag1) {
            if (forceSave) { // TODO Is this really 'forceSave'? Doesn't look like it tbh
                Bukkit.getPluginManager().callEvent(new WorldSaveEvent(getWorld()));
            }

            //this.timings.worldSaveChunks.startTiming();
            //this.getChunkProvider().save(forceSave);
            //this.timings.worldSaveChunks.stopTiming();
            this.E.a(this.getWorldBorder().t());
            this.E.setCustomBossEvents(MinecraftServer.getServer().getBossBattleCustomData().save());

            // Update level data
            NBTTagCompound compound = new NBTTagCompound();
            this.E.a(MinecraftServer.getServer().l, compound);
            slimeWorld.getExtraData().getValue().put(Converter.convertTag("LevelData", compound));

            if (MinecraftServer.getServer().isStopped()) { // Make sure the world gets saved before stopping the server by running it from the main thread
                save();

                // Have to manually unlock the world as well
                try {
                    slimeWorld.getLoader().unlockWorld(slimeWorld.getName());
                } catch (IOException ex) {
                    LOGGER.error("Failed to unlock the world " + slimeWorld.getName() + ". Please unlock it manually by using the command /swm manualunlock. Stack trace:");

                    ex.printStackTrace();
                } catch (UnknownWorldException ignored) {

                }
            } else {
                WORLD_SAVER_SERVICE.execute(this::save);
            }
        }
    }

    private void save() {
        synchronized (saveLock) { // Don't want to save the SlimeWorld from multiple threads simultaneously
            try {
                LOGGER.info("Saving world " + slimeWorld.getName() + "...");
                long start = System.currentTimeMillis();
                byte[] serializedWorld = slimeWorld.serialize();
                slimeWorld.getLoader().saveWorld(slimeWorld.getName(), serializedWorld, false);
                LOGGER.info("World " + slimeWorld.getName() + " saved in " + (System.currentTimeMillis() - start) + "ms.");
            } catch (IOException ex) {
                ex.printStackTrace();
            }
        }
    }

    ProtoChunkExtension getChunk(int x, int z) {
        SlimeChunk slimeChunk = slimeWorld.getChunk(x, z);
        Chunk chunk;

        if (slimeChunk instanceof NMSSlimeChunk) {
            chunk = ((NMSSlimeChunk) slimeChunk).getChunk();
        } else {
            if (slimeChunk == null) {
                ChunkCoordIntPair pos = new ChunkCoordIntPair(x, z);

                WorldChunkManager chunkManager = this.getChunkProvider().getChunkGenerator().getWorldChunkManager();
                // Biomes
                BiomeStorage biomeStorage = new BiomeStorage(t().b(IRegistry.aO), this, pos, chunkManager, new int[BiomeStorage.a]);

                // Tick lists
                ProtoChunkTickList<Block> blockTickList = new ProtoChunkTickList<>((block) ->
                        block == null || block.getBlockData().isAir(), pos, this);
                ProtoChunkTickList<FluidType> fluidTickList = new ProtoChunkTickList<>((type) ->
                        type == null || type == FluidTypes.a, pos, this);

                chunk = new Chunk(this, pos, biomeStorage, ChunkConverter.a, blockTickList, fluidTickList,
                        0L, null, null);

                // Height Maps
                HeightMap.a(chunk, ChunkStatus.m.h());
            } else {
                chunk = createChunk(slimeChunk);
            }

            slimeWorld.updateChunk(new NMSSlimeChunk(chunk));
        }

        return new ProtoChunkExtension(chunk);
    }
    
    private Chunk createChunk(SlimeChunk chunk) {
        int x = chunk.getX();
        int z = chunk.getZ();

        LOGGER.debug("Loading chunk (" + x + ", " + z + ") on world " + slimeWorld.getName());

        ChunkCoordIntPair pos = new ChunkCoordIntPair(x, z);

        // Biomes
        int[] biomeIntArray = chunk.getBiomes();
        BiomeStorage biomeStorage = new BiomeStorage(MinecraftServer.getServer().getCustomRegistry().b(IRegistry.aO), this, pos,
                this.E.getGeneratorSettings().getChunkGenerator().getWorldChunkManager(), biomeIntArray);

        // Tick lists
        ProtoChunkTickList<Block> blockTickList = new ProtoChunkTickList<>(
                (block) -> block == null || block.getBlockData().isAir(), pos, this);
        ProtoChunkTickList<FluidType> fluidTickList = new ProtoChunkTickList<>(
                (type) -> type == null || type == FluidTypes.a, pos, this);

        // Chunk sections
        LOGGER.debug("Loading chunk sections for chunk (" + pos.b + ", " + pos.c + ") on world " + slimeWorld.getName());
        ChunkSection[] sections = new ChunkSection[16];
        LightEngine lightEngine = getChunkProvider().getLightEngine();

        lightEngine.b(pos, true);

        for (int sectionId = 0; sectionId < chunk.getSections().length; sectionId++) {
            SlimeChunkSection slimeSection = chunk.getSections()[sectionId];

            if (slimeSection != null) {
                ChunkSection section = new ChunkSection(sectionId << 4);

                LOGGER.debug("ChunkSection #" + sectionId + " - Chunk (" + pos.b + ", " + pos.c + ") - World " + slimeWorld.getName() + ":");
                LOGGER.debug("Block palette:");
                LOGGER.debug(slimeSection.getPalette().toString());
                LOGGER.debug("Block states array:");
                LOGGER.debug(slimeSection.getBlockStates());
                LOGGER.debug("Block light array:");
                LOGGER.debug(slimeSection.getBlockLight() != null ? slimeSection.getBlockLight().getBacking() : "Not present");
                LOGGER.debug("Sky light array:");
                LOGGER.debug(slimeSection.getSkyLight() != null ? slimeSection.getSkyLight().getBacking() : "Not present");

                section.getBlocks().a((NBTTagList) Converter.convertTag(slimeSection.getPalette()), slimeSection.getBlockStates());

                if (slimeSection.getBlockLight() != null) {
                    lightEngine.a(EnumSkyBlock.b, SectionPosition.a(pos, sectionId), Converter.convertArray(slimeSection.getBlockLight()), true);
                }

                if (slimeSection.getSkyLight() != null) {
                    lightEngine.a(EnumSkyBlock.a, SectionPosition.a(pos, sectionId), Converter.convertArray(slimeSection.getSkyLight()), true);
                }

                section.recalcBlockCounts();
                sections[sectionId] = section;
            }
        }

        // Keep the chunk loaded at level 33 to avoid light glitches
        // Such a high level will let the server not tick the chunk,
        // but at the same time it won't be completely unloaded from memory
        getChunkProvider().addTicket(SWM_TICKET, pos, 33, Unit.a);
        // or this.C

        Consumer<Chunk> loadEntities = (nmsChunk) -> {

            // Load tile entities
            LOGGER.debug("Loading tile entities for chunk (" + pos.b + ", " + pos.c + ") on world " + slimeWorld.getName());
            List<CompoundTag> tileEntities = chunk.getTileEntities();
            int loadedEntities = 0;

            if (tileEntities != null) {
                for (CompoundTag tag : tileEntities) {
                    Optional<String> type = tag.getStringValue("id");

                    // Sometimes null tile entities are saved
                    if (type.isPresent()) {
                        BlockPosition blockPosition = new BlockPosition(tag.getIntValue("x").get(),
                                tag.getIntValue("y").get(), tag.getIntValue("z").get());
                        IBlockData blockData = nmsChunk.getType(blockPosition);
                        TileEntity entity = TileEntity.create(blockPosition, blockData, (NBTTagCompound) Converter.convertTag(tag));

                        if (entity != null) {
                            nmsChunk.b(entity);
                            loadedEntities++;
                        }
                    }
                }
            }

            LOGGER.debug("Loaded " + loadedEntities + " tile entities for chunk (" + pos.b + ", " + pos.c + ") on world " + slimeWorld.getName());

            // Load entities
            LOGGER.debug("Loading entities for chunk (" + pos.b + ", " + pos.c + ") on world " + slimeWorld.getName());
            List<CompoundTag> entities = chunk.getEntities();
            loadedEntities = 0;

            if (entities != null) {
                for (CompoundTag tag : entities) {
                    EntityTypes.a((NBTTagCompound) Converter.convertTag(tag), nmsChunk.i, (entity) -> {

                        nmsChunk.a(entity);
                        return entity;

                    });

                    //nmsChunk.d(true);
                    loadedEntities++;
                }
            }

            LOGGER.debug("Loaded " + loadedEntities + " entities for chunk (" + pos.b + ", " + pos.c + ") on world " + slimeWorld.getName());

        };

        CompoundTag upgradeDataTag = ((CraftSlimeChunk) chunk).getUpgradeData();
        Chunk nmsChunk = new Chunk(this, pos, biomeStorage, upgradeDataTag == null ? ChunkConverter.a : new ChunkConverter((NBTTagCompound) Converter.convertTag(upgradeDataTag), this)
                , blockTickList, fluidTickList, 0L, sections, loadEntities);

        // Height Maps
        EnumSet<HeightMap.Type> heightMapTypes = nmsChunk.getChunkStatus().h();
        CompoundMap heightMaps = chunk.getHeightMaps().getValue();
        EnumSet<HeightMap.Type> unsetHeightMaps = EnumSet.noneOf(HeightMap.Type.class);

        for (HeightMap.Type type : heightMapTypes) {
            String name = type.getName();

            if (heightMaps.containsKey(name)) {
                LongArrayTag heightMap = (LongArrayTag) heightMaps.get(name);
                nmsChunk.a(type, heightMap.getValue());
            } else {
                unsetHeightMaps.add(type);
            }
        }

        HeightMap.a(nmsChunk, unsetHeightMaps);
        LOGGER.debug("Loaded chunk (" + pos.b + ", " + pos.c + ") on world " + slimeWorld.getName());

        return nmsChunk;
    }

    void saveChunk(Chunk chunk) {
        SlimeChunk slimeChunk = slimeWorld.getChunk(chunk.getPos().b, chunk.getPos().c);

        if (slimeChunk instanceof NMSSlimeChunk) { // In case somehow the chunk object changes (might happen for some reason)
            ((NMSSlimeChunk) slimeChunk).setChunk(chunk);
        } else {
            slimeWorld.updateChunk(new NMSSlimeChunk(chunk));
        }
    }

}
