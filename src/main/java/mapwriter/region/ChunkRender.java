package mapwriter.region;

import mapwriter.config.Config;
import net.minecraft.block.state.IBlockState;

public class ChunkRender
{

	public static final byte FLAG_UNPROCESSED = 0;
	public static final byte FLAG_NON_OPAQUE = 1;
	public static final byte FLAG_OPAQUE = 2;

	// values that change how height shading algorithm works
	public static final double brightenExponent = 0.35;
	public static final double darkenExponent = 0.35;
	public static final double brightenAmplitude = 0.7;
	public static final double darkenAmplitude = 1.4;

	// get the height shading of a pixel.
	// requires the pixel to the west and the pixel to the north to have their
	// heights stored in the alpha channel to work.
	// the "height" of a pixel is the y value of the first opaque block in
	// the block column that created the pixel.
	// height values of 0 and 255 are ignored as these are used as the clear
	// values for pixels.
	public static double getHeightShading(int height, int heightW, int heightN)
	{
		int samples = 0;
		int heightDiff = 0;

		if ((heightW > 0) && (heightW < 255))
		{
			heightDiff += height - heightW;
			samples++;
		}

		if ((heightN > 0) && (heightN < 255))
		{
			heightDiff += height - heightN;
			samples++;
		}

		double heightDiffFactor = 0.0;
		if (samples > 0)
		{
			heightDiffFactor = (double) heightDiff / ((double) samples);
		}

		// emphasize small differences in height, but as the difference in
		// height increases,
		// don't increase so much
		if (Config.moreRealisticMap)
		{
			return Math.atan(heightDiffFactor) * 0.3;
		}

		return (heightDiffFactor >= 0.0) ? Math.pow(
				heightDiffFactor * (1 / 255.0),
				brightenExponent) * brightenAmplitude
				: -Math.pow(-(heightDiffFactor * (1 / 255.0)), darkenExponent) * darkenAmplitude;
	}

	// calculate the colour of a pixel by alpha blending the colour of each
	// block
	// in a column until an opaque block is reached.
	// y is topmost block height to start rendering at.
	// for maps without a ceiling y is simply the height of the highest block in
	// that chunk.
	// for maps with a ceiling y is the height of the first non opaque block
	// starting from
	// the ceiling.
	//
	// for every block in the column starting from the highest:
	// - get the block colour
	// - get the biome shading
	// - extract colour components as doubles in the range [0.0, 1.0]
	// - the shaded block colour is simply the block colour multiplied
	// by the biome shading for each component
	// - this shaded block colour is alpha blended with the running
	// colour for this column
	//
	// so the final map colour is an alpha blended stack of all the
	// individual shaded block colours in the sequence [yStart .. yEnd]
	//
	// note that the "front to back" alpha blending algorithm is used
	// rather than the more common "back to front".
	//
	public static int getColumnColour(BlockColours bc, IChunk chunk, int x, int y, int z,
			int heightW, int heightN)
	{
		double a = 1.0;
		double r = 0.0;
		double g = 0.0;
		double b = 0.0;
		for (; y > 0; y--)
		{
			IBlockState blockState = chunk.getBlockState(x, y, z);
			int c1 = bc.getColour(blockState);
			int alpha = (c1 >> 24) & 0xff;

			// this is the color that gets returned for air, so set aplha to 0
			// so the game continues to the next block in the colum
			if (c1 == -8650628)
			{
				alpha = 0;
			}

			// no need to process block if it is transparent
			if (alpha > 0)
			{

				int biome = chunk.getBiome(x, y, z);
				int c2 = bc.getBiomeColour(blockState, biome);

				// extract colour components as normalized doubles
				double c1A = (alpha) / 255.0;
				double c1R = ((c1 >> 16) & 0xff) / 255.0;
				double c1G = ((c1 >> 8) & 0xff) / 255.0;
				double c1B = ((c1 >> 0) & 0xff) / 255.0;

				// c2A is implicitly 1.0 (opaque)
				double c2R = ((c2 >> 16) & 0xff) / 255.0;
				double c2G = ((c2 >> 8) & 0xff) / 255.0;
				double c2B = ((c2 >> 0) & 0xff) / 255.0;

				// alpha blend and multiply
				r = r + (a * c1A * c1R * c2R);
				g = g + (a * c1A * c1G * c2G);
				b = b + (a * c1A * c1B * c2B);
				a = a * (1.0 - c1A);
			}
			// break when an opaque block is encountered
			if (alpha == 255)
			{
				break;
			}
		}

		/*
		 * // darken blocks depending on how far away they are from this depth
		 * slice if (depth != 0) { int bottomOfSlice = maxHeight - ((depth + 1)
		 * * maxHeight / Mw.HEIGHT_LEVELS) - 1; if (yRange[0] < bottomOfSlice) {
		 * shading *= 1.0 - 2.0 * ((double) (bottomOfSlice - yRange[0]) /
		 * (double) maxHeight); } }
		 */

		double heightShading = getHeightShading(y, heightW, heightN);
		int lightValue = chunk.getLightValue(x, y + 1, z);
		double lightShading = lightValue / 15.0;
		double shading = (heightShading + 1.0) * lightShading;

		// apply the shading
		r = Math.min(Math.max(0.0, r * shading), 1.0);
		g = Math.min(Math.max(0.0, g * shading), 1.0);
		b = Math.min(Math.max(0.0, b * shading), 1.0);

		// now we have our final RGB values as doubles, convert to a packed ARGB
		// pixel.
		return ((y & 0xff) << 24) | ((((int) (r * 255.0)) & 0xff) << 16) | ((((int) (g * 255.0))
				& 0xff) << 8) | ((((int) (b * 255.0)) & 0xff));
	}

