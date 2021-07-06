package com.grinderwolf.swm.plugin;

import com.flowpowered.nbt.CompoundMap;
import com.flowpowered.nbt.CompoundTag;
import com.grinderwolf.swm.api.SlimePlugin;
import com.grinderwolf.swm.api.exceptions.*;
import com.grinderwolf.swm.api.loaders.SlimeLoader;
import com.grinderwolf.swm.api.world.SlimeWorld;
import com.grinderwolf.swm.api.world.properties.SlimeProperties;
import com.grinderwolf.swm.api.world.properties.SlimePropertyMap;
import com.grinderwolf.swm.nms.CraftSlimeWorld;
import com.grinderwolf.swm.nms.SlimeNMS;
//import com.grinderwolf.swm.nms.v1_10_R1.v1_10_R1SlimeNMS;
import com.grinderwolf.swm.nms.v1_11_R1.v1_11_R1SlimeNMS;
import com.grinderwolf.swm.nms.v1_12_R1.v1_12_R1SlimeNMS;
import com.grinderwolf.swm.nms.v1_13_R1.v1_13_R1SlimeNMS;
import com.grinderwolf.swm.nms.v1_13_R2.v1_13_R2SlimeNMS;
import com.grinderwolf.swm.nms.v1_14_R1.v1_14_R1SlimeNMS;
import com.grinderwolf.swm.nms.v1_15_R1.v1_15_R1SlimeNMS;
import com.grinderwolf.swm.nms.v1_8_R3.v1_8_R3SlimeNMS;
import com.grinderwolf.swm.nms.v1_9_R1.v1_9_R1SlimeNMS;
import com.grinderwolf.swm.nms.v1_9_R2.v1_9_R2SlimeNMS;
import com.grinderwolf.swm.plugin.commands.CommandManager;
import com.grinderwolf.swm.plugin.config.ConfigManager;
import com.grinderwolf.swm.plugin.config.WorldData;
import com.grinderwolf.swm.plugin.config.WorldsConfig;
import com.grinderwolf.swm.plugin.loaders.LoaderUtils;
import com.grinderwolf.swm.plugin.log.Logging;
import com.grinderwolf.swm.plugin.update.Updater;
import com.grinderwolf.swm.plugin.upgrade.WorldUpgrader;
import com.grinderwolf.swm.plugin.world.WorldUnlocker;
import com.grinderwolf.swm.plugin.world.importer.WorldImporter;
import com.grinderwolf.swm.v1_16_R2.v1_16_R2SlimeNMS;
import com.grinderwolf.swm.v1_16_R3.v1_16_R3SlimeNMS;
import com.grinderwolf.swm.v1_17_R1.v1_17_R1SlimeNMS;
import lombok.Getter;
import ninja.leaping.configurate.objectmapping.ObjectMappingException;
import org.bstats.bukkit.Metrics;
import org.bukkit.Bukkit;
import org.bukkit.Difficulty;
import org.bukkit.World;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class SWMPlugin extends JavaPlugin implements SlimePlugin {

    @Getter
    private static SWMPlugin instance;
    @Getter
    private SlimeNMS nms;

    private final List<SlimeWorld> worlds = new ArrayList<>();
    private final ExecutorService worldGeneratorService = Executors.newFixedThreadPool(1);
    private boolean asyncWorldGen;

    @Override
    public void onLoad() {
        instance = this;

        try {
            ConfigManager.initialize();
        } catch (NullPointerException | IOException | ObjectMappingException ex) {
            Logging.error("Failed to load config files:");
            ex.printStackTrace();
            return;
        }

        LoaderUtils.registerLoaders();

        try {
            nms = getNMSBridge();
        } catch (InvalidVersionException ex) {
            Logging.error(ex.getMessage());
            return;
        }

        List<String> erroredWorlds = loadWorlds();

        // Default world override
        try {
            Properties props = new Properties();

            props.load(new FileInputStream("server.properties"));
            String defaultWorldName = props.getProperty("level-name");

            if (erroredWorlds.contains(defaultWorldName)) {
                Logging.error("Shutting down server, as the default world could not be loaded.");
                System.exit(1);
            } else if (getServer().getAllowNether() && erroredWorlds.contains(defaultWorldName + "_nether")) {
                Logging.error("Shutting down server, as the default nether world could not be loaded.");
                System.exit(1);
            } else if (getServer().getAllowEnd() && erroredWorlds.contains(defaultWorldName + "_the_end")) {
                Logging.error("Shutting down server, as the default end world could not be loaded.");
                System.exit(1);
            }

            SlimeWorld defaultWorld = worlds.stream().filter(world -> world.getName().equals(defaultWorldName)).findFirst().orElse(null);
            SlimeWorld netherWorld = (getServer().getAllowNether() ? worlds.stream().filter(world -> world.getName().equals(defaultWorldName + "_nether")).findFirst().orElse(null) : null);
            SlimeWorld endWorld = (getServer().getAllowEnd() ? worlds.stream().filter(world -> world.getName().equals(defaultWorldName + "_the_end")).findFirst().orElse(null) : null);

            nms.setDefaultWorlds(defaultWorld, netherWorld, endWorld);
        } catch (IOException ex) {
            Logging.error("Failed to retrieve default world name:");
            ex.printStackTrace();
        }
    }

    @Override
    public void onEnable() {
        if (nms == null) {
            this.setEnabled(false);
            return;
        }

        new Metrics(this);

        final CommandManager commandManager = new CommandManager();
        final PluginCommand swmCommand = getCommand("swm");
        swmCommand.setExecutor(commandManager);

        try {
            swmCommand.setTabCompleter(commandManager);
        } catch (Throwable throwable) {
            // For some versions that does not have TabComplete?
        }

        getServer().getPluginManager().registerEvents(new WorldUnlocker(), this);

        if (ConfigManager.getMainConfig().getUpdaterOptions().isEnabled()) {
            getServer().getPluginManager().registerEvents(new Updater(), this);
        }

        if (ConfigManager.getMainConfig().isAsyncWorldGenerate()) {
            try {
                nms.addWorldToServerList(null);
            } catch (IllegalArgumentException ignored) { // This exception is thrown as null is not a WorldServer object
                Logging.warning("You've enabled async world generation. Although it's quite faster, this feature is EXPERIMENTAL. Use at your own risk.");
                asyncWorldGen = true;
            } catch (UnsupportedOperationException ex) {
                Logging.error("Async world generation does not support this spigot version.");
                ConfigManager.getMainConfig().setAsyncWorldGenerate(false);
                ConfigManager.getMainConfig().save();
            }

        }

        for (SlimeWorld world : worlds) {
            if (Bukkit.getWorld(world.getName()) == null) {
                generateWorld(world);
            }
        }

        worlds.clear();
    }

    private SlimeNMS getNMSBridge() throws InvalidVersionException {
        String version = Bukkit.getServer().getClass().getPackage().getName();
        String nmsVersion = version.substring(version.lastIndexOf('.') + 1);

        switch (nmsVersion) {
            case "v1_8_R3":
                return new v1_8_R3SlimeNMS();
            case "v1_9_R1":
                return new v1_9_R1SlimeNMS();
            case "v1_9_R2":
                return new v1_9_R2SlimeNMS();
            //case "v1_10_R1":
             //   return new v1_10_R1SlimeNMS();
            case "v1_11_R1":
                return new v1_11_R1SlimeNMS();
            case "v1_12_R1":
                return new v1_12_R1SlimeNMS();
            case "v1_13_R1":
                return new v1_13_R1SlimeNMS();
            case "v1_13_R2":
                return new v1_13_R2SlimeNMS();
            case "v1_14_R1":
                return new v1_14_R1SlimeNMS();
            case "v1_15_R1":
                return new v1_15_R1SlimeNMS();
            case "v1_16_R2":
                return new v1_16_R2SlimeNMS();
            case "v1_16_R3":
                return new v1_16_R3SlimeNMS();
            case "v1_17_R1":
                return new v1_17_R1SlimeNMS();
            default:
                throw new InvalidVersionException(nmsVersion);
        }
    }

    private List<String> loadWorlds() {
        List<String> erroredWorlds = new ArrayList<>();
        WorldsConfig config = ConfigManager.getWorldConfig();

        for (Map.Entry<String, WorldData> entry : config.getWorlds().entrySet()) {
            String worldName = entry.getKey();
            WorldData worldData = entry.getValue();

            if (worldData.isLoadOnStartup()) {
                try {
                    SlimeLoader loader = getLoader(worldData.getDataSource());

                    if (loader == null) {
                        throw new IllegalArgumentException("invalid data source " + worldData.getDataSource() + "");
                    }

                    SlimePropertyMap propertyMap = worldData.toPropertyMap();
                    SlimeWorld world = loadWorld(loader, worldName, worldData.isReadOnly(), propertyMap);

                    worlds.add(world);
                } catch (IllegalArgumentException | UnknownWorldException | NewerFormatException | WorldInUseException | CorruptedWorldException | IOException ex) {
                    String message;

                    if (ex instanceof IllegalArgumentException) {
                        message = ex.getMessage();
                    } else if (ex instanceof UnknownWorldException) {
                        message = "world does not exist, are you sure you've set the correct data source?";
                    } else if (ex instanceof NewerFormatException) {
                        message = "world is serialized in a newer Slime Format version (" + ex.getMessage() + ") that SWM does not understand.";
                    } else if (ex instanceof WorldInUseException) {
                        message = "world is in use! If you think this is a mistake, please wait some time and try again.";
                    } else if (ex instanceof CorruptedWorldException) {
                        message = "world seems to be corrupted.";
                    } else {
                        message = "";

                        ex.printStackTrace();
                    }

                    Logging.error("Failed to load world " + worldName + (message.isEmpty() ? "." : ": " + message));
                    erroredWorlds.add(worldName);
                }
            }
        }

        config.save();
        return erroredWorlds;
    }

    @Override
    public SlimeWorld loadWorld(SlimeLoader loader, String worldName, SlimeWorld.SlimeProperties properties) throws UnknownWorldException,
            IOException, CorruptedWorldException, NewerFormatException, WorldInUseException {
        Objects.requireNonNull(properties, "Properties cannot be null");

        return loadWorld(loader, worldName, properties.isReadOnly(), propertiesToMap(properties));
    }

    @Override
    public SlimeWorld loadWorld(SlimeLoader loader, String worldName, boolean readOnly, SlimePropertyMap propertyMap) throws UnknownWorldException, IOException,
            CorruptedWorldException, NewerFormatException, WorldInUseException {
        Objects.requireNonNull(loader, "Loader cannot be null");
        Objects.requireNonNull(worldName, "World name cannot be null");
        Objects.requireNonNull(propertyMap, "Properties cannot be null");

        long start = System.currentTimeMillis();

        Logging.info("Loading world " + worldName + ".");
        byte[] serializedWorld = loader.loadWorld(worldName, readOnly);
        CraftSlimeWorld world;

        try {
            world = LoaderUtils.deserializeWorld(loader, worldName, serializedWorld, propertyMap, readOnly);

            if (world.getVersion() > nms.getWorldVersion()) {
                WorldUpgrader.downgradeWorld(world);
            } else if (world.getVersion() < nms.getWorldVersion()) {
                WorldUpgrader.upgradeWorld(world);
            }
        } catch (Exception ex) {
            if (!readOnly) { // Unlock the world as we're not using it
                loader.unlockWorld(worldName);
            }

            throw ex;
        }

        Logging.info("World " + worldName + " loaded in " + (System.currentTimeMillis() - start) + "ms.");

        return world;
    }

    @Override
    public SlimeWorld createEmptyWorld(SlimeLoader loader, String worldName, SlimeWorld.SlimeProperties properties) throws WorldAlreadyExistsException, IOException {
        Objects.requireNonNull(properties, "Properties cannot be null");

        return createEmptyWorld(loader, worldName, properties.isReadOnly(), propertiesToMap(properties));
    }

    @Override
    public SlimeWorld createEmptyWorld(SlimeLoader loader, String worldName, boolean readOnly, SlimePropertyMap propertyMap) throws WorldAlreadyExistsException, IOException {
        Objects.requireNonNull(loader, "Loader cannot be null");
        Objects.requireNonNull(worldName, "World name cannot be null");
        Objects.requireNonNull(propertyMap, "Properties cannot be null");

        if (loader.worldExists(worldName)) {
            throw new WorldAlreadyExistsException(worldName);
        }

        Logging.info("Creating empty world " + worldName + ".");
        long start = System.currentTimeMillis();
        CraftSlimeWorld world = new CraftSlimeWorld(loader, worldName, new HashMap<>(), new CompoundTag("",
                new CompoundMap()), new ArrayList<>(), nms.getWorldVersion(), propertyMap, readOnly, !readOnly);
        loader.saveWorld(worldName, world.serialize(), !readOnly);

        Logging.info("World " + worldName + " created in " + (System.currentTimeMillis() - start) + "ms.");

        return world;
    }

    private SlimePropertyMap propertiesToMap(SlimeWorld.SlimeProperties properties) {
        SlimePropertyMap propertyMap = new SlimePropertyMap();

        propertyMap.setInt(SlimeProperties.SPAWN_X, (int) properties.getSpawnX());
        propertyMap.setInt(SlimeProperties.SPAWN_Y, (int) properties.getSpawnY());
        propertyMap.setInt(SlimeProperties.SPAWN_Z, (int) properties.getSpawnZ());
        propertyMap.setString(SlimeProperties.DIFFICULTY, Difficulty.getByValue(properties.getDifficulty()).name());
        propertyMap.setBoolean(SlimeProperties.ALLOW_MONSTERS, properties.allowMonsters());
        propertyMap.setBoolean(SlimeProperties.ALLOW_ANIMALS, properties.allowAnimals());
        propertyMap.setBoolean(SlimeProperties.PVP, properties.isPvp());
        propertyMap.setString(SlimeProperties.ENVIRONMENT, properties.getEnvironment());

        return propertyMap;
    }

    @Override
    public void generateWorld(SlimeWorld world) {
        Objects.requireNonNull(world, "SlimeWorld cannot be null");

        if (!world.isReadOnly() && !world.isLocked()) {
            throw new IllegalArgumentException("This world cannot be loaded, as it has not been locked.");
        }

        if (asyncWorldGen) {
            worldGeneratorService.submit(() -> {

                Object nmsWorld = nms.createNMSWorld(world);
                Bukkit.getScheduler().runTask(this, () -> nms.addWorldToServerList(nmsWorld));

            });
        } else {
            nms.generateWorld(world);
        }
    }

    @Override
    public void migrateWorld(String worldName, SlimeLoader currentLoader, SlimeLoader newLoader) throws IOException,
            WorldInUseException, WorldAlreadyExistsException, UnknownWorldException {
        Objects.requireNonNull(worldName, "World name cannot be null");
        Objects.requireNonNull(currentLoader, "Current loader cannot be null");
        Objects.requireNonNull(newLoader, "New loader cannot be null");

        if (newLoader.worldExists(worldName)) {
            throw new WorldAlreadyExistsException(worldName);
        }

        World bukkitWorld = Bukkit.getWorld(worldName);

        boolean leaveLock = false;

        if (bukkitWorld != null) {
            // Make sure the loaded world really is a SlimeWorld and not a normal Bukkit world
            CraftSlimeWorld slimeWorld = (CraftSlimeWorld) SWMPlugin.getInstance().getNms().getSlimeWorld(bukkitWorld);

            if (slimeWorld != null && currentLoader.equals(slimeWorld.getLoader())) {
                slimeWorld.setLoader(newLoader);

                if (!slimeWorld.isReadOnly()) { // We have to manually unlock the world so no WorldInUseException is thrown
                    currentLoader.unlockWorld(worldName);
                    leaveLock = true;
                }
            }
        }

        byte[] serializedWorld = currentLoader.loadWorld(worldName, false);

        newLoader.saveWorld(worldName, serializedWorld, leaveLock);
        currentLoader.deleteWorld(worldName);
    }

    @Override
    public SlimeLoader getLoader(String dataSource) {
        Objects.requireNonNull(dataSource, "Data source cannot be null");

        return LoaderUtils.getLoader(dataSource);
    }

    @Override
    public void registerLoader(String dataSource, SlimeLoader loader) {
        Objects.requireNonNull(dataSource, "Data source cannot be null");
        Objects.requireNonNull(loader, "Loader cannot be null");

        LoaderUtils.registerLoader(dataSource, loader);
    }

    @Override
    public void importWorld(File worldDir, String worldName, SlimeLoader loader) throws WorldAlreadyExistsException,
            InvalidWorldException, WorldLoadedException, WorldTooBigException, IOException {
        Objects.requireNonNull(worldDir, "World directory cannot be null");
        Objects.requireNonNull(worldName, "World name cannot be null");
        Objects.requireNonNull(loader, "Loader cannot be null");

        if (loader.worldExists(worldName)) {
            throw new WorldAlreadyExistsException(worldName);
        }

        World bukkitWorld = Bukkit.getWorld(worldDir.getName());

        if (bukkitWorld != null && nms.getSlimeWorld(bukkitWorld) == null) {
            throw new WorldLoadedException(worldDir.getName());
        }

        CraftSlimeWorld world = WorldImporter.readFromDirectory(worldDir);

        byte[] serializedWorld;

        try {
            serializedWorld = world.serialize();
        } catch (IndexOutOfBoundsException ex) {
            throw new WorldTooBigException(worldDir.getName());
        }

        loader.saveWorld(worldName, serializedWorld, false);
    }
}
