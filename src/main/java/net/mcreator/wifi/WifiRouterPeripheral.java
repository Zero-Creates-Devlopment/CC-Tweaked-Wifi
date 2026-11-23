package net.mcreator.wifi;

import dan200.computercraft.api.lua.IArguments;
import dan200.computercraft.api.lua.LuaFunction;
import dan200.computercraft.api.lua.LuaException;
import dan200.computercraft.api.peripheral.IComputerAccess;
import dan200.computercraft.api.peripheral.IPeripheral;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import org.jetbrains.annotations.Nullable;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

public final class WifiRouterPeripheral implements IPeripheral, WifiRouterNetwork.Router {
	private static final Pattern IPV4 = Pattern.compile(
			"192\\.168\\.1\\.(?:[1-9]|[1-9][0-9]|1[0-9]{2}|2[0-4][0-9]|25[0-4])");
	private static final String IPV4_OCTET = "(?:25[0-5]|2[0-4][0-9]|1[0-9]{2}|[1-9]?[0-9])";
	private static final Pattern WAN = Pattern.compile(IPV4_OCTET + "(?:\\." + IPV4_OCTET + "){3}");
	private static final Pattern DOMAIN = WifiDomainReservations.DOMAIN;
	private static final SecureRandom RANDOM = new SecureRandom();

	private final WifiRouterBlockEntity target;
	private final Map<Integer, IComputerAccess> attached = new HashMap<>();
	private final Map<Integer, Client> clients = new LinkedHashMap<>();
	private final Map<Integer, PendingAirJoin> pendingAirJoins = new HashMap<>();
	private final WifiDomainReservations reservations = new WifiDomainReservations();
	private String ssid = "MC-WiFi";
	private String password = "";
	@Nullable
	private String wanIP;
	private long wifiRevision;
	@Nullable
	private IComputerAccess bridge;

	WifiRouterPeripheral(WifiRouterBlockEntity target) {
		this.target = target;
	}

	@Override
	public String getType() {
		return "wifi_router";
	}

	@Override
	@Nullable
	public Object getTarget() {
		return target;
	}

	@Override
	public synchronized void attach(IComputerAccess computer) {
		attached.put(computer.getID(), computer);
	}

	@Override
	public synchronized void detach(IComputerAccess computer) {
		attached.remove(computer.getID());
		Client removed = clients.remove(computer.getID());
		if (bridge != null && bridge.getID() == computer.getID()) {
			bridge = null;
			clearAirSessions();
			clients.entrySet().removeIf(entry -> entry.getValue().computer == null);
			pendingAirJoins.clear();
		}
		if (removed != null && removed.domain != null) {
			notifyDomainsChanged();
		}
	}

	@Override
	public boolean equals(@Nullable IPeripheral other) {
		return other == this || other instanceof WifiRouterPeripheral peripheral && peripheral.target == target;
	}

	@LuaFunction(mainThread = true)
	public final synchronized Map<String, Object> getWifi() {
		Map<String, Object> result = new LinkedHashMap<>();
		result.put("ssid", ssid);
		result.put("wanIP", getWanIP());
		result.put("security", password.isEmpty() ? "open" : "password");
		result.put("connectedClients", clients.size());
		result.put("range", WifiMod.WIRELESS_RANGE.get());
		result.put("maxClients", WifiMod.MAX_CLIENTS.get());
		result.put("x", target.getBlockPos().getX());
		result.put("y", target.getBlockPos().getY());
		result.put("z", target.getBlockPos().getZ());
		return result;
	}

	@LuaFunction(mainThread = true)
	public final synchronized String getWanIP() {
		if (wanIP == null) {
			wanIP = randomWanIP();
			target.setChanged();
		}
		return wanIP;
	}

	private static String randomWanIP() {
		return RANDOM.nextInt(1, 255) + "." + RANDOM.nextInt(1, 255) + "."
				+ RANDOM.nextInt(1, 255) + "." + RANDOM.nextInt(1, 255);
	}

