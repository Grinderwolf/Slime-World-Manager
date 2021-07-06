package com.grinderwolf.swm.v1_17_R1;

import com.flowpowered.nbt.CompoundMap;
import com.flowpowered.nbt.CompoundTag;
import com.flowpowered.nbt.ListTag;
import com.flowpowered.nbt.LongArrayTag;
import com.grinderwolf.swm.api.utils.NibbleArray;
import com.grinderwolf.swm.api.world.SlimeChunk;
import com.grinderwolf.swm.api.world.SlimeChunkSection;
import com.grinderwolf.swm.nms.CraftSlimeChunkSection;
import lombok.AllArgsConstructor;
import lombok.Data;
import net.minecraft.core.SectionPosition;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ChunkCoordIntPair;
import net.minecraft.world.level.EnumSkyBlock;
import net.minecraft.world.level.block.entity.TileEntity;
import net.minecraft.world.level.chunk.Chunk;
import net.minecraft.world.level.chunk.ChunkSection;
import net.minecraft.world.level.chunk.DataPaletteBlock;
import net.minecraft.world.level.entity.PersistentEntitySectionManager;
import net.minecraft.world.level.levelgen.HeightMap;
import net.minecraft.world.level.lighting.LightEngine;
import net.minecraft.world.level.storage.WorldDataServer;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

@Data
@AllArgsConstructor
public class NMSSlimeChunk implements SlimeChunk {

    private Chunk chunk;

    @Override
    public String getWorldName() {
        return ((WorldDataServer) chunk.getWorld().x).getName();
    }

    @Override
    public int getX() {
        return chunk.getPos().b;
    }

    @Override
    public int getZ() {
        return chunk.getPos().c;
    }

    @Override
    public SlimeChunkSection[] getSections() {
        SlimeChunkSection[] sections = new SlimeChunkSection[16];
        LightEngine lightEngine = chunk.getWorld().getChunkProvider().getLightEngine();

        for (int sectionId = 0; sectionId < chunk.getSections().length; sectionId++) {
            ChunkSection section = chunk.getSections()[sectionId];

            if (section != null) {
                section.recalcBlockCounts();

                if (!section.c()) { // If the section is empty, just ignore it to save space
                    // Block Light Nibble Array
                    NibbleArray blockLightArray = Converter.convertArray(lightEngine.a(EnumSkyBlock.b).a(SectionPosition.a(chunk.getPos(), sectionId)));

                    // Sky light Nibble Array
                    NibbleArray skyLightArray = Converter.convertArray(lightEngine.a(EnumSkyBlock.a).a(SectionPosition.a(chunk.getPos(), sectionId)));

                    // Block Data
                    DataPaletteBlock dataPaletteBlock = section.getBlocks();
                    NBTTagCompound blocksCompound = new NBTTagCompound();
                    dataPaletteBlock.a(blocksCompound, "Palette", "BlockStates");
                    NBTTagList paletteList = blocksCompound.getList("Palette", 10);
                    ListTag<CompoundTag> palette = (ListTag<CompoundTag>) Converter.convertTag("", paletteList);
                    long[] blockStates = blocksCompound.getLongArray("BlockStates");

                    sections[sectionId] = new CraftSlimeChunkSection(null, null, palette, blockStates, blockLightArray, skyLightArray);
                }
            }
        }

        return sections;
    }

    @Override
    public CompoundTag getHeightMaps() {
        // HeightMap
        CompoundMap heightMaps = new CompoundMap();

        for (Map.Entry<HeightMap.Type, HeightMap> entry : chunk.j.entrySet()) {
            HeightMap.Type type = entry.getKey();
            HeightMap map = entry.getValue();

            heightMaps.put(type.getName(), new LongArrayTag(type.getName(), map.a()));
        }

       return new CompoundTag("", heightMaps);
    }

    @Override
    public int[] getBiomes() {
        return chunk.getBiomeIndex().a();
    }

    @Override
    public List<CompoundTag> getTileEntities() {
        List<CompoundTag> tileEntities = new ArrayList<>();

        for (TileEntity entity : chunk.getTileEntities().values()) {
            NBTTagCompound entityNbt = new NBTTagCompound();
            entity.save(entityNbt);
            tileEntities.add((CompoundTag) Converter.convertTag("", entityNbt));
        }

        return tileEntities;
    }

    @Override
    public List<CompoundTag> getEntities() {
        List<CompoundTag> entities = new ArrayList<>();

        PersistentEntitySectionManager<Entity> entityManager = chunk.i.G;
        Iterator<Entity> entitySlices = entityManager.d().a().iterator();

        while(entitySlices.hasNext()) {
            Entity entity = entitySlices.next();

            ChunkCoordIntPair chunkPos = chunk.getPos();
            ChunkCoordIntPair entityPos = entity.cU();

            if(chunkPos.b == entityPos.b && chunkPos.c == entityPos.c) {
                NBTTagCompound entityNbt = new NBTTagCompound();
                if(entity.d(entityNbt)) {
                    chunk.b(true);
                    entities.add((CompoundTag) Converter.convertTag("", entityNbt));
                }
            }
        }
        return entities;
    }
}