	static int getPixelHeightN(int[] pixels, int offset, int scanSize)
	{
		return (offset >= scanSize) ? ((pixels[offset - scanSize] >> 24) & 0xff) : -1;
	}

	static int getPixelHeightW(int[] pixels, int offset, int scanSize)
	{
		return ((offset & (scanSize - 1)) >= 1) ? ((pixels[offset - 1] >> 24) & 0xff) : -1;
	}

	public static void renderSurface(BlockColours bc, IChunk chunk, int[] pixels, int offset,
			int scanSize, boolean dimensionHasCeiling)
	{
		int chunkMaxY = chunk.getMaxY();
		for (int z = 0; z < MwChunk.SIZE; z++)
		{
			for (int x = 0; x < MwChunk.SIZE; x++)
			{
				// for the nether dimension search for the first non-opaque
				// block below the ceiling.
				// cannot use y = chunkMaxY as the nether sometimes spawns
				// mushrooms above the ceiling height. this fixes the
				// rectangular grey areas (ceiling bedrock) on the nether map.
				int y;
				if (dimensionHasCeiling)
				{
					for (y = 127; y >= 0; y--)
					{
						IBlockState blockState = chunk.getBlockState(x, y, z);
						int color = bc.getColour(blockState);
						int alpha = (color >> 24) & 0xff;

						if (color == -8650628)
						{
							alpha = 0;
						}

						if (alpha != 0xff)
						{
							break;
						}
					}
				}
				else
				{
					y = chunkMaxY - 1;
				}

				int pixelOffset = offset + (z * scanSize) + x;
				pixels[pixelOffset] = getColumnColour(
						bc,
						chunk,
						x,
						y,
						z,
						getPixelHeightW(pixels, pixelOffset, scanSize),
						getPixelHeightN(pixels, pixelOffset, scanSize));
			}
		}
	}


	private static double checkBlockOpenAndVisible(BlockColours bc, IChunk chunk, int x, int y, int z)
	{
		int bcolor = 0;
		int lvalue = 0;
		double alpha = 0;
		// todo: This doesn't work as intended.
		// investigate why, address.
                IBlockState blockState = chunk.getBlockState(x, y, z);
		bcolor = bc.getColour(blockState);
		alpha = (256-((bcolor >> 24) & 0xff)) / 256;
		
		if (bcolor == -8650628)
		{
			alpha = 1.0;
		}

		if (alpha < 0.1)
		{
			alpha = 0.1;
		}
                        			
		if (chunk.getLightValue(x, y, z) > 0)
		{
			return alpha;
		} else
		{
			return 0.0;
		}
	
	}

	public static void renderUnderground(BlockColours bc, IChunk chunk, int[] pixels, int offset,
			int scanSize, int startY)
	{
		startY = Math.min(Math.max(0, startY), 255);
		for (int z = 0; z < MwChunk.SIZE; z++)
		{
			for (int x = 0; x < MwChunk.SIZE; x++)
			{
				
				int color = 0;
				int red = 0;
				int blue = 0;
				int green = 180;
				double voidbelow = 0;
				double voidabove = 0;			
				int lvalue = 0;
				double alpha = 0;

				int dist = 0;
				for (int y = startY-1; y >= startY-15;y--)
				{
					if (y >=0) 
					{
						alpha = checkBlockOpenAndVisible(bc, chunk, x, y, z);
						if (alpha > 0)
						{
							green = green - (int) (11-dist);
							red = red + (int) ((15-dist)*alpha);
						}
					}
					if (dist < 10)
					{
						dist++;
					}
				}

				dist = 0;
				for (int y = startY + 2; y <= startY+16; y++)
				{
					if (y <= 255)
					{
						alpha = checkBlockOpenAndVisible(bc, chunk, x, y, z);
						if (alpha > 0)
						{
							green = green - (int) (11-(dist));
							blue = blue + (int) ((16-dist)*alpha);
						}
					
					}
					else
					{
					green = green - (int) (14-dist);
					blue = blue + (int) (17-dist);
					}
					if (dist < 10)
					{
						dist++;
					}
				}
				
				if (checkBlockOpenAndVisible(bc, chunk, x, startY, z)>0)
				{
					green = green - 17;
				}

				if (checkBlockOpenAndVisible(bc, chunk, x, startY+1, z)>0)
				{
					green = green - 17;
				}

				if (red > 200)
				{
					red = 170;
				}

				if (green < 0)
				{
					green = 0;
				}

				if (blue > 200)
				{
					blue = 170;
				}
				color = red << 16 | green << 8 | blue;
				int pixelOffset = offset + (z * scanSize) + x;
				pixels[pixelOffset] = color;
			}
		}
	}
}