	@LuaFunction(mainThread = true)
	public final synchronized boolean configureWifi(IComputerAccess computer, String networkName, String networkPassword) {
		requireAttached(computer);
		if (networkName.isBlank() || networkName.length() > 32) {
			throw new IllegalArgumentException("SSID must be 1-32 characters");
		}
		if (!networkPassword.isEmpty() && (networkPassword.length() < 8 || networkPassword.length() > 63)) {
			throw new IllegalArgumentException("Password must be empty or 8-63 characters");
		}
		if (ssid.equals(networkName) && password.equals(networkPassword)) {
			return true;
		}

		String previousSsid = ssid;
		clearAirSessions();
		ssid = networkName;
		password = networkPassword;
		wifiRevision++;
		for (Client client : clients.values()) {
			if (client.computer != null) {
				client.computer.queueEvent("wifi_disconnected", previousSsid);
			}
		}
		clients.clear();
		pendingAirJoins.clear();
		notifyDomainsChanged();
		target.setChanged();
		return true;
	}

	@LuaFunction(mainThread = true)
	public final synchronized boolean connect(IComputerAccess computer, String networkName, String networkPassword)
			throws LuaException {
		if (!attached.containsKey(computer.getID()) || !ssid.equals(networkName) || !password.equals(networkPassword)) {
			return false;
		}
		if (!clients.containsKey(computer.getID()) && clients.size() >= WifiMod.MAX_CLIENTS.get()) {
			return false;
		}
		Client client = clients.computeIfAbsent(computer.getID(), id -> new Client(computer));
		if (client.ip == null) {
			client.ip = allocateAddress();
		}
		computer.queueEvent("wifi_connected", ssid, client.ip);
		return true;
	}

	@LuaFunction(mainThread = true)
	public final synchronized boolean disconnect(IComputerAccess computer) {
		requireAttached(computer);
		Client client = clients.remove(computer.getID());
		if (client == null) {
			return false;
		}
		computer.queueEvent("wifi_disconnected", ssid);
		if (client.domain != null) {
			notifyDomainsChanged();
		}
		return true;
	}

	@LuaFunction(mainThread = true)
	public final synchronized String getIP(IComputerAccess computer) {
		Client client = clients.get(computer.getID());
		return client == null ? "" : client.ip;
	}

	@LuaFunction(mainThread = true)
	public final synchronized boolean setStaticIP(IComputerAccess computer, String address) {
		Client client = clients.get(computer.getID());
		if (client == null || !IPV4.matcher(address).matches() || isAddressInUse(address, computer.getID())) {
			return false;
		}
		client.ip = address;
		if (client.domain != null) {
			notifyDomainsChanged();
		}
		return true;
	}

	@LuaFunction(mainThread = true)
	public final synchronized boolean registerDomain(IComputerAccess computer, String domain, String token) {
		Client client = clients.get(computer.getID());
		return registerClientDomain(client, domain, token);
	}

	@LuaFunction(mainThread = true)
	public final synchronized boolean releaseDomain(IComputerAccess computer, String domain, String token) {
		return releaseClientDomain(clients.get(computer.getID()), domain, token);
	}

	@LuaFunction(mainThread = true)
	public final synchronized String resolve(String domain) {
		WifiRouterNetwork.Route route = resolveDomainRoute(domain);
		return route == null ? "" : route.lanIP();
	}

	@LuaFunction(mainThread = true)
	public final synchronized boolean send(IComputerAccess computer, IArguments arguments) throws LuaException {
		requireArgumentCount(arguments, 2, "recipient, message");
		String recipient = arguments.getString(0);
		Object message = getRednetMessage(arguments, 1);
		Client sender = clients.get(computer.getID());
		if (sender == null) {
			return false;
		}
		if (!IPV4.matcher(recipient).matches() && resolveDomainRoute(recipient) == null) {
			return false;
		}
		Client destination = clients.values().stream()
				.filter(client -> recipient.equals(client.ip) || recipient.equalsIgnoreCase(client.domain))
				.findFirst()
				.orElse(null);
		if (destination == null) {
			return sendWanPacket(computer.getID(), sender, recipient, null, message);
		}
		if (destination.computerID == computer.getID() || (destination.computer == null && bridge == null)) {
			return false;
		}
		while (destination.inbox.size() >= WifiMod.MAX_INBOX_SIZE.get()) {
			destination.inbox.removeFirst();
		}
		Map<String, Object> packet = new LinkedHashMap<>();
		packet.put("from", sender.ip);
		packet.put("fromWan", getWanIP());
		packet.put("fromLan", sender.ip);
		packet.put("domain", sender.domain == null ? "" : sender.domain);
		packet.put("message", message);
		destination.inbox.addLast(packet);
		if (destination.computer != null) {
			destination.computer.queueEvent("wifi_message", sender.ip, message);
		}
		return true;
	}

