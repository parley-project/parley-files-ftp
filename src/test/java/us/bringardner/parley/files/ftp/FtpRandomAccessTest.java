package us.bringardner.parley.files.ftp;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Properties;
import java.util.Random;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import us.bringardner.parley.core.ILogger.Level;
import us.bringardner.parley.files.FileSourceFactory;
import us.bringardner.parley.ftp.server.FtpServer;

/**
 * FtpRandomAccessIoController: chunks read with REST and RETR and written with REST and STOR, against
 * parley's FtpServer with setStoreAtOffset(true), compared with java.io.RandomAccessFile doing the same
 * on a local file. Small chunks, so most writes land in the middle of the file. A write in the middle is
 * done only when the factory says the server stores at an offset; a server that refuses leaves the file as
 * it was, and one that replaces or adds to the file instead is caught by the size check.
 */
public class FtpRandomAccessTest {

	private static final int CHUNK = 64;

	@TempDir
	static Path dir;
	private static Path root;
	private static Path offRoot;
	private static FtpServer server;
	private static FtpServer offServer;
	private static FtpFileSourceFactory factory;
	private static FtpFileSourceFactory offFactory;

	private static FtpServer start(Path root, int port, boolean storeAtOffset) throws Exception {
		FtpServer s = new FtpServer();
		s.setFtpRoot(FileSourceFactory.getDefaultFactory().createFileSource(root.toString()));
		s.setPort(port);
		s.setStoreAtOffset(storeAtOffset);
		s.getLogger().setLevel(Level.ERROR);
		s.start();
		long t = System.currentTimeMillis();
		while( !s.isRunning() && System.currentTimeMillis() - t < 5000 ) {
			Thread.sleep(50);
		}
		assertTrue(s.isRunning(), "FTP server did not start");
		return s;
	}

	private static FtpFileSourceFactory connect(FtpServer s, boolean storeAtOffset) throws IOException {
		Properties prop = new Properties();
		prop.setProperty(FtpFileSourceFactory.PROP_USER, "ftp");
		prop.setProperty(FtpFileSourceFactory.PROP_PSWD, "foo@bar.com");
		prop.setProperty(FtpFileSourceFactory.PROP_HOST, "localhost");
		prop.setProperty(FtpFileSourceFactory.PROP_PORT, "" + s.getPort());
		prop.setProperty(FtpFileSourceFactory.PROP_SECURE, "false");
		prop.setProperty(FtpFileSourceFactory.PROP_STORE_AT_OFFSET, "" + storeAtOffset);
		FtpFileSourceFactory f = new FtpFileSourceFactory();
		f.getLogger().setLevel(Level.ERROR);
		assertTrue(f.connect(prop), "can't connect to the FTP server");
		return f;
	}

	@BeforeAll
	static void startServers() throws Exception {
		root = Files.createDirectories(dir.resolve("on"));
		offRoot = Files.createDirectories(dir.resolve("off"));
		server = start(root, Integer.parseInt(System.getProperty("FtpRandomAccessPort", "8034")), true);
		offServer = start(offRoot, Integer.parseInt(System.getProperty("FtpRandomAccessOffPort", "8035")), false);
		factory = connect(server, true);
		offFactory = connect(offServer, false);
	}

	@AfterAll
	static void stopServers() throws Exception {
		for(FtpFileSourceFactory f : new FtpFileSourceFactory[] {factory, offFactory}) {
			if( f != null ) {
				f.disConnect();
			}
		}
		for(FtpServer s : new FtpServer[] {server, offServer}) {
			if( s != null ) {
				s.stop();
			}
		}
	}

	private static FtpRandomAccessIoController open(FtpFileSourceFactory f, String name, String mode) throws IOException {
		return new FtpRandomAccessIoController((FtpFileSource) f.createFileSource("/" + name), mode, CHUNK);
	}

	private static byte[] bytes(int n, long seed) {
		byte[] b = new byte[n];
		new Random(seed).nextBytes(b);
		return b;
	}

