/**
 * <PRE>
 * 
 * Copyright Tony Bringarder 1998, 2025 <A href="http://bringardner.com/tony">Tony Bringardner</A>
 * 
 *
 *   Licensed under the Apache License, Version 2.0 (the "License");
 *   you may not use this file except in compliance with the License.
 *   You may obtain a copy of the License at
 *
 *       <A href="http://www.apache.org/licenses/LICENSE-2.0">http://www.apache.org/licenses/LICENSE-2.0</A>
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 *  </PRE>
 *   
 *   
 *	@author Tony Bringardner   
 *
 *
 * ~version~
 */
package us.bringardner.parley.files.ftp;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

import us.bringardner.parley.files.AbstractRandomAccessIoController;
import us.bringardner.parley.files.StreamOptions;

/**
 * Random access to an FTP file in chunks (the factory's buffer size unless the stream was opened with
 * a chunk size). Standard FTP has no command that reads or writes part of a file, but a transfer can
 * start at a position. A server that lists {@code RANG} in {@code FEAT} (draft-bryan-ftp-range, which
 * parley's FtpServer has) can do both, and is used for them:
 * <ul>
 * <li><b>Reading</b> a chunk is {@code RANG start end} then {@code RETR}, which sends just the chunk.
 * Without RANG it is {@code REST start} then {@code RETR}, and the transfer is dropped once the chunk
 * has arrived.</li>
 * <li><b>Writing</b> a chunk anywhere in the file is {@code RANG start end} then {@code STOR}, which
 * writes it in place and changes nothing else; {@link FtpFileSourceFactory#setStoreAtOffset(boolean)}
 * isn't needed. Without RANG it is done as follows.</li>
 * <li><b>Writing</b> a chunk in the middle without RANG is {@code REST start} then {@code STOR}, which a server that
 * writes in place stores from that position, leaving the rest of the file as it was. The FTP standards
 * don't say what a server does there, and servers differ: some refuse, some replace the file with the
 * new data, some add it to the end. So this is done only when the factory says the server stores in
 * place ({@link FtpFileSourceFactory#setStoreAtOffset(boolean)}, e.g. parley's FtpServer with
 * {@code setStoreAtOffset(true)}); otherwise saving such a chunk fails before anything is sent. A chunk
 * at the end of the file is added with {@code APPE}, which every server does.</li>
 * <li>After every write the file's size is checked against what it should be. A server that replaced
 * or added to the file instead of writing in place is reported (the file is damaged by then).</li>
 * <li>A chunk at position 0 can't be written with {@code REST}: {@code REST 0} is no restart, and
 * {@code STOR} would replace the whole file. If the file is longer than the chunk, the rest of it is
 * copied to a local temporary file and stored again after the chunk.</li>
 * <li>{@link #setLength(long)}: a longer file gets zeros added with {@code APPE}. FTP can't truncate,
 * so a shorter one is copied (the part that stays) to a local temporary file and stored again.</li>
 * </ul>
 * Every transfer is on a connection of its own from the factory, so commands and other streams work
 * meanwhile. Each chunk read or written is a data connection, so reading or writing at random is slow;
 * a larger chunk size makes fewer of them. Nothing is locked: changes made to the file by someone else
 * while it is open can be overwritten.
 *
 * @since 1.1.0
 */
public class FtpRandomAccessIoController extends AbstractRandomAccessIoController {

	private final FtpFileSource ftpFile;
	/** Bytes one transfer reads or writes; fixed when the stream was opened. */
	private final int chunkSize;
	/** Opened with mode "r". */
	private final boolean readOnly;

	/**
	 * Opens the file in one of RandomAccessFile's modes, with the factory's buffer size as the chunk size.
	 * @see #FtpRandomAccessIoController(FtpFileSource, String, int)
	 */
	public FtpRandomAccessIoController(FtpFileSource file, String mode) throws IOException {
		this(file, mode, 0);
	}