	@LuaFunction(mainThread = true)
	public final synchronized boolean setBridge(IComputerAccess computer, boolean enabled) {
		if (!attached.containsKey(computer.getID())) {
			return false;
		}
		if (enabled) {
			if (bridge != null && bridge.getID() != computer.getID()) {
				return false;
			}
			bridge = computer;
			return true;
		}
		if (bridge != null && bridge.getID() == computer.getID()) {
			bridge = null;
			clearAirSessions();
			clients.entrySet().removeIf(entry -> entry.getValue().computer == null);
			pendingAirJoins.clear();
			return true;
		}
		return false;
	}

	@LuaFunction(mainThread = true)
	public final synchronized Map<String, Object> getLinkInfo(IComputerAccess computer) {
		requireBridge(computer);
		Map<String, Object> result = new LinkedHashMap<>();
		result.put("ssid", ssid);
		result.put("wanIP", getWanIP());
		result.put("revision", wifiRevision);
		return result;
	}

	@LuaFunction(mainThread = true)
	public final synchronized ArrayList<Map<String, Object>> getDomainRecords(IComputerAccess computer) {
		requireBridge(computer);
		ArrayList<Map<String, Object>> result = new ArrayList<>();
		for (String domain : reservations.domains()) {
			Map<String, Object> record = new LinkedHashMap<>();
			record.put("domain", domain);
			record.put("lanIP", activeDomainAddress(domain));
			result.add(record);
		}
		return result;
	}

	@LuaFunction(mainThread = true)
	public final synchronized ArrayList<Map<String, Object>> getKnownDomainRecords(IComputerAccess computer) {
		requireAttached(computer);
		return network().records();
	}

	@Override
	public synchronized Map<String, String> localDomains() {
		Map<String, String> domains = new LinkedHashMap<>();
		for (String domain : reservations.domains()) {
			domains.put(domain, activeDomainAddress(domain));
		}
		return domains;
	}

	@LuaFunction(mainThread = true)
	public final synchronized Map<String, Object> beginAirJoin(IComputerAccess computer, int clientComputerId,
			String requestedSsid, String clientNonce) throws LuaException {
		requireBridge(computer);
		Map<String, Object> result = new LinkedHashMap<>();
		if (clientComputerId < 0 || !ssid.equals(requestedSsid)
				|| clientNonce.isEmpty() || clientNonce.length() > 128) {
			return result;
		}
		long now = System.currentTimeMillis();
		pendingAirJoins.values().removeIf(pending -> pending.expiresAt < now);
		if (pendingAirJoins.size() >= 256 && !pendingAirJoins.containsKey(clientComputerId)) {
			return result;
		}

		byte[] challengeBytes = new byte[32];
		RANDOM.nextBytes(challengeBytes);
		String challenge = HexFormat.of().formatHex(challengeBytes);
		pendingAirJoins.put(clientComputerId,
				new PendingAirJoin(challenge, clientNonce, wifiRevision, now + 15_000));
		result.put("challenge", challenge);
		result.put("wanIP", getWanIP());
		result.put("ssid", ssid);
		return result;
	}

	@LuaFunction(mainThread = true)
	public final synchronized Map<String, Object> completeAirJoin(IComputerAccess computer, int clientComputerId,
			String challenge, String proof) throws LuaException {
		requireBridge(computer);
		Map<String, Object> result = new LinkedHashMap<>();
		PendingAirJoin pending = pendingAirJoins.remove(clientComputerId);
		if (pending == null || pending.expiresAt < System.currentTimeMillis()
				|| !pending.challenge.equals(challenge) || clientComputerId < 0
				|| pending.wifiRevision != wifiRevision) {
			return result;
		}
		byte[] supplied;
		try {
			if (proof.length() != 64) {
				return result;
			}
			supplied = HexFormat.of().parseHex(proof);
		} catch (IllegalArgumentException exception) {
			return result;
		}
		if (!MessageDigest.isEqual(createAirJoinProofBytes(challenge, clientComputerId, pending.clientNonce), supplied)
				|| (clients.containsKey(clientComputerId)
				&& clients.get(clientComputerId).computer != null)
				|| (!clients.containsKey(clientComputerId) && clients.size() >= WifiMod.MAX_CLIENTS.get())) {
			return result;
		}

		Client client = clients.get(clientComputerId);
		if (client == null) {
			try {
				client = new Client(clientComputerId);
				client.ip = allocateAddress();
			} catch (IllegalStateException exception) {
				return result;
			}
			clients.put(clientComputerId, client);
		}
		result.put("ip", client.ip);
		result.put("wanIP", getWanIP());
		result.put("ssid", ssid);
		result.put("success", true);
		WifiAirSessions.create(getServer(), getWanIP(), clientComputerId, !password.isEmpty());
		return result;
	}

