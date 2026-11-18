package com.ocarinaengine.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.ocarinaengine.gametype.OcarinaGameType;
import com.ocarinaengine.gametype.OcarinaGameTypeServer;
import java.util.Collection;
import java.util.Collections;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

/**
 * Registers /Gametype <Zelda|Minecraft> [player] (plus lowercase /gametype alias)
 * to switch between Ocarina Engine Zelda gameplay and vanilla Minecraft gameplay mid-game.
 */
public final class GametypeCommand {
	private GametypeCommand() {}

	public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
		dispatcher.register(buildTree("Gametype"));
		dispatcher.register(buildTree("gametype"));
	}

	private static LiteralArgumentBuilder<CommandSourceStack> buildTree(String literal) {
		return Commands.literal(literal)
				.requires(source -> source.hasPermission(2))
				.then(Commands.argument("type", StringArgumentType.word())
						.suggests((ctx, builder) ->
								SharedSuggestionProvider.suggest(new String[] {"Zelda", "Minecraft"}, builder))
						.executes(ctx -> {
							ServerPlayer self = ctx.getSource().getPlayerOrException();
							return execute(
									ctx.getSource(),
									StringArgumentType.getString(ctx, "type"),
									Collections.singletonList(self)
							);
						})
						.then(Commands.argument("player", EntityArgument.players())
								.executes(ctx -> execute(
										ctx.getSource(),
										StringArgumentType.getString(ctx, "type"),
										EntityArgument.getPlayers(ctx, "player")
								))));
	}

	private static int execute(CommandSourceStack source, String rawType, Collection<ServerPlayer> targets) {
		OcarinaGameType parsed = OcarinaGameType.parse(rawType);
		if (parsed == null) {
			source.sendFailure(Component.literal("Unknown game type '" + rawType + "'. Use Zelda or Minecraft."));
			return 0;
		}

		for (ServerPlayer player : targets) {
			OcarinaGameTypeServer.setPlayerGameType(player, parsed);
			if (source.getEntity() != player) {
				player.sendSystemMessage(Component.translatable(
						"ocarina_engine.command.gametype.changed_self",
						parsed.displayComponent()
				));
			}
		}

		if (targets.size() == 1) {
			ServerPlayer target = targets.iterator().next();
			source.sendSuccess(() -> Component.translatable(
					"ocarina_engine.command.gametype.success.single",
					target.getDisplayName(),
					parsed.displayComponent()
			), true);
		} else {
			source.sendSuccess(() -> Component.translatable(
					"ocarina_engine.command.gametype.success.multiple",
					targets.size(),
					parsed.displayComponent()
			), true);
		}
		return targets.size();
	}
}
