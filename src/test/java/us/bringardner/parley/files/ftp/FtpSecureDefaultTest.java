package us.bringardner.parley.files.ftp;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Properties;

import org.junit.jupiter.api.Test;

/**
 * The secure (FTPS) setting has one default per factory type, whether the factory is new or its
 * connection properties leave secure out: false for ftp, true for ftps (To-Do 7.6).
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
		p.setProperty(FtpFileSourceFactory.PROP_SECURE, "false");
		FtpFileSourceFactory ftps = new FtpsFileSourceFactory();
		ftps.setConnectionProperties(p);
		assertFalse(ftps.isSecure());
	}
}
