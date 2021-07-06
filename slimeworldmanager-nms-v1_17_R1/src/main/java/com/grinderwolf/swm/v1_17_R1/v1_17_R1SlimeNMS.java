package com.grinderwolf.swm.v1_17_R1;

import com.flowpowered.nbt.CompoundTag;
import com.grinderwolf.swm.api.world.SlimeWorld;
import com.grinderwolf.swm.api.world.properties.SlimeProperties;
import com.grinderwolf.swm.nms.CraftSlimeWorld;
import com.grinderwolf.swm.nms.SlimeNMS;
import com.mojang.serialization.Dynamic;
import com.mojang.serialization.Lifecycle;
import lombok.Getter;
import net.minecraft.SharedConstants;
import net.minecraft.core.IRegistry;
import net.minecraft.core.RegistryMaterials;
import net.minecraft.nbt.DynamicOpsNBT;
import net.minecraft.nbt.GameProfileSerializer;
import net.minecraft.nbt.NBTBase;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.resources.MinecraftKey;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.dedicated.DedicatedServer;
import net.minecraft.util.datafix.DataConverterRegistry;
import net.minecraft.util.datafix.DataFixTypes;
import net.minecraft.world.EnumDifficulty;
import net.minecraft.world.level.EnumGamemode;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.WorldSettings;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.dimension.DimensionManager;
import net.minecraft.world.level.dimension.WorldDimension;
import net.minecraft.world.level.levelgen.GeneratorSettings;
import net.minecraft.world.level.storage.Convertable;
import net.minecraft.world.level.storage.LevelVersion;
import net.minecraft.world.level.storage.WorldDataServer;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.craftbukkit.libs.org.apache.commons.io.FileUtils;
import org.bukkit.craftbukkit.v1_17_R1.CraftWorld;
import org.bukkit.craftbukkit.v1_17_R1.util.CraftMagicNumbers;
import org.bukkit.event.world.WorldInitEvent;
import org.bukkit.event.world.WorldLoadEvent;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

@Getter
public class v1_17_R1SlimeNMS implements SlimeNMS {

    private static final Logger LOGGER = LogManager.getLogger("SWM");
    private static final File UNIVERSE_DIR;
    public static Convertable CONVERTABLE;

