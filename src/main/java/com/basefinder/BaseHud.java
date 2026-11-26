package com.basefinder;

import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;

/**
 * 2D overlay ESP. World positions are projected onto the screen by hand, so the markers show through walls
 * without any custom render pipeline. Best in first person with "FOV Effects" set to 0% in Video Settings.
 */
public final class BaseHud {
	private BaseHud() {}

	private static final int MAX_SHOWN = 12;

	private record Shown(Candidate c, double dist) {}

	public static void extract(GuiGraphicsExtractor g, DeltaTracker deltaTracker) {
		Minecraft mc = Minecraft.getInstance();
		if (!Config.enabled || mc.player == null || mc.level == null) return;
		if (!mc.options.getCameraType().isFirstPerson()) return;

		float pt = deltaTracker.getGameTimeDeltaPartialTick(false);
		int W = mc.getWindow().getGuiScaledWidth();
		int H = mc.getWindow().getGuiScaledHeight();
		Vec3 eye = mc.player.getEyePosition(pt);

		double yaw = Math.toRadians(mc.player.getViewYRot(pt));
		double pitch = Math.toRadians(mc.player.getViewXRot(pt));
		double fx = -Math.sin(yaw) * Math.cos(pitch), fy = -Math.sin(pitch), fz = Math.cos(yaw) * Math.cos(pitch);
		double rx = -Math.cos(yaw), ry = 0, rz = -Math.sin(yaw);
		double ux = ry * fz - rz * fy, uy = rz * fx - rx * fz, uz = rx * fy - ry * fx;
		double tanHalf = Math.tan(Math.toRadians(mc.options.fov().get()) / 2.0);
		double aspect = (double) W / H;

		List<Shown> list = new ArrayList<>();
		for (Candidate c : Scanner.all.values()) {
			if (!c.chunkLoaded) continue;
			if (!visible(c)) continue;
			double cxw = (c.minX + c.maxX + 1) / 2.0, cyw = (c.minY + c.maxY + 1) / 2.0, czw = (c.minZ + c.maxZ + 1) / 2.0;
			double d = Math.sqrt((cxw - eye.x) * (cxw - eye.x) + (cyw - eye.y) * (cyw - eye.y) + (czw - eye.z) * (czw - eye.z));
			list.add(new Shown(c, d));
		}
		list.sort((a, b) -> Double.compare(a.dist, b.dist));

		int shown = 0;
		for (Shown s : list) {
			if (shown++ >= MAX_SHOWN) break;
			Candidate c = s.c;
			int color = color(c.status);

			double cxw = (c.minX + c.maxX + 1) / 2.0, cyw = (c.minY + c.maxY + 1) / 2.0, czw = (c.minZ + c.maxZ + 1) / 2.0;
			double[] cs = camSpace(cxw - eye.x, cyw - eye.y, czw - eye.z, rx, ry, rz, ux, uy, uz, fx, fy, fz);
			String line1 = title(c.status);
			String line2 = c.chests + "C " + c.shulkers + "S " + c.stands + "A  " + Math.round(s.dist) + "m";

			boolean front = cs[2] > 0.1;
			double sx = 0, sy = 0;
			if (front) {
				sx = W / 2.0 + (cs[0] / (cs[2] * tanHalf * aspect)) * (W / 2.0);
				sy = H / 2.0 - (cs[1] / (cs[2] * tanHalf)) * (H / 2.0);
			}
			boolean onScreen = front && sx >= 0 && sx <= W && sy >= 0 && sy <= H;

			if (onScreen) {
				drawBox(g, c, eye, rx, ry, rz, ux, uy, uz, fx, fy, fz, tanHalf, aspect, W, H, color);
				int ix = (int) Math.round(sx), iy = (int) Math.round(sy);
				g.fill(ix - 2, iy - 2, ix + 3, iy + 3, color);
				label(g, mc, ix, iy - 24, line1, line2, color, W);
			} else {
				// edge marker pointing toward it (works for targets behind you too)
				double dx = cs[0], dy = -cs[1];
				double len = Math.sqrt(dx * dx + dy * dy);
				if (len < 1e-6) { dx = 0; dy = 1; len = 1; }
				dx /= len; dy /= len;
				double mx = W / 2.0 - 14, my = H / 2.0 - 22;
				double t = Math.min(Math.abs(dx) < 1e-6 ? 1e9 : mx / Math.abs(dx), Math.abs(dy) < 1e-6 ? 1e9 : my / Math.abs(dy));
				int ex = (int) Math.round(W / 2.0 + dx * t), ey = (int) Math.round(H / 2.0 + dy * t);
				g.fill(ex - 3, ey - 3, ex + 4, ey + 4, color);
				label(g, mc, ex, ey + 6, line1, line2, color, W);
			}
		}
	}

