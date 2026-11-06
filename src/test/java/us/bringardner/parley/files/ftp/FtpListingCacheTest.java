package us.bringardner.parley.files.ftp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Properties;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import us.bringardner.parley.core.ILogger.Level;
import us.bringardner.parley.files.FileSource;
import us.bringardner.parley.files.FileSourceFactory;
import us.bringardner.parley.files.test.FileSourceTestSupport;
import us.bringardner.parley.ftp.server.AnonymousUserRegistry;
import us.bringardner.parley.ftp.server.FtpServer;

/**
 * What a folder listing costs: the permissions of all its entries come from one LIST, a change
 * only drops what it touched, and a FileSource made from a path uses a listing already read.
 */
public class FtpListingCacheTest {

	private static final int ENTRIES = 12;

	private FtpServer server;
	private FtpFileSourceFactory factory;
	private int mark;

	@BeforeEach
	void start() throws Exception {
		Path root = Paths.get(FileSourceTestSupport.scratchDir("FtpListingCacheRoot"));
		// each test starts with the same files
		if( Files.exists(root) ) {
			try (java.util.stream.Stream<Path> all = Files.walk(root)) {
				all.sorted(java.util.Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
			}
		}
		for (String d : new String[] {"a", "b"}) {
			Files.createDirectories(root.resolve(d).resolve("sub"));
			for (int i = 0; i < ENTRIES; i++) {
				Files.writeString(root.resolve(d).resolve("f" + i + ".txt"), "x");
			}
		}
		server = new FtpServer();
		server.setUserRegistry(new AnonymousUserRegistry());
		server.setFtpRoot(FileSourceFactory.getDefaultFactory().createFileSource(root.toString()));
		server.setPort(Integer.parseInt(System.getProperty("FtpListingCachePort", "8037")));
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
		// long enough that nothing in a test expires on its own
		factory.setAttributeCacheTtl(60_000);
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

	/** @return how many times cmd was sent on the main connection since the last call */
	private int sent(String cmd) throws Exception {
		String[] lines = factory.getFtpClient().getDialog().split("\n");
		int ret = 0;
		for (int i = mark; i < lines.length; i++) {
			int w = lines[i].indexOf(" Write:");
			if( w >= 0 && lines[i].substring(w + 7).trim().toUpperCase().startsWith(cmd) ) {
				ret++;
			}
		}
		mark = lines.length;
		return ret;
	}

	private FileSource child(FileSource dir, String name) throws Exception {
		for (FileSource f : dir.listFiles()) {
			if( f.getName().equals(name) ) {
				return f;
			}
		}
		throw new AssertionError(name + " not in " + dir);
	}

	@Test
	void onePermissionListingPerFolder() throws Exception {
		FileSource a = factory.createFileSource("/a");
		FileSource[] kids = a.listFiles();
		assertEquals(ENTRIES + 1, kids.length);
		sent("LIST");
		for (FileSource f : kids) {
			assertTrue(f.canRead(), f + " should be readable");
		}
		// one LIST for every entry's permissions (it was one per entry)
		assertEquals(1, sent("LIST"));
	}

	@Test
	void listingsDontUseTheMainConnection() throws Exception {
		sent("MLSD");
		factory.createFileSource("/a").listFiles();
		factory.createFileSource("/b").listFiles();
		// on connections of their own, so they don't wait for the main one or each other
		assertEquals(0, sent("MLSD"));
		assertTrue(factory.idleTransferConnections() > 0, "the listing's connection is kept for the next one");
	}

	@Test
	void aChangeOnlyDropsWhatItTouched() throws Exception {
		FileSource a = factory.createFileSource("/a");
		FileSource b = factory.createFileSource("/b");
		FileSource[] aKids = a.listFiles();
		FileSource[] bKids = b.listFiles();

		assertTrue(factory.createFileSource("/a/new").mkdir());

		// /b wasn't touched: still the same listing (any change used to drop every listing)
		assertSame(bKids, b.listFiles());
		// /a was: listed again, with the new folder
		FileSource[] again = a.listFiles();
		assertNotSame(aKids, again);
		assertEquals(aKids.length + 1, again.length);
	}

	@Test
	void aRenamedFolderDropsWhatIsInIt() throws Exception {
		FileSource sub = child(factory.createFileSource("/b"), "sub");
		sub.listFiles();
		assertTrue(sub.exists());
		assertTrue(sub.renameTo(factory.createFileSource("/b/moved")));
		assertTrue(!sub.exists(), "the old path is gone");
		assertTrue(factory.createFileSource("/b/moved").isDirectory());
	}

	@Test
	void aFileSourceFromAPathUsesARecentListing() throws Exception {
		factory.createFileSource("/a").listFiles();
		sent("MLST");
		FileSource f = factory.createFileSource("/a/f3.txt");
		assertTrue(f.exists());
		assertEquals(1, f.length());
		// known from /a's listing: no MLST for it
		assertEquals(0, sent("MLST"));
		// and another FileSource for /a doesn't list it again
		FileSource[] kids = factory.createFileSource("/a").listFiles();
		assertEquals(ENTRIES + 1, kids.length);
	}

	@Test
	void refreshReadsTheFolderAgain() throws Exception {
		FileSource a = factory.createFileSource("/a");
		FileSource[] kids = a.listFiles();
		Path root = Paths.get(FileSourceTestSupport.scratchDir("FtpListingCacheRoot"));
		// made behind the factory's back, as someone else would
		Files.writeString(root.resolve("a").resolve("other.txt"), "y");
		assertSame(kids, a.listFiles(), "trusted until the cache time or a refresh");
		a.refresh();
		assertEquals(kids.length + 1, a.listFiles().length);
		// every FileSource for the folder sees it, not only the one refreshed
		assertEquals(kids.length + 1, factory.createFileSource("/a").listFiles().length);
	}

	@Test
	void writingAFileDropsItsFolderListing() throws Exception {
		FileSource a = factory.createFileSource("/a");
		int n = a.listFiles().length;
		try (java.io.OutputStream out = factory.createFileSource("/a/written.txt").getOutputStream()) {
			out.write(1);
		}
		assertEquals(n + 1, a.listFiles().length);
		assertEquals(n + 1, factory.createFileSource("/a").listFiles().length);
	}
}