	private static byte[] read(FtpRandomAccessIoController io, long pos, int n) throws IOException {
		byte[] b = new byte[n];
		int got = 0;
		while( got < n ) {
			int r = io.read(pos + got, b, got, n - got);
			if( r < 0 ) {
				break;
			}
			got += r;
		}
		return Arrays.copyOf(b, got);
	}

	@Test
	void readsChunksFromAnywhere() throws Exception {
		byte[] data = bytes(1000, 1);
		Files.write(root.resolve("read.bin"), data);
		try (FtpRandomAccessIoController io = open(factory, "read.bin", "r")) {
			assertEquals(CHUNK, io.getChunkSize());
			assertEquals(1000, io.length());
			assertArrayEquals(Arrays.copyOfRange(data, 900, 1000), read(io, 900, 100));
			assertEquals(data[10] & 0xff, io.read(10));
			assertArrayEquals(data, read(io, 0, 1000));
			assertEquals(-1, io.read(1000));
			assertEquals(-1, io.read(5000));
		}
	}

	@Test
	void writesInTheMiddleAndAtTheStartKeepTheRest() throws Exception {
		byte[] data = bytes(1000, 2);
		Files.write(root.resolve("write.bin"), data);
		try (FtpRandomAccessIoController io = open(factory, "write.bin", "rw")) {
			io.write(500, new byte[] {1, 2, 3}, 0, 3);   // middle: REST, STOR
			io.write(5, (byte) 9);                      // chunk 0: the rest is stored again after it
			io.write(990, new byte[20], 0, 20);         // over the end: it grows
		}
		byte[] expect = Arrays.copyOf(data, 1010);
		expect[500] = 1; expect[501] = 2; expect[502] = 3;
		expect[5] = 9;
		Arrays.fill(expect, 990, 1010, (byte) 0);
		assertArrayEquals(expect, Files.readAllBytes(root.resolve("write.bin")));
	}

	@Test
	void setLengthGrowsAndShrinks() throws Exception {
		byte[] d = bytes(300, 3);
		Files.write(root.resolve("len.bin"), d);
		try (FtpRandomAccessIoController io = open(factory, "len.bin", "rw")) {
			io.setLength(400);
			assertEquals(400, io.length());
		}
		assertArrayEquals(Arrays.copyOf(d, 400), Files.readAllBytes(root.resolve("len.bin")), "zeros added");
		try (FtpRandomAccessIoController io = open(factory, "len.bin", "rw")) {
			io.setLength(100);
			assertEquals(100, io.length());
		}
		assertArrayEquals(Arrays.copyOf(d, 100), Files.readAllBytes(root.resolve("len.bin")));
		try (FtpRandomAccessIoController io = open(factory, "len.bin", "rw")) {
			io.setLength(0);
			assertEquals(0, io.length());
		}
		assertEquals(0, Files.size(root.resolve("len.bin")));
	}

	/** Random writes, reads and setLength, the same as RandomAccessFile on a local copy. */
	@Test
	void actsLikeRandomAccessFile() throws Exception {
		byte[] start = bytes(777, 4);
		Files.write(root.resolve("same.bin"), start);
		Path oracle = dir.resolve("oracle.bin");
		Files.write(oracle, start);
		Random r = new Random(42);
		try (FtpRandomAccessIoController io = open(factory, "same.bin", "rw");
				RandomAccessFile raf = new RandomAccessFile(oracle.toFile(), "rw")) {
			for(int i = 0; i < 200; i++) {
				int op = r.nextInt(10);
				long len = raf.length();
				String step = " (step " + i + ")";
				if( op < 4 ) {
					long pos = r.nextInt((int) len + 50);
					byte[] b = bytes(1 + r.nextInt(150), i);
					io.write(pos, b, 0, b.length);
					raf.seek(pos);
					raf.write(b);
				} else if( op < 8 ) {
					long pos = r.nextInt((int) len + 10);
					int n = 1 + r.nextInt(150);
					byte[] e = new byte[n];
					raf.seek(pos);
					int got = Math.max(raf.read(e), 0);
					assertArrayEquals(Arrays.copyOf(e, got), read(io, pos, n), "read " + n + " at " + pos + step);
				} else if( op == 8 ) {
					long n = r.nextInt((int) len + 100);
					io.setLength(n);
					raf.setLength(n);
				}
				assertEquals(raf.length(), io.length(), "length" + step);
			}
		}
		assertArrayEquals(Files.readAllBytes(oracle), Files.readAllBytes(root.resolve("same.bin")));
	}

