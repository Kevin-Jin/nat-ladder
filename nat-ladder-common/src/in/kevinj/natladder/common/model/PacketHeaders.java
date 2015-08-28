package in.kevinj.natladder.common.model;

public class PacketHeaders {
	public static final byte
		IDENTIFY = 0x01,
		ACCEPTED = 0x02,
		REJECTED = 0x03,
		PING = 0x04,
		PONG = 0x05,
		FOUND_CUT = 0x06,
		MAKE_PIPE = 0x07,
		PIPE_MADE = 0x08,
		PIPE_FAIL = 0x09
	;

	public static final byte
		REJECTED_REASON_ID_IN_USE = 0x01,
		REJECTED_REASON_ID_NOT_IN_USE = 0x02,
		REJECTED_REASON_WRONG_PASSWORD = 0x03
	;

	public static final byte
		FOUND_CUT_TERMINUS = 0x01,
		FOUND_CUT_NODE = 0x02
	;
}
