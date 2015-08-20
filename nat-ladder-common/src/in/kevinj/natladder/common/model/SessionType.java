package in.kevinj.natladder.common.model;

import java.util.HashMap;
import java.util.Map;

public enum SessionType {
	DOWNWARDS_RELAY(-1),
	TERMINUS(0),
	UPWARDS_RELAY(1);

	private static final Map<Byte, SessionType> lookup;

	static {
		lookup = new HashMap<Byte, SessionType>();
		for (SessionType key : values())
			lookup.put(Byte.valueOf(key.byteValue()), key);
	}

	private final byte code;

	private SessionType(int code) {
		this.code = (byte) code;
	}

	public byte byteValue() {
		return code;
	}

	public SessionType invert() {
		return valueOf((byte) -byteValue());
	}

	public static SessionType valueOf(byte code) {
		return lookup.get(Byte.valueOf(code));
	}
}