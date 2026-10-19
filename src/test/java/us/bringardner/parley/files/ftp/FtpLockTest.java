package us.bringardner.parley.files.ftp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import us.bringardner.parley.core.ILogger.Level;
import us.bringardner.parley.files.FileSource;
import us.bringardner.parley.files.FileSourceFactory;
import us.bringardner.parley.files.lock.LockScope;
import us.bringardner.parley.files.test.AbstractLockTests;
import us.bringardner.parley.files.test.FileSourceTestSupport;
import us.bringardner.parley.ftp.server.FtpServer;

/**
 * The lock contract over FTP. The protocol has no locks, so FtpFileSource keeps the default:
 * locks within this JVM (PROCESS); the cross-process cases are skipped.
 */
public class FtpLockTest extends AbstractLockTests {

	private static final AtomicInteger DIRS = new AtomicInteger();
	private static FtpServer server;
	private static FtpFileSourceFactory factory;
	private static Path root;

	@BeforeAll
	static void startServer() throws Exception {
		root = Paths.get(FileSourceTestSupport.scratchDir("FtpLockRoot"));
		Files.createDirectories(root);
		server = new FtpServer();
		server.setFtpRoot(FileSourceFactory.getDefaultFactory().createFileSource(root.toString()));
		server.setPort(Integer.parseInt(System.getProperty("FtpLockPort", "8026")));
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

	@Override
	protected FileSource newLockDirectory() throws Exception {
		String name = "locks" + System.nanoTime() + "-" + DIRS.incrementAndGet();
		Files.createDirectories(root.resolve(name));
		return factory.createFileSource("/" + name);
	}

	@Test
	public void ftpLocksStayInThisJvm() {
		assertEquals(LockScope.PROCESS, dir.getLockScope());
	}
}
