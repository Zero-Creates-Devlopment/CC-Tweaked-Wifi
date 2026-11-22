package net.mcreator.wifi;

import dan200.computercraft.api.lua.LuaException;
import net.minecraft.core.HolderLookup;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.world.level.storage.TagValueOutput;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Stream;

public final class WifiAirSecurityChecks {
	private static final String WAN = "minecraft:overworld.1.2.3";

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
		WifiWanCrypto.startServer(null);
		byte[] secret = "shared secret one".getBytes(StandardCharsets.UTF_8);
		byte[] encrypted = WifiWanCrypto.encrypt(null, "domain record".getBytes(StandardCharsets.UTF_8), secret);
		check("domain record".equals(new String(WifiWanCrypto.decrypt(null, encrypted, secret), StandardCharsets.UTF_8)),
				"WAN decrypt failed");
		try {
			WifiWanCrypto.decrypt(null, encrypted, "shared secret two".getBytes(StandardCharsets.UTF_8));
			throw new AssertionError("WAN packets must reject a different secret");
		} catch (GeneralSecurityException expected) {
			// A mismatched secret must fail authentication.
		}
		WifiWanCrypto.stopServer(null);
		WifiWanCrypto.startServer(null);
		try {
			WifiWanCrypto.decrypt(null, encrypted, secret);
			throw new AssertionError("WAN packets must not survive server key rotation");
		} catch (GeneralSecurityException expected) {
			// A new server key must reject old packets.
		}
		WifiWanCrypto.stopServer(null);
		check(WifiRouterPeripheral.matchesDestinationDomain("storage.example", "storage.example"),
				"current domain owner should receive the packet");
		check(!WifiRouterPeripheral.matchesDestinationDomain("storage.example", null)
				&& !WifiRouterPeripheral.matchesDestinationDomain("storage.example", "other.example"),
				"stale domain routes must not deliver to a different LAN client");
		check(WifiRouterPeripheral.matchesDestinationDomain("", null),
				"explicit WAN and LAN addresses must remain supported");
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
}
