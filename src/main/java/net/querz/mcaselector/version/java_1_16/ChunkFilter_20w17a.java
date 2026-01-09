package net.querz.mcaselector.version.java_1_16;

import net.querz.mcaselector.io.mca.ChunkData;
import net.querz.mcaselector.util.math.Bits;
import net.querz.mcaselector.util.point.Point2i;
import net.querz.mcaselector.version.ChunkFilter;
import net.querz.mcaselector.version.Helper;
import net.querz.mcaselector.version.MCVersionImplementation;
import net.querz.mcaselector.version.java_1_13.ChunkFilter_17w47a;
import net.querz.nbt.CompoundTag;
import net.querz.nbt.ListTag;
import net.querz.nbt.StringTag;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

public class ChunkFilter_20w17a {

	@MCVersionImplementation(2529)
	public static class Blocks extends ChunkFilter_17w47a.Blocks {

		@Override
		protected int getPaletteIndex(int blockIndex, long[] blockStates) {
			int bits = blockStates.length >> 6;
			int indicesPerLong = (int) (64D / bits);
			int blockStatesIndex = blockIndex / indicesPerLong;
			int startBit = (blockIndex % indicesPerLong) * bits;
			return (int) Bits.bitRange(blockStates[blockStatesIndex], startBit, startBit + bits);
		}

		@Override
		protected void setPaletteIndex(int blockIndex, int paletteIndex, long[] blockStates) {
			int bits = blockStates.length >> 6;
			int indicesPerLong = (int) (64D / bits);
			int blockStatesIndex = blockIndex / indicesPerLong;
			int startBit = (blockIndex % indicesPerLong) * bits;
			blockStates[blockStatesIndex] = Bits.setBits(paletteIndex, blockStates[blockStatesIndex], startBit, startBit + bits);
		}

		@Override
		protected long[] adjustBlockStateBits(ListTag palette, long[] blockStates, Map<Integer, Integer> oldToNewMapping) {
			int newBits = 32 - Integer.numberOfLeadingZeros(palette.size() - 1);
			newBits = Math.max(newBits, 4);

			long[] newBlockStates;
			if (newBits == blockStates.length / 64) {
				newBlockStates = blockStates;
			} else {
				int newLength = (int) Math.ceil(4096D / (Math.floor(64D / newBits)));
				newBlockStates = new long[newLength];
			}

			if (oldToNewMapping != null) {
				for (int i = 0; i < 4096; i++) {
					setPaletteIndex(i, oldToNewMapping.get(getPaletteIndex(i, blockStates)), newBlockStates);
				}
			} else {
				for (int i = 0; i < 4096; i++) {
					setPaletteIndex(i, getPaletteIndex(i, blockStates), newBlockStates);
				}
			}

			return newBlockStates;
		}
	}

	@MCVersionImplementation(2529)
	public static class Heightmap extends ChunkFilter_20w06a.Heightmap {

		@Override
		protected long[] getHeightMap(CompoundTag root, Predicate<CompoundTag> matcher) {
			ListTag sections = Helper.getSectionsFromLevelFromRoot(root, "Sections");
			if (sections == null) {
				return new long[37];
			}

			ListTag[] palettes = new ListTag[16];
			long[][] blockStatesArray = new long[16][];
			sections.forEach(s -> {
				ListTag p = Helper.tagFromCompound(s, "Palette");
				long[] b = Helper.longArrayFromCompound(s, "BlockStates");
				int y = Helper.numberFromCompound(s, "Y", -1).intValue();
				if (y >= 0 && y <= 15 && p != null && b != null) {
					palettes[y] = p;
					blockStatesArray[y] = b;
				}
			});

			short[] heightmap = new short[256];

			// loop over x/z
			for (int cx = 0; cx < 16; cx++) {
				loop:
				for (int cz = 0; cz < 16; cz++) {
					for (int i = 15; i >= 0; i--) {
						ListTag palette = palettes[i];
						if (palette == null) {
							continue;
						}
						long[] blockStates = blockStatesArray[i];
						for (int cy = 15; cy >= 0; cy--) {
							int blockIndex = cy * 256 + cz * 16 + cx;
							if (matcher.test(getBlockAt(blockIndex, blockStates, palette))) {
								heightmap[cz * 16 + cx] = (short) (i * 16 + cy + 1);
								continue loop;
							}
						}
					}
				}
			}

			return applyHeightMap(heightmap);
		}

		@Override
		protected long[] applyHeightMap(short[] rawHeightmap) {
			long[] data = new long[37];
			int index = 0;
			for (int i = 0; i < 37; i++) {
				long l = 0L;
				for (int j = 0; j < 7 && index < 256; j++, index++) {
					l += ((long) rawHeightmap[index] << (9 * j));
				}
				data[i] = l;
			}
			return data;
		}