	/**
	 * Opens the file in one of RandomAccessFile's modes. "r" opens it read-only; "rw", "rws" and "rwd"
	 * open it for reading and writing and create it if it doesn't exist (with {@code APPE}, which never
	 * empties a file another client made meanwhile). Every write is sent to the server when its chunk is
	 * saved, so "rws"/"rwd" need nothing extra.
	 *
	 * @param chunkSize bytes one transfer reads or writes, and the size of the chunk that is cached; 0
	 *        for the factory's buffer size. It is fixed for the life of the stream.
	 * @throws IllegalArgumentException for any other mode
	 * @throws FileNotFoundException if it's a directory, or missing in "r" mode, or can't be created
	 */
	public FtpRandomAccessIoController(FtpFileSource file, String mode, int chunkSize) throws IOException {
		super(file);
		if( !("r".equals(mode) || "rw".equals(mode) || "rws".equals(mode) || "rwd".equals(mode))) {
			throw new IllegalArgumentException("Illegal mode \""+mode+"\" must be one of \"r\", \"rw\", \"rws\", or \"rwd\"");
		}
		this.ftpFile = file;
		this.readOnly = "r".equals(mode);
		FtpFileSourceFactory f = (FtpFileSourceFactory) file.getFileSourceFactory();
		this.chunkSize = chunkSize > 0 ? Math.min(chunkSize, FtpFileSourceFactory.MAX_BUFFER_SIZE) : f.getBufferSize();

		String path = file.getAbsolutePath();
		file.refresh();
		if( !file.exists() ) {
			if( readOnly ) {
				throw new FileNotFoundException(path+" (No such file or directory)");
			}
			try {
				file.createNewFile();   // APPE of nothing: never truncates
			} catch (IOException e) {
				FileNotFoundException fnf = new FileNotFoundException(path+" ("+e.getMessage()+")");
				fnf.initCause(e);
				throw fnf;
			}
			file.refresh();
			if( !file.exists() ) {
				throw new FileNotFoundException(path+" (Can't create it)");
			}
		}
		if( file.isDirectory() ) {
			throw new FileNotFoundException(path+" (Is a directory)");
		}
		// not checked against canWrite(): what a listing says of the permissions isn't always what the
		// server lets this user do, so a file that can't be written fails when a chunk is saved
	}

	/**
	 * true: the constructor checked the file on the server (a directory, a file missing in "r" mode, one
	 * that can't be created), so FileSourceRandomAccessStream doesn't check canRead()/canWrite() again.
	 * Those are guessed from the listing's owner and permission bits, which over FTP often aren't what the
	 * server lets this login do (an anonymous or virtual user isn't the owner the listing shows). A read
	 * or write the server refuses fails when it is done.
	 */
	@Override
	public boolean checksAccessOnOpen() {
		return true;
	}

	/** @return the chunk size this stream reads and writes in */
	public int getChunkSize() {
		return chunkSize;
	}

	private StreamOptions transferOptions() {
		return StreamOptions.buffer(Math.max(FtpFileSourceFactory.MIN_BUFFER_SIZE, chunkSize));
	}

	/** The length of the file on the server now (not counting what is held in a new chunk). */
	private long storedLength() throws IOException {
		ftpFile.refresh();
		return ftpFile.length();
	}

	private void checkWritable() throws IOException {
		if( readOnly ) {
			throw new IOException("Opened read-only (mode \"r\")");
		}
	}

	// ------------------------------------------------------------------ reading

	@Override
	protected Chunk readChunkForPos(long pos) throws IOException {
		Chunk ret = new Chunk();
		long len = length();
		if( len == 0 || pos >= len ) {
			ret.size = 0;
			ret.data = new byte[chunkSize];
			ret.start = len;
			ret.isNew = true;
		} else {
			long chunk = pos/chunkSize;
			ret.start = chunk * chunkSize;
			ret.data = readBlock(ret.start, (int)Math.min(chunkSize, len-ret.start));
			ret.size = ret.data.length;
		}
		return ret;
	}

