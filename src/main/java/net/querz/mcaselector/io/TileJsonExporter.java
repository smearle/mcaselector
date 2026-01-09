package net.querz.mcaselector.io;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.stream.JsonWriter;
import net.querz.mcaselector.io.mca.ChunkData;
import net.querz.mcaselector.io.mca.RegionChunk;
import net.querz.mcaselector.io.mca.RegionMCAFile;
import net.querz.mcaselector.util.point.Point2i;
import net.querz.mcaselector.version.ChunkFilter;
import net.querz.mcaselector.version.Helper;
import net.querz.mcaselector.version.VersionHandler;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.zip.GZIPOutputStream;

/**
 * Utility class for exporting Minecraft world tiles (32x32 blocks) to JSON format.
 * <p>
 * A "tile" in this context is a 32x32 block area, which corresponds to a 2x2 group
 * of Minecraft chunks (each chunk being 16x16 blocks).
 * <p>
 * Example usage:
 * <pre>{@code
 * // Export a single region file to tiles
 * File regionFile = new File("world/region/r.0.0.mca");
 * Path outputDir = Path.of("output/tiles");
 * 
 * TileJsonExporter exporter = new TileJsonExporter();
 * exporter.exportRegionToTiles(regionFile, outputDir, false); // exclude air
 * 
 * // Or with Y range filter
 * exporter.exportRegionToTiles(regionFile, outputDir, 60, 128, false);
 * }</pre>
 */
public class TileJsonExporter {

	private static final Logger LOGGER = LogManager.getLogger(TileJsonExporter.class);

	private static final Gson GSON = new GsonBuilder()
			.setPrettyPrinting()
			.disableHtmlEscaping()
			.create();

	private boolean compressOutput = false;
	private boolean prettyPrint = true;

	/**
	 * Enable or disable GZIP compression for output files.
	 * When enabled, files will have .json.gz extension.
	 */
	public TileJsonExporter setCompressOutput(boolean compress) {
		this.compressOutput = compress;
		return this;
	}

	/**
	 * Enable or disable pretty printing (indentation) in JSON output.
	 * Disabling produces smaller files.
	 */
	public TileJsonExporter setPrettyPrint(boolean pretty) {
		this.prettyPrint = pretty;
		return this;
	}

	/**
	 * Exports all 32x32-block tiles from a region file to JSON files.
	 * A region (512x512 blocks) contains 16x16 = 256 tiles.
	 *
	 * @param regionFile  The .mca region file to read
	 * @param outputDir   Directory to write JSON files to
	 * @param includeAir  Whether to include air blocks in output
	 * @return Number of tiles exported
	 */
	public int exportRegionToTiles(File regionFile, Path outputDir, boolean includeAir) throws IOException {
		return exportRegionToTiles(regionFile, outputDir, Integer.MIN_VALUE, Integer.MAX_VALUE, includeAir);
	}

	/**
	 * Exports all 32x32-block tiles from a region file to JSON files, with Y range filtering.
	 *
	 * @param regionFile  The .mca region file to read
	 * @param outputDir   Directory to write JSON files to
	 * @param minY        Minimum Y coordinate (inclusive)
	 * @param maxY        Maximum Y coordinate (inclusive)
	 * @param includeAir  Whether to include air blocks in output
	 * @return Number of tiles exported
	 */
	public int exportRegionToTiles(File regionFile, Path outputDir, int minY, int maxY, boolean includeAir) throws IOException {
		Files.createDirectories(outputDir);

		RegionMCAFile mcaFile = new RegionMCAFile(regionFile);
		mcaFile.load(false);

		Point2i regionPos = mcaFile.getLocation();
		int tilesExported = 0;

		// A region is 32x32 chunks. A tile is 2x2 chunks.
		// So a region contains 16x16 tiles.
		for (int tileX = 0; tileX < 16; tileX++) {
			for (int tileZ = 0; tileZ < 16; tileZ++) {
				TileData tile = extractTile(mcaFile, tileX, tileZ, minY, maxY, includeAir);
				
				if (tile != null && !tile.blocks.isEmpty()) {
					String filename = String.format("tile_%d_%d%s",
							tile.originX / 32, tile.originZ / 32,
							compressOutput ? ".json.gz" : ".json");
					
					Path outputFile = outputDir.resolve(filename);
					writeTileJson(tile, outputFile);
					tilesExported++;
					
					LOGGER.debug("Exported tile at ({}, {}) with {} blocks",
							tile.originX, tile.originZ, tile.blocks.size());
				}
			}
		}

		LOGGER.info("Exported {} tiles from {}", tilesExported, regionFile.getName());
		return tilesExported;
	}