	@LuaFunction(mainThread = true)
	public final synchronized String sealAir(IComputerAccess computer, int clientComputerId, String plaintext)
			throws LuaException {
		requireBridge(computer);
		return WifiAirSessions.seal(getServer(), getWanIP(), clientComputerId, false, plaintext);
	}

	@LuaFunction(mainThread = true)
	public final synchronized String openAir(IComputerAccess computer, int clientComputerId, String packet)
			throws LuaException {
		requireBridge(computer);
		return WifiAirSessions.open(getServer(), getWanIP(), clientComputerId, true, packet);
	}

	@LuaFunction(mainThread = true)
	public final synchronized Map<String, Object> processAirDomainRequest(IComputerAccess computer, int clientComputerId,
			String packet)
			throws LuaException {
		requireBridge(computer);
		String plaintext = WifiAirSessions.open(getServer(), getWanIP(), clientComputerId, true, packet, true);
		String[] fields = plaintext.split("\u0000", -1);
		if (fields.length != 4 || fields[0].length() > 32 || fields[1].length() > 253
				|| fields[2].length() != 64 || fields[3].isEmpty() || fields[3].length() > 128) {
			throw new LuaException("Invalid domain request");
		}
		Client client = clients.get(clientComputerId);
		boolean success = client != null && client.computer == null && switch (fields[0]) {
			case "register_domain" -> registerClientDomain(client, fields[1], fields[2]);
			case "release_domain" -> releaseClientDomain(client, fields[1], fields[2]);
			default -> false;
		};
		Map<String, Object> response = new LinkedHashMap<>();
		response.put("requestId", fields[3]);
		response.put("success", success);
		if (!success) {
			response.put("reason", "Domain request was rejected");
		}
		return response;
	}

	@LuaFunction(mainThread = true)
	public final synchronized boolean sendAir(IComputerAccess computer, IArguments arguments) throws LuaException {
		requireBridge(computer);
		requireArgumentCount(arguments, 3, "client computer ID, recipient, message");
		int clientComputerId = arguments.getInt(0);
		String recipient = arguments.getString(1);
		Object message = getRednetMessage(arguments, 2);
		Client sender = clients.get(clientComputerId);
		if (sender == null || sender.computer != null) {
			return false;
		}
		if (!IPV4.matcher(recipient).matches() && resolveDomainRoute(recipient) == null) {
			return false;
		}
		Client destination = clients.values().stream()
				.filter(client -> recipient.equals(client.ip) || recipient.equalsIgnoreCase(client.domain))
				.findFirst()
				.orElse(null);
		if (destination == null) {
			return sendWanPacket(clientComputerId, sender, recipient, null, message);
		}
		if (destination.computerID == clientComputerId || (destination.computer == null && bridge == null)) {
			return false;
		}

		Map<String, Object> packet = new LinkedHashMap<>();
		packet.put("from", sender.ip);
		packet.put("fromWan", getWanIP());
		packet.put("fromLan", sender.ip);
		packet.put("domain", sender.domain == null ? "" : sender.domain);
		packet.put("message", message);
		while (destination.inbox.size() >= WifiMod.MAX_INBOX_SIZE.get()) {
			destination.inbox.removeFirst();
		}
		destination.inbox.addLast(packet);
		if (destination.computer != null) {
			destination.computer.queueEvent("wifi_message", sender.ip, message);
		}
		return true;
	}

	@LuaFunction(mainThread = true)
	@Nullable
	public final synchronized Map<String, Object> receiveAir(IComputerAccess computer, int clientComputerId) {
		requireBridge(computer);
		Client client = clients.get(clientComputerId);
		return client == null || client.computer != null ? null : client.inbox.pollFirst();
	}

