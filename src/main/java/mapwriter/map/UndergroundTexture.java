package mapwriter.map;

import java.awt.Point;
import java.util.Arrays;

import org.lwjgl.opengl.GL11;


import mapwriter.Mw;
import mapwriter.region.ChunkRender;
import mapwriter.region.IChunk;
import mapwriter.util.Texture;
import mapwriter.config.Config;
import mapwriter.util.Logging;
import net.minecraft.block.Block;
import net.minecraft.block.state.IBlockState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.WorldClient;
import net.minecraft.init.Biomes;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.biome.Biome;
import net.minecraft.world.chunk.Chunk;

public class UndergroundTexture extends Texture
{

	private Mw mw;
	private int px = 0;
	private int py = 0;
	private int pz = 0;
	private int dimension = 0;
	private int updateX;
	private int updateZ;
	private Point[] loadedChunkArray;
	private int textureSize;
	private int textureChunks;
	private int[] pixels;

	class RenderChunk implements IChunk
	{
		Chunk chunk;

		public RenderChunk(Chunk chunk)
		{
			this.chunk = chunk;
		}

		@Override
		public int getMaxY()
		{
			return this.chunk.getTopFilledSegment() + 15;
		}

		@Override
		public IBlockState getBlockState(int x, int y, int z)
		{
			return this.chunk.getBlockState(x, y, z);
		}

		@Override
		public int getBiome(int x, int y, int z)
		{
			int i = x & 15;
			int j = z & 15;
			int k = this.chunk.getBiomeArray()[j << 4 | i] & 255;

			if (k == 255)
			{
				Biome biome =
						Minecraft.getMinecraft().world.getBiomeProvider().getBiome(
								new BlockPos(k, k, k),
								Biomes.PLAINS);
				k = Biome.getIdForBiome(biome);
			}
			;
			return k;
		}

		@Override
		public int getLightValue(int x, int y, int z)
		{
			return this.chunk.getLightSubtracted(new BlockPos(x, y, z), 0);
		}
	}

	public UndergroundTexture(Mw mw, int textureSize, boolean linearScaling)
	{
		super(textureSize, textureSize, 0x00000000, GL11.GL_NEAREST, GL11.GL_NEAREST, GL11.GL_REPEAT);
		this.setLinearScaling(false);
		this.textureSize = textureSize;
		this.textureChunks = textureSize >> 4;
		this.loadedChunkArray = new Point[this.textureChunks * this.textureChunks];
		this.pixels = new int[textureSize * textureSize];
		Arrays.fill(this.pixels, 0xff000000);
		this.mw = mw;
	}

	public void clear()
	{
		Arrays.fill(this.pixels, 0xff000000);
		this.updateTexture();
	}

	public void clearChunkPixels(int cx, int cz)
	{
		int tx = (cx << 4) & (this.textureSize - 1);
		int tz = (cz << 4) & (this.textureSize - 1);
		for (int j = 0; j < 16; j++)
		{
			int offset = ((tz + j) * this.textureSize) + tx;
			Arrays.fill(this.pixels, offset, offset + 16, 0xff000000);
		}
		this.updateTextureArea(tx, tz, 16, 16);
	}

	void renderToTexture(int y)
	{
		this.setPixelBufPosition(0);
		for (int i = 0; i < this.pixels.length; i++)
		{
			int colour = this.pixels[i];
 			int alpha = 255;
			this.pixelBufPut(((alpha << 24) & 0xff000000) | (colour & 0xffffff));
		}
		this.updateTexture();
	}

	public int getLoadedChunkOffset(int cx, int cz)
	{
		int cxOffset = cx & (this.textureChunks - 1);
		int czOffset = cz & (this.textureChunks - 1);
		return (czOffset * this.textureChunks) + cxOffset;
	}

	public void requestView(MapView view)
	{
		int cxMin = ((int) view.getMinX()) >> 4;
		int czMin = ((int) view.getMinZ()) >> 4;
		int cxMax = ((int) view.getMaxX()) >> 4;
		int czMax = ((int) view.getMaxZ()) >> 4;
		for (int cz = czMin; cz <= czMax; cz++)
		{
			for (int cx = cxMin; cx <= cxMax; cx++)
			{
				Point requestedChunk = new Point(cx, cz);
				int offset = this.getLoadedChunkOffset(cx, cz);
				Point currentChunk = this.loadedChunkArray[offset];
				if ((currentChunk == null) || !currentChunk.equals(requestedChunk))
				{
					this.clearChunkPixels(cx, cz);
					this.loadedChunkArray[offset] = requestedChunk;
				}
			}
		}
	}

