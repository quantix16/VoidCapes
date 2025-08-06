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
		// Check for pending cape set requests first
		final String foundSetPlayerName = CapeSetCommand.getPendingRequestPlayerName();
		if (foundSetPlayerName != null)
		{
			final CapeSetCommand.PendingCapeRequest setRequest = CapeSetCommand.getPendingRequest(foundSetPlayerName);
			if (setRequest != null)
			{
				context.getSource().sendFeedback(
					Text.literal("[VoidCapes] ").formatted(Formatting.GREEN)
						.append(Text.literal("Confirming cape replacement for player: ").formatted(Formatting.WHITE))
						.append(Text.literal(setRequest.playerName).formatted(Formatting.GOLD))
				);
				
				// Confirm the pending cape set request
				CapeSetCommand.confirmPendingRequest(foundSetPlayerName);
				return 1;
			}
		}
		
		// Check for pending cape removal requests
		final String foundRemovalPlayerName = CapeRemoveCommand.getPendingRemovalRequestPlayerName();
		if (foundRemovalPlayerName != null)
		{
			final CapeRemoveCommand.PendingCapeRemovalRequest removalRequest = CapeRemoveCommand.getPendingRemovalRequest(foundRemovalPlayerName);
			if (removalRequest != null)
			{
				context.getSource().sendFeedback(
					Text.literal("[VoidCapes] ").formatted(Formatting.GREEN)
						.append(Text.literal("Confirming cape removal for player: ").formatted(Formatting.WHITE))
						.append(Text.literal(removalRequest.playerName).formatted(Formatting.GOLD))
				);
				
				// Confirm the pending cape removal request
				CapeRemoveCommand.confirmPendingRemovalRequest(foundRemovalPlayerName);
				return 1;
			}
		}
		
		// No pending requests found
		context.getSource().sendError(
			Text.literal("[VoidCapes] No pending cape requests found.")
		);
		return 0;
	}
}