	@LuaFunction(mainThread = true)
	public final synchronized boolean disconnectAir(IComputerAccess computer, int clientComputerId) {
		requireBridge(computer);
		Client client = clients.get(clientComputerId);
		if (client == null || client.computer != null) {
			return false;
		}
		clients.remove(clientComputerId);
		pendingAirJoins.remove(clientComputerId);
		if (target.getLevel() != null && target.getLevel().getServer() != null) {
			WifiAirSessions.remove(target.getLevel().getServer(), getWanIP(), clientComputerId);
		}
		notifyDomainsChanged();
		return true;
	}

	@LuaFunction(mainThread = true)
	public final synchronized Map<String, Object> getNetworkConfig() {
		Map<String, Object> result = new LinkedHashMap<>();
		result.put("wirelessRange", WifiMod.WIRELESS_RANGE.get());
		result.put("maxClients", WifiMod.MAX_CLIENTS.get());
		result.put("maxInboxSize", WifiMod.MAX_INBOX_SIZE.get());
		result.put("announceIntervalSeconds", WifiMod.ANNOUNCE_INTERVAL_SECONDS.get());
		result.put("peerTimeoutSeconds", WifiMod.PEER_TIMEOUT_SECONDS.get());
		return result;
	}

	@LuaFunction(mainThread = true)
	public final synchronized ArrayList<Map<String, Object>> listNearbyRouters(IComputerAccess computer)
			throws LuaException {
		requireAttached(computer);

		int range = WifiMod.WIRELESS_RANGE.get();
		int centerX = target.getBlockPos().getX();
		int centerY = target.getBlockPos().getY();
		int centerZ = target.getBlockPos().getZ();
		int minChunkX = Math.floorDiv((int) Math.floor(centerX - range), 16);
		int maxChunkX = Math.floorDiv((int) Math.floor(centerX + range), 16);
		int minChunkZ = Math.floorDiv((int) Math.floor(centerZ - range), 16);
		int maxChunkZ = Math.floorDiv((int) Math.floor(centerZ + range), 16);
		double maxDistanceSquared = (double) range * range;
		ArrayList<Map<String, Object>> result = new ArrayList<>();

		for (int chunkX = minChunkX; chunkX <= maxChunkX; chunkX++) {
			for (int chunkZ = minChunkZ; chunkZ <= maxChunkZ; chunkZ++) {
				ChunkAccess chunkAccess = target.getLevel().getChunkSource()
						.getChunk(chunkX, chunkZ, ChunkStatus.FULL, false);
				if (!(chunkAccess instanceof LevelChunk chunk)) {
					continue;
				}
				for (BlockEntity blockEntity : chunk.getBlockEntities().values()) {
					if (blockEntity instanceof WifiRouterBlockEntity routerEntity
							&& !routerEntity.isRemoved() && routerEntity.getLevel() == target.getLevel()) {
						WifiRouterPeripheral router = routerEntity.peripheral();
						BlockPos pos = routerEntity.getBlockPos();
						double dx = pos.getX() + 0.5 - centerX - 0.5;
						double dy = pos.getY() + 0.5 - centerY - 0.5;
						double dz = pos.getZ() + 0.5 - centerZ - 0.5;
						double distanceSquared = dx * dx + dy * dy + dz * dz;
						if (distanceSquared <= maxDistanceSquared) {
							Map<String, Object> entry = new LinkedHashMap<>();
							entry.put("wanIP", router.getWanIP());
							entry.put("ssid", router.ssid);
							entry.put("security", router.password.isEmpty() ? "open" : "password");
							entry.put("x", pos.getX());
							entry.put("y", pos.getY());
							entry.put("z", pos.getZ());
							entry.put("distance", Math.sqrt(distanceSquared));
							entry.put("connectedClients", router.clients.size());
							result.add(entry);
						}
					}
				}
			}
		}
		result.sort(Comparator.comparingDouble(entry -> ((Number) entry.get("distance")).doubleValue()));
		return result;
	}

	@LuaFunction(mainThread = true)
	public final synchronized boolean sendWAN(IComputerAccess computer, IArguments arguments) throws LuaException {
		requireArgumentCount(arguments, 3, "destination, LAN IP, message");
		String destinationWan = arguments.getString(0);
		@Nullable String destinationLan = getOptionalString(arguments, 1, "LAN IP");
		Object message = getRednetMessage(arguments, 2);
		Client sender = clients.get(computer.getID());
		return sendWanPacket(computer.getID(), sender, destinationWan, destinationLan, message);
	}

