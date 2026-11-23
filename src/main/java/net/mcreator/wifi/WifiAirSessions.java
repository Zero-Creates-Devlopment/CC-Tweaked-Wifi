package net.mcreator.wifi;

import dan200.computercraft.api.lua.ILuaAPI;
import dan200.computercraft.api.lua.IComputerSystem;
import dan200.computercraft.api.lua.LuaException;
import dan200.computercraft.api.lua.LuaFunction;
import net.minecraft.server.MinecraftServer;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Map;
import java.util.WeakHashMap;

final class WifiAirSessions {
	private static final int MAX_BYTES = 262144;
	private static final SecureRandom RANDOM = new SecureRandom();
	private static final Map<MinecraftServer, Map<Identity, Session>> SESSIONS = new WeakHashMap<>();

	private WifiAirSessions() {
	}

	static synchronized void create(MinecraftServer server, String wan, int clientId, boolean encrypted) {
		byte[] key = new byte[32];
		RANDOM.nextBytes(key);
		SESSIONS.computeIfAbsent(server, ignored -> new HashMap<>())
				.put(new Identity(wan, clientId), new Session(key, encrypted));
	}

	static synchronized void remove(MinecraftServer server, String wan, int clientId) {
		Map<Identity, Session> sessions = SESSIONS.get(server);
		if (sessions != null) {
			sessions.remove(new Identity(wan, clientId));
		}
	}

	static synchronized void removeRouter(MinecraftServer server, String wan) {
		Map<Identity, Session> sessions = SESSIONS.get(server);
		if (sessions != null) {
			sessions.keySet().removeIf(identity -> identity.wan.equals(wan));
		}
	}

	static synchronized void stopServer(MinecraftServer server) {
		SESSIONS.remove(server);
	}

	static synchronized String seal(MinecraftServer server, String wan, int clientId, boolean fromClient,
			String plaintext) throws LuaException {
		return seal(server, wan, clientId, fromClient, plaintext, false);
	}

	static synchronized String seal(MinecraftServer server, String wan, int clientId, boolean fromClient,
			String plaintext, boolean confidential) throws LuaException {
		Session session = session(server, wan, clientId);
		byte[] bytes = plaintext.getBytes(StandardCharsets.UTF_8);
		if (bytes.length > MAX_BYTES) {
			throw new LuaException("Wireless packet exceeds the size limit");
		}
		long sequence = fromClient ? session.clientSend++ : session.routerSend++;
		byte[] header = ByteBuffer.allocate(9).put((byte) ((fromClient ? 1 : 2) + (confidential ? 2 : 0)))
				.putLong(sequence).array();
		try {
			byte[] body;
			if (session.encrypted || confidential) {
				byte[] nonce = new byte[12];
				RANDOM.nextBytes(nonce);
				Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
				cipher.init(Cipher.ENCRYPT_MODE, confidential ? session.sensitiveKey : session.key,
						new GCMParameterSpec(128, nonce));
				cipher.updateAAD(header);
				byte[] encrypted = cipher.doFinal(bytes);
				body = ByteBuffer.allocate(nonce.length + encrypted.length).put(nonce).put(encrypted).array();
			} else {
				Mac mac = Mac.getInstance("HmacSHA256");
				mac.init(session.key);
				mac.update(header);
				byte[] tag = mac.doFinal(bytes);
				body = ByteBuffer.allocate(bytes.length + tag.length).put(bytes).put(tag).array();
			}
			return Base64.getUrlEncoder().withoutPadding()
					.encodeToString(ByteBuffer.allocate(header.length + body.length).put(header).put(body).array());
		} catch (GeneralSecurityException exception) {
			throw new LuaException("Unable to protect wireless packet: " + exception.getMessage());
		}
	}

	static synchronized String open(MinecraftServer server, String wan, int clientId, boolean fromClient,
			String packet) throws LuaException {
		return open(server, wan, clientId, fromClient, packet, false);
	}

