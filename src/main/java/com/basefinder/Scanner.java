package com.basefinder;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.entity.ShulkerBoxBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.chunk.LevelChunk;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Passive, client-only analysis of chunks the server has already sent us.
 * Nothing here sends packets or touches the world.
 */
public final class Scanner {
	private Scanner() {}

	private static final int RADIUS = 32; // chunks to look at around the player
	private static final int REANALYSE_TICKS = 100; // 5s
	private static final long ACTIVE_WINDOW_TICKS = 20L * 60 * 30; // a change seen in the last 30 min = active

	public static final Map<Long, Candidate> all = new HashMap<>();
	private static Object lastConnection;

	private static final Set<Block> NATURAL = new HashSet<>(Arrays.asList(
			Blocks.STONE, Blocks.DEEPSLATE, Blocks.DIRT, Blocks.GRASS_BLOCK, Blocks.COARSE_DIRT,
			Blocks.SAND, Blocks.RED_SAND, Blocks.GRAVEL, Blocks.NETHERRACK, Blocks.TUFF,
			Blocks.GRANITE, Blocks.DIORITE, Blocks.ANDESITE, Blocks.BEDROCK, Blocks.SANDSTONE,
			Blocks.TERRACOTTA, Blocks.SNOW_BLOCK, Blocks.ICE, Blocks.PACKED_ICE, Blocks.BASALT,
			Blocks.BLACKSTONE, Blocks.SOUL_SAND, Blocks.SOUL_SOIL, Blocks.END_STONE,
			Blocks.MYCELIUM, Blocks.PODZOL, Blocks.CLAY, Blocks.MUD, Blocks.CALCITE,
			Blocks.SMOOTH_BASALT, Blocks.AMETHYST_BLOCK, Blocks.BUDDING_AMETHYST));

	private static long key(int cx, int cz) {
		return ((long) cx << 32) ^ (cz & 0xffffffffL);
	}

	public static void tick(Minecraft mc) {
		if (!Config.enabled || mc.level == null || mc.player == null) return;
		Object conn = mc.getConnection();
		if (conn != lastConnection) {
			lastConnection = conn;
			all.clear();
		}
		long now = mc.level.getGameTime();
		if (now % 20 == 0) recount(mc);
		analyseOne(mc, now);
	}

	public static void reset() {
		all.clear();
	}

	// ---------------------------------------------------------------- counting

	private static void recount(Minecraft mc) {
		ClientLevel level = mc.level;
		Map<Long, Candidate> acc = new HashMap<>();
		Map<Long, Integer> items = new HashMap<>();

		for (Entity e : level.entitiesForRendering()) {
			BlockPos bp = e.blockPosition();
			long k = key(bp.getX() >> 4, bp.getZ() >> 4);
			if (e instanceof ArmorStand) {
				Candidate c = acc.computeIfAbsent(k, kk -> new Candidate(bp.getX() >> 4, bp.getZ() >> 4));
				c.stands++;
				c.include(bp.getX(), bp.getY(), bp.getZ());
			} else if (e instanceof ItemEntity) {
				items.merge(k, 1, Integer::sum);
			}
		}

		int pcx = mc.player.blockPosition().getX() >> 4;
		int pcz = mc.player.blockPosition().getZ() >> 4;
		for (int cx = pcx - RADIUS; cx <= pcx + RADIUS; cx++) {
			for (int cz = pcz - RADIUS; cz <= pcz + RADIUS; cz++) {
				LevelChunk ch = level.getChunkSource().getChunkNow(cx, cz);
				if (ch == null) continue;
				for (BlockEntity be : ch.getBlockEntities().values()) {
					boolean chest = be instanceof ChestBlockEntity;
					boolean shulker = be instanceof ShulkerBoxBlockEntity;
					if (!chest && !shulker) continue;
					// a double chest is two block entities and each half counts as one chest
					BlockPos bp = be.getBlockPos();
					int fcx = cx, fcz = cz;
					Candidate c = acc.computeIfAbsent(key(cx, cz), kk -> new Candidate(fcx, fcz));
					if (chest) c.chests++; else c.shulkers++;
					c.containers.add(bp);
					c.include(bp.getX(), bp.getY(), bp.getZ());
				}
			}
		}

		// merge into persistent map (keeps growth history between recounts)
		Set<Long> keep = new HashSet<>();
		for (Map.Entry<Long, Candidate> en : acc.entrySet()) {
			Candidate f = en.getValue();
			if (f.total() < Config.threshold) continue;
			f.looseItems = items.getOrDefault(en.getKey(), 0);
			keep.add(en.getKey());
			Candidate existing = all.get(en.getKey());
			if (existing == null) all.put(en.getKey(), f);
			else existing.updateFrom(f);
		}
		all.entrySet().removeIf(en -> {
			Candidate c = en.getValue();
			boolean loaded = level.getChunkSource().getChunkNow(c.cx, c.cz) != null;
			c.chunkLoaded = loaded;
			// below threshold / emptied while loaded -> forget. Unloaded chunks are kept (for growth history).
			return loaded && !keep.contains(en.getKey());
		});
	}

