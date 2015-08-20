package in.kevinj.natladder.common.netimpl;

import java.io.UnsupportedEncodingException;
import java.nio.ByteBuffer;

public abstract class PacketBuilder {
	private final int prefixLen;
	private ByteBuffer buf;

	public PacketBuilder(short[] destinationChain, int initialMessageLength) {
		prefixLen = Integer.SIZE / 8 + Short.SIZE / 8 * destinationChain.length;
		buf = ByteBuffer.allocate(prefixLen + initialMessageLength);
		// reserve space for length
		buf.position(Integer.SIZE / 8);
		for (int i = 0; i < destinationChain.length; i++)
			buf.putShort(destinationChain[i]);
	}

	private void ensureCapacity(int size) {
		if (buf.remaining() < size) {
			ByteBuffer newBuf = ByteBuffer.allocate(buf.limit() + Math.max(buf.limit() / 2, size));
			buf.flip();
			newBuf.put(buf);
			buf = newBuf;
		}
	}

	public PacketBuilder writeBytes(byte[] bs, int offset, int length) {
		ensureCapacity(Byte.SIZE / 8 * length);
		buf.put(bs, offset, length);
		return this;
	}

	public PacketBuilder writeBytes(byte... bs) {
		ensureCapacity(Byte.SIZE / 8 * bs.length);
		buf.put(bs);
		return this;
	}

	public PacketBuilder writeBuf(ByteBuffer copy) {
		copy = copy.duplicate();
		copy.flip();
		ensureCapacity(Byte.SIZE / 8 * copy.remaining());
		buf.put(copy);
		return this;
	}

	public PacketBuilder writeLong(long l) {
		ensureCapacity(Long.SIZE / 8);
		buf.putLong(l);
		return this;
	}

	public PacketBuilder writeInt(int i) {
		ensureCapacity(Integer.SIZE / 8);
		buf.putInt(i);
		return this;
	}

	public PacketBuilder writeShort(short s) {
		ensureCapacity(Short.SIZE / 8);
		buf.putShort(s);
		return this;
	}

	public PacketBuilder writeByte(byte b) {
		ensureCapacity(Byte.SIZE / 8);
		buf.put(b);
		return this;
	}

	public PacketBuilder writeDouble(double d) {
		ensureCapacity(Double.SIZE / 8);
		buf.putDouble(d);
		return this;
	}

	public PacketBuilder writeFloat(float f) {
		ensureCapacity(Float.SIZE / 8);
		buf.putFloat(f);
		return this;
	}

	public PacketBuilder writePaddedString(String str, int fixedLength) {
		try {
			ensureCapacity(fixedLength);
			byte[] encoded = str.getBytes("UTF-8");
			int copied = Math.min(encoded.length, fixedLength);
			writeBytes(encoded, 0, copied);
			// pad with NUL characters. buf should have 0s in these
			// positions (retaining default values) since we don't
			// reset(), clear(), flip(), rewind(), or position() back.
			buf.position(buf.position() + fixedLength - copied);
			return this;
		} catch (UnsupportedEncodingException e) {
			throw new RuntimeException(e);
		}
	}

	public PacketBuilder writeString(String str) {
		try {
			return writeShort((short) str.length()).writeBytes(str.getBytes("UTF-8"));
		} catch (UnsupportedEncodingException e) {
			throw new RuntimeException(e);
		}
	}

	protected abstract void commit(ByteBuffer buf);

	public void send() {
		// fill in the length
		buf.putInt(0, buf.position() - prefixLen);
		commit(buf);
	}
}