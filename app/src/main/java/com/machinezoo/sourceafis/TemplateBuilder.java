// Part of SourceAFIS: https://sourceafis.machinezoo.com
package com.machinezoo.sourceafis;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

import java.awt.image.*;
import java.io.*;
import java.util.*;

import com.machinezoo.noexception.throwing.ThrowingSupplier;
import java8.util.Comparators;
import java8.util.J8Arrays;
import java8.util.Lists;
import java8.util.function.IntFunction;

import java8.util.function.Function;
import java8.util.function.ToIntFunction;
import java8.util.stream.*;
import javax.imageio.*;
import com.google.gson.*;
import com.machinezoo.noexception.*;
import java8.util.function.Predicate;
import java8.util.function.ToDoubleFunction;

class TemplateBuilder {
	FingerprintTransparency transparency = FingerprintTransparency.none;
	Cell size;
	Minutia[] minutiae;
	NeighborEdge[][] edges;
	void extract(byte[] image, double dpi) {
		DoubleMap raw = readImage(image);
		if (Math.abs(dpi - 500) > Parameters.dpiTolerance)
			raw = scaleImage(raw, dpi);
		transparency.logScaledImage(raw);
		size = raw.size();
		BlockMap blocks = new BlockMap(raw.width, raw.height, Parameters.blockSize);
		transparency.logBlockMap(blocks);
		Histogram histogram = histogram(blocks, raw);
		Histogram smoothHistogram = smoothHistogram(blocks, histogram);
		BooleanMap mask = mask(blocks, histogram);
		DoubleMap equalized = equalize(blocks, raw, smoothHistogram, mask);
		DoubleMap orientation = orientationMap(equalized, mask, blocks);
		Cell[][] smoothedLines = orientedLines(Parameters.parallelSmoothinigResolution, Parameters.parallelSmoothinigRadius, Parameters.parallelSmoothinigStep);
		DoubleMap smoothed = smoothRidges(equalized, orientation, mask, blocks, 0, smoothedLines);
		transparency.logParallelSmoothing(smoothed);
		Cell[][] orthogonalLines = orientedLines(Parameters.orthogonalSmoothinigResolution, Parameters.orthogonalSmoothinigRadius, Parameters.orthogonalSmoothinigStep);
		DoubleMap orthogonal = smoothRidges(smoothed, orientation, mask, blocks, Math.PI, orthogonalLines);
		transparency.logOrthogonalSmoothing(orthogonal);
		BooleanMap binary = binarize(smoothed, orthogonal, mask, blocks);
		cleanupBinarized(binary);
		BooleanMap pixelMask = fillBlocks(mask, blocks);
		transparency.logPixelMask(pixelMask);
		BooleanMap inverted = invert(binary, pixelMask);
		BooleanMap innerMask = innerMask(pixelMask);
		Skeleton ridges = new Skeleton(binary, SkeletonType.RIDGES, transparency);
		Skeleton valleys = new Skeleton(inverted, SkeletonType.VALLEYS, transparency);
		collectMinutiae(ridges, MinutiaType.ENDING);
		collectMinutiae(valleys, MinutiaType.BIFURCATION);
		transparency.logSkeletonMinutiae(this);
		maskMinutiae(innerMask);
		removeMinutiaClouds();
		limitTemplateSize();
		shuffleMinutiae();
		buildEdgeTable();
	}
	void deserialize(String json) {
		JsonTemplate data = new Gson().fromJson(json, JsonTemplate.class);
		size = data.size();
		minutiae = data.minutiae();
		transparency.logDeserializedMinutiae(this);
		buildEdgeTable();
	}
	void convert(byte[] iso) {
		if (iso.length < 30)
			throw new IllegalArgumentException("Array too small to be an ISO 19794-2 template");
		try {
			DataInput in = new DataInputStream(new ByteArrayInputStream(iso));
			// 4B magic header "FMR\0"
			if (in.readByte() != 'F' || in.readByte() != 'M' || in.readByte() != 'R' || in.readByte() != 0)
				throw new IllegalArgumentException("This is not an ISO 19794-2 template");
			// 4B version " 20\0"
			// 4B template length in bytes (should be 28 + 6 * count + 2 + extra-data)
			// 2B junk
			in.skipBytes(10);
			// image size
			int width = in.readUnsignedShort();
			int height = in.readUnsignedShort();
			// pixels per cm X and Y, assuming 500dpi
			int xPixelsPerCM = in.readShort();
			int yPixelsPerCM = in.readShort();
			transparency.logIsoMetadata(width, height, xPixelsPerCM, yPixelsPerCM);
			double dpiX = xPixelsPerCM * 2.55;
			double dpiY = yPixelsPerCM * 2.55;
			boolean rescaleX = Math.abs(dpiX - 500) > Parameters.dpiTolerance;
			boolean rescaleY = Math.abs(dpiY - 500) > Parameters.dpiTolerance;
			if (rescaleX)
				width = (int)Math.round(width / dpiX * 500);
			if (rescaleY)
				height = (int)Math.round(height / dpiY * 500);
			size = new Cell(width, height);
			// 1B number of fingerprints in the template (assuming 1)
			// 1B junk
			// 1B finger position
			// 1B junk
			// 1B fingerprint quality
			in.skipBytes(5);
			// minutia count
			int count = in.readUnsignedByte();
			List<Minutia> list = new ArrayList<>();
			for (int i = 0; i < count; ++i) {
				// X position, upper two bits are type
				int packedX = in.readUnsignedShort();
				// Y position, upper two bits ignored
				int packedY = in.readUnsignedShort();
				// angle, 0..255 equivalent to 0..2pi
				int angle = in.readUnsignedByte();
				// 1B minutia quality
				in.skipBytes(1);
				// type: 01 ending, 10 bifurcation, 00 other (treated as ending)
				int type = (packedX >> 14) & 0x3;
				int x = packedX & 0x3fff;
				int y = packedY & 0x3fff;
				if (rescaleX)
					x = (int)Math.round(x / dpiX * 500);
				if (rescaleY)
					y = (int)Math.round(y / dpiY * 500);
				Minutia minutia = new Minutia(
					new Cell(x, y),
					Angle.complementary(angle * Angle.PI2 / 256.0),
					type == 2 ? MinutiaType.BIFURCATION : MinutiaType.ENDING);
				list.add(minutia);
			}
			// extra data length
			int extra = in.readUnsignedShort();
			// variable-length extra data section
			in.skipBytes(extra);
			minutiae = StreamSupport.stream(list).toArray(new IntFunction<Minutia[]>() {
				@Override
				public Minutia[] apply(int value) {
					return new Minutia[value];
				}
			});
			transparency.logIsoMinutiae(this);
		} catch (IOException e) {
			throw new IllegalArgumentException("Invalid ISO 19794-2 template", e);
		}
		shuffleMinutiae();
		buildEdgeTable();
	}
	DoubleMap readImage(final byte[] serialized) {
		Bitmap buffered = BitmapFactory.decodeByteArray(serialized, 0, serialized.length);
		if (buffered == null)
			throw new IllegalArgumentException("Unsupported image format");
		int width = buffered.getWidth();
		int height = buffered.getHeight();
		int[] pixels = new int[width * height];
		buffered.getPixels(pixels, 0, buffered.getWidth(), 0, 0, buffered.getWidth(), buffered.getHeight());
		DoubleMap map = new DoubleMap(width, height);
		for (int y = 0; y < height; ++y) {
			for (int x = 0; x < width; ++x) {
				int pixel = pixels[y * width + x];
				int color = (pixel & 0xff) + ((pixel >> 8) & 0xff) + ((pixel >> 16) & 0xff);
				map.set(x, y, 1 - color * (1.0 / (3.0 * 255.0)));
			}
		}
		transparency.logDecodedImage(map);
		return map;
	}
	/*DoubleMap readImage(final byte[] serialized) {
		BufferedImage buffered = Exceptions.sneak().get(new ThrowingSupplier<BufferedImage>() {
			@Override
			public BufferedImage get() throws Exception {
				return ImageIO.read(new ByteArrayInputStream(serialized));
			}
		});
		if (buffered == null)
			throw new IllegalArgumentException("Unsupported image format");
		int width = buffered.getWidth();
		int height = buffered.getHeight();
		int[] pixels = new int[width * height];
		buffered.getRGB(0, 0, width, height, pixels, 0, width);
		DoubleMap map = new DoubleMap(width, height);
		for (int y = 0; y < height; ++y) {
			for (int x = 0; x < width; ++x) {
				int pixel = pixels[y * width + x];
				int color = (pixel & 0xff) + ((pixel >> 8) & 0xff) + ((pixel >> 16) & 0xff);
				map.set(x, y, 1 - color * (1.0 / (3.0 * 255.0)));
			}
		}
		transparency.logDecodedImage(map);
		return map;
	}*/
	private DoubleMap scaleImage(DoubleMap input, double dpi) {
		return scaleImage(input, (int)Math.round(500.0 / dpi * input.width), (int)Math.round(500.0 / dpi * input.height));
	}
	private DoubleMap scaleImage(DoubleMap input, int newWidth, int newHeight) {
		DoubleMap output = new DoubleMap(newWidth, newHeight);
		double scaleX = newWidth / (double)input.width;
		double scaleY = newHeight / (double)input.height;
		double descaleX = 1 / scaleX;
		double descaleY = 1 / scaleY;
		for (int y = 0; y < newHeight; ++y) {
			double y1 = y * descaleY;
			double y2 = y1 + descaleY;
			int y1i = (int)y1;
			int y2i = (int)Math.ceil(y2);
			for (int x = 0; x < newWidth; ++x) {
				double x1 = x * descaleX;
				double x2 = x1 + descaleX;
				int x1i = (int)x1;
				int x2i = (int)Math.ceil(x2);
				double sum = 0;
				for (int oy = y1i; oy < y2i; ++oy) {
					double ry = Math.min(oy + 1, y2) - Math.max(oy, y1);
					for (int ox = x1i; ox < x2i; ++ox) {
						double rx = Math.min(ox + 1, x2) - Math.max(ox, x1);
						sum += rx * ry * input.get(ox, oy);
					}
				}
				output.set(x, y, sum * (scaleX * scaleY));
			}
		}
		return output;
	}
	private Histogram histogram(BlockMap blocks, DoubleMap image) {
		Histogram histogram = new Histogram(blocks.primary.blocks, Parameters.histogramDepth);
		for (Cell block : blocks.primary.blocks) {
			Block area = blocks.primary.block(block);
			for (int y = area.top(); y < area.bottom(); ++y)
				for (int x = area.left(); x < area.right(); ++x) {
					int depth = (int)(image.get(x, y) * histogram.depth);
					histogram.increment(block, histogram.constrain(depth));
				}
		}
		transparency.logHistogram(histogram);
		return histogram;
	}
	private Histogram smoothHistogram(BlockMap blocks, Histogram input) {
		Cell[] blocksAround = new Cell[] { new Cell(0, 0), new Cell(-1, 0), new Cell(0, -1), new Cell(-1, -1) };
		Histogram output = new Histogram(blocks.secondary.blocks, input.depth);
		for (Cell corner : blocks.secondary.blocks) {
			for (Cell relative : blocksAround) {
				Cell block = corner.plus(relative);
				if (blocks.primary.blocks.contains(block)) {
					for (int i = 0; i < input.depth; ++i)
						output.add(corner, i, input.get(block, i));
				}
			}
		}
		transparency.logSmoothedHistogram(output);
		return output;
	}
	private BooleanMap mask(BlockMap blocks, Histogram histogram) {
		DoubleMap contrast = clipContrast(blocks, histogram);
		BooleanMap mask = filterAbsoluteContrast(contrast);
		mask.merge(filterRelativeContrast(contrast, blocks));
		transparency.logCombinedMask(mask);
		mask.merge(vote(mask, Parameters.contrastVoteRadius, Parameters.contrastVoteMajority, Parameters.contrastVoteBorderDistance));
		mask.merge(filterBlockErrors(mask));
		mask.invert();
		mask.merge(filterBlockErrors(mask));
		mask.merge(filterBlockErrors(mask));
		mask.merge(vote(mask, Parameters.maskVoteRadius, Parameters.maskVoteMajority, Parameters.maskVoteBorderDistance));
		transparency.logFilteredMask(mask);
		return mask;
	}
	private DoubleMap clipContrast(BlockMap blocks, Histogram histogram) {
		DoubleMap result = new DoubleMap(blocks.primary.blocks);
		for (Cell block : blocks.primary.blocks) {
			int volume = histogram.sum(block);
			int clipLimit = (int)Math.round(volume * Parameters.clippedContrast);
			int accumulator = 0;
			int lowerBound = histogram.depth - 1;
			for (int i = 0; i < histogram.depth; ++i) {
				accumulator += histogram.get(block, i);
				if (accumulator > clipLimit) {
					lowerBound = i;
					break;
				}
			}
			accumulator = 0;
			int upperBound = 0;
			for (int i = histogram.depth - 1; i >= 0; --i) {
				accumulator += histogram.get(block, i);
				if (accumulator > clipLimit) {
					upperBound = i;
					break;
				}
			}
			result.set(block, (upperBound - lowerBound) * (1.0 / (histogram.depth - 1)));
		}
		transparency.logClippedContrast(result);
		return result;
	}
	private BooleanMap filterAbsoluteContrast(DoubleMap contrast) {
		BooleanMap result = new BooleanMap(contrast.size());
		for (Cell block : contrast.size())
			if (contrast.get(block) < Parameters.minAbsoluteContrast)
				result.set(block, true);
		transparency.logAbsoluteContrastMask(result);
		return result;
	}
	private BooleanMap filterRelativeContrast(DoubleMap contrast, BlockMap blocks) {
		List<Double> sortedContrast = new ArrayList<>();
		for (Cell block : contrast.size())
			sortedContrast.add(contrast.get(block));
		Lists.sort(sortedContrast, Comparators.<Double> reverseOrder());
		int pixelsPerBlock = blocks.pixels.area() / blocks.primary.blocks.area();
		int sampleCount = Math.min(sortedContrast.size(), Parameters.relativeContrastSample / pixelsPerBlock);
		int consideredBlocks = Math.max((int)Math.round(sampleCount * Parameters.relativeContrastPercentile), 1);
		double averageContrast = StreamSupport.stream(sortedContrast).mapToDouble(new ToDoubleFunction<Double>() {
			@Override
			public double applyAsDouble(Double n) {
				return n;
			}
		}).limit(consideredBlocks).average().getAsDouble();
		double limit = averageContrast * Parameters.minRelativeContrast;
		BooleanMap result = new BooleanMap(blocks.primary.blocks);
		for (Cell block : blocks.primary.blocks)
			if (contrast.get(block) < limit)
				result.set(block, true);
		transparency.logRelativeContrastMask(result);
		return result;
	}
	private BooleanMap vote(BooleanMap input, int radius, double majority, int borderDistance) {
		Cell size = input.size();
		Block rect = new Block(borderDistance, borderDistance, size.x - 2 * borderDistance, size.y - 2 * borderDistance);
		BooleanMap output = new BooleanMap(size);
		for (Cell center : rect) {
			Block neighborhood = Block.around(center, radius).intersect(new Block(size));
			int ones = 0;
			for (int ny = neighborhood.top(); ny < neighborhood.bottom(); ++ny)
				for (int nx = neighborhood.left(); nx < neighborhood.right(); ++nx)
					if (input.get(nx, ny))
						++ones;
			double voteWeight = 1.0 / neighborhood.area();
			if (ones * voteWeight >= majority)
				output.set(center, true);
		}
		return output;
	}
	private BooleanMap filterBlockErrors(BooleanMap input) {
		return vote(input, Parameters.blockErrorsVoteRadius, Parameters.blockErrorsVoteMajority, Parameters.blockErrorsVoteBorderDistance);
	}
	private DoubleMap equalize(BlockMap blocks, DoubleMap image, Histogram histogram, BooleanMap blockMask) {
		final double rangeMin = -1;
		final double rangeMax = 1;
		final double rangeSize = rangeMax - rangeMin;
		final double widthMax = rangeSize / 256 * Parameters.maxEqualizationScaling;
		final double widthMin = rangeSize / 256 * Parameters.minEqualizationScaling;
		double[] limitedMin = new double[histogram.depth];
		double[] limitedMax = new double[histogram.depth];
		double[] dequantized = new double[histogram.depth];
		for (int i = 0; i < histogram.depth; ++i) {
			limitedMin[i] = Math.max(i * widthMin + rangeMin, rangeMax - (histogram.depth - 1 - i) * widthMax);
			limitedMax[i] = Math.min(i * widthMax + rangeMin, rangeMax - (histogram.depth - 1 - i) * widthMin);
			dequantized[i] = i / (double)(histogram.depth - 1);
		}
		Map<Cell, double[]> mappings = new HashMap<>();
		for (Cell corner : blocks.secondary.blocks) {
			double[] mapping = new double[histogram.depth];
			mappings.put(corner, mapping);
			if (blockMask.get(corner, false) || blockMask.get(corner.x - 1, corner.y, false)
				|| blockMask.get(corner.x, corner.y - 1, false) || blockMask.get(corner.x - 1, corner.y - 1, false)) {
				double step = rangeSize / histogram.sum(corner);
				double top = rangeMin;
				for (int i = 0; i < histogram.depth; ++i) {
					double band = histogram.get(corner, i) * step;
					double equalized = top + dequantized[i] * band;
					top += band;
					if (equalized < limitedMin[i])
						equalized = limitedMin[i];
					if (equalized > limitedMax[i])
						equalized = limitedMax[i];
					mapping[i] = equalized;
				}
			}
		}
		DoubleMap result = new DoubleMap(blocks.pixels);
		for (Cell block : blocks.primary.blocks) {
			Block area = blocks.primary.block(block);
			if (blockMask.get(block)) {
				double[] topleft = mappings.get(block);
				double[] topright = mappings.get(new Cell(block.x + 1, block.y));
				double[] bottomleft = mappings.get(new Cell(block.x, block.y + 1));
				double[] bottomright = mappings.get(new Cell(block.x + 1, block.y + 1));
				for (int y = area.top(); y < area.bottom(); ++y)
					for (int x = area.left(); x < area.right(); ++x) {
						int depth = histogram.constrain((int)(image.get(x, y) * histogram.depth));
						double rx = (x - area.x + 0.5) / area.width;
						double ry = (y - area.y + 0.5) / area.height;
						result.set(x, y, Doubles.interpolate(bottomleft[depth], bottomright[depth], topleft[depth], topright[depth], rx, ry));
					}
			} else {
				for (int y = area.top(); y < area.bottom(); ++y)
					for (int x = area.left(); x < area.right(); ++x)
						result.set(x, y, -1);
			}
		}
		transparency.logEqualizedImage(result);
		return result;
	}
	private DoubleMap orientationMap(DoubleMap image, BooleanMap mask, BlockMap blocks) {
		PointMap accumulated = pixelwiseOrientation(image, mask, blocks);
		PointMap byBlock = blockOrientations(accumulated, blocks, mask);
		PointMap smooth = smoothOrientation(byBlock, mask);
		return orientationAngles(smooth, mask);
	}
	private static class ConsideredOrientation {
		Cell offset;
		Point orientation;
	}
	private static class OrientationRandom {
		static final int prime = 1610612741;
		static final int bits = 30;
		static final int mask = (1 << bits) - 1;
		static final double scaling = 1.0 / (1 << bits);
		long state = prime * prime * prime;
		double next() {
			state *= prime;
			return ((state & mask) + 0.5) * scaling;
		}
	}
	private ConsideredOrientation[][] planOrientations() {
		OrientationRandom random = new OrientationRandom();
		ConsideredOrientation[][] splits = new ConsideredOrientation[Parameters.orientationSplit][];
		for (int i = 0; i < Parameters.orientationSplit; ++i) {
			ConsideredOrientation[] orientations = splits[i] = new ConsideredOrientation[Parameters.orientationsChecked];
			for (int j = 0; j < Parameters.orientationsChecked; ++j) {
				final ConsideredOrientation sample = orientations[j] = new ConsideredOrientation();
				do {
					double angle = random.next() * Math.PI;
					double distance = Doubles.interpolateExponential(Parameters.minOrientationRadius, Parameters.maxOrientationRadius, random.next());
					sample.offset = Angle.toVector(angle).multiply(distance).round();
				} while (sample.offset.equals(Cell.zero) || sample.offset.y < 0 || J8Arrays.stream(orientations).limit(j).anyMatch(new Predicate<ConsideredOrientation>() {
					@Override
					public boolean test(ConsideredOrientation o) {
						return o.offset.equals(sample.offset);
					}
				}));
				sample.orientation = Angle.toVector(Angle.add(Angle.toOrientation(Angle.atan(sample.offset.toPoint())), Math.PI));
			}
		}
		return splits;
	}
	private PointMap pixelwiseOrientation(DoubleMap input, BooleanMap mask, BlockMap blocks) {
		ConsideredOrientation[][] neighbors = planOrientations();
		PointMap orientation = new PointMap(input.size());
		for (int blockY = 0; blockY < blocks.primary.blocks.y; ++blockY) {
			Range maskRange = maskRange(mask, blockY);
			if (maskRange.length() > 0) {
				Range validXRange = new Range(
					blocks.primary.block(maskRange.start, blockY).left(),
					blocks.primary.block(maskRange.end - 1, blockY).right()
				);
				for (int y = blocks.primary.block(0, blockY).top(); y < blocks.primary.block(0, blockY).bottom(); ++y) {
					for (ConsideredOrientation neighbor : neighbors[y % neighbors.length]) {
						int radius = Math.max(Math.abs(neighbor.offset.x), Math.abs(neighbor.offset.y));
						if (y - radius >= 0 && y + radius < input.height) {
							Range xRange = new Range(Math.max(radius, validXRange.start), Math.min(input.width - radius, validXRange.end));
							for (int x = xRange.start; x < xRange.end; ++x) {
								double before = input.get(x - neighbor.offset.x, y - neighbor.offset.y);
								double at = input.get(x, y);
								double after = input.get(x + neighbor.offset.x, y + neighbor.offset.y);
								double strength = at - Math.max(before, after);
								if (strength > 0)
									orientation.add(x, y, neighbor.orientation.multiply(strength));
							}
						}
					}
				}
			}
		}
		transparency.logPixelwiseOrientation(orientation);
		return orientation;
	}
	private static Range maskRange(BooleanMap mask, int y) {
		int first = -1;
		int last = -1;
		for (int x = 0; x < mask.width; ++x)
			if (mask.get(x, y)) {
				last = x;
				if (first < 0)
					first = x;
			}
		if (first >= 0)
			return new Range(first, last + 1);
		else
			return Range.zero;
	}
	private PointMap blockOrientations(PointMap orientation, BlockMap blocks, BooleanMap mask) {
		PointMap sums = new PointMap(blocks.primary.blocks);
		for (Cell block : blocks.primary.blocks) {
			if (mask.get(block)) {
				Block area = blocks.primary.block(block);
				for (int y = area.top(); y < area.bottom(); ++y)
					for (int x = area.left(); x < area.right(); ++x)
						sums.add(block, orientation.get(x, y));
			}
		}
		transparency.logBlockOrientation(sums);
		return sums;
	}
	private PointMap smoothOrientation(PointMap orientation, BooleanMap mask) {
		Cell size = mask.size();
		PointMap smoothed = new PointMap(size);
		for (Cell block : size)
			if (mask.get(block)) {
				Block neighbors = Block.around(block, Parameters.orientationSmoothingRadius).intersect(new Block(size));
				for (int ny = neighbors.top(); ny < neighbors.bottom(); ++ny)
					for (int nx = neighbors.left(); nx < neighbors.right(); ++nx)
						if (mask.get(nx, ny))
							smoothed.add(block, orientation.get(nx, ny));
			}
		transparency.logSmoothedOrientation(smoothed);
		return smoothed;
	}
	private static DoubleMap orientationAngles(PointMap vectors, BooleanMap mask) {
		Cell size = mask.size();
		DoubleMap angles = new DoubleMap(size);
		for (Cell block : size)
			if (mask.get(block))
				angles.set(block, Angle.atan(vectors.get(block)));
		return angles;
	}
	private Cell[][] orientedLines(int resolution, int radius, double step) {
		Cell[][] result = new Cell[resolution][];
		for (int orientationIndex = 0; orientationIndex < resolution; ++orientationIndex) {
			List<Cell> line = new ArrayList<>();
			line.add(Cell.zero);
			Point direction = Angle.toVector(Angle.fromOrientation(Angle.bucketCenter(orientationIndex, resolution)));
			for (double r = radius; r >= 0.5; r /= step) {
				Cell sample = direction.multiply(r).round();
				if (!line.contains(sample)) {
					line.add(sample);
					line.add(sample.negate());
				}
			}
			result[orientationIndex] = line.toArray(new Cell[line.size()]);
		}
		return result;
	}
	private static DoubleMap smoothRidges(DoubleMap input, DoubleMap orientation, BooleanMap mask, BlockMap blocks, double angle, Cell[][] lines) {
		DoubleMap output = new DoubleMap(input.size());
		for (Cell block : blocks.primary.blocks) {
			if (mask.get(block)) {
				Cell[] line = lines[Angle.quantize(Angle.add(orientation.get(block), angle), lines.length)];
				for (Cell linePoint : line) {
					Block target = blocks.primary.block(block);
					Block source = target.move(linePoint).intersect(new Block(blocks.pixels));
					target = source.move(linePoint.negate());
					for (int y = target.top(); y < target.bottom(); ++y)
						for (int x = target.left(); x < target.right(); ++x)
							output.add(x, y, input.get(x + linePoint.x, y + linePoint.y));
				}
				Block blockArea = blocks.primary.block(block);
				for (int y = blockArea.top(); y < blockArea.bottom(); ++y)
					for (int x = blockArea.left(); x < blockArea.right(); ++x)
						output.multiply(x, y, 1.0 / line.length);
			}
		}
		return output;
	}
	private BooleanMap binarize(DoubleMap input, DoubleMap baseline, BooleanMap mask, BlockMap blocks) {
		Cell size = input.size();
		BooleanMap binarized = new BooleanMap(size);
		for (Cell block : blocks.primary.blocks)
			if (mask.get(block)) {
				Block rect = blocks.primary.block(block);
				for (int y = rect.top(); y < rect.bottom(); ++y)
					for (int x = rect.left(); x < rect.right(); ++x)
						if (input.get(x, y) - baseline.get(x, y) > 0)
							binarized.set(x, y, true);
			}
		transparency.logBinarizedImage(binarized);
		return binarized;
	}
	private void cleanupBinarized(BooleanMap binary) {
		Cell size = binary.size();
		BooleanMap inverted = new BooleanMap(binary);
		inverted.invert();
		BooleanMap islands = vote(inverted, Parameters.binarizedVoteRadius, Parameters.binarizedVoteMajority, Parameters.binarizedVoteBorderDistance);
		BooleanMap holes = vote(binary, Parameters.binarizedVoteRadius, Parameters.binarizedVoteMajority, Parameters.binarizedVoteBorderDistance);
		for (int y = 0; y < size.y; ++y)
			for (int x = 0; x < size.x; ++x)
				binary.set(x, y, binary.get(x, y) && !islands.get(x, y) || holes.get(x, y));
		removeCrosses(binary);
		transparency.logFilteredBinarydImage(binary);
	}
	private static void removeCrosses(BooleanMap input) {
		Cell size = input.size();
		boolean any = true;
		while (any) {
			any = false;
			for (int y = 0; y < size.y - 1; ++y)
				for (int x = 0; x < size.x - 1; ++x)
					if (input.get(x, y) && input.get(x + 1, y + 1) && !input.get(x, y + 1) && !input.get(x + 1, y)
						|| input.get(x, y + 1) && input.get(x + 1, y) && !input.get(x, y) && !input.get(x + 1, y + 1)) {
						input.set(x, y, false);
						input.set(x, y + 1, false);
						input.set(x + 1, y, false);
						input.set(x + 1, y + 1, false);
						any = true;
					}
		}
	}
	private static BooleanMap fillBlocks(BooleanMap mask, BlockMap blocks) {
		BooleanMap pixelized = new BooleanMap(blocks.pixels);
		for (Cell block : blocks.primary.blocks)
			if (mask.get(block))
				for (Cell pixel : blocks.primary.block(block))
					pixelized.set(pixel, true);
		return pixelized;
	}
	private static BooleanMap invert(BooleanMap binary, BooleanMap mask) {
		Cell size = binary.size();
		BooleanMap inverted = new BooleanMap(size);
		for (int y = 0; y < size.y; ++y)
			for (int x = 0; x < size.x; ++x)
				inverted.set(x, y, !binary.get(x, y) && mask.get(x, y));
		return inverted;
	}
	private BooleanMap innerMask(BooleanMap outer) {
		Cell size = outer.size();
		BooleanMap inner = new BooleanMap(size);
		for (int y = 1; y < size.y - 1; ++y)
			for (int x = 1; x < size.x - 1; ++x)
				inner.set(x, y, outer.get(x, y));
		if (Parameters.innerMaskBorderDistance >= 1)
			inner = shrinkMask(inner, 1);
		int total = 1;
		for (int step = 1; total + step <= Parameters.innerMaskBorderDistance; step *= 2) {
			inner = shrinkMask(inner, step);
			total += step;
		}
		if (total < Parameters.innerMaskBorderDistance)
			inner = shrinkMask(inner, Parameters.innerMaskBorderDistance - total);
		transparency.logInnerMask(inner);
		return inner;
	}
	private static BooleanMap shrinkMask(BooleanMap mask, int amount) {
		Cell size = mask.size();
		BooleanMap shrunk = new BooleanMap(size);
		for (int y = amount; y < size.y - amount; ++y)
			for (int x = amount; x < size.x - amount; ++x)
				shrunk.set(x, y, mask.get(x, y - amount) && mask.get(x, y + amount) && mask.get(x - amount, y) && mask.get(x + amount, y));
		return shrunk;
	}
	private void collectMinutiae(Skeleton skeleton, final MinutiaType type) {
		minutiae = RefStreams.concat(
			J8Arrays.stream(java8.util.Optional.ofNullable(minutiae).orElse(new Minutia[0])),
			StreamSupport.stream(skeleton.minutiae)
				.filter(new Predicate<SkeletonMinutia>() {
					@Override
					public boolean test(SkeletonMinutia m) {
						return m.ridges.size() == 1;
					}
				})
				.map(new Function<SkeletonMinutia, Object>() {
					@Override
					public Object apply(SkeletonMinutia m) {
						return new Minutia(m.position, m.ridges.get(0).direction(), type);
					}
				}))
			.toArray(new IntFunction<Minutia[]>() {
				@Override
				public Minutia[] apply(int value) {
					return new Minutia[value];
				}
			});
	}
	private void maskMinutiae(final BooleanMap mask) {
		minutiae = J8Arrays.stream(minutiae)
			.filter(new Predicate<Minutia>() {
				@Override
				public boolean test(Minutia minutia) {
					Cell arrow = Angle.toVector(minutia.direction).multiply(-Parameters.maskDisplacement).round();
					return mask.get(minutia.position.plus(arrow), false);
				}
			})
			.toArray(new IntFunction<Minutia[]>() {
				@Override
				public Minutia[] apply(int i) {
					return new Minutia[i];
				}
			});
		transparency.logInnerMinutiae(this);
	}
	private void removeMinutiaClouds() {
		final int radiusSq = Integers.sq(Parameters.minutiaCloudRadius);
		final Set<Minutia> removed = J8Arrays.stream(minutiae)
			.filter(new Predicate<Minutia>() {
				@Override
				public boolean test(final Minutia minutia) {
					return Parameters.maxCloudSize < J8Arrays.stream(minutiae)
							.filter(new Predicate<Minutia>() {
								@Override
								public boolean test(Minutia neighbor) {
									return neighbor.position.minus(minutia.position).lengthSq() <= radiusSq;
								}
							})
							.count() - 1;
				}
			})
			.collect(Collectors.<Minutia>toSet());
		minutiae = J8Arrays.stream(minutiae)
			.filter(new Predicate<Minutia>() {
				@Override
				public boolean test(Minutia minutia) {
					return !removed.contains(minutia);
				}
			})
			.toArray(new IntFunction<Minutia[]>() {
				@Override
				public Minutia[] apply(int i) {
					return new Minutia[i];
				}
			});
		transparency.logRemovedMinutiaClouds(this);
	}
	private void limitTemplateSize() {
		if (minutiae.length > Parameters.maxMinutiae) {
			List<Minutia> sorted = J8Arrays.stream(minutiae)
				.sorted(Comparators.<Minutia>comparingInt(
						new ToIntFunction<Minutia>() {
							@Override
							public int applyAsInt(final Minutia minutia) {
								return J8Arrays.stream(minutiae)
										.mapToInt(new ToIntFunction<Minutia>() {
											@Override
											public int applyAsInt(Minutia neighbor) {
												return minutia.position.minus(neighbor.position).lengthSq();
											}
										})
										.sorted()
										.skip(Parameters.sortByNeighbor)
										.findFirst().orElse(Integer.MAX_VALUE);
							}
						})
					).collect(Collectors.<Minutia>toList());
			Collections.reverse(sorted);
			minutiae = sorted.subList(0, Parameters.maxMinutiae).toArray(new Minutia[0]);
		}
		transparency.logTopMinutiae(this);
	}
	private void shuffleMinutiae() {
		int seed = 0;
		for (Minutia minutia : minutiae)
			seed += minutia.direction + minutia.position.x + minutia.position.y + minutia.type.ordinal();
		Collections.shuffle(Arrays.asList(minutiae), new Random(seed));
		/*final int prime = 1610612741;
		Comparator<> y = Comparators.comparing(new Function<int>() {
			@Override
			public int apply(Minutia m) {
				return ((m.position.x * prime) + m.position.y) * prime;
			}
		});
		/*Arrays.sort(minutiae, java8.util.Comparators
			.comparing((Minutia m) -> ((m.position.x * prime) + m.position.y) * prime)
			.thenComparing(m -> m.position.x)
			.thenComparing(m -> m.position.y)
			.thenComparing(m -> m.direction)
			.thenComparing(m -> m.type)
		);*/
		//transparency.logShuffledMinutiae(this);
	}
	private void buildEdgeTable() {
		edges = new NeighborEdge[minutiae.length][];
		List<NeighborEdge> star = new ArrayList<>();
		int[] allSqDistances = new int[minutiae.length];
		for (int reference = 0; reference < edges.length; ++reference) {
			Cell referencePosition = minutiae[reference].position;
			int sqMaxDistance = Integers.sq(Parameters.edgeTableRange);
			if (minutiae.length - 1 > Parameters.edgeTableNeighbors) {
				for (int neighbor = 0; neighbor < minutiae.length; ++neighbor)
					allSqDistances[neighbor] = referencePosition.minus(minutiae[neighbor].position).lengthSq();
				Arrays.sort(allSqDistances);
				sqMaxDistance = allSqDistances[Parameters.edgeTableNeighbors];
			}
			for (int neighbor = 0; neighbor < minutiae.length; ++neighbor) {
				if (neighbor != reference && referencePosition.minus(minutiae[neighbor].position).lengthSq() <= sqMaxDistance)
					star.add(new NeighborEdge(minutiae, reference, neighbor));
			}
			Comparator<NeighborEdge> left = Comparators.comparingInt(new ToIntFunction<NeighborEdge>() {
				@Override
				public int applyAsInt(NeighborEdge e) {
					return e.length;
				}
			});
			Comparator<NeighborEdge> right = Comparators.comparingInt(new ToIntFunction<NeighborEdge>() {
				@Override
				public int applyAsInt(NeighborEdge e) {
					return e.neighbor;
				}
			});
			Lists.sort(star, Comparators.thenComparing(left, right));
			while (star.size() > Parameters.edgeTableNeighbors)
				star.remove(star.size() - 1);
			edges[reference] = star.toArray(new NeighborEdge[star.size()]);
			star.clear();
		}
		transparency.logEdgeTable(edges);
	}
}