	// ---------------------------------------------------------------- analysis

	private static void analyseOne(Minecraft mc, long now) {
		Candidate pick = null;
		for (Candidate c : all.values()) {
			if (!c.chunkLoaded) continue;
			if (now - c.lastAnalysisTick < REANALYSE_TICKS) continue;
			if (pick == null || c.lastAnalysisTick < pick.lastAnalysisTick) pick = c;
		}
		if (pick != null) analyse(mc.level, pick, now);
	}

	private static final class Plants {
		final Set<Long> seen = new HashSet<>();
		long score;
		int growing;
		int count;
	}

	private static void analyse(ClientLevel level, Candidate c, long now) {
		c.lastAnalysisTick = now;
		int yLo = c.minY - 24;
		int yHi = c.maxY + 24;

		BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
		BlockPos.MutableBlockPos q = new BlockPos.MutableBlockPos();
		Plants plants = new Plants();
		int loaded = 0, isoObsidian = 0, solid = 0, dangling = 0, autoScans = 0;

		for (int dcx = -1; dcx <= 1; dcx++) {
			for (int dcz = -1; dcz <= 1; dcz++) {
				LevelChunk ch = level.getChunkSource().getChunkNow(c.cx + dcx, c.cz + dcz);
				if (ch == null) continue;
				loaded++;
				int bx = (c.cx + dcx) << 4;
				int bz = (c.cz + dcz) << 4;
				for (int x = 0; x < 16; x++) {
					for (int z = 0; z < 16; z++) {
						for (int y = yLo; y <= yHi; y++) {
							p.set(bx + x, y, bz + z);
							BlockState s = ch.getBlockState(p);
							if (s.isAir()) continue;
							Block b = s.getBlock();
							if (b == Blocks.OBSIDIAN || b == Blocks.CRYING_OBSIDIAN) {
								if (isIsolatedObsidian(level, p, q)) isoObsidian++;
								continue;
							}
							if (b == Blocks.OBSERVER || b == Blocks.PISTON || b == Blocks.STICKY_PISTON) {
								if (autoScans < 60) {
									autoScans++;
									scanPlants(level, p.getX(), p.getY(), p.getZ(), plants, q);
								}
								continue;
							}
							if (NATURAL.contains(b) || !s.canOcclude()) continue;
							solid++;
							if (solidNeighbours(level, p, q) <= 1) dangling++;
						}
					}
				}
			}
		}

		// --- activity: did the farm blocks change since last look (same chunks loaded)?
		long score = plants.score;
		if (c.prevLoaded == loaded && c.growthScore >= 0 && score != c.growthScore && plants.count > 0) {
			c.lastChangeTime = now;
		}
		c.growthScore = score;
		c.prevLoaded = loaded;
		boolean observed = c.lastChangeTime >= 0 && now - c.lastChangeTime <= ACTIVE_WINDOW_TICKS;

		// --- organisation of the containers themselves
		double align = alignment(c);

		// --- raid score
		int raid = 0;
		StringBuilder raidWhy = new StringBuilder();
		if (isoObsidian >= 4) { raid += 2; raidWhy.append("stray obsidian ").append(isoObsidian).append("; "); }
		double dangleRatio = solid == 0 ? 0 : (double) dangling / solid;
		if (dangling >= 20 && dangleRatio >= 0.12) { raid += 2; raidWhy.append("debris ").append(Math.round(dangleRatio * 100)).append("%; "); }
		if (align >= 0 && align < 0.4) { raid += 1; raidWhy.append("containers scattered; "); }
		if (c.looseItems >= 15) { raid += 1; raidWhy.append("loose items ").append(c.looseItems).append("; "); }

		String facts = String.format("obs-stray=%d debris=%d/%d align=%s items=%d plants=%d growing=%d%s",
				isoObsidian, dangling, solid, align < 0 ? "n/a" : Math.round(align * 100) + "%",
				c.looseItems, plants.count, plants.growing, observed ? " CHANGE-SEEN" : "");

		if (raid >= 2) {
			c.status = Candidate.Status.RAIDED;
			c.why = "raided/messy (" + raidWhy + ") " + facts;
		} else if (observed) {
			c.status = Candidate.Status.ACTIVE;
			c.why = "farm blocks changed while watching " + facts;
		} else if (plants.growing >= 2) {
			c.status = Candidate.Status.GROWING;
			c.why = "farm mid-growth next to pistons/observers " + facts;
		} else {
			c.status = Candidate.Status.STORAGE;
			c.why = "no farm activity seen " + facts;
		}
	}

