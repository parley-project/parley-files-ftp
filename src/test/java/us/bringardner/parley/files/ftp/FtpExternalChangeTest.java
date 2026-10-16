package us.bringardner.parley.files.ftp;

import us.bringardner.parley.files.test.FileSourceTestSupport;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Properties;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import us.bringardner.parley.core.ILogger.Level;
import us.bringardner.parley.files.FileSource;
import us.bringardner.parley.files.FileSourceFactory;
import us.bringardner.parley.ftp.server.FtpServer;

/**
 * A change made through another connection can't be announced to this factory, so a FileSource
 * that was kept sees it when what it remembered expires (getAttributeCacheTtl).
 */
public class FtpExternalChangeTest {

	private static final long TTL = 150;
	private FtpServer server;
	private FtpFileSourceFactory mine;
	private FtpFileSourceFactory other;

	private FtpFileSourceFactory connect(Properties prop) throws Exception {
		FtpFileSourceFactory f = new FtpFileSourceFactory();
		f.getLogger().setLevel(Level.ERROR);
		assertTrue(f.connect(prop), "can't connect to the FTP server");
		return f;
	}

	@BeforeEach
	void start() throws Exception {
		Path root = Paths.get(FileSourceTestSupport.scratchDir("FtpExternalChangeRoot"));
		// a root that is empty: an earlier run's files would still be there
		if( Files.exists(root) ) {
			try (java.util.stream.Stream<Path> walk = Files.walk(root)) {
				walk.sorted(java.util.Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
			}
		}
		Files.createDirectories(root);
		server = new FtpServer();
		server.setFtpRoot(FileSourceFactory.getDefaultFactory().createFileSource(root.toString()));
		// A free port for each test: the server is restarted before every one, and the last one's
		// port may not be released yet (BindException: Address already in use)
		String port = System.getProperty("FtpExternalChangePort");
		if( port == null ) {
			try (java.net.ServerSocket s = new java.net.ServerSocket(0)) {
				port = "" + s.getLocalPort();
			}
		}
		server.setPort(Integer.parseInt(port));
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
		mine = connect(prop);
		other = connect(prop);
		mine.setAttributeCacheTtl(TTL);
	}

	@AfterEach
	void stop() throws Exception {
		if( mine != null ) {
			mine.disConnect();
		}
		if( other != null ) {
			other.disConnect();
		}
		if( server != null ) {
			server.stop();
		}
	}

	private static void pause() throws InterruptedException {
		Thread.sleep(TTL + 100);
	}

	private static void write(FileSource f, byte... data) throws Exception {
		try (OutputStream out = f.getOutputStream()) {
			out.write(data);
		}
	}

	@Test
	void theDefaultIsTwoSecondsAndCanBeSet() {
		FtpFileSourceFactory f = new FtpFileSourceFactory();
		assertEquals(2000, f.getAttributeCacheTtl());
		f.setAttributeCacheTtl(0);
		assertEquals(0, f.getAttributeCacheTtl());
		f.setAttributeCacheTtl(-5);
		assertEquals(0, f.getAttributeCacheTtl(), "never negative");
		FtpFileSourceFactory copy = (FtpFileSourceFactory) f.createThreadSafeCopy();
		assertEquals(0, copy.getAttributeCacheTtl(), "a copy keeps it");
	}

	@Test
	void aDeleteRewriteAndCreateByAnotherConnectionAreSeen() throws Exception {
		write(mine.createFileSource("/ext.bin"), (byte) 1, (byte) 2, (byte) 3);
		FileSource held = mine.createFileSource("/ext.bin");
		assertTrue(held.exists());
		assertEquals(3, held.length());

		write(other.createFileSource("/ext.bin"), (byte) 1, (byte) 2, (byte) 3, (byte) 4, (byte) 5);
		pause();
		assertEquals(5, held.length(), "a rewrite");

		assertTrue(other.createFileSource("/ext.bin").delete());
		pause();
		assertFalse(held.exists(), "a delete");
		assertFalse(held.isFile());

		write(other.createFileSource("/ext.bin"), (byte) 9);
		pause();
		assertTrue(held.exists(), "created again");
		assertEquals(1, held.length());
	}

	@Test
	void aListingSeesChildrenAnotherConnectionMade() throws Exception {
		FileSource dir = mine.createFileSource("/extdir");
		assertTrue(dir.mkdirs());
		assertEquals(0, dir.list().length);
		write(other.createFileSource("/extdir/new.txt"), (byte) 1);
		pause();
		assertEquals(Arrays.asList("new.txt"), Arrays.asList(dir.list()));
	}
}
