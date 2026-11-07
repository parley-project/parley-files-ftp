package us.bringardner.parley.files.ftp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.TimeUnit;

import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import us.bringardner.parley.core.ILogger.Level;
import us.bringardner.parley.core.SecureBaseObject;
import us.bringardner.parley.files.FileSource;
import us.bringardner.parley.files.FileSourceFactory;
import us.bringardner.parley.files.test.FileSourceTestSupport;
import us.bringardner.parley.ftp.server.AnonymousUserRegistry;
import us.bringardner.parley.iam.permission.StandardPermissions;
import us.bringardner.parley.ftp.server.FtpServer;

/**
 * How each type uses TLS: ftp with secure is explicit TLS (AUTH TLS on a server that starts in plain
 * text, and no login without it), ftp without it is plain FTP, and ftps is implicit TLS.
 */
public class FtpTlsTest {

	private static final String[] KEYSTORE_PROPERTIES = {
			"parley.ftp.keyStoreName", "parley.ftp.keyStorePassword", "parley.ftp.keyStoreType", "parley.ftp.algorithm" };
	private static final String PASSWORD = "test-only";

	private static final int EXPLICIT_PORT = Integer.parseInt(System.getProperty("FtpTlsExplicitPort", "8038"));
	private static final int IMPLICIT_PORT = Integer.parseInt(System.getProperty("FtpTlsImplicitPort", "8039"));

	private static final List<String> savedProperties = new ArrayList<>();
	private static TrustManager[] savedTrust;
	private static File dir;
	private static FtpServer explicitServer;
	private static FtpServer implicitServer;

	static class TrustAll implements X509TrustManager {
		@Override
		public void checkClientTrusted(X509Certificate[] chain, String authType) {
		}
		@Override
		public void checkServerTrusted(X509Certificate[] chain, String authType) {
		}
		@Override
		public X509Certificate[] getAcceptedIssuers() {
			return new X509Certificate[0];
		}
	}

	@BeforeAll
	static void start() throws Exception {
		dir = Files.createTempDirectory("parley-files-ftp-tls").toFile();
		File ks = new File(dir, "server.p12");
		String keytool = Paths.get(System.getProperty("java.home"), "bin", "keytool").toString();
		Process p = new ProcessBuilder(keytool, "-genkeypair", "-noprompt", "-alias", "server",
				"-dname", "CN=localhost", "-ext", "SAN=dns:localhost,ip:127.0.0.1",
				"-keystore", ks.getPath(), "-storetype", "PKCS12", "-storepass", PASSWORD, "-keypass", PASSWORD,
				"-keyalg", "RSA", "-keysize", "2048", "-validity", "2")
				.redirectErrorStream(true).start();
		p.getInputStream().readAllBytes();
		assertTrue(p.waitFor(60, TimeUnit.SECONDS) && p.exitValue() == 0, "keytool failed");

		for (String name : KEYSTORE_PROPERTIES) {
			savedProperties.add(System.getProperty(name));
		}
		System.setProperty("parley.ftp.keyStoreName", ks.getPath());
		System.setProperty("parley.ftp.keyStorePassword", PASSWORD);
		System.setProperty("parley.ftp.keyStoreType", "PKCS12");
		System.setProperty("parley.ftp.algorithm", "SunX509");
		// the test certificate is trusted as the viewer's certificate dialog would after asking
		savedTrust = SecureBaseObject.getDefaultTrustManagers();
		SecureBaseObject.setDefaultTrustManagers(new TrustManager[] { new TrustAll() });

		explicitServer = server("FtpTlsExplicitRoot", EXPLICIT_PORT, false);
		implicitServer = server("FtpTlsImplicitRoot", IMPLICIT_PORT, true);
	}

	/** A server with its own root (two servers can't share one), holding /folder/a.txt. */
	private static FtpServer server(String rootName, int port, boolean implicit) throws Exception {
		Path root = Paths.get(FileSourceTestSupport.scratchDir(rootName));
		Files.createDirectories(root.resolve("folder"));
		Files.writeString(root.resolve("folder").resolve("a.txt"), "hello");
		FtpServer s = new FtpServer();
		s.setUserRegistry(new AnonymousUserRegistry(StandardPermissions.READ, StandardPermissions.WRITE));
		s.setFtpRoot(FileSourceFactory.getDefaultFactory().createFileSource(root.toString()));
		s.setPort(port);
		s.setSecure(implicit);
		s.getLogger().setLevel(Level.ERROR);
		s.start();
		long start = System.currentTimeMillis();
		while( !s.isRunning() && System.currentTimeMillis() - start < 5000 ) {
			Thread.sleep(50);
		}
		assertTrue(s.isRunning(), "FTP server did not start on " + port);
		return s;
	}

	@AfterAll
	static void stop() throws Exception {
		for (FtpServer s : new FtpServer[] { explicitServer, implicitServer }) {
			if( s != null ) {
				s.stop();
			}
		}
		SecureBaseObject.setDefaultTrustManagers(savedTrust);
		for (int i = 0; i < KEYSTORE_PROPERTIES.length; i++) {
			if( savedProperties.get(i) == null ) {
				System.clearProperty(KEYSTORE_PROPERTIES[i]);
			} else {
				System.setProperty(KEYSTORE_PROPERTIES[i], savedProperties.get(i));
			}
		}
		if( dir != null ) {
			for (File f : dir.listFiles()) {
				f.delete();
			}
			dir.delete();
		}
	}