		@Override
		protected int getPaletteIndex(int blockIndex, long[] blockStates) {
			int bits = blockStates.length >> 6;
			int indicesPerLong = (int) (64D / bits);
			int blockStatesIndex = blockIndex / indicesPerLong;
			int startBit = (blockIndex % indicesPerLong) * bits;
			return (int) Bits.bitRange(blockStates[blockStatesIndex], startBit, startBit + bits);
		}
	}

	/**
	 * BlockExtractor implementation for Minecraft 1.16+ (data version 2529+).
	 * Uses the "Level" structure with "Sections" containing "Palette" and "BlockStates".
	 */
	@MCVersionImplementation(2529)
	public static class BlockExtractor implements ChunkFilter.BlockExtractor {

		@Override
		public List<BlockInfo> extractBlocks(ChunkData data, boolean includeAir) {
			return extractBlocks(data, Integer.MIN_VALUE, Integer.MAX_VALUE, includeAir);
		}

		@Override
		public List<BlockInfo> extractBlocks(ChunkData data, int minY, int maxY, boolean includeAir) {
			List<BlockInfo> blocks = new ArrayList<>();

			CompoundTag root = Helper.getRegion(data);
			CompoundTag level = Helper.levelFromRoot(root);
			if (level == null) {
				return blocks;
			}

			ListTag sections = Helper.tagFromCompound(level, "Sections");
			if (sections == null) {
				return blocks;
			}

			// Get chunk position for world coordinates
			Point2i chunkPos = Helper.point2iFromCompound(level, "xPos", "zPos");
			if (chunkPos == null) {
				return blocks;
			}
			int baseX = chunkPos.getX() * 16;
			int baseZ = chunkPos.getZ() * 16;

			for (CompoundTag section : sections.iterateType(CompoundTag.class)) {
				Number sectionY = Helper.numberFromCompound(section, "Y", null);
				if (sectionY == null) {
					continue;
				}

				int sectionBaseY = sectionY.intValue() * 16;

				// Check if this section overlaps with our Y range
				if (sectionBaseY > maxY || sectionBaseY + 15 < minY) {
					continue;
				}

				ListTag palette = Helper.tagFromCompound(section, "Palette");
				long[] blockStates = Helper.longArrayFromCompound(section, "BlockStates");

				if (palette == null) {
					continue;
				}

				// Handle single-palette sections (all same block, no blockStates array)
				if (blockStates == null) {
					if (palette.size() == 1) {
						CompoundTag blockState = palette.getCompound(0);
						String name = Helper.stringFromCompound(blockState, "Name");
						Map<String, String> properties = extractProperties(blockState);

						boolean isAir = isAirBlock(name);
						if (!isAir || includeAir) {
							for (int y = 0; y < 16; y++) {
								int worldY = sectionBaseY + y;
								if (worldY < minY || worldY > maxY) continue;
								for (int z = 0; z < 16; z++) {
									for (int x = 0; x < 16; x++) {
										blocks.add(new BlockInfo(baseX + x, worldY, baseZ + z, name, properties));
									}
								}
							}
						}
					}
					continue;
				}

				// Process each block in the section
				for (int y = 0; y < 16; y++) {
					int worldY = sectionBaseY + y;
					if (worldY < minY || worldY > maxY) continue;

					for (int z = 0; z < 16; z++) {
						for (int x = 0; x < 16; x++) {
							int blockIndex = y * 256 + z * 16 + x;
							int paletteIndex = getPaletteIndex(blockIndex, blockStates);

							if (paletteIndex >= palette.size()) {
								continue;
							}

							CompoundTag blockState = palette.getCompound(paletteIndex);
							String name = Helper.stringFromCompound(blockState, "Name");

							if (name == null) {
								continue;
							}

							boolean isAir = isAirBlock(name);
							if (!isAir || includeAir) {
								Map<String, String> properties = extractProperties(blockState);
								blocks.add(new BlockInfo(baseX + x, worldY, baseZ + z, name, properties));
							}
						}
					}
				}
			}

			return blocks;
		}

		protected int getPaletteIndex(int blockIndex, long[] blockStates) {
			int bits = blockStates.length >> 6;
			int indicesPerLong = (int) (64D / bits);
			int blockStatesIndex = blockIndex / indicesPerLong;
			int startBit = (blockIndex % indicesPerLong) * bits;
			return (int) Bits.bitRange(blockStates[blockStatesIndex], startBit, startBit + bits);
		}

		protected Map<String, String> extractProperties(CompoundTag blockState) {
			CompoundTag propertiesTag = Helper.tagFromCompound(blockState, "Properties");
			if (propertiesTag == null || propertiesTag.isEmpty()) {
				return Map.of();
			}

			Map<String, String> properties = new HashMap<>();
			for (var entry : propertiesTag) {
				if (entry.getValue() instanceof StringTag stringTag) {
					properties.put(entry.getKey(), stringTag.getValue());
				}
			}
			return properties;
		}

		protected boolean isAirBlock(String name) {
			return "minecraft:air".equals(name) || "minecraft:cave_air".equals(name) || "minecraft:void_air".equals(name);
		}
	}
}
