package us.bringardner.parley.files.ftp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Properties;

import org.junit.jupiter.api.Test;

import us.bringardner.parley.files.ConnectionSetting;
import us.bringardner.parley.files.ConnectionSettings;

/** FTP's connection settings, described for any UI (they used to be a Swing panel). */
public class FtpConnectionSettingsTest {

	@Test
	public void everyPropertyIsDescribedAndSecretsStaySecret() {
		FtpFileSourceFactory f = new FtpFileSourceFactory();
		List<ConnectionSetting> settings = f.getConnectionSettings();
		for(String key : f.getConnectProperties().stringPropertyNames()) {
			ConnectionSetting s = ConnectionSettings.find(settings, key);
			assertNotNull(s, key+" isn't described");
			assertEquals(f.isSecretProperty(key), s.isSecret(), key);
		}
	}

	@Test
	public void aNewConnectionStartsEmpty() {
		// the old panel started with one particular account filled in
		FtpFileSourceFactory f = new FtpFileSourceFactory();
		Properties p = ConnectionSettings.initialValues(f.getConnectionSettings(), f.getConnectProperties());
		assertEquals("", p.getProperty(FtpFileSourceFactory.PROP_HOST));
		assertEquals("", p.getProperty(FtpFileSourceFactory.PROP_USER));
		assertEquals("", p.getProperty(FtpFileSourceFactory.PROP_ACCT));
		assertEquals(""+FtpFileSourceFactory.DEFAULT_PORT, p.getProperty(FtpFileSourceFactory.PROP_PORT));
	}

	@Test
	public void checkedBeforeConnecting() {
		FtpFileSourceFactory f = new FtpFileSourceFactory();
		Properties p = new Properties();
		p.setProperty(FtpFileSourceFactory.PROP_PORT, "x");
		p.setProperty(FtpFileSourceFactory.PROP_TIMEOUT, "-5");
		assertEquals(List.of("Host is required", "Port must be a whole number", "Timeout (ms) must be at least 0"),
				f.validateConnection(p));
	}

	@Test
	public void theTimeoutSurvivesARoundTrip() {
		FtpFileSourceFactory f = new FtpFileSourceFactory();
		Properties p = f.getConnectProperties();
		p.setProperty(FtpFileSourceFactory.PROP_HOST, "example.org");
		p.setProperty(FtpFileSourceFactory.PROP_TIMEOUT, "9000");
		f.setConnectionProperties(p);
		assertEquals("9000", f.getConnectProperties().getProperty(FtpFileSourceFactory.PROP_TIMEOUT));
		assertTrue(f.getConnectionSettings().size() >= f.getConnectProperties().size());
	}
}