	/**
	 * RANG start end then RETR (or, without RANG, REST start then RETR), and read 'want' bytes (a
	 * transfer may return them in pieces). Without RANG the transfer is dropped once they have arrived,
	 * which the server sees as an aborted RETR.
	 */
	private byte[] readBlock(long start, int want) throws IOException {
		byte[] ret = new byte[want];
		int got = 0;
		try (InputStream in = ftpFile.getInputStream(start, start + want - 1, transferOptions())) {
			while( got < want ) {
				int n = in.read(ret, got, want-got);
				if( n < 0 ) {
					break; // EOF
				}
				got += n;
			}
		}
		if( got == 0 ) {
			throw new IOException("Unexpected end of file at "+start+" (length was "+length()
					+"); was "+ftpFile.getAbsolutePath()+" changed while it was open?");
		}
		return got == want ? ret : Arrays.copyOf(ret, got);
	}

	/** Copies count bytes of the stored file from start to out (RANG or REST, then RETR). */
	private void copyRange(long start, long count, OutputStream out) throws IOException {
		if( count <= 0 ) {
			return;
		}
		byte[] buf = new byte[(int) Math.min(count, Math.max(8192, chunkSize))];
		long left = count;
		try (InputStream in = ftpFile.getInputStream(start, start + count - 1, transferOptions())) {
			while( left > 0 ) {
				int n = in.read(buf, 0, (int) Math.min(buf.length, left));
				if( n < 0 ) {
					throw new IOException(ftpFile.getAbsolutePath()+" ended at "+(start+count-left)
							+" while "+count+" bytes from "+start+" were copied; was it changed while it was open?");
				}
				out.write(buf, 0, n);
				left -= n;
			}
		}
	}

	// ------------------------------------------------------------------ writing

	/**
	 * Writes are kept in the chunk and sent when it is saved. Opened read-only, the first one fails
	 * here rather than at a later save.
	 */
	@Override
	public void write(long pos, byte value) throws IOException {
		checkWritable();
		super.write(pos, value);
	}

	@Override
	protected void writeChunk(Chunk chunk) throws IOException {
		checkWritable();
		byte[] data = chunk.data;
		long start = chunk.start;
		long len = storedLength();
		if( start >= len ) {
			// at (or past) the end: APPE, with zeros for any gap
			appendZerosThen(start - len, data);
			checkSize(start + data.length, "adding "+data.length+" bytes at "+start+" (APPE)");
		} else if( rangSupported() ) {
			// RANG then STOR: in place, anywhere in the file, and nothing outside the range changes
			long end = start + data.length - 1;
			try (OutputStream out = ftpFile.getOutputStream(start, end, transferOptions())) {
				out.write(data);
			}
			checkSize(Math.max(len, start + data.length), "writing "+data.length+" bytes at "+start+" (RANG "+start+" "+end+" then STOR)");
		} else if( start == 0 ) {
			if( data.length >= len ) {
				// covers the whole file: a plain STOR replaces it
				try (OutputStream out = ftpFile.getOutputStream(false, transferOptions())) {
					out.write(data);
				}
				checkSize(data.length, "storing "+data.length+" bytes (STOR)");
			} else {
				// REST 0 is no restart, so STOR would lose the rest: store it again after the chunk
				replaceWith(data, data.length, len - data.length);
				checkSize(len, "storing the file again with "+data.length+" bytes changed at 0 (STOR)");
			}
		} else {
			// in the middle: REST start then STOR, only where the server is known to store in place
			if( !factoryStoresAtOffset() ) {
				throw new IOException("Can't write "+data.length+" bytes at "+start+" in "+ftpFile.getAbsolutePath()
						+" ("+len+" bytes): writing in the middle of a file over FTP is REST then STOR, which servers"
						+ " handle differently (some replace or add to the file). It is done only for a server"
						+ " that stores in place at a REST offset: set "+FtpFileSourceFactory.PROP_STORE_AT_OFFSET
						+ " (FtpFileSourceFactory.setStoreAtOffset(true)) for one that does. Nothing was written.");
			}
			try (OutputStream out = openAt(start)) {
				out.write(data);
			}
			long expect = Math.max(len, start + data.length);
			long now = storedLength();
			if( now != expect ) {
				throw new IOException(ftpFile.getAbsolutePath()+" is "+now+" bytes after writing "+data.length
						+" bytes at "+start+" (REST then STOR) into "+len+" bytes; it should be "+expect
						+ ". The server didn't store in place at the offset ("+(now < expect ? "it cut the file off or replaced it" : "it added to the file")
						+ "), so the file is damaged. Set "+FtpFileSourceFactory.PROP_STORE_AT_OFFSET+" false for this server.");
			}
		}
	}

