package net.mcreator.wifi;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.Map;

final class WifiWanPayloadCodec {
	private static final int MAX_PACKET_SIZE = 65_536;
	private static final int MAX_DEPTH = 64;
	private static final int STRING = 1;
	private static final int NUMBER = 2;
	private static final int BOOLEAN = 3;
	private static final int TABLE = 4;

	private WifiWanPayloadCodec() {
	}

	static byte[] encode(Map<String, Object> packet) throws IOException {
		return encode(packet, MAX_PACKET_SIZE);
	}

	static byte[] encode(Map<String, Object> packet, int maxPacketSize) throws IOException {
		ByteArrayOutputStream bytes = new ByteArrayOutputStream();
		try (DataOutputStream output = new DataOutputStream(bytes)) {
			writeValue(output, packet, new IdentityHashMap<>(), 0, maxPacketSize);
		}
		if (bytes.size() > maxPacketSize) {
			throw new IOException("WAN packet exceeds the size limit");
		}
		return bytes.toByteArray();
	}

	static Map<String, Object> decode(byte[] bytes) throws IOException {
		return decode(bytes, MAX_PACKET_SIZE);
	}

	static Map<String, Object> decode(byte[] bytes, int maxPacketSize) throws IOException {
		if (bytes.length > maxPacketSize) {
			throw new IOException("WAN packet exceeds the size limit");
		}
		try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(bytes))) {
			Object decoded = readValue(input, 0, maxPacketSize);
			if (!(decoded instanceof Map<?, ?> map) || input.available() != 0) {
				throw new IOException("Invalid WAN packet structure");
			}
			Map<String, Object> packet = new LinkedHashMap<>();
			for (Map.Entry<?, ?> entry : map.entrySet()) {
				if (!(entry.getKey() instanceof String key)) {
					throw new IOException("WAN packet fields must have string keys");
				}
				packet.put(key, entry.getValue());
			}
			return packet;
		}
	}

	private static void writeValue(DataOutputStream output, Object value,
			IdentityHashMap<Map<?, ?>, Boolean> visited, int depth, int maxPacketSize) throws IOException {
		if (depth > MAX_DEPTH) {
			throw new IOException("WAN packet nesting exceeds the limit");
		}
		if (value instanceof String string) {
			output.writeByte(STRING);
			byte[] encoded = string.getBytes(StandardCharsets.UTF_8);
			if (encoded.length + output.size() + Integer.BYTES > maxPacketSize) {
				throw new IOException("WAN packet exceeds the size limit");
			}
			output.writeInt(encoded.length);
			output.write(encoded);
		} else if (value instanceof Number number) {
			double encoded = number.doubleValue();
			if (!Double.isFinite(encoded)) {
				throw new IOException("WAN packet numbers must be finite");
			}
			output.writeByte(NUMBER);
			output.writeDouble(encoded);
		} else if (value instanceof Boolean bool) {
			output.writeByte(BOOLEAN);
			output.writeBoolean(bool);
		} else if (value instanceof Map<?, ?> table) {
			if (visited.put(table, Boolean.TRUE) != null) {
				throw new IOException("WAN packets cannot contain cyclic tables");
			}
			if (table.size() > 65_536) {
				throw new IOException("WAN table exceeds the entry limit");
			}
			output.writeByte(TABLE);
			output.writeInt(table.size());
			for (Map.Entry<?, ?> entry : table.entrySet()) {
				Object key = entry.getKey();
				if (!(key instanceof String || key instanceof Number || key instanceof Boolean)
						|| entry.getValue() == null) {
					throw new IOException("WAN tables require serializable keys and non-nil values");
				}
				writeValue(output, key, visited, depth + 1, maxPacketSize);
				writeValue(output, entry.getValue(), visited, depth + 1, maxPacketSize);
			}
			visited.remove(table);
		} else {
			throw new IOException("WAN packet contains an unsupported value");
		}
		if (output.size() > maxPacketSize) {
			throw new IOException("WAN packet exceeds the size limit");
		}
	}

	private static Object readValue(DataInputStream input, int depth, int maxPacketSize) throws IOException {
		if (depth > MAX_DEPTH) {
			throw new IOException("WAN packet nesting exceeds the limit");
		}
		return switch (input.readUnsignedByte()) {
			case STRING -> {
				int length = input.readInt();
				if (length < 0 || length > maxPacketSize || length > input.available()) {
					throw new IOException("Invalid WAN string length");
				}
				byte[] encoded = new byte[length];
				input.readFully(encoded);
				yield new String(encoded, StandardCharsets.UTF_8);
			}
			case NUMBER -> {
				double number = input.readDouble();
				if (!Double.isFinite(number)) {
					throw new IOException("WAN packet numbers must be finite");
				}
				yield number;
			}
			case BOOLEAN -> input.readBoolean();
			case TABLE -> {
				int size = input.readInt();
				if (size < 0 || size > 65_536) {
					throw new IOException("Invalid WAN table size");
				}
				Map<Object, Object> table = new LinkedHashMap<>();
				for (int i = 0; i < size; i++) {
					Object key = readValue(input, depth + 1, maxPacketSize);
					if (!(key instanceof String || key instanceof Number || key instanceof Boolean)) {
						throw new IOException("Invalid WAN table key");
					}
					Object value = readValue(input, depth + 1, maxPacketSize);
					if (table.put(key, value) != null) {
						throw new IOException("Duplicate WAN table key");
					}
				}
				yield table;
			}
			default -> throw new IOException("Unknown WAN value type");
		};
	}
}
