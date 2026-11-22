package net.mcreator.wifi;

import net.minecraft.server.MinecraftServer;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Map;
import java.util.WeakHashMap;

final class WifiWanCrypto {
	private static final int KEY_SIZE_BYTES = 32;
	private static final int NONCE_SIZE_BYTES = 12;
	private static final int TAG_SIZE_BITS = 128;
	private static final byte[] ASSOCIATED_DATA = "wifi-wan-v1".getBytes(StandardCharsets.US_ASCII);
	private static final SecureRandom RANDOM = new SecureRandom();
	private static final Map<MinecraftServer, SecretKeySpec> KEYS = new WeakHashMap<>();

	private WifiWanCrypto() {
	}

	static synchronized void startServer(MinecraftServer server) {
		byte[] key = new byte[KEY_SIZE_BYTES];
		RANDOM.nextBytes(key);
		KEYS.put(server, new SecretKeySpec(key, "AES"));
	}

	static synchronized void stopServer(MinecraftServer server) {
		KEYS.remove(server);
	}

	static byte[] encrypt(MinecraftServer server, byte[] plaintext) throws GeneralSecurityException {
		byte[] nonce = new byte[NONCE_SIZE_BYTES];
		RANDOM.nextBytes(nonce);
		Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
		cipher.init(Cipher.ENCRYPT_MODE, getKey(server), new GCMParameterSpec(TAG_SIZE_BITS, nonce));
		cipher.updateAAD(ASSOCIATED_DATA);
		byte[] encrypted = cipher.doFinal(plaintext);
		return ByteBuffer.allocate(nonce.length + encrypted.length).put(nonce).put(encrypted).array();
	}

	static byte[] decrypt(MinecraftServer server, byte[] encrypted) throws GeneralSecurityException {
		if (encrypted.length < NONCE_SIZE_BYTES + TAG_SIZE_BITS / Byte.SIZE) {
			throw new GeneralSecurityException("Encrypted WAN packet is too short");
		}
		ByteBuffer packet = ByteBuffer.wrap(encrypted);
		byte[] nonce = new byte[NONCE_SIZE_BYTES];
		packet.get(nonce);
		byte[] ciphertext = new byte[packet.remaining()];
		packet.get(ciphertext);

		Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
		cipher.init(Cipher.DECRYPT_MODE, getKey(server), new GCMParameterSpec(TAG_SIZE_BITS, nonce));
		cipher.updateAAD(ASSOCIATED_DATA);
		return cipher.doFinal(ciphertext);
	}

	private static synchronized SecretKeySpec getKey(MinecraftServer server) {
		SecretKeySpec key = KEYS.get(server);
		if (key == null) {
			throw new IllegalStateException("WAN encryption key is unavailable for this server");
		}
		return key;
	}
}
