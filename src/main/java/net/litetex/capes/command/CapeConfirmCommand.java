package net.litetex.capes.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.context.CommandContext;

import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import static net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.literal;


/**
 * Command to confirm a pending cape replacement.
 * Usage: /capeconfirm
 */
public class CapeConfirmCommand
{
	public static void register(final CommandDispatcher<FabricClientCommandSource> dispatcher)
	{
		dispatcher.register(
			literal("capeconfirm")
				.executes(CapeConfirmCommand::execute)
		);
	}
	
	private static int execute(final CommandContext<FabricClientCommandSource> context)
	{
		// Check for any pending requests (we'll take the first one)
		// In practice, there should typically only be one pending request at a time
		final String foundPlayerName = CapeSetCommand.getPendingRequestPlayerName();
		
		if (foundPlayerName == null)
		{
			context.getSource().sendError(
				Text.literal("[VoidCapes] No pending cape requests found.")
			);
			return 0;
		}
		
		final CapeSetCommand.PendingCapeRequest request = CapeSetCommand.getPendingRequest(foundPlayerName);
		if (request == null)
		{
			context.getSource().sendError(
				Text.literal("[VoidCapes] No pending cape requests found.")
			);
			return 0;
		}
		
		context.getSource().sendFeedback(
			Text.literal("[VoidCapes] ").formatted(Formatting.GREEN)
				.append(Text.literal("Confirming cape replacement for player: ").formatted(Formatting.WHITE))
				.append(Text.literal(request.playerName).formatted(Formatting.GOLD))
		);
		
		// Confirm the pending request
		CapeSetCommand.confirmPendingRequest(foundPlayerName);
		
		return 1;
	}
}
