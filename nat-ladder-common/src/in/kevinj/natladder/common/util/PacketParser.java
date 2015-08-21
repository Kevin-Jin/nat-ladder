package in.kevinj.natladder.common.util;

import java.nio.ByteBuffer;
import java.nio.charset.Charset;

public abstract class PacketParser {
	private static final Charset utf8 = Charset.forName("UTF-8");

	protected final ByteBuffer buf;

	public PacketParser(ByteBuffer buf) {
		this.buf = buf;
	}

	public void readBytes(byte[] bs, int offset, int length) {
		buf.get(bs, offset, length);
	}

	public byte[] readBytes(int length) {
		byte[] bs = new byte[length];
		readBytes(bs, 0, length);
		return bs;
	}

	public long readLong() {
		return buf.getLong();
	}

	public int readInt() {
		return buf.getInt();
	}

	public short readShort() {
		return buf.getShort();
	}

	public byte readByte() {
		return buf.get();
	}

	public double readDouble() {
		return buf.getDouble();
	}

	public float readFloat() {
		return buf.getFloat();
	}

	public String readPaddedString(int fixedLength) {
		return new String(readBytes(fixedLength), utf8);
	}

	public String readString() {
		return readPaddedString(readShort());
	}

	public abstract void dispose();
}