	@Test
	void modes() throws Exception {
		assertThrows(IllegalArgumentException.class, () -> open(factory, "x.bin", "w"));
		assertThrows(FileNotFoundException.class, () -> open(factory, "missing.bin", "r"));
		assertFalse(Files.exists(root.resolve("missing.bin")));
		Files.createDirectories(root.resolve("aDir"));
		assertThrows(FileNotFoundException.class, () -> open(factory, "aDir", "rw"));

		try (FtpRandomAccessIoController io = open(factory, "made.bin", "rw")) {
			assertEquals(0, io.length());
			io.write(0, new byte[] {7, 8}, 0, 2);
		}
		assertArrayEquals(new byte[] {7, 8}, Files.readAllBytes(root.resolve("made.bin")));

		try (FtpRandomAccessIoController io = open(factory, "made.bin", "r")) {
			assertThrows(IOException.class, () -> io.write(0, (byte) 1));
			assertThrows(IOException.class, () -> io.setLength(0));
			assertEquals(7, io.read(0));
		}
		assertArrayEquals(new byte[] {7, 8}, Files.readAllBytes(root.resolve("made.bin")));
	}

	/** Not said to store at an offset: a write in the middle fails before anything is sent. */
	@Test
	void notSaidToStoreAtAnOffset() throws Exception {
		byte[] data = bytes(500, 5);
		Files.write(offRoot.resolve("off.bin"), data);
		FtpRandomAccessIoController io = open(offFactory, "off.bin", "rw");
		io.write(200, new byte[] {1, 2, 3}, 0, 3);
		IOException e = assertThrows(IOException.class, io::save);
		assertTrue(e.getMessage().contains(FtpFileSourceFactory.PROP_STORE_AT_OFFSET), "says why: " + e);
		assertTrue(e.getMessage().contains("Nothing was written"), e.getMessage());
		assertArrayEquals(data, Files.readAllBytes(offRoot.resolve("off.bin")));
		closeQuietly(io);

		// at the end it is APPE, which every server does; chunk 0 stores the whole file again
		try (FtpRandomAccessIoController io2 = open(offFactory, "off.bin", "rw")) {
			io2.write(500, new byte[] {4, 5}, 0, 2);
		}
		try (FtpRandomAccessIoController io2 = open(offFactory, "off.bin", "rw")) {
			io2.write(3, (byte) 6);
		}
		byte[] expect = Arrays.copyOf(data, 502);
		expect[500] = 4; expect[501] = 5; expect[3] = 6;
		assertArrayEquals(expect, Files.readAllBytes(offRoot.resolve("off.bin")));
	}

	/** Said to, but the server refuses REST then STOR: the save fails and nothing changes. */
	@Test
	void aServerThatRefuses() throws Exception {
		FtpFileSourceFactory wrong = (FtpFileSourceFactory) offFactory.createThreadSafeCopy();
		assertFalse(wrong.isStoreAtOffset());
		wrong.setStoreAtOffset(true);
		try {
			byte[] data = bytes(500, 6);
			Files.write(offRoot.resolve("refused.bin"), data);
			FtpRandomAccessIoController io = open(wrong, "refused.bin", "rw");
			io.write(200, new byte[] {1, 2, 3}, 0, 3);
			IOException e = assertThrows(IOException.class, io::save);
			assertTrue(e.getMessage().contains("won't store at an offset"), "says why: " + e);
			assertArrayEquals(data, Files.readAllBytes(offRoot.resolve("refused.bin")));
			closeQuietly(io);
		} finally {
			wrong.disConnect();
		}
	}

