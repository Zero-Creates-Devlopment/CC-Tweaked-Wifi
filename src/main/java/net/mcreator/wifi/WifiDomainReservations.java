package net.mcreator.wifi;

import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

final class WifiDomainReservations {
	static final Pattern DOMAIN = Pattern.compile(
			"(?=.{1,253}$)(?:[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\\.)*[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?");
	private static final Pattern TOKEN = Pattern.compile("[0-9a-f]{64}");
	private static final int MAX_RESERVATIONS = 254;
	private static final Logger LOGGER = LoggerFactory.getLogger(WifiDomainReservations.class);
	private final Map<String, byte[]> owners = new LinkedHashMap<>();

	boolean contains(String domain) {
		return owners.containsKey(domain);
	}

	Set<String> domains() {
		return owners.keySet();
	}

	boolean owns(String domain, String token) {
		byte[] owner = owners.get(domain);
		return owner != null && TOKEN.matcher(token).matches()
				&& MessageDigest.isEqual(owner, hash(token));
	}

	boolean reserve(String domain, String token) {
		if (!DOMAIN.matcher(domain).matches() || !TOKEN.matcher(token).matches()) {
			return false;
		}
		if (owners.containsKey(domain)) {
			return owns(domain, token);
		}
		if (owners.size() >= MAX_RESERVATIONS) {
			return false;
		}
		owners.put(domain, hash(token));
		return true;
	}

	boolean release(String domain, String token) {
		if (!owns(domain, token)) {
			return false;
		}
		owners.remove(domain);
		return true;
	}

	void save(ValueOutput output) {
		ValueOutput.ValueOutputList list = output.childrenList("DomainReservations");
		for (Map.Entry<String, byte[]> entry : owners.entrySet()) {
			ValueOutput record = list.addChild();
			record.putString("Domain", entry.getKey());
			record.putString("OwnerHash", HexFormat.of().formatHex(entry.getValue()));
		}
	}

	void load(ValueInput input) {
		owners.clear();
		for (ValueInput record : input.childrenListOrEmpty("DomainReservations")) {
			String domain = record.getStringOr("Domain", "");
			String hash = record.getStringOr("OwnerHash", "");
			if (!DOMAIN.matcher(domain).matches() || !TOKEN.matcher(hash).matches()
					|| owners.containsKey(domain) || owners.size() >= MAX_RESERVATIONS) {
				LOGGER.error("Invalid or duplicate persisted Wi-Fi domain reservation: {}", domain);
				throw new IllegalStateException("Invalid persisted Wi-Fi domain reservation");
			}
			owners.put(domain, HexFormat.of().parseHex(hash));
		}
	}

	private static byte[] hash(String token) {
		try {
			return MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.US_ASCII));
		} catch (NoSuchAlgorithmException exception) {
			throw new IllegalStateException("SHA-256 is unavailable", exception);
		}
	}
}
