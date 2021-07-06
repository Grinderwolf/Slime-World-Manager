package com.grinderwolf.swm.v1_17_R1;

import com.flowpowered.nbt.*;
import com.flowpowered.nbt.Tag;
import com.grinderwolf.swm.api.utils.NibbleArray;
import net.minecraft.nbt.*;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.ArrayList;
import java.util.List;

public class Converter {

    private static final Logger LOGGER = LogManager.getLogger("SWM Converter");

    static net.minecraft.world.level.chunk.NibbleArray convertArray(NibbleArray array) {
        return new net.minecraft.world.level.chunk.NibbleArray(array.getBacking());
    }

    static NibbleArray convertArray(net.minecraft.world.level.chunk.NibbleArray array) {
        if (array == null) {
            return null;
        }

        return new NibbleArray(array.asBytes());
    }

    static NBTBase convertTag(Tag tag) {
        try {
            switch (tag.getType()) {
                case TAG_BYTE:
                    return NBTTagByte.a(((ByteTag) tag).getValue());
                case TAG_SHORT:
                    return NBTTagShort.a(((ShortTag) tag).getValue());
                case TAG_INT:
                    return NBTTagInt.a(((IntTag) tag).getValue());
                case TAG_LONG:
                    return NBTTagLong.a(((LongTag) tag).getValue());
                case TAG_FLOAT:
                    return NBTTagFloat.a(((FloatTag) tag).getValue());
                case TAG_DOUBLE:
                    return NBTTagDouble.a(((DoubleTag) tag).getValue());
                case TAG_BYTE_ARRAY:
                    return new NBTTagByteArray(((ByteArrayTag) tag).getValue());
                case TAG_STRING:
                    return NBTTagString.a(((StringTag) tag).getValue());
                case TAG_LIST:
                    NBTTagList list = new NBTTagList();
                    ((ListTag<?>) tag).getValue().stream().map(Converter::convertTag).forEach(list::add);

                    return list;
                case TAG_COMPOUND:
                    NBTTagCompound compound = new NBTTagCompound();

                    ((CompoundTag) tag).getValue().forEach((key, value) -> compound.set(key, convertTag(value)));

                    return compound;
                case TAG_INT_ARRAY:
                    return new NBTTagIntArray(((IntArrayTag) tag).getValue());
                case TAG_LONG_ARRAY:
                    return new NBTTagLongArray(((LongArrayTag) tag).getValue());
                default:
                    throw new IllegalArgumentException("Invalid tag type " + tag.getType().name());
            }
        } catch (Exception ex) {
            LOGGER.error("Failed to convert NBT object:");
            LOGGER.error(tag.toString());

            throw ex;
        }
    }

    static Tag convertTag(String name, NBTBase base) {
        switch (base.getTypeId()) {
            case 1:
                return new ByteTag(name, ((NBTTagByte) base).asByte());
            case 2:
                return new ShortTag(name, ((NBTTagShort) base).asShort());
            case 3:
                return new IntTag(name, ((NBTTagInt) base).asInt());
            case 4:
                return new LongTag(name, ((NBTTagLong) base).asLong());
            case 5:
                return new FloatTag(name, ((NBTTagFloat) base).asFloat());
            case 6:
                return new DoubleTag(name, ((NBTTagDouble) base).asDouble());
            case 7:
                return new ByteArrayTag(name, ((NBTTagByteArray) base).getBytes());
            case 8:
                return new StringTag(name, ((NBTTagString) base).asString());
            case 9:
                List<Tag> list = new ArrayList<>();
                NBTTagList originalList = ((NBTTagList) base);

                for (NBTBase entry : originalList) {
                    list.add(convertTag("", entry));
                }

                return new ListTag<>(name, TagType.getById(originalList.e()), list);
            case 10:
                NBTTagCompound originalCompound = ((NBTTagCompound) base);
                CompoundTag compound = new CompoundTag(name, new CompoundMap());

                for (String key : originalCompound.getKeys()) {
                    compound.getValue().put(key, convertTag(key, originalCompound.get(key)));
                }

                return compound;
            case 11:
                return new IntArrayTag("", ((NBTTagIntArray) base).getInts());
            case 12:
                return new LongArrayTag("", ((NBTTagLongArray) base).getLongs());
            default:
                throw new IllegalArgumentException("Invalid tag type " + base.getTypeId());
        }
    }

}