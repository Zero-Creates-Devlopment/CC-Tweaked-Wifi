package net.mcreator.wifi;

import dan200.computercraft.api.lua.LuaException;
import dan200.computercraft.api.peripheral.IComputerAccess;
import net.minecraft.core.HolderLookup;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.world.level.storage.TagValueOutput;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Stream;

public final class WifiAirSecurityChecks {
	private static final String WAN = "22.145.56.55";

	public static void main(String[] args) throws Exception {
		WifiAirSessions.create(null, WAN, 1, true);
		WifiAirSessions.create(null, WAN, 2, true);
		String first = WifiAirSessions.seal(null, WAN, 1, true, "private message");
		check(!new String(Base64.getUrlDecoder().decode(first), StandardCharsets.UTF_8).contains("private message"),
				"password-protected traffic must not expose the plaintext");
		reject(() -> WifiAirSessions.open(null, WAN, 2, true, first));
		reject(() -> WifiAirSessions.open(null, WAN, 1, false, first));
		byte[] modified = Base64.getUrlDecoder().decode(first);
		modified[modified.length - 1] ^= 1;
		reject(() -> WifiAirSessions.open(null, WAN, 1, true,
				Base64.getUrlEncoder().withoutPadding().encodeToString(modified)));
		check("private message".equals(WifiAirSessions.open(null, WAN, 1, true, first)), "decrypt failed");
		reject(() -> WifiAirSessions.open(null, WAN, 1, true, first));

		String reply = WifiAirSessions.seal(null, WAN, 1, false, "reply");
		check("reply".equals(WifiAirSessions.open(null, WAN, 1, false, reply)), "reply decrypt failed");
		WifiAirSessions.create(null, WAN, 1, true);
		reject(() -> WifiAirSessions.open(null, WAN, 1, false, reply));

		WifiAirSessions.create(null, WAN, 3, false);
		String open = WifiAirSessions.seal(null, WAN, 3, true, "open network");
		check(new String(Base64.getUrlDecoder().decode(open), StandardCharsets.UTF_8).contains("open network"),
				"open network traffic should remain readable");
		byte[] forgedOpen = Base64.getUrlDecoder().decode(open);
		forgedOpen[9] ^= 1;
		reject(() -> WifiAirSessions.open(null, WAN, 3, true,
				Base64.getUrlEncoder().withoutPadding().encodeToString(forgedOpen)));
		check("open network".equals(WifiAirSessions.open(null, WAN, 3, true, open)), "open packet failed");
		reject(() -> WifiAirSessions.open(null, WAN, 3, true, open));
		String sensitive = WifiAirSessions.seal(null, WAN, 3, true, "ownership token", true);
		check(!new String(Base64.getUrlDecoder().decode(sensitive), StandardCharsets.UTF_8)
				.contains("ownership token"), "domain tokens must be encrypted on open networks");
		reject(() -> WifiAirSessions.open(null, WAN, 3, true, sensitive));
		check("ownership token".equals(WifiAirSessions.open(null, WAN, 3, true, sensitive, true)),
				"sensitive packet decrypt failed");
		reject(() -> WifiAirSessions.open(null, WAN, 3, true, sensitive, true));
		WifiAirSessions.removeRouter(null, WAN);
		reject(() -> WifiAirSessions.seal(null, WAN, 1, true, "removed"));
		check(WifiRouterPeripheral.matchesDestinationDomain("storage.example", "storage.example"),
				"current domain owner should receive the packet");
		check(!WifiRouterPeripheral.matchesDestinationDomain("storage.example", null)
				&& !WifiRouterPeripheral.matchesDestinationDomain("storage.example", "other.example"),
				"stale domain routes must not deliver to a different LAN client");
		check(WifiRouterPeripheral.matchesDestinationDomain("", null),
				"explicit WAN and LAN addresses must remain supported");
		check(WifiRouterPeripheral.validWanRoute("109.98.114.16", "192.168.1.100"),
				"IPv4 WAN and LAN addresses must be accepted even when the WAN resembles a domain");
		check(!WifiRouterPeripheral.validWanRoute("storage.example", "192.168.1.100")
				&& !WifiRouterPeripheral.validWanRoute("256.98.114.16", "192.168.1.100")
				&& !WifiRouterPeripheral.validWanRoute("109.98.114.16", "192.168.1.255"),
				"invalid WAN or LAN destinations must be rejected");
		String threadA = "a".repeat(64);
		String threadB = "b".repeat(64);
		Map<String, Object> firstPacket = Map.of("thread", threadA, "message", "first");
		Map<String, Object> secondPacket = Map.of("thread", threadB, "message", "second");
		ArrayDeque<Map<String, Object>> inbox = new ArrayDeque<>();
		inbox.add(firstPacket);
		inbox.add(secondPacket);
		check(WifiRouterPeripheral.pollInbox(inbox, "c".repeat(64)) == null && inbox.size() == 2,
				"filtering for a missing thread must preserve the inbox");
		check(WifiRouterPeripheral.pollInbox(inbox, threadB) == secondPacket,
				"filtered receive must select the matching thread");
		check(WifiRouterPeripheral.pollInbox(inbox, null) == firstPacket && inbox.isEmpty(),
				"unmatched packets must remain available in order");
		checkBroadcast(threadA);
		WifiRouterNetwork network = new WifiRouterNetwork();
		TestRouter home = new TestRouter("22.145.56.55");
		TestRouter remote = new TestRouter("19.84.216.3");
		home.domains.put("home.example", "192.168.1.100");
		remote.domains.put("storage.example", "192.168.1.101");
		remote.domains.put("offline.example", "");
		network.register(home);
		network.register(remote);
		check(network.records().size() == 3, "loaded routers must share online and offline reservations");
		check(new WifiRouterNetwork().records().isEmpty(), "router networks must not share server state");
		check(network.resolve("storage.example").equals(new WifiRouterNetwork.Route(remote.wan, "192.168.1.101")),
				"cross-dimension domain lookup failed");
		check(network.resolve("offline.example") == null && network.hasOtherClaim("offline.example", home.wan),
				"offline reservation must remain visible and protected");
		Map<String, Object> message = Map.of("message", "hello");
		check(network.deliver(remote.wan, message) && remote.received == message,
				"server-wide routing failed");
		home.domains.put("storage.example", "");
		check(network.resolve("storage.example") == null, "conflicting reservations must be ambiguous");
		home.domains.remove("storage.example");
		network.unregister(remote);
		check(network.resolve("storage.example") == null && !network.deliver(remote.wan, message),
				"unloaded routers must not remain reachable");
		network.register(remote);
		check(network.resolve("storage.example") != null, "reloaded routers must share domains again");
		TestRouter replacement = new TestRouter(remote.wan);
		network.register(replacement);
		network.unregister(remote);
		check(network.deliver(remote.wan, message) && replacement.received == message,
				"unloading an old router must not remove its replacement");
		for (java.lang.reflect.Method method : WifiRouterPeripheral.class.getMethods()) {
			check(!method.getName().equals("configureWan") && !method.getName().equals("registerPeerDomain")
					&& !method.getName().equals("receiveWAN"),
					"legacy WAN control endpoints must not be exposed");
		}
		WifiDomainReservations reservations = new WifiDomainReservations();
		String owner = "a".repeat(64);
		String stranger = "b".repeat(64);
		check(reservations.reserve("storage.example", owner), "initial reservation failed");
		check(reservations.reserve("storage.example", owner), "owner reclaim failed");
		check(!reservations.reserve("storage.example", stranger), "another token claimed the domain");
		check(!reservations.release("storage.example", stranger), "another token released the domain");
		check(!reservations.reserve("invalid.example", "short"), "invalid tokens must be rejected");
		TagValueOutput output = TagValueOutput.createWithoutContext(ProblemReporter.DISCARDING);
		reservations.save(output);
		check(!output.buildResult().toString().contains(owner), "plaintext token must not be persisted on the router");
		WifiDomainReservations reloaded = new WifiDomainReservations();
		reloaded.load(TagValueInput.create(ProblemReporter.DISCARDING,
				HolderLookup.Provider.create(Stream.empty()), output.buildResult()));
		check(reloaded.contains("storage.example") && reloaded.owns("storage.example", owner),
				"reservation did not survive reload");
		check(!reloaded.owns("storage.example", stranger), "wrong token accepted after reload");
		check(reloaded.release("storage.example", owner), "owner could not release reservation");
		check(reloaded.reserve("storage.example", stranger), "released name could not be claimed");
		WifiDomainReservations full = new WifiDomainReservations();
		Map<Integer, Object> records = new LinkedHashMap<>();
		for (int i = 1; i <= 254; i++) {
			String label = String.format("%03d", i) + "a".repeat(57);
			String name = String.join(".", label, label, label, label);
			check(full.reserve(name, owner), "could not reserve domain " + i);
			records.put(i, Map.of("domain", name, "lanIP", i == 1 ? "" : "192.168.1.254"));
		}
		check(!full.reserve("overflow.example", owner), "reservation limit was not enforced");
		byte[] largePacket = WifiWanPayloadCodec.encode(Map.of("records", records), 131_072);
		Map<?, ?> decodedRecords = (Map<?, ?>) WifiWanPayloadCodec.decode(largePacket, 131_072).get("records");
		check(decodedRecords.size() == 254 && "".equals(((Map<?, ?>) decodedRecords.get(1.0)).get("lanIP")),
				"full domain announcement including offline records did not round-trip");
		try {
			WifiWanPayloadCodec.decode(largePacket);
			throw new AssertionError("WAN data size limit must remain unchanged");
		} catch (java.io.IOException expected) {
			// Domain announcements have a separate limit from ordinary WAN data.
		}
		System.out.println("Wireless session tests passed");
	}

