package mapwriter;

import java.util.Arrays;
import java.util.Map;

import com.google.common.collect.Maps;

import mapwriter.config.Config;
import mapwriter.region.MwChunk;
import mapwriter.tasks.SaveChunkTask;
import mapwriter.tasks.UpdateSurfaceChunksTask;
import mapwriter.util.Utils;
import net.minecraft.client.Minecraft;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.storage.ExtendedBlockStorage;

public class ChunkManager
{
	public Mw mw;
	private boolean closed = false;
	private CircularHashMap<Chunk, Integer> chunkMap = new CircularHashMap<Chunk, Integer>();
	
	private int ugpatch = 0;
	private long renderTick = 0;

	private static final int VISIBLE_FLAG = 0x01;
	private static final int VIEWED_FLAG = 0x02;

	public ChunkManager(Mw mw)
	{
		this.mw = mw;
	}

	public synchronized void close()
	{
		this.closed = true;
		this.saveChunks();
		this.chunkMap.clear();
	}

	// create MwChunk from Minecraft chunk.
	// only MwChunk's should be used in the background thread.
	// make this a full copy of chunk data to prevent possible race conditions
	// <-- done
	public static MwChunk copyToMwChunk(Chunk chunk)
	{
		Map<BlockPos, TileEntity> TileEntityMap = Maps.newHashMap();
		TileEntityMap = Utils.checkedMapByCopy(chunk.getTileEntityMap(), BlockPos.class, TileEntity.class, false);
		byte[] biomeArray = Arrays.copyOf(chunk.getBiomeArray(), chunk.getBiomeArray().length);
		ExtendedBlockStorage[] dataArray = Arrays.copyOf(chunk.getBlockStorageArray(), chunk.getBlockStorageArray().length);
		
		return new MwChunk(chunk.xPosition, chunk.zPosition, chunk.getWorld().provider.getDimensionType().getId(), dataArray, biomeArray, TileEntityMap);
	}

	public synchronized void addChunk(Chunk chunk)
	{
		if (!this.closed && (chunk != null))
		{
			this.chunkMap.put(chunk, 0);
		}
	}

	public synchronized void removeChunk(Chunk chunk)
	{
		if (!this.closed && (chunk != null))
		{
			if (!this.chunkMap.containsKey(chunk))
			{
				return; // FIXME: Is this failsafe enough for unloading?
			}
			int flags = this.chunkMap.get(chunk);
			if ((flags & VIEWED_FLAG) != 0)
			{
				this.addSaveChunkTask(chunk);
			}
			this.chunkMap.remove(chunk);
		}
	}

	public synchronized void saveChunks()
	{
		for (Map.Entry<Chunk, Integer> entry : this.chunkMap.entrySet())
		{
			int flags = entry.getValue();
			if ((flags & VIEWED_FLAG) != 0)
			{
				this.addSaveChunkTask(entry.getKey());
			}
		}
	}

	public void updateUndergroundChunks()
	{
		World world = Minecraft.getMinecraft().theWorld;
		long thisTick = world.getTotalWorldTime();
		if (thisTick == renderTick)
		{
			return;
		}
		ugpatch = (int) thisTick % 5;
		renderTick = thisTick;
		int diameter = Config.undergroundRange;
		int radius = (diameter - 1) / 2;
		int center = ((diameter-1) / 2);
		if (center % 2 == 0)
		{
			center++;
		}
		int band = ((diameter-center)/2);
		int minX = 0;
		int maxX = diameter;
		int minZ = 0;
		int maxZ = diameter;
		int chunkArrayX = (this.mw.playerXInt >> 4) - radius;
		int chunkArrayZ = (this.mw.playerZInt >> 4) - radius;
		MwChunk[] chunkArray = new MwChunk[diameter*diameter];
		
		ugpatch++;
		if (diameter > 3)
		{
			switch (this.ugpatch)
			{
				case 0:
					maxX = band;
					maxZ = diameter-band;
					break;
				case 1:
					minX = band+1;
					maxZ = band;
					break;
				case 2:
					minX = (diameter-band)+1;
					minZ = band+1;
					break;
				case 3:
					maxX = diameter-band;
					minZ = (diameter-band)+1;
					maxZ = diameter-band;
					break;
				default:
					minX = band+1;
					maxX = diameter-band;
					minZ = band+1;
					maxZ = diameter-band;
					ugpatch = 0;
					break;
			}
		}
		
		for (int z = minZ; z < maxZ; z++)
		{
			for (int x = minX; x < maxX; x++)
			{
				Chunk chunk = this.mw.mc.theWorld.getChunkFromChunkCoords(chunkArrayX + x, chunkArrayZ + z);
				if (!chunk.isEmpty())
				{
					chunkArray[(z * diameter) + x] = copyToMwChunk(chunk);
				}
			}
		}
	}

	public void updateSurfaceChunks()
	{
		int chunksToUpdate = Math.min(this.chunkMap.size(), Config.chunksPerTick);
		MwChunk[] chunkArray = new MwChunk[chunksToUpdate];
		for (int i = 0; i < chunksToUpdate; i++)
		{
			Map.Entry<Chunk, Integer> entry = this.chunkMap.getNextEntry();
			if (entry != null)
			{
				// if this chunk is within a certain distance to the player then
				// add it to the viewed set
				Chunk chunk = entry.getKey();

				int flags = entry.getValue();
				if (Utils.distToChunkSq(this.mw.playerXInt, this.mw.playerZInt, chunk) <= Config.maxChunkSaveDistSq)
				{
					flags |= (VISIBLE_FLAG | VIEWED_FLAG);
				}
				else
				{
					flags &= ~VISIBLE_FLAG;
				}
				entry.setValue(flags);

				if ((flags & VISIBLE_FLAG) != 0)
				{
					chunkArray[i] = copyToMwChunk(chunk);
					this.mw.executor.addTask(new UpdateSurfaceChunksTask(this.mw, chunkArray[i]));
				}
				else
				{
					chunkArray[i] = null;
				}
			}
		}

		// this.mw.executor.addTask(new UpdateSurfaceChunksTask(this.mw,
		// chunkArray));
	}

	public void onTick()
	{
		if (!this.closed)
		{
			if ((this.mw.tickCounter & 0xf) == 0)
			{
				this.updateUndergroundChunks();
			}
			else
			{
				this.updateSurfaceChunks();
			}
		}
	}

	private void addSaveChunkTask(Chunk chunk)
	{
		if ((Minecraft.getMinecraft().isSingleplayer() && Config.regionFileOutputEnabledMP) || (!Minecraft.getMinecraft().isSingleplayer() && Config.regionFileOutputEnabledSP))
		{
			if (!chunk.isEmpty())
			{
				this.mw.executor.addTask(new SaveChunkTask(copyToMwChunk(chunk), this.mw.regionManager));
			}
		}
	}
}