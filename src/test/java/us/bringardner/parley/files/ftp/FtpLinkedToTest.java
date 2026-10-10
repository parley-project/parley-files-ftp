package us.bringardner.parley.files.ftp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import us.bringardner.parley.core.ILogger.Level;
import us.bringardner.parley.files.FileSource;

/**
 * FTP has no command for links, but a server can say a path is one: an MLST type of
 * "OS.unix=slink:target" (RFC 3659). A scripted server that does says so for a few paths, and a
 * link then answers for what it points to, as a java.io.File does.
 */
public class FtpLinkedToTest {

	private static final String TIME = "modify=20260102030405;perm=r;";

	private ServerSocket listener;
	private final Map<String, String> entries = new ConcurrentHashMap<>();
	private FtpFileSourceFactory factory;

	private void entry(String path, String facts) {
		entries.put(path, facts + " " + path);
	}

	@BeforeEach
	void start() throws Exception {
		entry("/real.txt", "type=file;size=5;" + TIME);
		entry("/dir", "type=dir;size=0;" + TIME);
		entry("/dir/inner.txt", "type=file;size=3;" + TIME);
		entry("/ln", "type=OS.unix=slink:/real.txt;size=9;" + TIME);
		entry("/dir/rel", "type=OS.unix=slink:../real.txt;size=9;" + TIME);
		entry("/lnd", "type=OS.unix=slink:/dir;size=4;" + TIME);
		entry("/dangling", "type=OS.unix=slink:/nothing;size=8;" + TIME);
		entry("/loop1", "type=OS.unix=slink:/loop2;size=5;" + TIME);
		entry("/loop2", "type=OS.unix=slink:/loop1;size=5;" + TIME);
		entry("/chain", "type=OS.unix=slink:/ln;size=3;" + TIME);

		listener = new ServerSocket(0);
		Thread t = new Thread(() -> {
			try {
				while( !listener.isClosed() ) {
					Socket s = listener.accept();
					Thread c = new Thread(() -> serve(s), "fake-ftp-client");
					c.setDaemon(true);
					c.start();
				}
			} catch (IOException e) {
				// closed
			}
		}, "fake-ftp-server");
		t.setDaemon(true);
		t.start();

		Properties prop = new Properties();
		prop.setProperty(FtpFileSourceFactory.PROP_USER, "ftp");
		prop.setProperty(FtpFileSourceFactory.PROP_PSWD, "x");
		prop.setProperty(FtpFileSourceFactory.PROP_HOST, "localhost");
		prop.setProperty(FtpFileSourceFactory.PROP_PORT, "" + listener.getLocalPort());
		prop.setProperty(FtpFileSourceFactory.PROP_SECURE, "false");
		factory = new FtpFileSourceFactory();
		factory.getLogger().setLevel(Level.ERROR);
		assertTrue(factory.connect(prop), "can't connect to the scripted server");
	}

	@AfterEach
	void stop() throws Exception {
		if( factory != null ) {
			factory.disConnect();
		}
		if( listener != null ) {
			listener.close();
		}
	}

	private void serve(Socket s) {
		try (Socket sock = s;
				BufferedReader in = new BufferedReader(new InputStreamReader(sock.getInputStream(), StandardCharsets.UTF_8));
				Writer out = new OutputStreamWriter(sock.getOutputStream(), StandardCharsets.UTF_8)) {
			reply(out, "220 scripted server ready");
			String line;
			while( (line = in.readLine()) != null ) {
				String cmd = line.trim();
				String upper = cmd.toUpperCase();
				if( upper.startsWith("USER") ) {
					reply(out, "331 password please");
				} else if( upper.startsWith("PASS") ) {
					reply(out, "230 logged in");
				} else if( upper.startsWith("SYST") ) {
					reply(out, "215 UNIX Type: L8");
				} else if( upper.startsWith("FEAT") ) {
					reply(out, "211-Features:\r\n MLST type*;size*;modify*;perm*;\r\n UTF8\r\n211 End");
				} else if( upper.startsWith("PWD") ) {
					reply(out, "257 \"/\" is the current directory");
				} else if( upper.startsWith("MLST") ) {
					String path = cmd.length() > 5 ? cmd.substring(5).trim() : "/";
					String e = entries.get(path);
					if( e == null ) {
						reply(out, "550 " + path + ": no such file");
					} else {
						reply(out, "250-Listing " + path + "\r\n " + e + "\r\n250 End");
					}
				} else if( upper.startsWith("QUIT") ) {
					reply(out, "221 bye");
					return;
				} else {
					// TYPE, OPTS, NOOP, ...
					reply(out, "200 ok");
				}
			}
		} catch (IOException e) {
			// the client went away
		}
	}

	private static void reply(Writer out, String text) throws IOException {
		out.write(text + "\r\n");
		out.flush();
	}

	private FileSource at(String path) throws Exception {
		return factory.createFileSource(path);
	}

	@Test
	void aLinkSaysWhereItPoints() throws Exception {
		FileSource target = at("/ln").getLinkedTo();
		assertNotNull(target);
		assertEquals("/real.txt", target.getAbsolutePath());
		assertNull(at("/real.txt").getLinkedTo(), "a file is not a link");
		assertNull(at("/dir").getLinkedTo());
		assertNull(at("/nothing-here").getLinkedTo());
	}

	@Test
	void aRelativeTargetIsTakenFromTheLinksDirectory() throws Exception {
		assertEquals("/real.txt", at("/dir/rel").getLinkedTo().getAbsolutePath());
	}

	@Test
	void aLinkActsLikeWhatItPointsTo() throws Exception {
		FileSource ln = at("/ln");
		assertTrue(ln.exists());
		assertTrue(ln.isFile());
		assertFalse(ln.isDirectory());
		assertEquals(5, ln.length(), "the target's size, not the link's own");
		assertEquals(at("/real.txt").lastModified(), ln.lastModified());

		FileSource lnd = at("/lnd");
		assertTrue(lnd.exists());
		assertTrue(lnd.isDirectory());
		assertFalse(lnd.isFile());

		FileSource chain = at("/chain");
		assertTrue(chain.isFile(), "a link to a link follows both");
		assertEquals(5, chain.length());
		assertEquals("/ln", chain.getLinkedTo().getAbsolutePath());
	}

	@Test
	void aLinkThatPointsAtNothingIsThereButDoesNotExist() throws Exception {
		FileSource d = at("/dangling");
		assertEquals("/nothing", d.getLinkedTo().getAbsolutePath());
		assertFalse(d.exists());
		assertFalse(d.isFile());
		assertFalse(d.isDirectory());
		assertEquals(0, d.length());
	}

	@Test
	void linksThatLeadBackToThemselvesDoNotHang() throws Exception {
		FileSource l = at("/loop1");
		assertNotNull(l.getLinkedTo());
		assertFalse(l.exists());
		assertFalse(l.isDirectory());
		assertEquals(0, l.length());
	}
}