	@LuaFunction(mainThread = true)
	public final synchronized boolean sendAirWAN(IComputerAccess computer, IArguments arguments) throws LuaException {
		requireBridge(computer);
		requireArgumentCount(arguments, 4, "client computer ID, destination, LAN IP, message");
		int clientComputerId = arguments.getInt(0);
		String destinationWan = arguments.getString(1);
		@Nullable String destinationLan = getOptionalString(arguments, 2, "LAN IP");
		Object message = getRednetMessage(arguments, 3);
		Client sender = clients.get(clientComputerId);
		if (sender == null || sender.computer != null) {
			return false;
		}
		return sendWanPacket(clientComputerId, sender, destinationWan, destinationLan, message);
	}

	private boolean sendWanPacket(int sourceComputerId, @Nullable Client sender, String destinationWan,
			@Nullable String destinationLan, Object message) throws LuaException {
		if (sender == null) {
			return false;
		}
		String destinationDomain = "";
		if (destinationLan == null) {
			WifiRouterNetwork.Route route = resolveDomainRoute(destinationWan);
			if (route == null) {
				return false;
			}
			destinationDomain = destinationWan.toLowerCase(Locale.ROOT);
			destinationWan = route.wanIP();
			destinationLan = route.lanIP();
		} else if (DOMAIN.matcher(destinationWan.toLowerCase(Locale.ROOT)).matches()
				|| !WAN.matcher(destinationWan).matches() || !IPV4.matcher(destinationLan).matches()) {
			return false;
		}
		if (destinationWan.equals(getWanIP())) {
			String targetLanIP = destinationLan;
			Client destination = clients.values().stream()
					.filter(client -> targetLanIP.equals(client.ip))
					.findFirst()
					.orElse(null);
			if (destination == null || destination.computerID == sourceComputerId) {
				return false;
			}
			Map<String, Object> localPacket = new LinkedHashMap<>();
			localPacket.put("from", sender.ip);
			localPacket.put("fromWan", getWanIP());
			localPacket.put("fromLan", sender.ip);
			localPacket.put("domain", sender.domain == null ? "" : sender.domain);
			localPacket.put("message", message);
			while (destination.inbox.size() >= WifiMod.MAX_INBOX_SIZE.get()) {
				destination.inbox.removeFirst();
			}
			destination.inbox.addLast(localPacket);
			if (destination.computer != null) {
				destination.computer.queueEvent("wifi_message", sender.ip, message);
			}
			return true;
		}

		Map<String, Object> payload = new LinkedHashMap<>();
		payload.put("destinationWan", destinationWan);
		payload.put("destinationLan", destinationLan);
		payload.put("destinationDomain", destinationDomain);
		payload.put("sourceWan", getWanIP());
		payload.put("sourceLan", sender.ip);
		payload.put("sourceDomain", sender.domain == null ? "" : sender.domain);
		payload.put("message", message);
		try {
			byte[] serialized = WifiWanPayloadCodec.encode(payload);
			return network().deliver(destinationWan, WifiWanPayloadCodec.decode(serialized));
		} catch (java.io.IOException exception) {
			throw new LuaException("Unable to encode WAN packet: " + exception.getMessage());
		}
	}

