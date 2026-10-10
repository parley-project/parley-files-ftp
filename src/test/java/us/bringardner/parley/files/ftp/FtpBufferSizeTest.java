package us.bringardner.parley.files.ftp;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Properties;
import java.util.Random;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import us.bringardner.parley.core.ILogger.Level;
import us.bringardner.parley.files.ConnectionSettings;
import us.bringardner.parley.files.FileSource;
import us.bringardner.parley.files.FileSourceFactory;
import us.bringardner.parley.ftp.server.FtpServer;

/**
 * The buffer size is for programmers: a per-connection value (the connect property
 * "bufferSize", or setBufferSize), else the JVM default ftp.bufferSize, else the
 * factory's own default. No dialog shows it, it isn't written into connections that
 * didn't set it, and a value that can't be used never stops a connection.
 */
public class FtpBufferSizeTest {

	private static final String KEY = FtpFileSourceFactory.PROP_BUFFER_SIZE;
	private static final String SYSTEM_PROPERTY = FtpFileSourceFactory.FACTORY_ID + "." + KEY;

	@AfterEach
	void clearSystemProperty() {
		System.clearProperty(SYSTEM_PROPERTY);
	}

	@Test
	void defaultIsTheClientsOwn() {
		FtpFileSourceFactory f = new FtpFileSourceFactory();
		assertEquals(FtpFileSourceFactory.DEFAULT_BUFFER_SIZE, f.getBufferSize());
		assertEquals(1024 * 65, FtpFileSourceFactory.DEFAULT_BUFFER_SIZE, "what the client has always used");
	}

	@Test
	void notShownToUsersAndNotWrittenUnlessSet() {
		FtpFileSourceFactory f = new FtpFileSourceFactory();
		assertNull(f.getConnectProperties().getProperty(KEY), "unset: saved connections follow the default");
		assertNull(ConnectionSettings.find(f.getConnectionSettings(), KEY), "never a setting in a dialog");

		f.setBufferSize(128 * 1024);
		assertEquals("131072", f.getConnectProperties().getProperty(KEY));
		assertNull(ConnectionSettings.find(f.getConnectionSettings(), KEY), "still not a setting");
	}

	@Test
	void keptInRange() {
		FtpFileSourceFactory f = new FtpFileSourceFactory();
		f.setBufferSize(1);
		assertEquals(FtpFileSourceFactory.MIN_BUFFER_SIZE, f.getBufferSize());
		f.setBufferSize(Integer.MAX_VALUE);
		assertEquals(FtpFileSourceFactory.MAX_BUFFER_SIZE, f.getBufferSize());
		f.setBufferSize(-5);
		assertEquals(FtpFileSourceFactory.MIN_BUFFER_SIZE, f.getBufferSize());
	}

	@Test
	void roundTripsThroughConnectPropertiesAndCopies() {
		FtpFileSourceFactory a = new FtpFileSourceFactory();
		a.setBufferSize(256 * 1024);

		FtpFileSourceFactory b = new FtpFileSourceFactory();
		b.setConnectionProperties(a.getConnectProperties());
		assertEquals(256 * 1024, b.getBufferSize());
		assertEquals("262144", b.getConnectProperties().getProperty(KEY), "still explicit after loading");

		FtpFileSourceFactory c = (FtpFileSourceFactory) a.createThreadSafeCopy();
		assertEquals(256 * 1024, c.getBufferSize());

		assertEquals(FtpFileSourceFactory.DEFAULT_BUFFER_SIZE, new FtpFileSourceFactory().getBufferSize());
	}

	@Test
	void aDialogCarriesItThrough() {
		FtpFileSourceFactory f = new FtpFileSourceFactory();
		f.setBufferSize(512 * 1024);
		Properties shown = f.getConnectProperties();
		assertTrue(ConnectionSettings.validate(f.getConnectionSettings(), shown).stream().noneMatch(p -> p.contains(KEY)));
		Properties saved = ConnectionSettings.forConnect(f.getConnectionSettings(), shown);
		assertEquals("524288", saved.getProperty(KEY));

		FtpFileSourceFactory g = new FtpFileSourceFactory();
		g.setConnectionProperties(saved);
		assertEquals(512 * 1024, g.getBufferSize());
	}