	public boolean isChunkInTexture(int cx, int cz)
	{
		Point requestedChunk = new Point(cx, cz);
		int offset = this.getLoadedChunkOffset(cx, cz);
		Point chunk = this.loadedChunkArray[offset];
		return (chunk != null) && chunk.equals(requestedChunk);
	}

	public void update()
	{
		if (this.dimension != this.mw.playerDimension)
		{
			this.clear();
			this.dimension = this.mw.playerDimension;
		}
		this.px = this.mw.playerXInt;
		this.py = this.mw.playerYInt;
		this.pz = this.mw.playerZInt;
		WorldClient world = this.mw.mc.theWorld;

		
		
		int diameter = Config.undergroundRange;
		int radius = (diameter - 1) / 2;

		                                
		this.updateX = (this.px >> 4) - radius;
		this.updateZ = (this.pz >> 4) - radius;

		int cxMax = this.updateX + diameter-1;
		int czMax = this.updateZ + diameter-1;
				
		
                int center = ((diameter-1) / 2);

		if (center % 2 == 0)

		{
			center++;
		}
		int band = ((diameter-center)/2); 

		int minX = this.updateX;
		int maxX = cxMax;
		int minZ = this.updateZ;
		int maxZ = czMax;

                if (diameter > 3)
                {
                        switch ((int) mw.tickCounter % 10)
                        {
			case 0:
                        	maxX = minX + band - 1;
				maxZ = minZ + diameter - band - 1;
				break;
			case 1:
				minX = minX + band;
				maxZ = minZ + band - 1;
				break;
			case 2:
				minX = minX + diameter-band;
				minZ = minZ + band;
				break;
			case 3:
				maxX = minX + diameter-band-1;
				maxZ = minZ + diameter-1;
				minZ = minZ + diameter-band;
				break;
			case 4:
				maxX = minX + diameter-band-1;
				minX = minX + band;
				maxZ = minZ + diameter-band-1;
				minZ = minZ + band;
				break;
			default:
				return;
                        }
                }
		
		
//		Logging.logInfo("tick: %d, minX: %d, maxX: %d, minZ: %d, maxZ: %d, band: %d, center: %d, diameter: %d.",mw.tickCounter, minX, maxX, minZ, maxZ, band, center, diameter);
		
//		int flagOffset = 0;
		for (int cz = minZ; cz <= maxZ; cz++)
		{
			for (int cx = minX; cx <= maxX; cx++)
			{
				if (this.isChunkInTexture(cx, cz))
				{
					Chunk chunk = world.getChunkFromChunkCoords(cx, cz);
					int tx = (cx << 4) & (this.textureSize - 1);
					int tz = (cz << 4) & (this.textureSize - 1);
					int pixelOffset = (tz * this.textureSize) + tx;
					ChunkRender.renderUnderground(
							this.mw.blockColours,
							new RenderChunk(chunk),
							this.pixels,
							pixelOffset,
							this.textureSize,
							this.py);
				}
//				flagOffset += 1;
			}
		}

		this.renderToTexture(this.py + 1);
	}

	private void clearFlags()
	{
		for (byte[] chunkFlags : this.updateFlags)
		{
			Arrays.fill(chunkFlags, ChunkRender.FLAG_UNPROCESSED);
		}
	}

	private void processBlock(int xi, int y, int zi)
	{
		int x = (this.updateX << 4) + xi;
		int z = (this.updateZ << 4) + zi;

		int xDist = this.px - x;
		int zDist = this.pz - z;

		if (((xDist * xDist) + (zDist * zDist)) <= 512)
		{
			if (this.isChunkInTexture(x >> 4, z >> 4))
			{
				int chunkOffset = ((zi >> 4) * 5) + (xi >> 4);
				int columnXi = xi & 0xf;
				int columnZi = zi & 0xf;
				int columnOffset = (columnZi << 4) + columnXi;
				byte columnFlag = this.updateFlags[chunkOffset][columnOffset];

				if (columnFlag == ChunkRender.FLAG_UNPROCESSED)
				{
					// if column not yet processed
					WorldClient world = this.mw.mc.world;
					IBlockState state = world.getBlockState(new BlockPos(x, y, z));
					Block block = state.getBlock();
					if ((block == null) || !block.isOpaqueCube(state))
					{
						// if block is not opaque
						this.updateFlags[chunkOffset][columnOffset] = ChunkRender.FLAG_NON_OPAQUE;
						this.processBlock(xi + 1, y, zi);
						this.processBlock(xi - 1, y, zi);
						this.processBlock(xi, y, zi + 1);
						this.processBlock(xi, y, zi - 1);
					}
					else
					{
						// block is opaque
						this.updateFlags[chunkOffset][columnOffset] = ChunkRender.FLAG_OPAQUE;
					}
				}
			}
		}
	}

}
