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
 * Command to set a cape for a player using the VoidCube API.
 * Usage: /capeset <totp> <playername> <url>
 */
public class CapeSetCommand
{
	private static final String API_BASE_URL = "https://capes.voidcube.de/api";
	private static final ConcurrentHashMap<String, PendingCapeRequest> pendingRequests = new ConcurrentHashMap<>();
	private static final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
		final Thread thread = new Thread(r, "CapeSet-Confirmation-Timer");
		thread.setDaemon(true);
		return thread;
	});
	private static final Gson gson = new Gson();
	
	public static void register(final CommandDispatcher<FabricClientCommandSource> dispatcher)
	{
		dispatcher.register(
			literal("capeset")
				.then(argument("totp", StringArgumentType.word())
					.then(argument("playername", StringArgumentType.word())
						.then(argument("url", StringArgumentType.greedyString())
							.executes(CapeSetCommand::execute)
						)
					)
				)
		);
	}
	
	private static int execute(final CommandContext<FabricClientCommandSource> context)
	{
		final String totp = StringArgumentType.getString(context, "totp");
		final String playerName = StringArgumentType.getString(context, "playername");
		final String url = StringArgumentType.getString(context, "url");
		
		// Check if there's already a pending request for this player
		if (pendingRequests.containsKey(playerName.toLowerCase()))
		{
			context.getSource().sendError(
				Text.literal("[VoidCapes] There is already a pending cape request for player: " + playerName + ". Please wait for it to timeout or use /capeconfirm.")
			);
			return 0;
		}
		
		// First, check if the player already has a cape
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
				
				if (capeCheckResult.hasCape)
				{
					// Player has a cape, need confirmation
					final PendingCapeRequest request = new PendingCapeRequest(playerName, url, totp, context.getSource());
					pendingRequests.put(playerName.toLowerCase(), request);
					
					// Schedule automatic cleanup after 10 seconds
					scheduler.schedule(() -> {
						final PendingCapeRequest removed = pendingRequests.remove(playerName.toLowerCase());
						if (removed != null)
						{
							context.getSource().sendError(
								Text.literal("[VoidCapes] Cape replacement confirmation for ").formatted(Formatting.RED)
									.append(Text.literal(playerName).formatted(Formatting.YELLOW))
									.append(Text.literal(" timed out.").formatted(Formatting.RED))
							);
						}
					}, 10, TimeUnit.SECONDS);
					
					context.getSource().sendFeedback(
						Text.literal("[VoidCapes] ").formatted(Formatting.YELLOW)
							.append(Text.literal("Player ").formatted(Formatting.WHITE))
							.append(Text.literal(playerName).formatted(Formatting.GOLD))
							.append(Text.literal(" already has a cape! Run ").formatted(Formatting.WHITE))
							.append(Text.literal("/capeconfirm").formatted(Formatting.AQUA))
							.append(Text.literal(" within 10 seconds to replace it.").formatted(Formatting.WHITE))
					);
				}
				else
				{
					// Player doesn't have a cape, set it directly
					setCapeDirectly(playerName, url, totp, context.getSource());
				}
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
	
	private static void setCapeDirectly(final String playerName, final String url, final String totp, final FabricClientCommandSource source)
	{
		CompletableFuture.supplyAsync(() -> {
			final CredentialsManager.CredentialsData credentials = CapeLoginCommand.getStoredCredentials();
			if (credentials == null)
			{
				return new CapeSetResult(false, "No stored credentials found. Please use /capelogin first.");
			}
			
			return performCapeSet(playerName, url, totp, credentials.getUsername(), credentials.getPassword());
		})
		.thenAccept(result -> {
			if (result.success)
			{
				source.sendFeedback(
					Text.literal("[VoidCapes] ").formatted(Formatting.GREEN)
						.append(Text.literal("Cape set successfully for player: ").formatted(Formatting.WHITE))
						.append(Text.literal(playerName).formatted(Formatting.GOLD))
				);
			}
			else
			{
				source.sendError(
					Text.literal("[VoidCapes] Failed to set cape: " + result.error)
				);
			}
		})
		.exceptionally(throwable -> {
			source.sendError(
				Text.literal("[VoidCapes] Failed to set cape: " + throwable.getMessage())
			);
			return null;
		});
	}
	
	private static CapeSetResult performCapeSet(final String playerName, final String url, final String totp, final String username, final String password)
	{
		try
		{
			final HttpClient client = HttpClient.newBuilder()
				.connectTimeout(Duration.ofSeconds(15))
				.build();
			
			// Prepare form data
			final String formData = "url=" + URLEncoder.encode(url, StandardCharsets.UTF_8) +
				"&player_name=" + URLEncoder.encode(playerName, StandardCharsets.UTF_8) +
				"&username=" + URLEncoder.encode(username, StandardCharsets.UTF_8) +
				"&password=" + URLEncoder.encode(password, StandardCharsets.UTF_8) +
				"&totp=" + URLEncoder.encode(totp, StandardCharsets.UTF_8);
			
			final HttpRequest request = HttpRequest.newBuilder()
				.uri(URI.create(API_BASE_URL + "/download_cape"))
				.timeout(Duration.ofSeconds(15))
				.header("Content-Type", "application/x-www-form-urlencoded")
				.header("User-Agent", "VoidCapes-Minecraft-Mod/1.0")
				.POST(HttpRequest.BodyPublishers.ofString(formData))
				.build();
			
			final HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
			
			if (response.statusCode() != 200)
			{
				return new CapeSetResult(false, "HTTP " + response.statusCode() + ": " + response.body());
			}
			
			final JsonObject jsonResponse = gson.fromJson(response.body(), JsonObject.class);
			final boolean success = jsonResponse.has("success") && jsonResponse.get("success").getAsBoolean();
			
			if (!success)
			{
				final String error = jsonResponse.has("error") ? jsonResponse.get("error").getAsString() : "Unknown error";
				return new CapeSetResult(false, error);
			}
			
			final String message = jsonResponse.has("message") ? jsonResponse.get("message").getAsString() : "Cape set successfully";
			return new CapeSetResult(true, message);
		}
		catch (final Exception e)
		{
			return new CapeSetResult(false, e.getMessage());
		}
	}
	
	public static boolean hasPendingRequest(final String playerName)
	{
		return pendingRequests.containsKey(playerName.toLowerCase());
	}
	
	public static PendingCapeRequest getPendingRequest(final String playerName)
	{
		return pendingRequests.get(playerName.toLowerCase());
	}
	
	public static void removePendingRequest(final String playerName)
	{
		pendingRequests.remove(playerName.toLowerCase());
	}
	
	public static String getPendingRequestPlayerName()
	{
		return pendingRequests.keySet().stream().findFirst().orElse(null);
	}
	
	public static void confirmPendingRequest(final String playerName)
	{
		final PendingCapeRequest request = pendingRequests.remove(playerName.toLowerCase());
		if (request != null)
		{
			setCapeDirectly(request.playerName, request.url, request.totp, request.source);
		}
	}
	
	public static void shutdown()
	{
		if (scheduler != null && !scheduler.isShutdown())
		{
			scheduler.shutdown();
		}
	}
	
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
	
	private static class CapeSetResult
	{
		final boolean success;
		final String error;
		
		CapeSetResult(final boolean success, final String error)
		{
			this.success = success;
			this.error = error;
		}
	}
	
	public static class PendingCapeRequest
	{
		final String playerName;
		final String url;
		final String totp;
		final FabricClientCommandSource source;
		
		PendingCapeRequest(final String playerName, final String url, final String totp, final FabricClientCommandSource source)
		{
			this.playerName = playerName;
			this.url = url;
			this.totp = totp;
			this.source = source;
		}
	}
}