	@Override
	public synchronized boolean deliverWan(Map<String, Object> payload) {
		String payloadSourceWan = payload.get("sourceWan") instanceof String value ? value : "";
		String sourceLan = payload.get("sourceLan") instanceof String value ? value : "";
		String payloadDestinationWan = payload.get("destinationWan") instanceof String value ? value : "";
		String destinationLan = payload.get("destinationLan") instanceof String value ? value : "";
		String destinationDomain = payload.get("destinationDomain") instanceof String value ? value : null;
		String sourceDomain = payload.get("sourceDomain") instanceof String value ? value : "";
		Object message = payload.get("message");
		if (!WAN.matcher(payloadSourceWan).matches() || !getWanIP().equals(payloadDestinationWan)
				|| !IPV4.matcher(sourceLan).matches() || !IPV4.matcher(destinationLan).matches()
				|| destinationDomain == null || (!destinationDomain.isEmpty()
						&& !DOMAIN.matcher(destinationDomain).matches())
				|| sourceDomain.length() > 253 || !isRednetValue(message, new IdentityHashMap<>())) {
			return false;
		}
		Client destination = clients.values().stream()
				.filter(client -> destinationLan.equals(client.ip))
				.findFirst()
				.orElse(null);
		if (destination == null || !matchesDestinationDomain(destinationDomain, destination.domain)) {
			return false;
		}

		Map<String, Object> packet = new LinkedHashMap<>();
		packet.put("from", sourceLan);
		packet.put("fromWan", payloadSourceWan);
		packet.put("fromLan", sourceLan);
		packet.put("domain", sourceDomain);
		packet.put("message", message);
		destination.inbox.addLast(packet);
		while (destination.inbox.size() > WifiMod.MAX_INBOX_SIZE.get()) {
			destination.inbox.removeFirst();
		}
		if (destination.computer != null) {
			destination.computer.queueEvent("wifi_message", payloadSourceWan, sourceLan, message);
		}
		return true;
	}

	static boolean matchesDestinationDomain(String destinationDomain, @Nullable String clientDomain) {
		return destinationDomain.isEmpty() || destinationDomain.equals(clientDomain);
	}

	@LuaFunction(mainThread = true)
	@Nullable
	public final synchronized Map<String, Object> receive(IComputerAccess computer) {
		Client client = clients.get(computer.getID());
		return client == null ? null : client.inbox.pollFirst();
	}

	@LuaFunction(mainThread = true)
	public final synchronized ArrayList<Map<String, Object>> getClients() {
		ArrayList<Map<String, Object>> result = new ArrayList<>();
		for (Client client : clients.values()) {
			Map<String, Object> entry = new LinkedHashMap<>();
			entry.put("computerId", client.computerID);
			entry.put("ip", client.ip);
			entry.put("lanIP", client.ip);
			entry.put("domain", client.domain == null ? "" : client.domain);
			entry.put("connectionType", client.computer == null ? "wireless" : "wired");
			result.add(entry);
		}
		return result;
	}

	void save(ValueOutput output) {
		output.putString("Ssid", ssid);
		output.putString("Password", password);
		if (wanIP != null) {
			output.putString("WanIP", wanIP);
		}
		reservations.save(output);
	}

	synchronized void load(ValueInput input) {
		ssid = input.getStringOr("Ssid", ssid);
		password = input.getStringOr("Password", password);
		String savedWanIP = input.getStringOr("WanIP", "");
		wanIP = WAN.matcher(savedWanIP).matches() ? savedWanIP : null;
		reservations.load(input);
	}

	private void requireAttached(IComputerAccess computer) {
		if (!attached.containsKey(computer.getID())) {
			throw new IllegalStateException("Computer is not attached to this router");
		}
	}

	private void clearAirSessions() {
		if (target.getLevel() != null && target.getLevel().getServer() != null) {
			WifiAirSessions.removeRouter(target.getLevel().getServer(), getWanIP());
		}
	}

	private boolean registerClientDomain(@Nullable Client client, String domain, String token) {
		String normalized = domain.toLowerCase(Locale.ROOT);
		if (client == null || !DOMAIN.matcher(normalized).matches()
				|| (reservations.contains(normalized) && !reservations.owns(normalized, token))
				|| (!reservations.contains(normalized) && network().hasOtherClaim(normalized, getWanIP()))) {
			return false;
		}
		if (clients.values().stream()
				.anyMatch(other -> other != client && normalized.equals(other.domain))) {
			return false;
		}
		if (!reservations.reserve(normalized, token)) {
			return false;
		}
		client.domain = normalized;
		target.setChanged();
		notifyDomainsChanged();
		return true;
	}

	private boolean releaseClientDomain(@Nullable Client client, String domain, String token) {
		String normalized = domain.toLowerCase(Locale.ROOT);
		if (client == null || !reservations.release(normalized, token)) {
			return false;
		}
		for (Client current : clients.values()) {
			if (normalized.equals(current.domain)) {
				current.domain = null;
			}
		}
		target.setChanged();
		notifyDomainsChanged();
		return true;
	}

	private String activeDomainAddress(String domain) {
		return clients.values().stream().filter(client -> domain.equals(client.domain) && client.ip != null)
				.map(client -> client.ip).findFirst().orElse("");
	}

