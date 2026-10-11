package us.bringardner.parley.files.ftp;

import java.io.IOException;
import java.io.InputStream;
import java.util.Objects;

import us.bringardner.parley.files.FileSource;
import us.bringardner.parley.files.ISeekableInputStream;
import us.bringardner.parley.files.StreamOptions;

/**
 * A read-only view of an FTP file that can be moved around, as a RandomAccessFile opened with
 * "r": bytes are 0..255, seeking past the end only moves the pointer (the next read is -1) and
 * never changes the file.
 * <p>
 * The protocol can start a download at an offset (REST) but not write at one, so this is read
 * only. One transfer is kept open at the pointer: reading on from where the last read ended
 * continues it. A seek costs nothing by itself; the next read either reads through a short step
 * forward on the open transfer, or drops it and starts another at the new place (a data
 * connection each time, so reading at random is slow).
 */
final class FtpSeekableInputStream extends InputStream implements ISeekableInputStream {

	/** A step forward of at most this many bytes is read through, which is cheaper than a new transfer. */
	static final long READ_THROUGH_LIMIT = 256 * 1024;

	private final FtpFileSource file;
	private final int bufferSize;
	private long pointer;
	private boolean closed;
	/** the transfer in use and where it is; null if there isn't one */
	private InputStream current;
	private long currentAt = -1;
	private final byte[] one = new byte[1];

	/** @param bufferSize the transfers' buffer in bytes, or 0 for the connection's */
	FtpSeekableInputStream(FtpFileSource file, int bufferSize) {
		this.file = file;
		this.bufferSize = bufferSize;
	}

	@Override
	public long length() throws IOException {
		// a file is as long as it is now, not as long as it was when it was last looked at
		file.refresh();
		return file.length();
	}

	@Override
	public void seek(long whereTo) throws IOException {
		if( whereTo < 0 ) {
			throw new IOException("Negative seek offset");
		}
		pointer = whereTo;
	}

	@Override
	public int read() throws IOException {
		return read(one, 0, 1) == 1 ? one[0] & 0xff : -1;
	}

	@Override
	public int read(byte[] data) throws IOException {
		return read(data, 0, data.length);
	}

	@Override
	public int read(byte[] data, int off, int len) throws IOException {
		Objects.checkFromIndexSize(off, len, data.length);
		if( closed ) {
			return -1;
		}
		if( len == 0 ) {
			return 0;
		}
		position();
		int n = current.read(data, off, len);
		if( n > 0 ) {
			pointer += n;
			currentAt += n;
		}
		return n;
	}

	/** Makes the transfer in use be at the pointer: continue it, read it through, or start another. */
	private void position() throws IOException {
		if( current != null ) {
			if( currentAt == pointer ) {
				return;
			}
			long step = pointer - currentAt;
			if( step > 0 && step <= READ_THROUGH_LIMIT ) {
				byte[] sink = new byte[(int) Math.min(step, 8192)];
				while( step > 0 ) {
					int r = current.read(sink, 0, (int) Math.min(sink.length, step));
					if( r < 0 ) {
						// the end is before the pointer: reads from here are -1
						currentAt = pointer;
						return;
					}
					step -= r;
					currentAt += r;
				}
				return;
			}
			drop();
		}
		FileSource f = file;
		StreamOptions options = bufferSize > 0 ? StreamOptions.buffer(bufferSize) : StreamOptions.NONE;
		current = f.getInputStream(pointer, options);
		currentAt = pointer;
	}

	private void drop() throws IOException {
		InputStream c = current;
		current = null;
		currentAt = -1;
		if( c != null ) {
			c.close();
		}
	}

	@Override
	public void close() throws IOException {
		closed = true;
		drop();
	}

	@Override
	public long getFilePointer() throws IOException {
		return pointer;
	}

	@Override
	public FileSource getFile() throws IOException {
		return file;
	}

	@Override
	public InputStream getInputStream() throws IOException {
		return this;
	}
}