	@Test
	void anEmptyOrBadValueKeepsTheDefaultAndDoesNotThrow() {
		FtpFileSourceFactory f = new FtpFileSourceFactory();
		Properties p = f.getConnectProperties();
		p.setProperty(FtpFileSourceFactory.PROP_HOST, "example.org");
		p.setProperty(KEY, "");
		f.setConnectionProperties(p);
		assertEquals(FtpFileSourceFactory.DEFAULT_BUFFER_SIZE, f.getBufferSize());
		assertNull(f.getConnectProperties().getProperty(KEY), "empty is unset");

		p.setProperty(KEY, "lots");
		f.setConnectionProperties(p);
		assertEquals(FtpFileSourceFactory.DEFAULT_BUFFER_SIZE, f.getBufferSize());
		assertNull(f.getConnectProperties().getProperty(KEY));

		p.setProperty(KEY, "0");
		f.setConnectionProperties(p);
		assertEquals(FtpFileSourceFactory.MIN_BUFFER_SIZE, f.getBufferSize(), "kept within range");
	}

	@Test
	void jvmDefaultAppliesAndIsNotWrittenOut() {
		System.setProperty(SYSTEM_PROPERTY, "32768");
		FtpFileSourceFactory f = new FtpFileSourceFactory();
		assertEquals(32768, f.getBufferSize());
		assertNull(f.getConnectProperties().getProperty(KEY), "a JVM default isn't frozen into the connection");

		f.setBufferSize(16384);
		assertEquals(16384, f.getBufferSize(), "an explicit value wins");

		System.setProperty(SYSTEM_PROPERTY, "nonsense");
		assertEquals(FtpFileSourceFactory.DEFAULT_BUFFER_SIZE, new FtpFileSourceFactory().getBufferSize());
		System.setProperty(SYSTEM_PROPERTY, "10");
		assertEquals(FtpFileSourceFactory.MIN_BUFFER_SIZE, new FtpFileSourceFactory().getBufferSize());
		assertFalse(new FtpFileSourceFactory().getConnectProperties().containsKey(KEY));
	}

	/** The value reaches the client of a real connection, and transfers still come out right. */
	@Test
	void reachesTheClientAndTransfersStayCorrect() throws Exception {
		Path root = Paths.get("target", "FtpBufferRoot").toAbsolutePath();
		Files.createDirectories(root);
		FtpServer server = new FtpServer();
		server.setFtpRoot(FileSourceFactory.getDefaultFactory().createFileSource(root.toString()));
		server.setPort(Integer.parseInt(System.getProperty("FtpBufferPort", "8023")));
		server.getLogger().setLevel(Level.ERROR);
		server.start();
		FtpFileSourceFactory factory = null;
		try {
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
			prop.setProperty(KEY, "20000");
			factory = new FtpFileSourceFactory();
			factory.getLogger().setLevel(Level.ERROR);
			assertTrue(factory.connect(prop), "can't connect to the FTP server");

			assertEquals(20000, factory.getFtpClient().getTransferBufferSize(), "applied when the client was created");
			assertEquals(20000, factory.getBufferSize());

			// changing it on an open connection reaches the client too
			factory.setBufferSize(9000);
			assertEquals(9000, factory.getFtpClient().getTransferBufferSize());

			byte[] data = new byte[300_000];
			new Random(3).nextBytes(data);
			FileSource file = factory.createFileSource("/bufferSizeTest.bin");
			try (OutputStream out = file.getOutputStream()) {
				out.write(data);
			}
			byte[] back;
			try (InputStream in = file.getInputStream()) {
				back = in.readAllBytes();
			}
			assertArrayEquals(data, back);
			file.delete();
		} finally {
			if( factory != null ) {
				factory.disConnect();
			}
			server.stop();
		}
	}
}