	@Nullable
	private WifiRouterNetwork.Route resolveDomainRoute(String domain) {
		String normalized = domain.toLowerCase(Locale.ROOT);
		if (!DOMAIN.matcher(normalized).matches()) {
			return null;
		}
		return network().resolve(normalized);
	}

	private void requireBridge(IComputerAccess computer) {
		if (bridge == null || bridge.getID() != computer.getID()) {
			throw new IllegalStateException("This method is only available to the active router bridge");
		}
	}

	private void notifyDomainsChanged() {
		if (bridge != null) {
			bridge.queueEvent("wifi_domains_changed");
		}
	}

	private MinecraftServer getServer() throws LuaException {
		MinecraftServer server = target.getLevel().getServer();
		if (server == null) {
			throw new LuaException("Wireless sessions require a running server");
		}
		return server;
	}

	private WifiRouterNetwork network() {
		MinecraftServer server = target.getLevel().getServer();
		if (server == null) {
			throw new IllegalStateException("Router networking requires a running server");
		}
		return WifiRouterNetwork.forServer(server);
	}

	private byte[] createAirJoinProofBytes(String challenge, int clientComputerId, String clientNonce) {
		try {
			Mac mac = Mac.getInstance("HmacSHA256");
			byte[] key = (ssid + "\u0000" + password).getBytes(StandardCharsets.UTF_8);
			mac.init(new SecretKeySpec(key, "HmacSHA256"));
			String message = challenge + "\u0000" + clientComputerId + "\u0000" + clientNonce
					+ "\u0000" + getWanIP() + "\u0000" + ssid;
			return mac.doFinal(message.getBytes(StandardCharsets.UTF_8));
		} catch (GeneralSecurityException exception) {
			throw new IllegalStateException("Unable to verify wireless client", exception);
		}
	}

	private static void requireArgumentCount(IArguments arguments, int expected, String description) throws LuaException {
		if (arguments.count() != expected) {
			throw new LuaException("Expected " + description);
		}
	}

	@Nullable
	private static String getOptionalString(IArguments arguments, int index, String description) throws LuaException {
		Object value = arguments.get(index);
		if (value == null) {
			return null;
		}
		if (value instanceof String string) {
			return string;
		}
		throw new LuaException(description + " must be a string or nil");
	}

	private static Object getRednetMessage(IArguments arguments, int index) throws LuaException {
		Object message = arguments.get(index);
		if (!isRednetValue(message, new IdentityHashMap<>())) {
			throw new LuaException("Message must be a non-nil string, number, boolean, or serializable table");
		}
		return message;
	}

	private static boolean isRednetValue(@Nullable Object value, IdentityHashMap<Map<?, ?>, Boolean> visited) {
		if (value instanceof String || value instanceof Number || value instanceof Boolean) {
			return true;
		}
		if (!(value instanceof Map<?, ?> table)) {
			return false;
		}
		if (visited.put(table, Boolean.TRUE) != null) {
			return true;
		}
		for (Map.Entry<?, ?> entry : table.entrySet()) {
			Object key = entry.getKey();
			if (!(key instanceof String || key instanceof Number || key instanceof Boolean)
					|| !isRednetValue(entry.getValue(), visited)) {
				return false;
			}
		}
		return true;
	}

	private String allocateAddress() {
		for (int lastOctet = 100; lastOctet <= 254; lastOctet++) {
			String address = "192.168.1." + lastOctet;
			if (!isAddressInUse(address, -1)) {
				return address;
			}
		}
		throw new IllegalStateException("No DHCP addresses are available");
	}

	private boolean isAddressInUse(String address, int ignoredComputerId) {
		return clients.entrySet().stream()
				.anyMatch(entry -> entry.getKey() != ignoredComputerId && address.equals(entry.getValue().ip));
	}

	private static final class Client {
		@Nullable
		private final IComputerAccess computer;
		private final int computerID;
		private final ArrayDeque<Map<String, Object>> inbox = new ArrayDeque<>();
		private String ip;
		private String domain;

		private Client(IComputerAccess computer) {
			this.computer = computer;
			this.computerID = computer.getID();
		}

		private Client(int computerID) {
			this.computer = null;
			this.computerID = computerID;
		}
	}

	private record PendingAirJoin(String challenge, String clientNonce, long wifiRevision, long expiresAt) {
	}

}
