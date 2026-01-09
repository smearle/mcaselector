package net.querz.mcaselector.io.job;

import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import net.querz.mcaselector.io.*;
import net.querz.mcaselector.io.mca.Region;
import net.querz.mcaselector.io.mca.RegionChunk;
import net.querz.mcaselector.io.mca.RegionMCAFile;
import net.querz.mcaselector.selection.ChunkSet;
import net.querz.mcaselector.selection.Selection;
import net.querz.mcaselector.util.point.Point2i;
import net.querz.mcaselector.util.progress.Progress;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.File;
import java.util.function.Consumer;

/**
 * Job handler for exporting selected chunks to 32x32-block JSON tile files.
 */
public final class TileJsonExporter {

	private static final Logger LOGGER = LogManager.getLogger(TileJsonExporter.class);

	private TileJsonExporter() {}

	/**
	 * Export selection as JSON tile files.
	 *
	 * @param selection      The chunks to export
	 * @param destination    Output directory
	 * @param minY           Minimum Y coordinate
	 * @param maxY           Maximum Y coordinate
	 * @param includeAir     Whether to include air blocks
	 * @param compress       Whether to GZIP compress output
	 * @param progressChannel Progress reporting
	 */
	public static void exportTilesJson(Selection selection, File destination, int minY, int maxY,
									   boolean includeAir, boolean compress, Progress progressChannel) {
		if (selection.isEmpty()) {
			progressChannel.done("no selection");
			return;
		}

		JobHandler.clearQueues();

		Selection trueSelection = selection.getTrueSelection(null);

		progressChannel.setMax(trueSelection.size());
		progressChannel.updateProgress(FileHelper.createMCAFileName(trueSelection.one()), 0);

		net.querz.mcaselector.io.TileJsonExporter exporter = new net.querz.mcaselector.io.TileJsonExporter()
				.setCompressOutput(compress)
				.setPrettyPrint(!compress); // Compact if compressing

		Consumer<Throwable> errorHandler = t -> {
			LOGGER.error("Error exporting tile", t);
			progressChannel.incrementProgress("error");
		};

		for (Long2ObjectMap.Entry<ChunkSet> entry : trueSelection) {
			TileJsonExportJob job = new TileJsonExportJob(
					FileHelper.createRegionDirectories(new Point2i(entry.getLongKey())),
					entry.getValue(),
					destination,
					minY, maxY,
					includeAir,
					exporter,
					progressChannel);
			job.errorHandler = errorHandler;
			JobHandler.addJob(job);
		}
	}

	private static class TileJsonExportJob extends ProcessDataJob {

		private final Progress progressChannel;
		private final ChunkSet selectedChunks;
		private final File destination;
		private final int minY;
		private final int maxY;
		private final boolean includeAir;
		private final net.querz.mcaselector.io.TileJsonExporter exporter;

		private TileJsonExportJob(RegionDirectories dirs, ChunkSet selectedChunks, File destination,
								  int minY, int maxY, boolean includeAir,
								  net.querz.mcaselector.io.TileJsonExporter exporter,
								  Progress progressChannel) {
			super(dirs, PRIORITY_LOW);
			this.selectedChunks = selectedChunks;
			this.destination = destination;
			this.minY = minY;
			this.maxY = maxY;
			this.includeAir = includeAir;
			this.exporter = exporter;
			this.progressChannel = progressChannel;
		}

		@Override
		public boolean execute() {
			try {
				// Load the region
				Region region = Region.loadRegion(getRegionDirectories());
				RegionMCAFile regionMca = region.getRegion();
				if (regionMca == null) {
					LOGGER.debug("Region {} is empty or could not be loaded", getRegionDirectories().getLocationAsFileName());
					progressChannel.incrementProgress(getRegionDirectories().getLocationAsFileName());
					return true;
				}

				Point2i regionPos = getRegionDirectories().getLocation();
				int tilesExported = 0;

				// Iterate over 16x16 tiles in this region (each tile is 2x2 chunks = 32x32 blocks)
				for (int tileX = 0; tileX < 16; tileX++) {
					for (int tileZ = 0; tileZ < 16; tileZ++) {
						// Get the 4 chunk indices for this tile
						int chunkX0 = tileX * 2;
						int chunkZ0 = tileZ * 2;

						// Calculate indices (chunks are stored in 32x32 array within region)
						int idx00 = chunkZ0 * 32 + chunkX0;
						int idx10 = chunkZ0 * 32 + chunkX0 + 1;
						int idx01 = (chunkZ0 + 1) * 32 + chunkX0;
						int idx11 = (chunkZ0 + 1) * 32 + chunkX0 + 1;

						// Check if any of the 4 chunks are selected
						boolean hasSelected = selectedChunks == null || (
								selectedChunks.get(idx00) || selectedChunks.get(idx10) ||
								selectedChunks.get(idx01) || selectedChunks.get(idx11));

						if (!hasSelected) {
							continue;
						}

						// Get ALL 4 chunks for this tile (export full 32x32 tile even if only some chunks selected)
						RegionChunk[] chunks = new RegionChunk[4];
						chunks[0] = regionMca.getChunk(idx00);
						chunks[1] = regionMca.getChunk(idx10);
						chunks[2] = regionMca.getChunk(idx01);
						chunks[3] = regionMca.getChunk(idx11);

						// Calculate tile origin in world coordinates
						int tileOriginX = (regionPos.getX() * 512) + (tileX * 32);
						int tileOriginZ = (regionPos.getZ() * 512) + (tileZ * 32);

						// Extract and save
						net.querz.mcaselector.io.TileJsonExporter.TileData tile =
								exporter.extractTileFromChunks(chunks, tileOriginX, tileOriginZ, minY, maxY, includeAir);

						if (tile != null && !tile.blocks.isEmpty()) {
							String filename = String.format("tile_%d_%d%s",
									tileOriginX / 32, tileOriginZ / 32,
									exporter.isCompressOutput() ? ".json.gz" : ".json");

							java.nio.file.Path outputFile = destination.toPath().resolve(filename);
							exporter.writeTileJson(tile, outputFile);
							tilesExported++;
						}
					}
				}

				LOGGER.debug("Exported {} tiles from region {}", tilesExported, getRegionDirectories().getLocationAsFileName());
				progressChannel.incrementProgress(getRegionDirectories().getLocationAsFileName());

			} catch (Exception ex) {
				LOGGER.warn("Error exporting tiles from region {}", getRegionDirectories().getLocationAsFileName(), ex);
				progressChannel.incrementProgress(getRegionDirectories().getLocationAsFileName());
			}

			return true;
		}
	}
}