	/**
	 * Exports a single 32x32-block tile from specified chunks.
	 *
	 * @param chunks      Array of 4 chunks in order: [0,0], [1,0], [0,1], [1,1] (relative to tile)
	 * @param tileOriginX World X coordinate of tile origin (must be multiple of 32)
	 * @param tileOriginZ World Z coordinate of tile origin (must be multiple of 32)
	 * @param minY        Minimum Y coordinate (inclusive)
	 * @param maxY        Maximum Y coordinate (inclusive)
	 * @param includeAir  Whether to include air blocks
	 * @return TileData containing all blocks, or null if no valid chunks
	 */
	public TileData extractTileFromChunks(RegionChunk[] chunks, int tileOriginX, int tileOriginZ,
										  int minY, int maxY, boolean includeAir) {
		List<BlockEntry> allBlocks = new ArrayList<>();

		for (int i = 0; i < 4; i++) {
			RegionChunk chunk = chunks[i];
			if (chunk == null || chunk.isEmpty()) {
				continue;
			}

			int dataVersion = Helper.getDataVersion(chunk.getData());
			ChunkFilter.BlockExtractor extractor;
			try {
				extractor = VersionHandler.getImpl(dataVersion, ChunkFilter.BlockExtractor.class);
			} catch (IllegalArgumentException e) {
				LOGGER.warn("No BlockExtractor for data version {}", dataVersion);
				continue;
			}

			ChunkData chunkData = new ChunkData(chunk, null, null, false);
			List<ChunkFilter.BlockExtractor.BlockInfo> blocks = extractor.extractBlocks(chunkData, minY, maxY, includeAir);

			for (ChunkFilter.BlockExtractor.BlockInfo block : blocks) {
				// Convert to tile-relative coordinates (0-31 range)
				int relX = block.x() - tileOriginX;
				int relZ = block.z() - tileOriginZ;

				// Sanity check - block should be within this tile
				if (relX >= 0 && relX < 32 && relZ >= 0 && relZ < 32) {
					allBlocks.add(new BlockEntry(relX, block.y(), relZ, block.name(), block.properties()));
				}
			}
		}

		if (allBlocks.isEmpty()) {
			return null;
		}

		return new TileData(tileOriginX, tileOriginZ, minY, maxY, allBlocks);
	}

	/**
	 * Extract a tile from a loaded region file.
	 *
	 * @param mcaFile     Loaded region file
	 * @param tileX       Tile X index within region (0-15)
	 * @param tileZ       Tile Z index within region (0-15)
	 * @param minY        Minimum Y coordinate
	 * @param maxY        Maximum Y coordinate
	 * @param includeAir  Whether to include air blocks
	 * @return TileData or null if tile is empty
	 */
	private TileData extractTile(RegionMCAFile mcaFile, int tileX, int tileZ, int minY, int maxY, boolean includeAir) {
		Point2i regionPos = mcaFile.getLocation();
		
		// Calculate world coordinates for this tile
		int tileOriginX = (regionPos.getX() * 512) + (tileX * 32);
		int tileOriginZ = (regionPos.getZ() * 512) + (tileZ * 32);

		// Get the 4 chunks that make up this tile (2x2 chunks)
		// Chunk indices within the region (0-31)
		int chunkX0 = tileX * 2;
		int chunkZ0 = tileZ * 2;

		RegionChunk[] chunks = new RegionChunk[4];
		chunks[0] = mcaFile.getChunk(chunkZ0 * 32 + chunkX0);           // (0, 0)
		chunks[1] = mcaFile.getChunk(chunkZ0 * 32 + chunkX0 + 1);       // (1, 0)
		chunks[2] = mcaFile.getChunk((chunkZ0 + 1) * 32 + chunkX0);     // (0, 1)
		chunks[3] = mcaFile.getChunk((chunkZ0 + 1) * 32 + chunkX0 + 1); // (1, 1)

		return extractTileFromChunks(chunks, tileOriginX, tileOriginZ, minY, maxY, includeAir);
	}

