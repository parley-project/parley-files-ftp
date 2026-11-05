package us.bringardner.parley.files.ftp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Properties;

import org.junit.jupiter.api.Test;

/**
 * The secure (FTPS) setting has one default per factory type, whether the factory is new or its
 * connection properties leave secure out: false for ftp, true for ftps (To-Do 7.6). ftps is implicit
 * TLS, always secure, on port 990 unless another is given; ftp's secure is explicit TLS on port 21.
 */
public class FtpSecureDefaultTest {

	private static Properties withoutSecure() {
		Properties p = new Properties();
		p.setProperty(FtpFileSourceFactory.PROP_HOST, "localhost");
		p.setProperty(FtpFileSourceFactory.PROP_USER, "tony");
		return p;
	}

	@Test
	public void ftpIsPlainByDefault() {
		FtpFileSourceFactory f = new FtpFileSourceFactory();
		assertFalse(f.isSecure(), "a new ftp factory");
		f.setConnectionProperties(withoutSecure());
		assertFalse(f.isSecure(), "ftp properties without secure");
		f.setConnectionProperties((Properties) null);
		assertFalse(f.isSecure(), "ftp properties cleared");
	}

	@Test
	public void ftpsIsSecureByDefault() {
		FtpFileSourceFactory f = new FtpsFileSourceFactory();
		assertTrue(f.isSecure(), "a new ftps factory");
		f.setConnectionProperties(withoutSecure());
		assertTrue(f.isSecure(), "ftps properties without secure");
		f.setConnectionProperties((Properties) null);
		assertTrue(f.isSecure(), "ftps properties cleared");
	}

	@Test
	public void secureGivenWins() {
		Properties p = withoutSecure();
		p.setProperty(FtpFileSourceFactory.PROP_SECURE, "true");
		FtpFileSourceFactory ftp = new FtpFileSourceFactory();
		ftp.setConnectionProperties(p);
		assertTrue(ftp.isSecure());
	}

	@Test
	public void ftpsIsAlwaysSecure() {
		// implicit TLS: there's nothing to turn off
		Properties p = withoutSecure();
		p.setProperty(FtpFileSourceFactory.PROP_SECURE, "false");
		FtpFileSourceFactory ftps = new FtpsFileSourceFactory();
		ftps.setConnectionProperties(p);
		assertTrue(ftps.isSecure());
		assertEquals("true", ftps.getConnectProperties().getProperty(FtpFileSourceFactory.PROP_SECURE));
		ftps.setSecure(false);
		assertTrue(ftps.isSecure());
	}

	@Test
	public void defaultPortFollowsTheType() {
		FtpFileSourceFactory ftp = new FtpFileSourceFactory();
		FtpFileSourceFactory ftps = new FtpsFileSourceFactory();
		assertEquals(21, ftp.getPort(), "a new ftp factory");
		assertEquals(990, ftps.getPort(), "a new ftps factory");
		ftp.setConnectionProperties(withoutSecure());
		ftps.setConnectionProperties(withoutSecure());
		assertEquals(21, ftp.getPort(), "ftp properties without a port");
		assertEquals(990, ftps.getPort(), "ftps properties without a port");
		Properties p = withoutSecure();
		p.setProperty(FtpFileSourceFactory.PROP_PORT, "2121");
		ftps.setConnectionProperties(p);
		assertEquals(2121, ftps.getPort(), "a port given wins");
		// ftp with TLS is explicit TLS: still port 21
		p = withoutSecure();
		p.setProperty(FtpFileSourceFactory.PROP_SECURE, "true");
		ftp.setConnectionProperties(p);
		assertEquals(21, ftp.getPort());
	}

	@Test
	public void theFormShowsTheTypesPortAndOnlyFtpHasTheTlsChoice() {
		FtpFileSourceFactory ftp = new FtpFileSourceFactory();
		FtpFileSourceFactory ftps = new FtpsFileSourceFactory();
		assertEquals("21", setting(ftp, FtpFileSourceFactory.PROP_PORT).defaultValue());
		assertEquals("990", setting(ftps, FtpFileSourceFactory.PROP_PORT).defaultValue());
		assertNotNull(setting(ftp, FtpFileSourceFactory.PROP_SECURE));
		assertNull(setting(ftps, FtpFileSourceFactory.PROP_SECURE));
	}

	private static us.bringardner.parley.files.ConnectionSetting setting(FtpFileSourceFactory f, String name) {
		for (us.bringardner.parley.files.ConnectionSetting s : f.getConnectionSettings()) {
			if( s.key().equals(name) ) {
				return s;
			}
		}
		return null;
	}
}