    static {
        Path path;

        try {
            path = Files.createTempDirectory("swm-" + UUID.randomUUID().toString().substring(0, 5) + "-");
        } catch (IOException ex) {
            LOGGER.log(Level.FATAL, "Failed to create temp directory", ex);
            path = null;
            System.exit(1);
        }

        UNIVERSE_DIR = path.toFile();
        CONVERTABLE = Convertable.a(path);

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {

            try {
                FileUtils.deleteDirectory(UNIVERSE_DIR);
            } catch (IOException ex) {
                LOGGER.log(Level.FATAL, "Failed to delete temp directory", ex);
            }

        }));
    }

    private final byte worldVersion = 0x07;

    private boolean loadingDefaultWorlds = true; // If true, the addWorld method will not be skipped

    private CustomWorldServer defaultWorld;
    private CustomWorldServer defaultNetherWorld;
    private CustomWorldServer defaultEndWorld;

    public v1_17_R1SlimeNMS() {
        try {
            CraftCLSMBridge.initialize(this);
        } catch (NoClassDefFoundError ex) {
            LOGGER.error("Failed to find ClassModifier classes. Are you sure you installed it correctly?");
            System.exit(1); // No ClassModifier, no party
        }
    }

    @Override
    public void setDefaultWorlds(SlimeWorld normalWorld, SlimeWorld netherWorld, SlimeWorld endWorld) {
        if (normalWorld != null) {
            defaultWorld = createDefaultWorld(normalWorld, WorldDimension.b, net.minecraft.world.level.World.f);
        }

        if (netherWorld != null) {
            defaultNetherWorld = createDefaultWorld(netherWorld, WorldDimension.c, net.minecraft.world.level.World.g);
        }

        if (endWorld != null) {
            defaultEndWorld = createDefaultWorld(endWorld, WorldDimension.d, net.minecraft.world.level.World.h);
        }

        loadingDefaultWorlds = false;
    }

    private CustomWorldServer createDefaultWorld(SlimeWorld world, ResourceKey<WorldDimension> dimensionKey,
                                                 ResourceKey<net.minecraft.world.level.World> worldKey) {
        WorldDataServer worldDataServer = createWorldData(world.getName(), world.getExtraData());

        RegistryMaterials<WorldDimension> registryMaterials = worldDataServer.getGeneratorSettings().d();
        WorldDimension worldDimension = registryMaterials.a(dimensionKey);
        DimensionManager dimensionManager = worldDimension.b();
        ChunkGenerator chunkGenerator = worldDimension.c();

        World.Environment environment = getEnvironment(world);

        if (dimensionKey == WorldDimension.b && environment != World.Environment.NORMAL) {
            LOGGER.warn("The environment for the default world should always be 'NORMAL'.");
        }

        try {
            return new CustomWorldServer((CraftSlimeWorld) world, worldDataServer,
                    worldKey, dimensionKey, dimensionManager, chunkGenerator, environment);
        } catch (IOException ex) {
            throw new RuntimeException(ex); // TODO do something better with this?
        }
    }

    @Override
    public void generateWorld(SlimeWorld world) {
        String worldName = world.getName();

        if (Bukkit.getWorld(worldName) != null) {
            throw new IllegalArgumentException("World " + worldName + " already exists! Maybe it's an outdated SlimeWorld object?");
        }

        WorldDataServer worldDataServer = createWorldData(world.getName(), world.getExtraData());
        RegistryMaterials<WorldDimension> materials = worldDataServer.getGeneratorSettings().d();
        WorldDimension worldDimension = materials.a(WorldDimension.b);
        DimensionManager dimensionManager = worldDimension.b();
        ChunkGenerator chunkGenerator = worldDimension.c();

        ResourceKey<net.minecraft.world.level.World> worldKey = ResourceKey.a(IRegistry.Q,
                new MinecraftKey(worldName.toLowerCase(java.util.Locale.ENGLISH)));

        World.Environment environment = getEnvironment(world);

        CustomWorldServer server;

        try {
            server = new CustomWorldServer((CraftSlimeWorld) world, worldDataServer,
                    worldKey, WorldDimension.b, dimensionManager, chunkGenerator, environment);
        } catch (IOException ex) {
            throw new RuntimeException(ex); // TODO do something better with this?
        }

        LOGGER.info("Loading world " + worldName);
        long startTime = System.currentTimeMillis();

        server.setReady(true);

        MinecraftServer mcServer = MinecraftServer.getServer();
        mcServer.initWorld(server, worldDataServer, mcServer.getSaveData(), worldDataServer.getGeneratorSettings());

        mcServer.server.addWorld(server.getWorld());
        mcServer.R.put(worldKey, server);

        Bukkit.getPluginManager().callEvent(new WorldInitEvent(server.getWorld()));
        mcServer.loadSpawn(server.getChunkProvider().a.z, server);
        Bukkit.getPluginManager().callEvent(new WorldLoadEvent(server.getWorld()));

        LOGGER.info("World " + worldName + " loaded in " + (System.currentTimeMillis() - startTime) + "ms.");
    }

    private World.Environment getEnvironment(SlimeWorld world) {
        return World.Environment.valueOf(world.getPropertyMap().getString(SlimeProperties.ENVIRONMENT).toUpperCase());
    }

    private WorldDataServer createWorldData(String worldName, CompoundTag extraData) {
        WorldDataServer worldDataServer;
        NBTTagCompound extraTag = (NBTTagCompound) Converter.convertTag(extraData);
        MinecraftServer mcServer = MinecraftServer.getServer();

        if (extraTag.hasKeyOfType("LevelData", CraftMagicNumbers.NBT.TAG_COMPOUND)) {
            NBTTagCompound levelData = extraTag.getCompound("LevelData");
            int dataVersion = levelData.hasKeyOfType("DataVersion", 99) ? levelData.getInt("DataVersion") : -1;
            Dynamic<NBTBase> dynamic = mcServer.getDataFixer().update(DataFixTypes.a.a(),
                    new Dynamic<>(DynamicOpsNBT.a, levelData), dataVersion, SharedConstants.getGameVersion()
                            .getWorldVersion());
            GeneratorSettings generatorSettings = GeneratorSettings.a(mcServer.l);
            Lifecycle lifecycle = Lifecycle.stable();
            LevelVersion levelVersion = LevelVersion.a(dynamic);
            WorldSettings worldSettings = WorldSettings.a(dynamic, mcServer.datapackconfiguration);

            worldDataServer = WorldDataServer.a(dynamic, mcServer.getDataFixer(), dataVersion, null,
                    worldSettings, levelVersion, generatorSettings, lifecycle);
        } else {
            GeneratorSettings generatorSettings = ((DedicatedServer) mcServer)
                    .getDedicatedServerProperties().Y;
            if (generatorSettings == null) {
                generatorSettings = GeneratorSettings.a(mcServer.l);
            }
            EnumDifficulty difficulty = ((DedicatedServer) mcServer).getDedicatedServerProperties().n;
            EnumGamemode defaultGamemode = ((DedicatedServer) mcServer).getDedicatedServerProperties().o;
            WorldSettings worldSettings = new WorldSettings(worldName, defaultGamemode, false,
                    difficulty, false, new GameRules(), mcServer.datapackconfiguration);
            worldDataServer = new WorldDataServer(worldSettings, generatorSettings, Lifecycle.stable());
        }

        worldDataServer.checkName(worldName);
        worldDataServer.a(mcServer.getServerModName(), mcServer.getModded().isPresent());
        worldDataServer.c(true);

        return worldDataServer;
    }

    @Override
    public SlimeWorld getSlimeWorld(World world) {
        CraftWorld craftWorld = (CraftWorld) world;

        if (!(craftWorld.getHandle() instanceof CustomWorldServer)) {
            return null;
        }

        CustomWorldServer worldServer = (CustomWorldServer) craftWorld.getHandle();
        return worldServer.getSlimeWorld();
    }

    @Override
    public CompoundTag convertChunk(CompoundTag tag) {
        NBTTagCompound nmsTag = (NBTTagCompound) Converter.convertTag(tag);
        int version = nmsTag.getInt("DataVersion");

        NBTTagCompound newNmsTag = GameProfileSerializer.a(DataConverterRegistry.a(), DataFixTypes.c, nmsTag, version);

        return (CompoundTag) Converter.convertTag("", newNmsTag);
    }

}