	/**
	 * Check if compression is enabled.
	 */
	public boolean isCompressOutput() {
		return compressOutput;
	}

	/**
	 * Write tile data to a JSON file.
	 */
	public void writeTileJson(TileData tile, Path outputFile) throws IOException {
		Gson gson = prettyPrint ? GSON : new GsonBuilder().disableHtmlEscaping().create();

		OutputStream os = Files.newOutputStream(outputFile);
		if (compressOutput) {
			os = new GZIPOutputStream(os);
		}

		try (Writer writer = new BufferedWriter(new OutputStreamWriter(os, StandardCharsets.UTF_8))) {
			gson.toJson(tile.toJsonObject(), writer);
		}
	}

	/**
	 * Represents a single block entry in the tile export.
	 */
	public record BlockEntry(int x, int y, int z, String id, Map<String, String> properties) {

		/**
		 * Returns full block state string (e.g., "minecraft:oak_stairs[facing=north,half=bottom]")
		 */
		public String toStateString() {
			if (properties == null || properties.isEmpty()) {
				return id;
			}
			StringBuilder sb = new StringBuilder(id).append('[');
			boolean first = true;
			for (Map.Entry<String, String> e : properties.entrySet()) {
				if (!first) sb.append(',');
				sb.append(e.getKey()).append('=').append(e.getValue());
				first = false;
			}
			return sb.append(']').toString();
		}

		/**
		 * Convert to JSON-serializable map.
		 */
		Map<String, Object> toJsonMap() {
			Map<String, Object> map = new LinkedHashMap<>();
			map.put("x", x);
			map.put("y", y);
			map.put("z", z);
			map.put("id", id);
			if (properties != null && !properties.isEmpty()) {
				map.put("properties", properties);
			}
			return map;
		}
	}

	/**
	 * Represents a complete 32x32-block tile with metadata.
	 */
	public static class TileData {
		public final int originX;
		public final int originZ;
		public final int minY;
		public final int maxY;
		public final List<BlockEntry> blocks;

		public TileData(int originX, int originZ, int minY, int maxY, List<BlockEntry> blocks) {
			this.originX = originX;
			this.originZ = originZ;
			this.minY = minY;
			this.maxY = maxY;
			this.blocks = blocks;
		}

		/**
		 * Convert to a JSON-serializable object.
		 */
		public Map<String, Object> toJsonObject() {
			Map<String, Object> root = new LinkedHashMap<>();

			// Metadata
			Map<String, Object> meta = new LinkedHashMap<>();
			meta.put("format_version", 1);
			meta.put("tile_size", 32);
			meta.put("origin_x", originX);
			meta.put("origin_z", originZ);
			if (minY != Integer.MIN_VALUE) {
				meta.put("min_y", minY);
			}
			if (maxY != Integer.MAX_VALUE) {
				meta.put("max_y", maxY);
			}
			meta.put("block_count", blocks.size());
			root.put("metadata", meta);

			// Blocks array
			List<Map<String, Object>> blockList = new ArrayList<>(blocks.size());
			for (BlockEntry block : blocks) {
				blockList.add(block.toJsonMap());
			}
			root.put("blocks", blockList);

			return root;
		}

		/**
		 * Get the number of blocks in this tile.
		 */
		public int getBlockCount() {
			return blocks.size();
		}
	}
}
