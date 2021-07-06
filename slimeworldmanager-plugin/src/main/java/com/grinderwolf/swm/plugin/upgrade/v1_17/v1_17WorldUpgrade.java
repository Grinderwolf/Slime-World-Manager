package com.grinderwolf.swm.plugin.upgrade.v1_17;

import com.flowpowered.nbt.*;
import com.grinderwolf.swm.api.world.SlimeChunk;
import com.grinderwolf.swm.api.world.SlimeChunkSection;
import com.grinderwolf.swm.nms.CraftSlimeWorld;
import com.grinderwolf.swm.plugin.upgrade.Upgrade;

import java.util.*;

public class v1_17WorldUpgrade implements Upgrade {

    private static Map<String, String> oldToNewMap = new HashMap<>();
    private static Map<String, String> newToOldMap = new HashMap<>();

    static {
        rename("minecraft:grass_path", "dirt_path");
    }

    private static void rename(String oldName, String newName) {
        oldToNewMap.put(oldName, newName);
        newToOldMap.put(newName, oldName);
    }

    @Override
    public void upgrade(CraftSlimeWorld world) {
        for (SlimeChunk chunk : new ArrayList<>(world.getChunks().values())) {
            // Update renamed blocks
            for (int sectionIndex = 0; sectionIndex < chunk.getSections().length; sectionIndex++) {
                SlimeChunkSection section = chunk.getSections()[sectionIndex];

                if (section != null) {
                    List<CompoundTag> palette = section.getPalette().getValue();

                    for (int paletteIndex = 0; paletteIndex < palette.size(); paletteIndex++) {
                        CompoundTag blockTag = palette.get(paletteIndex);
                        String name = blockTag.getStringValue("Name").get();

                        String newName = oldToNewMap.get(name);

                        if (newName != null) {
                            blockTag.getValue().put("Name", new StringTag("Name", newName));
                        }
                    }
                }
            }
        }
    }

    @Override
    public void downgrade(CraftSlimeWorld world) {
        for (SlimeChunk chunk : new ArrayList<>(world.getChunks().values())) {
            // Update renamed blocks
            for (int sectionIndex = 0; sectionIndex < chunk.getSections().length; sectionIndex++) {
                SlimeChunkSection section = chunk.getSections()[sectionIndex];

                if (section != null) {
                    List<CompoundTag> palette = section.getPalette().getValue();

                    for (int paletteIndex = 0; paletteIndex < palette.size(); paletteIndex++) {
                        CompoundTag blockTag = palette.get(paletteIndex);
                        String name = blockTag.getStringValue("Name").get();

                        String newName = newToOldMap.get(name);

                        if (newName != null) {
                            blockTag.getValue().put("Name", new StringTag("Name", newName));
                        }
                    }
                }
            }
        }
    }
}
