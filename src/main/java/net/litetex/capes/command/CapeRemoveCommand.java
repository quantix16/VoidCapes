package net.litetex.capes.command;

import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;

import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.litetex.capes.config.CredentialsManager;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import static net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.argument;
import static net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.literal;


/**
 * Command to remove a cape for a player using the VoidCube API.
 * Usage: /caperemove <totp> <playername>
 */
public class CapeRemoveCommand
{
	private static final String API_BASE_URL = "https://capes.voidcube.de/api";
	private static final ConcurrentHashMap<String, PendingCapeRemovalRequest> pendingRemovalRequests = new ConcurrentHashMap<>();
	private static final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
		final Thread thread = new Thread(r, "CapeRemove-Confirmation-Timer");
		thread.setDaemon(true);
		return thread;
	});
	private static final Gson gson = new Gson();
	
	public static void register(final CommandDispatcher<FabricClientCommandSource> dispatcher)
	{
		dispatcher.register(
			literal("caperemove")
				.then(argument("totp", StringArgumentType.word())
					.then(argument("playername", StringArgumentType.word())
						.executes(CapeRemoveCommand::execute)
					)
				)
		);
	}
	
	private static int execute(final CommandContext<FabricClientCommandSource> context)
	{
		final String totp = StringArgumentType.getString(context, "totp");
		final String playerName = StringArgumentType.getString(context, "playername");
		
		// Get stored credentials
		final CredentialsManager.CredentialsData credentials = CapeLoginCommand.getStoredCredentials();
		if (credentials == null)
		{
			context.getSource().sendError(
				Text.literal("[VoidCapes] ").formatted(Formatting.RED)
					.append(Text.literal("No stored credentials found. Use /capelogin first.").formatted(Formatting.WHITE))
			);
			return 0;
		}
		
		// Check if there's already a pending removal request for this player
		if (pendingRemovalRequests.containsKey(playerName.toLowerCase()))
		{
			context.getSource().sendError(
				Text.literal("[VoidCapes] There is already a pending cape removal request for player: " + playerName + ". Please wait for it to timeout or use /capeconfirm.")
			);
			return 0;
		}
		
		// First, check if the player has a cape
		CompletableFuture.supplyAsync(() -> checkPlayerCape(playerName))
			.thenAccept(capeCheckResult -> {
				if (capeCheckResult == null)
				{
					context.getSource().sendError(
						Text.literal("[VoidCapes] Failed to check cape status for player: " + playerName)
					);
					return;
				}
				
				if (capeCheckResult.hasError())
				{
					context.getSource().sendError(
						Text.literal("[VoidCapes] Error checking cape: " + capeCheckResult.error)
					);
					return;
				}
				
				if (!capeCheckResult.playerExists)
				{
					context.getSource().sendError(
						Text.literal("[VoidCapes] Player not found: " + playerName)
					);
					return;
				}
				
				if (!capeCheckResult.hasCape)
				{
					context.getSource().sendError(
						Text.literal("[VoidCapes] Player ").formatted(Formatting.RED)
							.append(Text.literal(playerName).formatted(Formatting.YELLOW))
							.append(Text.literal(" does not have a cape to remove.").formatted(Formatting.RED))
					);
					return;
				}
				
				// Player has a cape, need confirmation
				final PendingCapeRemovalRequest request = new PendingCapeRemovalRequest(playerName, totp, context.getSource());
				pendingRemovalRequests.put(playerName.toLowerCase(), request);
				
				// Schedule automatic cleanup after 10 seconds
				scheduler.schedule(() -> {
					final PendingCapeRemovalRequest removed = pendingRemovalRequests.remove(playerName.toLowerCase());
					if (removed != null)
					{
						context.getSource().sendError(
							Text.literal("[VoidCapes] Cape removal confirmation for ").formatted(Formatting.RED)
								.append(Text.literal(playerName).formatted(Formatting.YELLOW))
								.append(Text.literal(" timed out.").formatted(Formatting.RED))
						);
					}
				}, 10, TimeUnit.SECONDS);
				
				context.getSource().sendFeedback(
					Text.literal("[VoidCapes] ").formatted(Formatting.YELLOW)
						.append(Text.literal("Player ").formatted(Formatting.WHITE))
						.append(Text.literal(playerName).formatted(Formatting.GOLD))
						.append(Text.literal(" has a cape! Run ").formatted(Formatting.WHITE))
						.append(Text.literal("/capeconfirm").formatted(Formatting.AQUA))
						.append(Text.literal(" within 10 seconds to remove it.").formatted(Formatting.WHITE))
				);
			})
			.exceptionally(throwable -> {
				context.getSource().sendError(
					Text.literal("[VoidCapes] Failed to check cape status: " + throwable.getMessage())
				);
				return null;
			});
		
		return 1;
	}
	
	private static CapeCheckResult checkPlayerCape(final String playerName)
	{
		try
		{
			final HttpClient client = HttpClient.newBuilder()
				.connectTimeout(Duration.ofSeconds(10))
				.build();
			
			final String encodedPlayerName = URLEncoder.encode(playerName, StandardCharsets.UTF_8);
			final HttpRequest request = HttpRequest.newBuilder()
				.uri(URI.create(API_BASE_URL + "/check_cape/" + encodedPlayerName))
				.timeout(Duration.ofSeconds(10))
				.header("User-Agent", "VoidCapes-Minecraft-Mod/1.0")
				.GET()
				.build();
			
			final HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
			
			if (response.statusCode() != 200)
			{
				return new CapeCheckResult(false, false, false, "HTTP " + response.statusCode());
			}
			
			final JsonObject jsonResponse = gson.fromJson(response.body(), JsonObject.class);
			final boolean success = jsonResponse.has("success") && jsonResponse.get("success").getAsBoolean();
			
			if (!success)
			{
				final String error = jsonResponse.has("error") ? jsonResponse.get("error").getAsString() : "Unknown error";
				return new CapeCheckResult(false, false, false, error);
			}
			
			final boolean playerExists = jsonResponse.has("player_exists") && jsonResponse.get("player_exists").getAsBoolean();
			final boolean hasCape = jsonResponse.has("has_cape") && jsonResponse.get("has_cape").getAsBoolean();
			
			return new CapeCheckResult(success, playerExists, hasCape, null);
		}
		catch (final Exception e)
		{
			return new CapeCheckResult(false, false, false, e.getMessage());
		}
	}
	
	public static void removeCapeDirectly(final String playerName, final String totp, final FabricClientCommandSource source)
	{
		CompletableFuture.supplyAsync(() -> {
			final CredentialsManager.CredentialsData credentials = CapeLoginCommand.getStoredCredentials();
			if (credentials == null)
			{
				return new CapeRemovalResult(false, "No stored credentials found. Please use /capelogin first.");
			}
			
			return performCapeRemoval(playerName, totp, credentials.getUsername(), credentials.getPassword());
		})
		.thenAccept(result -> {
			if (result.success)
			{
				source.sendFeedback(
					Text.literal("[VoidCapes] ").formatted(Formatting.GREEN)
						.append(Text.literal("Cape removed successfully for player: ").formatted(Formatting.WHITE))
						.append(Text.literal(playerName).formatted(Formatting.GOLD))
				);
			}
			else
			{
				source.sendError(
					Text.literal("[VoidCapes] Failed to remove cape: " + result.error)
				);
			}
		})
		.exceptionally(throwable -> {
			source.sendError(
				Text.literal("[VoidCapes] Failed to remove cape: " + throwable.getMessage())
			);
			return null;
		});
	}
	
	private static CapeRemovalResult performCapeRemoval(final String playerName, final String totp, final String username, final String password)
	{
		try
		{
			final HttpClient client = HttpClient.newBuilder()
				.connectTimeout(Duration.ofSeconds(10))
				.followRedirects(HttpClient.Redirect.NEVER) // Don't follow redirects automatically
				.build();
			
			// Prepare form data
			final String formData = "player_name=" + URLEncoder.encode(playerName, StandardCharsets.UTF_8)
				+ "&username=" + URLEncoder.encode(username, StandardCharsets.UTF_8)
				+ "&password=" + URLEncoder.encode(password, StandardCharsets.UTF_8)
				+ "&totp=" + URLEncoder.encode(totp, StandardCharsets.UTF_8);
			
			final HttpRequest request = HttpRequest.newBuilder()
				.uri(URI.create(API_BASE_URL + "/delete_cape"))
				.header("Content-Type", "application/x-www-form-urlencoded")
				.header("User-Agent", "VoidCapes-Minecraft-Client/1.0")
				.timeout(Duration.ofSeconds(30))
				.POST(HttpRequest.BodyPublishers.ofString(formData))
				.build();
			
			final HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
			
			// Handle redirects specifically
			if (response.statusCode() == 302 || response.statusCode() == 301 || response.statusCode() == 307 || response.statusCode() == 308)
			{
				final String location = response.headers().firstValue("Location").orElse("unknown");
				return new CapeRemovalResult(false, "Server redirected to: " + location + " (HTTP " + response.statusCode() + "). Check API endpoint URL.");
			}
			
			if (response.statusCode() == 200)
			{
				final JsonObject jsonResponse = gson.fromJson(response.body(), JsonObject.class);
				final boolean success = jsonResponse.has("success") && jsonResponse.get("success").getAsBoolean();
				
				if (success)
				{
					return new CapeRemovalResult(true, null);
				}
				else
				{
					final String error = jsonResponse.has("error") 
						? jsonResponse.get("error").getAsString() 
						: "Unknown error occurred";
					return new CapeRemovalResult(false, error);
				}
			}
			else if (response.statusCode() == 401)
			{
				return new CapeRemovalResult(false, "Authentication failed. Check your credentials and TOTP token.");
			}
			else if (response.statusCode() == 404)
			{
				return new CapeRemovalResult(false, "Player not found or no cape exists for this player.");
			}
			else
			{
				return new CapeRemovalResult(false, "Server error (HTTP " + response.statusCode() + "): " + response.body());
			}
		}
		catch (final Exception e)
		{
			return new CapeRemovalResult(false, "Network error: " + e.getMessage());
		}
	}
	
	// Static methods for managing pending removal requests
	public static boolean hasPendingRemovalRequest(final String playerName)
	{
		return pendingRemovalRequests.containsKey(playerName.toLowerCase());
	}
	
	public static PendingCapeRemovalRequest getPendingRemovalRequest(final String playerName)
	{
		return pendingRemovalRequests.get(playerName.toLowerCase());
	}
	
	public static void removePendingRemovalRequest(final String playerName)
	{
		pendingRemovalRequests.remove(playerName.toLowerCase());
	}
	
	public static String getPendingRemovalRequestPlayerName()
	{
		return pendingRemovalRequests.keySet().stream().findFirst().orElse(null);
	}
	
	public static void confirmPendingRemovalRequest(final String playerName)
	{
		final PendingCapeRemovalRequest request = pendingRemovalRequests.remove(playerName.toLowerCase());
		if (request != null)
		{
			removeCapeDirectly(request.playerName, request.totp, request.source);
		}
	}
	
	public static void shutdown()
	{
		if (scheduler != null && !scheduler.isShutdown())
		{
			scheduler.shutdown();
		}
	}
	
	// Helper classes
	private static class CapeCheckResult
	{
		final boolean playerExists;
		final boolean hasCape;
		final String error;
		
		CapeCheckResult(final boolean success, final boolean playerExists, final boolean hasCape, final String error)
		{
			this.playerExists = playerExists;
			this.hasCape = hasCape;
			this.error = error;
		}
		
		boolean hasError()
		{
			return error != null;
		}
	}
	
	private static class CapeRemovalResult
	{
		final boolean success;
		final String error;
		
		CapeRemovalResult(final boolean success, final String error)
		{
			this.success = success;
			this.error = error;
		}
	}
	
	public static class PendingCapeRemovalRequest
	{
		final String playerName;
		final String totp;
		final FabricClientCommandSource source;
		
		PendingCapeRemovalRequest(final String playerName, final String totp, final FabricClientCommandSource source)
		{
			this.playerName = playerName;
			this.totp = totp;
			this.source = source;
		}
	}
}