	private static void check(boolean condition, String message) {
		if (!condition) {
			throw new AssertionError(message);
		}
	}

	@SuppressWarnings("unchecked")
	private static void checkBroadcast(String thread) throws Exception {
		WifiRouterPeripheral router = new WifiRouterPeripheral(null);
		Field wan = WifiRouterPeripheral.class.getDeclaredField("wanIP");
		wan.setAccessible(true);
		wan.set(router, WAN);
		Field clientsField = WifiRouterPeripheral.class.getDeclaredField("clients");
		clientsField.setAccessible(true);
		Map<Integer, Object> clients = (Map<Integer, Object>) clientsField.get(router);
		Class<?> clientClass = Class.forName("net.mcreator.wifi.WifiRouterPeripheral$Client");
		Constructor<?> wired = clientClass.getDeclaredConstructor(IComputerAccess.class);
		Constructor<?> wireless = clientClass.getDeclaredConstructor(int.class);
		wired.setAccessible(true);
		wireless.setAccessible(true);
		Field ip = clientClass.getDeclaredField("ip");
		Field inboxField = clientClass.getDeclaredField("inbox");
		ip.setAccessible(true);
		inboxField.setAccessible(true);
		int[] events = { 0 };
		IComputerAccess computer = (IComputerAccess) Proxy.newProxyInstance(
				IComputerAccess.class.getClassLoader(), new Class<?>[] { IComputerAccess.class },
				(proxy, method, args) -> {
					if (method.getName().equals("getID")) return 1;
					if (method.getName().equals("queueEvent")) events[0]++;
					return null;
				});
		Object first = wired.newInstance(computer);
		Object second = wireless.newInstance(2);
		ip.set(first, "192.168.1.100");
		ip.set(second, "192.168.1.101");
		clients.put(1, first);
		clients.put(2, second);
		Field bridge = WifiRouterPeripheral.class.getDeclaredField("bridge");
		bridge.setAccessible(true);
		bridge.set(router, computer);
		Map<String, Object> payload = new LinkedHashMap<>();
		payload.put("sourceWan", "19.84.216.3");
		payload.put("sourceLan", "192.168.1.102");
		payload.put("destinationWan", WAN);
		payload.put("destinationLan", "");
		payload.put("destinationDomain", "");
		payload.put("sourceDomain", "");
		payload.put("broadcast", true);
		payload.put("thread", thread);
		payload.put("message", "broadcast");
		Map<String, Object> decoded = WifiWanPayloadCodec.decode(WifiWanPayloadCodec.encode(payload));
		check(Boolean.TRUE.equals(decoded.get("broadcast")) && thread.equals(decoded.get("thread"))
				&& "".equals(decoded.get("destinationLan")), "WAN broadcast metadata must survive serialization");
		var broadcast = WifiRouterPeripheral.class.getDeclaredMethod("broadcast", java.util.Collection.class,
				Map.class, int.class, boolean.class, boolean.class, int.class);
		broadcast.setAccessible(true);
		Map<String, Object> packet = Map.of("fromWan", payload.get("sourceWan"), "fromLan", payload.get("sourceLan"),
				"thread", thread, "message", "broadcast");
		check((boolean) broadcast.invoke(null, clients.values(), packet, -1, true, true, 64),
				"WAN broadcast must deliver to connected clients");
		ArrayDeque<Map<String, Object>> firstInbox = (ArrayDeque<Map<String, Object>>) inboxField.get(first);
		ArrayDeque<Map<String, Object>> secondInbox = (ArrayDeque<Map<String, Object>>) inboxField.get(second);
		check(firstInbox.size() == 1 && secondInbox.size() == 1 && events[0] == 1,
				"broadcast must reach both wired and wireless clients and notify wired clients");
		check(thread.equals(firstInbox.peek().get("thread")) && thread.equals(secondInbox.peek().get("thread")),
				"broadcast must preserve the thread token for every recipient");
		check((boolean) broadcast.invoke(null, clients.values(), packet, 1, true, false, 1)
				&& firstInbox.size() == 1 && secondInbox.size() == 1,
				"local broadcasts must exclude the sender and respect inbox limits");
		payload.put("thread", "invalid");
		check(!router.deliverWan(payload) && firstInbox.size() == 1 && secondInbox.size() == 1,
				"invalid thread tokens must not be delivered");
		payload.put("thread", thread);
		payload.put("destinationDomain", "storage.example");
		check(!router.deliverWan(payload), "domain-targeted packets must not be broadcast");
		clients.clear();
		check(!(boolean) broadcast.invoke(null, clients.values(), packet, -1, true, true, 64),
				"broadcast to an empty router must fail");
	}

	private static void reject(Checked action) throws Exception {
		try {
			action.run();
		} catch (LuaException expected) {
			return;
		}
		throw new AssertionError("Expected packet rejection");
	}

	@FunctionalInterface
	private interface Checked {
		void run() throws Exception;
	}

	private static final class TestRouter implements WifiRouterNetwork.Router {
		private final String wan;
		private final Map<String, String> domains = new LinkedHashMap<>();
		private Map<String, Object> received;

		private TestRouter(String wan) {
			this.wan = wan;
		}

		@Override
		public String getWanIP() {
			return wan;
		}

		@Override
		public Map<String, String> localDomains() {
			return domains;
		}

		@Override
		public boolean deliverWan(Map<String, Object> packet) {
			received = packet;
			return true;
		}
	}
}
