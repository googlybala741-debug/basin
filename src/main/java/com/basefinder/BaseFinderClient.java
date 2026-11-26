package com.basefinder;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

import java.util.ArrayList;
import java.util.List;

public class BaseFinderClient implements ClientModInitializer {
	public static final String MOD_ID = "basefinder";

	@Override
	public void onInitializeClient() {
		Config.load();

		ClientTickEvents.END_CLIENT_TICK.register(Scanner::tick);

		HudElementRegistry.attachElementBefore(
				VanillaHudElements.CHAT,
				Identifier.fromNamespaceAndPath(MOD_ID, "esp"),
				BaseHud::extract);

		ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) ->
				dispatcher.register(ClientCommands.literal("esp")
						.executes(ctx -> { status(ctx.getSource()); return 1; })
						.then(ClientCommands.literal("on").executes(ctx -> {
							Config.enabled = true;
							say(ctx.getSource(), "Base ESP ON (threshold " + Config.threshold + ", " + mode() + ").");
							return 1;
						}))
						.then(ClientCommands.literal("off").executes(ctx -> {
							Config.enabled = false;
							Scanner.reset();
							say(ctx.getSource(), "Base ESP OFF.");
							return 1;
						}))
						.then(ClientCommands.literal("threshold")
								.then(ClientCommands.argument("count", IntegerArgumentType.integer(2, 64)).executes(ctx -> {
									Config.threshold = IntegerArgumentType.getInteger(ctx, "count");
									Config.save();
									Scanner.reset();
									say(ctx.getSource(), "Threshold set to " + Config.threshold + " per chunk.");
									return 1;
								})))
						.then(ClientCommands.literal("strict").executes(ctx -> {
							Config.strict = true;
							Config.save();
							say(ctx.getSource(), "Strict mode: only bases with farm growth/activity are shown.");
							return 1;
						}))
						.then(ClientCommands.literal("relaxed").executes(ctx -> {
							Config.strict = false;
							Config.save();
							say(ctx.getSource(), "Relaxed mode: quiet stashes are shown too (raided ones still hidden).");
							return 1;
						}))
						.then(ClientCommands.literal("debug").executes(ctx -> {
							debug(ctx.getSource());
							return 1;
						}))));
	}

	private static String mode() {
		return Config.strict ? "strict" : "relaxed";
	}

	private static void say(FabricClientCommandSource src, String s) {
		src.sendFeedback(Component.literal(s));
	}

	private static void status(FabricClientCommandSource src) {
		say(src, "Base ESP is " + (Config.enabled ? "ON" : "OFF") + " | threshold " + Config.threshold
				+ " per chunk | " + mode() + " | candidates: " + Scanner.all.size());
		say(src, "/esp on|off, /esp threshold <n>, /esp strict|relaxed, /esp debug");
	}

	/** Explains every candidate chunk and why it was kept or rejected. */
	private static void debug(FabricClientCommandSource src) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.player == null) return;
		if (Scanner.all.isEmpty()) {
			say(src, "No chunk has " + Config.threshold + "+ chests/shulkers/armor stands loaded right now"
					+ (Config.enabled ? "." : " (ESP is off - /esp on first)."));
			return;
		}
		List<Candidate> list = new ArrayList<>(Scanner.all.values());
		list.sort((a, b) -> Double.compare(dist(mc, a), dist(mc, b)));
		int n = 0;
		for (Candidate c : list) {
			if (n++ >= 12) break;
			say(src, String.format("[%d,%d] %dC %dS %dA %dm -> %s: %s",
					c.cx, c.cz, c.chests, c.shulkers, c.stands, Math.round(dist(mc, c)), c.status, c.why));
		}
	}

	private static double dist(Minecraft mc, Candidate c) {
		double x = (c.cx << 4) + 8 - mc.player.getX();
		double z = (c.cz << 4) + 8 - mc.player.getZ();
		return Math.sqrt(x * x + z * z);
	}
}