	private static boolean isIsolatedObsidian(ClientLevel level, BlockPos.MutableBlockPos p, BlockPos.MutableBlockPos q) {
		for (Direction d : Direction.values()) {
			Block nb = level.getBlockState(q.setWithOffset(p, d)).getBlock();
			if (nb == Blocks.OBSIDIAN || nb == Blocks.CRYING_OBSIDIAN) return false;
		}
		return true;
	}

	private static int solidNeighbours(ClientLevel level, BlockPos.MutableBlockPos p, BlockPos.MutableBlockPos q) {
		int n = 0;
		for (Direction d : Direction.values()) {
			if (level.getBlockState(q.setWithOffset(p, d)).canOcclude()) n++;
		}
		return n;
	}

	/** Fraction of containers lined up with another container (same height, same row/column within 3). -1 if too few. */
	private static double alignment(Candidate c) {
		List<BlockPos> l = new ArrayList<>(c.containers);
		if (l.size() < 4) return -1;
		int aligned = 0;
		for (int i = 0; i < l.size(); i++) {
			BlockPos a = l.get(i);
			for (int j = 0; j < l.size(); j++) {
				if (i == j) continue;
				BlockPos b = l.get(j);
				if (a.getY() != b.getY()) continue;
				int dx = Math.abs(a.getX() - b.getX());
				int dz = Math.abs(a.getZ() - b.getZ());
				if ((dx == 0 && dz <= 3) || (dz == 0 && dx <= 3)) { aligned++; break; }
			}
		}
		return (double) aligned / l.size();
	}

	/** Look in a small cube around a piston/observer for growing things (kelp, crops, cane, bamboo, cactus, amethyst buds). */
	private static void scanPlants(ClientLevel level, int x, int y, int z, Plants acc, BlockPos.MutableBlockPos q) {
		for (int dx = -3; dx <= 3; dx++) {
			for (int dy = -3; dy <= 3; dy++) {
				for (int dz = -3; dz <= 3; dz++) {
					q.set(x + dx, y + dy, z + dz);
					long k = q.asLong();
					if (acc.seen.contains(k)) continue;
					BlockState s = level.getBlockState(q);
					Block b = s.getBlock();
					int val;
					boolean grow = false;
					if (b == Blocks.KELP) {
						int age = s.hasProperty(BlockStateProperties.AGE_25) ? s.getValue(BlockStateProperties.AGE_25) : 25;
						val = 1;
						grow = age < 25;
					} else if (b == Blocks.KELP_PLANT || b == Blocks.SUGAR_CANE || b == Blocks.BAMBOO || b == Blocks.CACTUS) {
						val = 1;
					} else if (b instanceof CropBlock crop) {
						int age = s.hasProperty(BlockStateProperties.AGE_7) ? s.getValue(BlockStateProperties.AGE_7)
								: s.hasProperty(BlockStateProperties.AGE_3) ? s.getValue(BlockStateProperties.AGE_3) : 0;
						val = 1 + age;
						grow = !crop.isMaxAge(s);
					} else if (b == Blocks.SMALL_AMETHYST_BUD) {
						val = 1; grow = true;
					} else if (b == Blocks.MEDIUM_AMETHYST_BUD) {
						val = 2; grow = true;
					} else if (b == Blocks.LARGE_AMETHYST_BUD) {
						val = 3; grow = true;
					} else if (b == Blocks.AMETHYST_CLUSTER) {
						val = 4;
					} else {
						continue;
					}
					acc.seen.add(k);
					acc.score += val;
					acc.count++;
					if (grow) acc.growing++;
				}
			}
		}
	}
}