	private static Properties login(int port, String secure) {
		Properties prop = new Properties();
		prop.setProperty(FtpFileSourceFactory.PROP_USER, "ftp");
		prop.setProperty(FtpFileSourceFactory.PROP_PSWD, "foo@bar.com");
		prop.setProperty(FtpFileSourceFactory.PROP_HOST, "localhost");
		prop.setProperty(FtpFileSourceFactory.PROP_PORT, "" + port);
		if( secure != null ) {
			prop.setProperty(FtpFileSourceFactory.PROP_SECURE, secure);
		}
		return prop;
	}

	/** Lists /folder and reads a.txt, so the data connections are tried too. */
	private static void useIt(FtpFileSourceFactory f) throws Exception {
		FileSource folder = f.createFileSource("/folder");
		assertEquals(1, folder.listFiles().length);
		try (java.io.InputStream in = f.createFileSource("/folder/a.txt").getInputStream()) {
			assertEquals("hello", new String(in.readAllBytes(), StandardCharsets.UTF_8));
		}
	}

	@Test
	void ftpWithSecureIsExplicitTls() throws Exception {
		FtpFileSourceFactory f = new FtpFileSourceFactory();
		f.getLogger().setLevel(Level.ERROR);
		try {
			assertTrue(f.connect(login(EXPLICIT_PORT, "true")), "explicit TLS login");
			assertTrue(f.getFtpClient().isChannelSecure(), "AUTH TLS made the control connection TLS");
			assertTrue(f.getFtpClient().getDialog().contains("AUTH TLS"), f.getFtpClient().getDialog());
			useIt(f);
		} finally {
			f.disConnect();
		}
	}

	@Test
	void ftpWithoutSecureIsPlain() throws Exception {
		FtpFileSourceFactory f = new FtpFileSourceFactory();
		f.getLogger().setLevel(Level.ERROR);
		try {
			assertTrue(f.connect(login(EXPLICIT_PORT, "false")), "plain login");
			assertFalse(f.getFtpClient().isChannelSecure());
			assertFalse(f.getFtpClient().getDialog().contains("AUTH"), f.getFtpClient().getDialog());
			useIt(f);
		} finally {
			f.disConnect();
		}
	}

	@Test
	void ftpsIsImplicitTls() throws Exception {
		FtpFileSourceFactory f = new FtpsFileSourceFactory();
		f.getLogger().setLevel(Level.ERROR);
		try {
			// secure=false is ignored: ftps is always TLS
			assertTrue(f.connect(login(IMPLICIT_PORT, "false")), "implicit TLS login");
			assertFalse(f.getFtpClient().getDialog().contains("AUTH"), "no AUTH on an implicit TLS connection");
			useIt(f);
			assertTrue(f.createFileSource("/folder").toURL().toString().contains("=ftps"),
					"a file's URL names its own type: " + f.createFileSource("/folder").toURL());
		} finally {
			f.disConnect();
		}
	}

	/** One connection to a server that answers in plain text and refuses AUTH. */
	private static void answer(Socket socket, List<String> received) {
		try (Socket s = socket) {
			s.setSoTimeout(5000);
			BufferedReader in = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.US_ASCII));
			OutputStream out = s.getOutputStream();
			out.write("220 plain only\r\n".getBytes(StandardCharsets.US_ASCII));
			out.flush();
			String line;
			while( (line = in.readLine()) != null ) {
				received.add(line);
				String cmd = line.split(" ")[0].toUpperCase();
				String reply = cmd.equals("USER") ? "331 password" : cmd.equals("QUIT") ? "221 bye" : "502 not here";
				out.write((reply + "\r\n").getBytes(StandardCharsets.US_ASCII));
				out.flush();
				if( cmd.equals("QUIT") ) {
					break;
				}
			}
		} catch (Exception e) {
			// the client closed
		}
	}

	@Test
	void noLoginWhenTheServerRefusesAuthTls() throws Exception {
		// a server that answers in plain text and refuses AUTH: the password must not be sent
		List<String> received = Collections.synchronizedList(new ArrayList<>());
		try (ServerSocket ss = new ServerSocket(0)) {
			// every connection the client makes (it may try more than one) gets the same answers
			Thread t = new Thread(() -> {
				while( !ss.isClosed() ) {
					try {
						Socket s = ss.accept();
						Thread c = new Thread(() -> answer(s, received));
						c.setDaemon(true);
						c.start();
					} catch (Exception e) {
						// closed
					}
				}
			});
			t.setDaemon(true);
			t.start();

			FtpFileSourceFactory f = new FtpFileSourceFactory();
			f.getLogger().setLevel(Level.ERROR);
			boolean connected;
			try {
				connected = f.connect(login(ss.getLocalPort(), "true"));
			} catch (Exception e) {
				connected = false;
			} finally {
				f.disConnect();
			}
			assertFalse(connected, "no login without TLS");
			assertTrue(received.stream().anyMatch(l -> l.toUpperCase().startsWith("AUTH")), "AUTH was asked for: " + received);
			assertFalse(received.stream().anyMatch(l -> l.toUpperCase().startsWith("PASS")), "the password was sent in plain text: " + received);
		}
	}
}
