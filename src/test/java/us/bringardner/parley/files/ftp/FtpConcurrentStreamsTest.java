package us.bringardner.parley.files.ftp;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.Random;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import us.bringardner.parley.core.ILogger.Level;
import us.bringardner.parley.files.FileSource;
import us.bringardner.parley.files.FileSourceFactory;
import us.bringardner.parley.ftp.server.FtpServer;

/**
 * A control connection runs one transfer and refuses every command while it does, so each stream
 * has a connection of its own: any number can be open together, commands work while they are,
 * and a finished connection is kept for the next stream.
 */
public class FtpConcurrentStreamsTest {

	private FtpServer server;
	private FtpFileSourceFactory factory;

	@BeforeEach
	void start() throws Exception {
		Path root = Paths.get("target", "FtpConcurrentStreamsRoot").toAbsolutePath();
		Files.createDirectories(root);
		server = new FtpServer();
		server.setFtpRoot(FileSourceFactory.getDefaultFactory().createFileSource(root.toString()));
		server.setPort(Integer.parseInt(System.getProperty("FtpConcurrentStreamsPort", "8031")));
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

	private void write(String path, byte[] data) throws Exception {
		try (OutputStream out = factory.createFileSource(path).getOutputStream()) {
			out.write(data);
		}
	}

	@Test
	void readersAndAWriterAtOnceWithCommandsBetween() throws Exception {
		byte[] a = new byte[200_000];
		new Random(1).nextBytes(a);
		byte[] b = new byte[150_000];
		new Random(2).nextBytes(b);
		write("/a.bin", a);
		write("/b.bin", b);

		FileSource fa = factory.createFileSource("/a.bin");
		FileSource fb = factory.createFileSource("/b.bin");
		FileSource fc = factory.createFileSource("/c.bin");
		try (InputStream ra = fa.getInputStream(); InputStream rb = fb.getInputStream(); OutputStream wc = fc.getOutputStream()) {
			// commands on the main connection while three transfers are open
			assertTrue(factory.createFileSource("/").isDirectory());
			assertEquals(a.length, factory.createFileSource("/a.bin").length());
			assertTrue(java.util.Arrays.asList(factory.createFileSource("/").list()).contains("b.bin"));

			byte[] gotA = new byte[a.length];
			byte[] gotB = new byte[b.length];
			int half = a.length / 2;
			assertEquals(half, ra.readNBytes(gotA, 0, half));
			wc.write(a, 0, 1000);
			assertEquals(b.length, rb.readNBytes(gotB, 0, b.length));
			assertEquals(a.length - half, ra.readNBytes(gotA, half, a.length - half));
			wc.write(a, 1000, a.length - 1000);
			assertArrayEquals(a, gotA);
			assertArrayEquals(b, gotB);
		}
		// what was written is there, and the directory lists it
		try (InputStream in = factory.createFileSource("/c.bin").getInputStream()) {
			assertArrayEquals(a, in.readAllBytes());
		}
		assertTrue(java.util.Arrays.asList(factory.createFileSource("/").list()).contains("c.bin"));
		assertEquals(a.length, factory.createFileSource("/c.bin").length());
	}

	@Test
	void aFinishedConnectionIsKeptForTheNextStream() throws Exception {
		write("/k.bin", new byte[] {1, 2, 3});
		assertEquals(1, factory.idleTransferConnections(), "the write's connection is kept");
		for(int i = 0; i < 3; i++) {
			try (InputStream in = factory.createFileSource("/k.bin").getInputStream()) {
				assertEquals(3, in.readAllBytes().length);
			}
			assertEquals(1, factory.idleTransferConnections(), "one at a time keeps using the same one");
		}

		// several at once need several, and no more than the limit is kept
		List<InputStream> open = new ArrayList<>();
		try {
			for(int i = 0; i < FtpFileSourceFactory.MAX_IDLE_TRANSFER_CONNECTIONS + 2; i++) {
				open.add(factory.createFileSource("/k.bin").getInputStream());
			}
		} finally {
			for(InputStream in : open) {
				in.close();
			}
		}
		assertEquals(FtpFileSourceFactory.MAX_IDLE_TRANSFER_CONNECTIONS, factory.idleTransferConnections());

		factory.disConnect();
		assertEquals(0, factory.idleTransferConnections(), "disconnecting closes them");
	}

	@Test
	void aStreamThatFailsGivesItsConnectionUp() throws Exception {
		int before = factory.idleTransferConnections();
		try {
			factory.createFileSource("/missing-dir/x.bin").getOutputStream().close();
		} catch (java.io.IOException expected) {
			// the server refuses it
		}
		// nothing broken is kept: the next stream still works
		write("/ok.bin", new byte[] {9});
		try (InputStream in = factory.createFileSource("/ok.bin").getInputStream()) {
			assertArrayEquals(new byte[] {9}, in.readAllBytes());
		}
		assertTrue(factory.idleTransferConnections() >= before);
	}
}
