package us.bringardner.parley.files.ftp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Properties;

import org.junit.jupiter.api.Test;

/**
 * createThreadSafeCopy() is "the same configuration but not the same connection". It used
 * to copy only host, port, user and password, so a copy of a TLS connection connected in
 * the clear, and a copy of an FTPS factory was a plain FTP one.
 */
public class FtpThreadSafeCopyTest {

	private static void configure(FtpFileSourceFactory f) {
		f.setHost("ftp.example.org");
		f.setPort(2121);
		f.setUser("someone");
		f.setPasswd("not-a-real-password");
		f.setAccount("billing");
		f.setTimeout(9000);
		f.setBufferSize(128 * 1024);
	}

	@Test
	void aCopyHasTheSameConfiguration() {
		FtpFileSourceFactory f = new FtpFileSourceFactory();
		configure(f);
		f.setSecure(true);

		FtpFileSourceFactory c = (FtpFileSourceFactory) f.createThreadSafeCopy();
		assertNotSame(f, c);
		assertEquals(f.getConnectProperties(), c.getConnectProperties());
		// spelled out, so a failure says which setting was lost
		Properties p = c.getConnectProperties();
		assertEquals("true", p.getProperty(FtpFileSourceFactory.PROP_SECURE), "secure");
		assertEquals("billing", p.getProperty(FtpFileSourceFactory.PROP_ACCT), "account");
		assertEquals("9000", p.getProperty(FtpFileSourceFactory.PROP_TIMEOUT), "timeout");
		assertEquals("131072", p.getProperty(FtpFileSourceFactory.PROP_BUFFER_SIZE), "buffer size");
	}

	@Test
	void aPlainCopyStaysPlain() {
		FtpFileSourceFactory f = new FtpFileSourceFactory();
		configure(f);
		f.setSecure(false);
		FtpFileSourceFactory c = (FtpFileSourceFactory) f.createThreadSafeCopy();
		assertEquals("false", c.getConnectProperties().getProperty(FtpFileSourceFactory.PROP_SECURE));
		assertEquals(f.getConnectProperties(), c.getConnectProperties());
	}

	@Test
	void aCopyOfAnFtpsFactoryIsAnFtpsFactory() {
		FtpsFileSourceFactory f = new FtpsFileSourceFactory();
		configure(f);

		FileSourceFactoryHolder h = new FileSourceFactoryHolder(f.createThreadSafeCopy());
		assertTrue(h.factory instanceof FtpsFileSourceFactory, "was " + h.factory.getClass().getName());
		assertEquals("ftps", h.factory.getTypeId());
		assertEquals(f.getTitle(), h.factory.getTitle());
		assertEquals("true", h.factory.getConnectProperties().getProperty(FtpFileSourceFactory.PROP_SECURE));
		assertEquals(f.getConnectProperties(), h.factory.getConnectProperties());
	}

	@Test
	void aCopyIsIndependentOfTheOriginal() {
		FtpFileSourceFactory f = new FtpFileSourceFactory();
		configure(f);
		FtpFileSourceFactory c = (FtpFileSourceFactory) f.createThreadSafeCopy();

		c.setTimeout(1);
		c.setSecure(true);
		c.setBufferSize(32 * 1024);
		assertEquals("9000", f.getConnectProperties().getProperty(FtpFileSourceFactory.PROP_TIMEOUT));
		assertEquals("false", f.getConnectProperties().getProperty(FtpFileSourceFactory.PROP_SECURE));
		assertEquals(128 * 1024, f.getBufferSize());
	}

	/** Keeps the cast out of the assertions above, which are about the factory's real type. */
	private static final class FileSourceFactoryHolder {
		final us.bringardner.parley.files.FileSourceFactory factory;

		FileSourceFactoryHolder(us.bringardner.parley.files.FileSourceFactory factory) {
			this.factory = factory;
		}
	}
}