	private static boolean visible(Candidate c) {
		return switch (c.status) {
			case ACTIVE, GROWING -> true;
			case STORAGE -> !Config.strict;
			default -> false; // RAIDED and PENDING never drawn
		};
	}

	private static int color(Candidate.Status s) {
		return switch (s) {
			case ACTIVE -> 0xFF55FF55;
			case GROWING -> 0xFFFFDD55;
			default -> 0xFFAAAAAA;
		};
	}

	private static String title(Candidate.Status s) {
		return switch (s) {
			case ACTIVE -> "ACTIVE BASE";
			case GROWING -> "BASE (farm growing)";
			default -> "STASH";
		};
	}

	private static double[] camSpace(double dx, double dy, double dz,
	                                 double rx, double ry, double rz,
	                                 double ux, double uy, double uz,
	                                 double fx, double fy, double fz) {
		return new double[]{
				dx * rx + dy * ry + dz * rz,
				dx * ux + dy * uy + dz * uz,
				dx * fx + dy * fy + dz * fz};
	}

	private static void drawBox(GuiGraphicsExtractor g, Candidate c, Vec3 eye,
	                            double rx, double ry, double rz, double ux, double uy, double uz,
	                            double fx, double fy, double fz, double tanHalf, double aspect, int W, int H, int color) {
		double x0 = c.minX - 0.5, x1 = c.maxX + 1.5, y0 = c.minY - 0.5, y1 = c.maxY + 1.5, z0 = c.minZ - 0.5, z1 = c.maxZ + 1.5;
		double minSx = Double.MAX_VALUE, minSy = Double.MAX_VALUE, maxSx = -Double.MAX_VALUE, maxSy = -Double.MAX_VALUE;
		for (int i = 0; i < 8; i++) {
			double x = (i & 1) == 0 ? x0 : x1;
			double y = (i & 2) == 0 ? y0 : y1;
			double z = (i & 4) == 0 ? z0 : z1;
			double[] cs = camSpace(x - eye.x, y - eye.y, z - eye.z, rx, ry, rz, ux, uy, uz, fx, fy, fz);
			if (cs[2] <= 0.1) return; // a corner is behind the camera: skip the outline, marker still shows
			double sx = W / 2.0 + (cs[0] / (cs[2] * tanHalf * aspect)) * (W / 2.0);
			double sy = H / 2.0 - (cs[1] / (cs[2] * tanHalf)) * (H / 2.0);
			minSx = Math.min(minSx, sx); maxSx = Math.max(maxSx, sx);
			minSy = Math.min(minSy, sy); maxSy = Math.max(maxSy, sy);
		}
		int ax = (int) Math.max(-2000, Math.min(2000, Math.round(minSx)));
		int ay = (int) Math.max(-2000, Math.min(2000, Math.round(minSy)));
		int bx = (int) Math.max(-2000, Math.min(2000, Math.round(maxSx)));
		int by = (int) Math.max(-2000, Math.min(2000, Math.round(maxSy)));
		if (bx - ax < 6 || by - ay < 6) return;
		g.outline(ax, ay, bx - ax, by - ay, color);
	}

	private static void label(GuiGraphicsExtractor g, Minecraft mc, int cx, int y, String l1, String l2, int color, int W) {
		int w = Math.max(mc.font.width(l1), mc.font.width(l2)) + 6;
		int x = Math.max(2, Math.min(W - w - 2, cx - w / 2));
		g.fill(x, y, x + w, y + 22, 0x99000000);
		g.text(mc.font, l1, x + 3, y + 3, color, false);
		g.text(mc.font, l2, x + 3, y + 12, 0xFFFFFFFF, false);
	}
}
