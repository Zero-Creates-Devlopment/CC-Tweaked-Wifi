package net.mcreator.wifi;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.permissions.Permissions;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

import java.util.ArrayList;
import java.util.Locale;
import java.util.Map;

final class WifiAdminCommands {
	private WifiAdminCommands() {}

	static void register(RegisterCommandsEvent event) {
		CommandDispatcher<CommandSourceStack> dispatcher = event.getDispatcher();
		var command = Commands.literal("wifi")
				.requires(source -> source.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER));
		command.then(Commands.literal("routers").executes(context -> listRouters(context.getSource())));
		command.then(Commands.literal("info")
				.then(Commands.argument("wanIP", StringArgumentType.word())
						.executes(context -> showRouterInfo(context.getSource(),
								StringArgumentType.getString(context, "wanIP")))));
		command.then(Commands.literal("domains").executes(context -> listDomains(context.getSource())));
		command.then(Commands.literal("domain")
				.then(Commands.argument("name", StringArgumentType.word())
						.executes(context -> lookupDomain(context.getSource(),
								StringArgumentType.getString(context, "name")))));
		command.then(Commands.literal("clients")
				.then(Commands.argument("wanIP", StringArgumentType.word())
						.executes(context -> listClients(context.getSource(),
								StringArgumentType.getString(context, "wanIP")))));
		command.then(Commands.literal("kick")
				.then(Commands.argument("wanIP", StringArgumentType.word())
						.then(Commands.argument("lanIP", StringArgumentType.word())
								.executes(context -> kickClient(context.getSource(),
										StringArgumentType.getString(context, "wanIP"),
										StringArgumentType.getString(context, "lanIP"))))));
		command.then(Commands.literal("kickall")
				.then(Commands.argument("wanIP", StringArgumentType.word())
						.executes(context -> kickAllClients(context.getSource(),
								StringArgumentType.getString(context, "wanIP")))));
		dispatcher.register(command);
	}

	private static int listRouters(CommandSourceStack source) {
		ArrayList<WifiRouterNetwork.Router> routers = WifiRouterNetwork.forServer(source.getServer()).routers();
		source.sendSuccess(() -> Component.literal("Loaded Wi-Fi routers: " + routers.size()), false);
		for (WifiRouterNetwork.Router router : routers) {
			WifiRouterNetwork.RouterAdminInfo info = router.adminInfo();
			source.sendSuccess(() -> Component.literal(info.wanIP() + " | " + info.ssid()
					+ " | clients: " + info.connectedClients()), false);
		}
		return routers.size();
	}

	private static int showRouterInfo(CommandSourceStack source, String wanIP) {
		WifiRouterNetwork.Router router = WifiRouterNetwork.forServer(source.getServer()).router(wanIP);
		if (router == null) {
			source.sendFailure(Component.literal("No loaded Wi-Fi router has WAN address " + wanIP));
			return 0;
		}
		WifiRouterNetwork.RouterAdminInfo info = router.adminInfo();
		source.sendSuccess(() -> Component.literal("Router " + info.wanIP() + " | SSID: " + info.ssid()
				+ " | security: " + (info.passwordProtected() ? "password-protected" : "open")
				+ " | clients: " + info.connectedClients()
				+ " | domain reservations: " + info.domainReservations()), false);
		return 1;
	}

	private static int listDomains(CommandSourceStack source) {
		ArrayList<Map<String, Object>> records = WifiRouterNetwork.forServer(source.getServer()).records();
		source.sendSuccess(() -> Component.literal("Loaded Wi-Fi domain records: " + records.size()), false);
		for (Map<String, Object> record : records) {
			String status = Boolean.TRUE.equals(record.get("online")) ? "online" : "offline";
			source.sendSuccess(() -> Component.literal(record.get("domain") + " -> "
					+ (status.equals("online") ? record.get("lanIP") : status)
					+ " | router: " + record.get("wanIP")), false);
		}
		return records.size();
	}

	private static int lookupDomain(CommandSourceStack source, String domain) {
		String normalized = domain.toLowerCase(Locale.ROOT);
		ArrayList<Map<String, Object>> records = WifiRouterNetwork.forServer(source.getServer()).records();
		int matches = 0;
		for (Map<String, Object> record : records) {
			if (!normalized.equals(record.get("domain"))) {
				continue;
			}
			matches++;
			String status = Boolean.TRUE.equals(record.get("online"))
					? String.valueOf(record.get("lanIP")) : "offline";
			source.sendSuccess(() -> Component.literal(domain + " -> " + status
					+ " | router: " + record.get("wanIP")), false);
		}
		if (matches == 0) {
			source.sendFailure(Component.literal("No loaded router has a reservation for " + domain));
		}
		return matches;
	}

	private static int listClients(CommandSourceStack source, String wanIP) {
		WifiRouterNetwork.Router router = WifiRouterNetwork.forServer(source.getServer()).router(wanIP);
		if (router == null) {
			source.sendFailure(Component.literal("No loaded Wi-Fi router has WAN address " + wanIP));
			return 0;
		}
		ArrayList<WifiRouterNetwork.ClientAdminInfo> clients = router.adminClients();
		source.sendSuccess(() -> Component.literal("Clients on " + wanIP + ": " + clients.size()), false);
		for (WifiRouterNetwork.ClientAdminInfo client : clients) {
			String domain = client.domain().isEmpty() ? "" : " | domain: " + client.domain();
			source.sendSuccess(() -> Component.literal(client.lanIP() + " | " + client.connectionType() + domain),
					false);
		}
		return clients.size();
	}

	private static int kickClient(CommandSourceStack source, String wanIP, String lanIP) {
		WifiRouterNetwork.Router router = WifiRouterNetwork.forServer(source.getServer()).router(wanIP);
		if (router == null) {
			source.sendFailure(Component.literal("No loaded Wi-Fi router has WAN address " + wanIP));
			return 0;
		}
		if (!router.disconnectClient(lanIP)) {
			source.sendFailure(Component.literal("No connected client with LAN address " + lanIP + " on " + wanIP));
			return 0;
		}
		source.sendSuccess(() -> Component.literal("Disconnected " + lanIP + " from Wi-Fi router " + wanIP), true);
		return 1;
	}

	private static int kickAllClients(CommandSourceStack source, String wanIP) {
		WifiRouterNetwork.Router router = WifiRouterNetwork.forServer(source.getServer()).router(wanIP);
		if (router == null) {
			source.sendFailure(Component.literal("No loaded Wi-Fi router has WAN address " + wanIP));
			return 0;
		}
		int disconnected = router.disconnectAllClients();
		source.sendSuccess(() -> Component.literal("Disconnected " + disconnected
				+ " client(s) from Wi-Fi router " + wanIP), true);
		return disconnected;
	}
}
