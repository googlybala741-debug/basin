package com.basefinder;

import net.minecraft.core.BlockPos;

import java.util.ArrayList;
import java.util.List;

/** One chunk that holds enough chests/shulkers/armor stands to be worth analysing. */
public final class Candidate {
	public enum Status { PENDING, RAIDED, ACTIVE, GROWING, STORAGE }

	public final int cx, cz;
	public int chests, shulkers, stands;
	public int minX, minY, minZ, maxX, maxY, maxZ;
	public final List<BlockPos> containers = new ArrayList<>();
	public int looseItems;
	public boolean chunkLoaded = true;

	// analysis state / results
	public long lastAnalysisTick = Long.MIN_VALUE / 2;
	public Status status = Status.PENDING;
	public String why = "not analysed yet";
	public long growthScore = -1;
	public int prevLoaded = -1;
	public long lastChangeTime = -1;

	public Candidate(int cx, int cz) {
		this.cx = cx;
		this.cz = cz;
		minX = minY = minZ = Integer.MAX_VALUE;
		maxX = maxY = maxZ = Integer.MIN_VALUE;
	}

	public int total() {
		return chests + shulkers + stands;
	}

	public void include(int x, int y, int z) {
		minX = Math.min(minX, x);
		minY = Math.min(minY, y);
		minZ = Math.min(minZ, z);
		maxX = Math.max(maxX, x);
		maxY = Math.max(maxY, y);
		maxZ = Math.max(maxZ, z);
	}

	/** Refresh counts/positions from a fresh scan but keep analysis history. */
	public void updateFrom(Candidate f) {
		chests = f.chests;
		shulkers = f.shulkers;
		stands = f.stands;
		minX = f.minX; minY = f.minY; minZ = f.minZ;
		maxX = f.maxX; maxY = f.maxY; maxZ = f.maxZ;
		containers.clear();
		containers.addAll(f.containers);
		looseItems = f.looseItems;
	}
}
