package us.bringardner.parley.files.ftp;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;

import us.bringardner.parley.core.ILogger.Level;
import us.bringardner.parley.files.FileSource;
import us.bringardner.parley.files.FileSourceFactory;
import us.bringardner.parley.files.test.FileLikeBehaviorTests;
import us.bringardner.parley.ftp.server.FtpServer;

/** An FTP server, reached through FtpFileSource, acts like a java.io.File. */
public class FtpFileLikeTest extends FileLikeBehaviorTests {

	private static final AtomicInteger TREES = new AtomicInteger();

	private static FtpServer server;
	private static FtpFileSourceFactory factory;
	private static Path root;

	private String tree;

	@BeforeAll
	static void startServer() throws Exception {
		root = Paths.get("target", "FtpFileLikeRoot").toAbsolutePath();
		// start empty: a tree left by an earlier run would be taken for part of this one
		deleteAll(root.toFile());
		Files.createDirectories(root);
		server = new FtpServer();
		server.setFtpRoot(FileSourceFactory.getDefaultFactory().createFileSource(root.toString()));
		server.setPort(Integer.parseInt(System.getProperty("FtpFileLikePort", "8025")));
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

	@AfterAll
	static void stopServer() throws Exception {
		if( factory != null ) {
			factory.disConnect();
		}
		if( server != null ) {
			server.stop();
		}
	}

	/**
	 * Removes a tree, making each directory writable and enterable first: an earlier run (or a case
	 * that failed) can leave a read-only directory, which can't have its children deleted. This
	 * silently failed to, so the next run found its "empty" tree numbers already used, with a
	 * read-only directory in them, which looked like a difference in the FTP client.
	 */
	private static void deleteAll(java.io.File f) {
		f.setWritable(true);
		f.setExecutable(true);
		java.io.File[] kids = f.listFiles();
		if( kids != null ) {
			for(java.io.File k : kids) {
				deleteAll(k);
			}
		}
		f.delete();
	}

	/**
	 * The embedded server only lets "ftp" in, and the files belong to whoever runs the tests, so
	 * canWrite() of an existing file is judged from the permission bits as "other", while a
	 * java.io.File says true for its owner. Missing paths are still compared.
	 */
	@Override
	protected boolean permissionsOfExistingPathsAreComparable() {
		return false;
	}

	@Override
	protected void newTree() throws Exception {
		tree = "tree" + TREES.incrementAndGet();
		Files.createDirectories(root.resolve(tree));
	}

	@Override
	protected FileSource sourceFor(String relative) throws Exception {
		return factory.createFileSource("/" + tree + "/" + relative);
	}

	/** The FTP protocol has no links: FtpFileSourceFactory throws UnsupportedOperationException. */
	@Override
	protected boolean supportsSymbolicLinks() {
		return false;
	}

	/** The FTP protocol has no links: FtpFileSourceFactory throws UnsupportedOperationException. */
	@Override
	protected boolean supportsHardLinks() {
		return false;
	}
}
