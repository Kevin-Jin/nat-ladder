package in.kevinj.natladder.common.model;

import java.util.HashMap;
import java.util.Map;

public enum ClientType {
	ENTRY_NODE(-1),
	CENTRAL_RELAY(0),
	EXIT_NODE(1);

	public static final short CENTRAL_RELAY_NODE_CODE = 0;

	private static final Map<Byte, ClientType> lookup;

	static {
		lookup = new HashMap<Byte, ClientType>();
		for (ClientType key : values())
			lookup.put(Byte.valueOf(key.byteValue()), key);
	}

	private final byte code;

	private ClientType(int code) {
		this.code = (byte) code;
	}

	public byte byteValue() {
		return code;
	}

	public ClientType invert() {
		return valueOf((byte) -byteValue());
	}

	public static ClientType valueOf(byte code) {
		return lookup.get(Byte.valueOf(code));
	}
}