package com.grinderwolf.swm.plugin.upgrade;

import com.grinderwolf.swm.nms.CraftSlimeWorld;
import com.grinderwolf.swm.plugin.SWMPlugin;
import com.grinderwolf.swm.plugin.log.Logging;
import com.grinderwolf.swm.plugin.upgrade.v1_9.v1_9WorldUpgrade;
import com.grinderwolf.swm.plugin.upgrade.v1_11.v1_11WorldUpgrade;
import com.grinderwolf.swm.plugin.upgrade.v1_13.v1_13WorldUpgrade;
import com.grinderwolf.swm.plugin.upgrade.v1_14.v1_14WorldUpgrade;
import com.grinderwolf.swm.plugin.upgrade.v1_16.v1_16WorldUpgrade;
import com.grinderwolf.swm.plugin.upgrade.v1_17.v1_17WorldUpgrade;

import java.util.HashMap;
import java.util.Map;

public class WorldUpgrader {

    private static final Map<Byte, Upgrade> upgrades = new HashMap<>();

    static {
        upgrades.put((byte) 0x02, new v1_9WorldUpgrade());
        upgrades.put((byte) 0x03, new v1_11WorldUpgrade());
        upgrades.put((byte) 0x04, new v1_13WorldUpgrade());
        upgrades.put((byte) 0x05, new v1_14WorldUpgrade());
        upgrades.put((byte) 0x06, new v1_16WorldUpgrade());
        upgrades.put((byte) 0x07, new v1_17WorldUpgrade());
    }

    public static void upgradeWorld(CraftSlimeWorld world) {
        byte serverVersion = SWMPlugin.getInstance().getNms().getWorldVersion();

        for (byte ver = (byte) (world.getVersion() + 1); ver <= serverVersion; ver++) {
            Upgrade upgrade = upgrades.get(ver);

            if (upgrade == null) {
                Logging.warning("Missing world upgrader for version " + ver + ". World will not be upgraded.");
                continue;
            }

            upgrade.upgrade(world);
        }

        world.setVersion(serverVersion);
    }

    public static void downgradeWorld(CraftSlimeWorld world) {
        byte serverVersion = SWMPlugin.getInstance().getNms().getWorldVersion();

        for (byte ver = world.getVersion(); ver > serverVersion; ver--) {
            Upgrade upgrade = upgrades.get(ver);

            if (upgrade == null) {
                Logging.warning("Missing world upgrader for version " + ver + ". World will not be downgraded.");
                continue;
            }

            upgrade.downgrade(world);
        }

        world.setVersion(serverVersion);
    }
}
