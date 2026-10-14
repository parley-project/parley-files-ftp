package us.bringardner.parley.files.ftp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import us.bringardner.parley.core.ILogger.Level;
import us.bringardner.parley.files.FileSource;
import us.bringardner.parley.files.credentials.CredentialException;
import us.bringardner.parley.files.credentials.Credentials;
import us.bringardner.parley.files.credentials.EnvCredentialProvider;

/** The password and account may be references (@env:..., @prompt:...), resolved only to log in. */
public class FtpCredentialReferenceTest {

	private static FtpTestServer server;

	@BeforeAll
	public static void start() throws IOException {
		server = new FtpTestServer();
		server.start();
	}

	@AfterAll
	public static void stop() throws IOException {
		server.stop();
	}

	@AfterEach
	public void defaults() {
		Credentials.restoreDefaults();
		Credentials.setPrompter(null);
	}

	private static Properties props(String password) {
		Properties prop = new Properties();
		prop.setProperty(FtpFileSourceFactory.PROP_USER, "ftp");
		prop.setProperty(FtpFileSourceFactory.PROP_PSWD, password);
		prop.setProperty(FtpFileSourceFactory.PROP_HOST, "localhost");
		prop.setProperty(FtpFileSourceFactory.PROP_PORT, ""+FtpTestServer.PORT);
		prop.setProperty(FtpFileSourceFactory.PROP_SECURE, "false");
		return prop;
	}

	private static FtpFileSourceFactory factory() {
		FtpFileSourceFactory f = new FtpFileSourceFactory();
		f.getLogger().setLevel(Level.ERROR);
		return f;
	}

	@Test
	public void aReferenceIsResolvedToLogInAndKeptAsIs() throws IOException {
		Credentials.register(new EnvCredentialProvider(Map.of("FTP_PW", "foo@bar.com")::get));
		FtpFileSourceFactory f = factory();
		try {
			assertTrue(f.connect(props("@env:FTP_PW")));
			Properties p = f.getConnectProperties();
			assertEquals("@env:FTP_PW", p.getProperty(FtpFileSourceFactory.PROP_PSWD), "the factory keeps the reference");
		} finally {
			f.disConnect();
		}
	}

	@Test
	public void aPromptIsAskedOnceForEveryConnectionOfTheSession() throws IOException {
		List<String> asked = new ArrayList<>();
		Credentials.setPrompter((label, req) -> { asked.add(label); return "foo@bar.com".toCharArray(); });
		FtpFileSourceFactory f = factory();
		try {
			assertTrue(f.connect(props("@prompt:")));
			// streams log in on their own connections, with the same answer
			FileSource file = f.createFileSource("/credential-ref.txt");
			try (OutputStream out = file.getOutputStream()) {
				out.write("hello".getBytes(StandardCharsets.UTF_8));
			}
			try (InputStream in = file.getInputStream()) {
				assertEquals("hello", new String(in.readAllBytes(), StandardCharsets.UTF_8));
			}
			file.delete();
			assertEquals(List.of("password for ftp@localhost (ftp)"), asked);
		} finally {
			f.disConnect();
		}
	}

	@Test
	public void aReferenceThatCantBeResolvedFailsTheConnectNamingIt() {
		FtpFileSourceFactory f = factory();
		CredentialException e = assertThrows(CredentialException.class, () -> f.connect(props("@env:NO_SUCH_FTP_PW_VAR")));
		assertTrue(e.getMessage().startsWith("@env:NO_SUCH_FTP_PW_VAR"), e.getMessage());
		assertFalse(f.isConnected());
	}

	@Test
	public void aPlainPasswordStillWorks() throws IOException {
		FtpFileSourceFactory f = factory();
		try {
			assertTrue(f.connect(props("foo@bar.com")));
		} finally {
			f.disConnect();
		}
	}
}
