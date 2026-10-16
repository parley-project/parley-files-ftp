package us.bringardner.parley.files.ftp;

import us.bringardner.parley.files.test.FileSourceTestSupport;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.FileNotFoundException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Properties;
import java.util.Random;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import us.bringardner.parley.core.ILogger.Level;
import us.bringardner.parley.files.FileSource;
import us.bringardner.parley.files.FileSourceFactory;
import us.bringardner.parley.files.ISeekableInputStream;
import us.bringardner.parley.ftp.server.FtpServer;

/**
 * FTP reads from an offset (REST) but can't write at one, so the seekable stream is read only:
 * it acts as a RandomAccessFile opened with "r" does.
 */
public class FtpSeekableInputStreamTest {

	private FtpServer server;
	private FtpFileSourceFactory factory;
	private byte[] data;

	@BeforeEach
	void start() throws Exception {
		Path root = Paths.get(FileSourceTestSupport.scratchDir("FtpSeekableRoot"));
		if( Files.exists(root) ) {
			try (java.util.stream.Stream<Path> walk = Files.walk(root)) {
				walk.sorted(java.util.Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
			}
		}
		Files.createDirectories(root);
		server = new FtpServer();
		server.setFtpRoot(FileSourceFactory.getDefaultFactory().createFileSource(root.toString()));
		server.setPort(Integer.parseInt(System.getProperty("FtpSeekablePort", "8033")));
		server.getLogger().setLevel(Level.ERROR);
		server.start();
		long start = System.currentTimeMillis();
		while( !server.isRunning() && System.currentTimeMillis() - start < 5000 ) {
			Thread.sleep(50);
		}
		assertTrue(server.isRunning(), "FTP server did not start");

		Properties prop = new Properties();
		prop.setProperty(FtpFileSourceFactory.PROP_USER, "ftp");
		prop.setProperty(FtpFileSourceFactory.PROP_PSWD, "foo@bar.com");
		prop.setProperty(FtpFileSourceFactory.PROP_HOST, "localhost");
		prop.setProperty(FtpFileSourceFactory.PROP_PORT, "" + server.getPort());
		prop.setProperty(FtpFileSourceFactory.PROP_SECURE, "false");
		factory = new FtpFileSourceFactory();
		factory.getLogger().setLevel(Level.ERROR);
		assertTrue(factory.connect(prop), "can't connect to the FTP server");

		data = new byte[400_000];
		new Random(21).nextBytes(data);
		data[0] = (byte) 0xFF;
		try (OutputStream out = factory.createFileSource("/seek.bin").getOutputStream()) {
			out.write(data);
		}
	}

	@AfterEach
	void stop() throws Exception {
		if( factory != null ) {
			factory.disConnect();
		}
		if( server != null ) {
			server.stop();
		}
	}

	private ISeekableInputStream open() throws Exception {
		return factory.createFileSource("/seek.bin").getSeekableInputStream();
	}

	private static byte[] readN(ISeekableInputStream s, int n) throws Exception {
		byte[] b = new byte[n];
		int got = 0;
		while( got < n ) {
			int r = s.read(b, got, n - got);
			if( r < 0 ) {
				break;
			}
			got += r;
		}
		return Arrays.copyOf(b, got);
	}

	@Test
	void readsSequentiallyAndFromTheStart() throws Exception {
		ISeekableInputStream s = open();
		try {
			assertEquals(data.length, s.length());
			assertEquals(0, s.getFilePointer());
			assertEquals(255, s.read(), "a byte is 0..255");
			assertArrayEquals(Arrays.copyOfRange(data, 1, 5001), readN(s, 5000));
			assertEquals(5001, s.getFilePointer());
			try (InputStream in = s.getInputStream()) {
				assertEquals(data[5001] & 0xff, in.read());
			}
		} finally {
			s.close();
		}
	}

	@Test
	void seeksAnywhereAndReadsThere() throws Exception {
		ISeekableInputStream s = open();
		try {
			Random r = new Random(5);
			for(int i = 0; i < 40; i++) {
				// some far, some a short step on from where the last read ended
				long pos = i % 3 == 0 ? Math.min(data.length - 1, s.getFilePointer() + r.nextInt(5000)) : r.nextInt(data.length);
				int len = 1 + r.nextInt(4000);
				s.seek(pos);
				assertEquals(pos, s.getFilePointer());
				byte[] got = readN(s, len);
				int expectedLen = (int) Math.min(len, data.length - pos);
				assertArrayEquals(Arrays.copyOfRange(data, (int) pos, (int) pos + expectedLen), got, "at " + pos + " for " + len);
			}
		} finally {
			s.close();
		}
	}

	@Test
	void theEndAndPastIt() throws Exception {
		ISeekableInputStream s = open();
		try {
			s.seek(data.length - 3);
			assertArrayEquals(Arrays.copyOfRange(data, data.length - 3, data.length), readN(s, 10));
			assertEquals(-1, s.read());
			s.seek(data.length + 5000);
			assertEquals(data.length + 5000, s.getFilePointer());
			assertEquals(-1, s.read());
			assertEquals(-1, s.read(new byte[10], 0, 10));
			assertEquals(0, s.read(new byte[10], 0, 0));
			// and back: it goes on working
			s.seek(10);
			assertArrayEquals(Arrays.copyOfRange(data, 10, 20), readN(s, 10));
			assertThrows(java.io.IOException.class, () -> s.seek(-1));
		} finally {
			s.close();
		}
		assertEquals(data.length, factory.createFileSource("/seek.bin").length(), "nothing was changed");
	}

	@Test
	void whatCantBeOpenedIsRefused() throws Exception {
		assertThrows(FileNotFoundException.class, () -> factory.createFileSource("/nope.bin").getSeekableInputStream());
		assertTrue(factory.createFileSource("/adir").mkdir());
		assertThrows(java.io.IOException.class, () -> factory.createFileSource("/adir").getSeekableInputStream());
	}

	@Test
	void commandsAndOtherStreamsWorkWhileItIsOpen() throws Exception {
		ISeekableInputStream a = open();
		ISeekableInputStream b = open();
		try {
			a.seek(1000);
			assertArrayEquals(Arrays.copyOfRange(data, 1000, 1100), readN(a, 100));
			b.seek(200_000);
			assertArrayEquals(Arrays.copyOfRange(data, 200_000, 200_100), readN(b, 100));
			// commands on the main connection with two transfers open
			assertTrue(factory.createFileSource("/").isDirectory());
			assertTrue(Arrays.asList(factory.createFileSource("/").list()).contains("seek.bin"));
			assertArrayEquals(Arrays.copyOfRange(data, 1100, 1200), readN(a, 100));
		} finally {
			a.close();
			b.close();
		}
	}

	@Test
	void lengthIsWhatTheFileIsNow() throws Exception {
		ISeekableInputStream s = open();
		try {
			assertEquals(data.length, s.length());
			try (OutputStream out = factory.createFileSource("/seek.bin").getOutputStream(true)) {
				out.write(new byte[] {1, 2, 3});
			}
			assertEquals(data.length + 3, s.length());
		} finally {
			s.close();
		}
	}

	@Test
	void closedIsAtTheEndAndClosingTwiceIsFine() throws Exception {
		ISeekableInputStream s = open();
		s.seek(5);
		assertEquals(data[5] & 0xff, s.read());
		s.close();
		s.close();
		assertEquals(-1, s.read());
		// the connection it used is back for the next one
		ISeekableInputStream again = open();
		try {
			assertEquals(255, again.read());
		} finally {
			again.close();
		}
		FileSource f = factory.createFileSource("/seek.bin");
		assertTrue(f.exists());
	}
}
