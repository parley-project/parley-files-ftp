package us.bringardner.parley.files.ftp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import us.bringardner.parley.core.ILogger.Level;
import us.bringardner.parley.files.FileSource;
import us.bringardner.parley.files.FileSourceFactory;
import us.bringardner.parley.ftp.server.FtpServer;

/**
 * Setting times over FTP: the modification time with MFMT (parley's FtpServer has it), the creation time
 * with MFCT where the file system keeps one, and never the access time.
 */
public class FtpTimesTest {

	@TempDir
	static Path dir;
	private static FtpServer server;
	private static FtpFileSourceFactory factory;
	private static final long T = 1026940035000L; // 2002-07-17 21:07:15 UTC

	@BeforeAll
	static void start() throws Exception {
		server = new FtpServer();
		server.setFtpRoot(FileSourceFactory.getDefaultFactory().createFileSource(dir.toString()));
		server.setPort(Integer.parseInt(System.getProperty("FtpTimesPort", "8036")));
		server.getLogger().setLevel(Level.ERROR);
		server.start();
		long t = System.currentTimeMillis();
		while( !server.isRunning() && System.currentTimeMillis() - t < 5000 ) {
			Thread.sleep(50);
		}
		Properties prop = new Properties();
		prop.setProperty(FtpFileSourceFactory.PROP_USER, "ftp");
		prop.setProperty(FtpFileSourceFactory.PROP_PSWD, "foo@bar.com");
		prop.setProperty(FtpFileSourceFactory.PROP_HOST, "localhost");
		prop.setProperty(FtpFileSourceFactory.PROP_PORT, "" + server.getPort());
		prop.setProperty(FtpFileSourceFactory.PROP_SECURE, "false");
		factory = new FtpFileSourceFactory();
		factory.getLogger().setLevel(Level.ERROR);
		assertTrue(factory.connect(prop));
	}

	@AfterAll
	static void stop() throws Exception {
		factory.disConnect();
		server.stop();
	}

	@Test
	void modificationTimeWithMfmt() throws Exception {
		Path p = dir.resolve("m.txt");
		Files.write(p, new byte[] {1, 2, 3});
		FileSource f = factory.createFileSource("/m.txt");
		assertTrue(f.setLastModifiedTime(T + 999));
		assertEquals(T, Files.getLastModifiedTime(p).toMillis(), "whole seconds");
		assertTrue(factory.getFtpClient().getDialog().contains("MFMT 20020717210715 /m.txt"), factory.getFtpClient().getDialog());
		assertTrue(f.setLastModified(T + 60000));
		assertEquals(T + 60000, Files.getLastModifiedTime(p).toMillis());
	}

	@Test
	void accessAndCreationTimes() throws Exception {
		Files.write(dir.resolve("a.txt"), new byte[] {1});
		FileSource f = factory.createFileSource("/a.txt");
		assertFalse(f.setLastAccessTime(T), "FTP can't");
		// true only where the server's file system can set it; it mustn't throw either way
		f.setCreateTime(T);
	}
}