	static synchronized String open(MinecraftServer server, String wan, int clientId, boolean fromClient,
			String packet, boolean confidential) throws LuaException {
		Session session = session(server, wan, clientId);
		try {
			if (packet.length() > (MAX_BYTES + 128) * 2) {
				throw new GeneralSecurityException("Packet is too large");
			}
			byte[] frame = Base64.getUrlDecoder().decode(packet);
			if (frame.length < 9 + (session.encrypted || confidential ? 28 : 32)) {
				throw new GeneralSecurityException("Packet is too short");
			}
			ByteBuffer buffer = ByteBuffer.wrap(frame);
			byte direction = buffer.get();
			long sequence = buffer.getLong();
			long last = fromClient ? session.clientReceived : session.routerReceived;
			if (direction != (fromClient ? 1 : 2) + (confidential ? 2 : 0) || sequence < 0 || sequence <= last) {
				throw new GeneralSecurityException("Invalid or replayed packet");
			}
			byte[] header = ByteBuffer.allocate(9).put(direction).putLong(sequence).array();
			byte[] plaintext;
			if (session.encrypted || confidential) {
				byte[] nonce = new byte[12];
				buffer.get(nonce);
				byte[] ciphertext = new byte[buffer.remaining()];
				buffer.get(ciphertext);
				Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
				cipher.init(Cipher.DECRYPT_MODE, confidential ? session.sensitiveKey : session.key,
						new GCMParameterSpec(128, nonce));
				cipher.updateAAD(header);
				plaintext = cipher.doFinal(ciphertext);
			} else {
				int length = buffer.remaining() - 32;
				plaintext = new byte[length];
				buffer.get(plaintext);
				byte[] tag = new byte[32];
				buffer.get(tag);
				Mac mac = Mac.getInstance("HmacSHA256");
				mac.init(session.key);
				mac.update(header);
				if (!MessageDigest.isEqual(tag, mac.doFinal(plaintext))) {
					throw new GeneralSecurityException("Invalid packet authentication");
				}
			}
			if (plaintext.length > MAX_BYTES) {
				throw new GeneralSecurityException("Packet is too large");
			}
			if (fromClient) {
				session.clientReceived = sequence;
			} else {
				session.routerReceived = sequence;
			}
			return new String(plaintext, StandardCharsets.UTF_8);
		} catch (IllegalArgumentException | GeneralSecurityException exception) {
			throw new LuaException("Rejected invalid wireless packet");
		}
	}

	private static Session session(MinecraftServer server, String wan, int clientId) throws LuaException {
		Map<Identity, Session> sessions = SESSIONS.get(server);
		Session session = sessions == null ? null : sessions.get(new Identity(wan, clientId));
		if (session == null) {
			throw new LuaException("Wireless session is unavailable");
		}
		return session;
	}

	private record Identity(String wan, int clientId) {
	}

	private static final class Session {
		private final SecretKeySpec key;
		private final SecretKeySpec sensitiveKey;
		private final boolean encrypted;
		private long clientSend;
		private long routerSend;
		private long clientReceived = -1;
		private long routerReceived = -1;

		private Session(byte[] key, boolean encrypted) {
			this.key = new SecretKeySpec(key, encrypted ? "AES" : "HmacSHA256");
			this.encrypted = encrypted;
			try {
				Mac mac = Mac.getInstance("HmacSHA256");
				mac.init(new SecretKeySpec(key, "HmacSHA256"));
				this.sensitiveKey = new SecretKeySpec(
						mac.doFinal("wifi-domain-token-v1".getBytes(StandardCharsets.US_ASCII)), "AES");
			} catch (GeneralSecurityException exception) {
				throw new IllegalStateException("Unable to derive domain token encryption key", exception);
			}
		}
	}

	static final class Api implements ILuaAPI {
		private final IComputerSystem computer;

		Api(IComputerSystem computer) {
			this.computer = computer;
		}

		@Override
		public String[] getNames() {
			return new String[] { "wifi_air_crypto" };
		}

		@LuaFunction(mainThread = true)
		public String seal(String wan, String plaintext) throws LuaException {
			return WifiAirSessions.seal(computer.getLevel().getServer(), wan, computer.getID(), true, plaintext);
		}

		@LuaFunction(mainThread = true)
		public String sealSensitive(String wan, String plaintext) throws LuaException {
			return WifiAirSessions.seal(computer.getLevel().getServer(), wan, computer.getID(), true, plaintext, true);
		}

		@LuaFunction
		public String newToken() {
			byte[] token = new byte[32];
			RANDOM.nextBytes(token);
			return HexFormat.of().formatHex(token);
		}

		@LuaFunction(mainThread = true)
		public String open(String wan, String packet) throws LuaException {
			return WifiAirSessions.open(computer.getLevel().getServer(), wan, computer.getID(), false, packet);
		}
	}
}