	/** null until the server has been asked (FEAT is read once per connection anyway). */
	private Boolean rang;

	private boolean rangSupported() throws IOException {
		if( rang == null ) {
			rang = ftpFile.isRangSupported();
		}
		return rang;
	}

	private boolean factoryStoresAtOffset() {
		return ((FtpFileSourceFactory) ftpFile.getFileSourceFactory()).isStoreAtOffset();
	}

	/** After a write: the stored file must be this long, or something else changed it. */
	private void checkSize(long expect, String what) throws IOException {
		long now = storedLength();
		if( now != expect ) {
			throw new IOException(ftpFile.getAbsolutePath()+" is "+now+" bytes after "+what
					+"; it should be "+expect+". Was it changed by someone else meanwhile?");
		}
	}

	/** REST position then STOR; a server that won't write there is reported as such. */
	private OutputStream openAt(long position) throws IOException {
		try {
			return ftpFile.getOutputStreamAt(position, transferOptions());
		} catch (IOException e) {
			throw new IOException("Can't write "+ftpFile.getAbsolutePath()+" at "+position
					+": the server won't store at an offset (REST then STOR), although "
					+ FtpFileSourceFactory.PROP_STORE_AT_OFFSET+" is set. Nothing was written.", e);
		}
	}

	/** APPE: zeros, then data (either may be empty). */
	private void appendZerosThen(long zeros, byte[] data) throws IOException {
		try (OutputStream out = ftpFile.getOutputStream(true, transferOptions())) {
			writeZeros(out, zeros);
			if( data != null ) {
				out.write(data);
			}
		}
	}

	private static void writeZeros(OutputStream out, long count) throws IOException {
		byte[] zeros = new byte[(int) Math.min(Math.max(count, 0), 64 * 1024)];
		while( count > 0 ) {
			int n = (int) Math.min(zeros.length, count);
			out.write(zeros, 0, n);
			count -= n;
		}
	}

	/**
	 * Stores the file again: head, then tailCount bytes of the stored file from tailStart. The tail is
	 * copied to a local temporary file first, because STOR may empty the file before it is read.
	 *
	 * @param head written first (may be null)
	 */
	private void replaceWith(byte[] head, long tailStart, long tailCount) throws IOException {
		Path tmp = Files.createTempFile("parley-ftp-ra-", ".tmp");
		try {
			try (OutputStream local = Files.newOutputStream(tmp)) {
				copyRange(tailStart, tailCount, local);
			}
			try (OutputStream out = ftpFile.getOutputStream(false, transferOptions());
					InputStream local = Files.newInputStream(tmp)) {
				if( head != null ) {
					out.write(head);
				}
				local.transferTo(out);
			}
		} finally {
			Files.deleteIfExists(tmp);
		}
	}

	/**
	 * FTP has no truncate: a longer file gets zeros added (APPE); a shorter one is stored again with
	 * the part that stays (copied through a local temporary file).
	 */
	@Override
	protected void setLength0(long newLength) throws IOException {
		checkWritable();
		if( newLength < 0 ) {
			throw new IOException("Negative length "+newLength);
		}
		long len = storedLength();
		if( newLength == len ) {
			return;
		}
		if( newLength > len ) {
			appendZerosThen(newLength - len, null);
		} else if( newLength == 0 ) {
			try (OutputStream out = ftpFile.getOutputStream(false, transferOptions())) {
				// nothing: STOR of nothing empties it
			}
		} else {
			replaceWith(null, 0, newLength);
		}
		checkSize(newLength, "setting its length from "+len+" to "+newLength);
	}

	/** Every chunk is stored (the server has replied that the transfer is complete) when it is saved. */
	@Override
	public void sync() throws IOException {
	}
}