	/**
	 * A server that accepts REST then STOR but doesn't write in place: one that replaces the file with
	 * the data (as IIS is reported to), and one that adds it to the end (as FileZilla Server is). They
	 * are played here by sending a plain STOR, or APPE, where REST then STOR would go.
	 */
	@Test
	void aServerThatDoesSomethingElseIsCaught() throws Exception {
		for(boolean append : new boolean[] {false, true}) {
			String name = append ? "appends.bin" : "replaces.bin";
			byte[] data = bytes(500, 7);
			Files.write(root.resolve(name), data);
			FtpFileSource liar = new FtpFileSource("/" + name, factory) {
				private static final long serialVersionUID = 1L;

				@Override
				java.io.OutputStream getOutputStreamAt(long position, us.bringardner.parley.files.StreamOptions options) throws IOException {
					return getOutputStream(append, options);
				}
			};
			FtpRandomAccessIoController io = new FtpRandomAccessIoController(liar, "rw", CHUNK);
			io.write(200, new byte[] {1, 2, 3}, 0, 3);
			IOException e = assertThrows(IOException.class, io::save, name);
			assertTrue(e.getMessage().contains("damaged"), name + ": " + e.getMessage());
			assertTrue(e.getMessage().contains(append ? "added to the file" : "cut the file off or replaced it"), e.getMessage());
			assertTrue(e.getMessage().contains(FtpFileSourceFactory.PROP_STORE_AT_OFFSET), e.getMessage());
			closeQuietly(io);
		}
	}

	/**
	 * Through the stream: the anonymous login isn't the files' owner, so canWrite() says false (from the
	 * "other" bits of a rw-r--r-- file), but the controller checked the file itself and the stream opens.
	 */
	@Test
	void theStreamOpensAFileCanWriteGuessesWrong() throws Exception {
		Path p = root.resolve("guess.bin");
		Files.write(p, bytes(300, 8));
		try {
			Files.setPosixFilePermissions(p, java.nio.file.attribute.PosixFilePermissions.fromString("rw-r--r--"));
		} catch (UnsupportedOperationException e) {
			org.junit.jupiter.api.Assumptions.abort("no POSIX permissions here");
		}
		us.bringardner.parley.files.FileSource f = factory.createFileSource("/guess.bin");
		org.junit.jupiter.api.Assumptions.assumeFalse(f.canWrite(), "the login owns the files here");
		try (us.bringardner.parley.files.IRandomAccessStream s = f.getRandomAccessStream("rw")) {
			s.seek(100);
			s.write(new byte[] {1, 2, 3});
		}
		byte[] got = Files.readAllBytes(p);
		assertEquals(300, got.length);
		assertEquals(1, got[100]);
		assertEquals(3, got[102]);
	}

	@Test
	void storeAtOffsetIsAConnectionProperty() throws Exception {
		FtpFileSourceFactory f = new FtpFileSourceFactory();
		assertFalse(f.isStoreAtOffset(), "off by default");
		assertEquals("false", f.getConnectProperties().getProperty(FtpFileSourceFactory.PROP_STORE_AT_OFFSET));
		Properties p = f.getConnectProperties();
		p.setProperty(FtpFileSourceFactory.PROP_STORE_AT_OFFSET, "true");
		f.setConnectionProperties(p);
		assertTrue(f.isStoreAtOffset());
		assertTrue(((FtpFileSourceFactory) f.createThreadSafeCopy()).isStoreAtOffset(), "copies keep it");
		assertTrue(us.bringardner.parley.files.ConnectionSettings.find(f.getConnectionSettings(),
				FtpFileSourceFactory.PROP_STORE_AT_OFFSET) != null, "a setting in a dialog");
		f.setConnectionProperties((Properties) null);
		assertFalse(f.isStoreAtOffset());
	}

	private static void closeQuietly(FtpRandomAccessIoController io) {
		try {
			io.close();   // tries to save again
		} catch (Exception expected) {
			// the same failure
		}
	}
}
