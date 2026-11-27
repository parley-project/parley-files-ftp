package us.bringardner.parley.files.ftp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Properties;
import java.util.Set;

import org.junit.jupiter.api.Test;

import us.bringardner.parley.files.FileSource;
import us.bringardner.parley.files.FileSourceFactory;
import us.bringardner.parley.files.test.FileSourceTestSupport;
import us.bringardner.parley.ftp.server.AnonymousUserRegistry;
import us.bringardner.parley.ftp.server.FtpServer;
import us.bringardner.parley.iam.permission.StandardPermissions;
import us.bringardner.parley.logging.testing.ErrorsExpected;

/**
 * connect() against a server that accepts the connection and never answers gives up after one
 * timeout (it took two: isConnected() connected too), throws a SocketTimeoutException naming the
 * wait instead of returning false, and leaves no connection or thread behind. Also: connect()
 * replaces a main connection the server has dropped.
 */
@ErrorsExpected
public class FtpConnectTimeoutTest {

	private static final int TIMEOUT_MS = 2000;

	@Test
	public void ftpGivesUpAfterOneTimeout() throws Exception {
		check(new FtpFileSourceFactory());
	}

	@Test
	public void ftpsGivesUpAfterOneTimeout() throws Exception {
		check(new FtpsFileSourceFactory());
	}

	private void check(FtpFileSourceFactory factory) throws Exception {
		Set<Thread> before = new HashSet<>(Thread.getAllStackTraces().keySet());
		try (SilentServer server = new SilentServer()) {
			try {
				long start = System.nanoTime();
				SocketTimeoutException e = assertThrows(SocketTimeoutException.class,
						() -> factory.connect(props(server.getPort())));
				long ms = (System.nanoTime() - start) / 1_000_000;
				assertTrue(ms >= TIMEOUT_MS - 100 && ms < TIMEOUT_MS * 3 / 2, "gave up after " + ms + " ms");
				assertTrue(e.getMessage().contains("127.0.0.1:" + server.getPort()) && e.getMessage().contains("2 s"),
						e.getMessage());
				assertFalse(factory.isConnected());
				assertEquals(1, server.accepted(), "connections accepted");
				// before disConnect(): the failed connect must have closed it itself
				assertTrue(server.allClosedByClient(TIMEOUT_MS), "the client's socket was left open");

				// isConnected() no longer connects, so this doesn't wait either
				start = System.nanoTime();
				factory.disConnect();
				ms = (System.nanoTime() - start) / 1_000_000;
				assertTrue(ms < 500, "disConnect took " + ms + " ms");
				assertEquals(1, server.accepted(), "connections accepted after disConnect");
			} finally {
				factory.disConnect();
			}
		}
		assertNoNewThreads(before);
	}

	@ErrorsExpected
	@Test
	public void connectReplacesADroppedMainConnection() throws Exception {
		int port;
		try (ServerSocket s = new ServerSocket(0, 50, InetAddress.getLoopbackAddress())) {
			port = s.getLocalPort();
		}
		FileSource root = FileSourceFactory.getDefaultFactory()
				.createFileSource(FileSourceTestSupport.scratchDir("FtpConnectTimeoutRoot"));
		if( !root.exists() ) {
			root.mkdirs();
		}
		FtpServer svr = startServer(root, port);
		FtpFileSourceFactory factory = new FtpFileSourceFactory();
		try {
			assertTrue(factory.connect(props(port)));
			stopServer(svr);
			// a command on the dropped connection fails, and the client marks it closed
			try {
				factory.getFtpClient().setAutoReconnect(false);
				factory.createFileSource("/").listFiles();
			} catch (IOException | RuntimeException expected) {
				// the server is gone
			}
			assertFalse(factory.isConnected());
			svr = startServer(root, port);
			assertTrue(factory.connect(), "connect() didn't replace the dropped connection");
			assertTrue(factory.isConnected());
		} finally {
			factory.disConnect();
			stopServer(svr);
		}
	}

	private static Properties props(int port) {
		Properties p = new Properties();
		p.setProperty(FtpFileSourceFactory.PROP_HOST, "127.0.0.1");
		p.setProperty(FtpFileSourceFactory.PROP_PORT, "" + port);
		p.setProperty(FtpFileSourceFactory.PROP_USER, "ftp");
		p.setProperty(FtpFileSourceFactory.PROP_PSWD, "foo@bar.com");
		p.setProperty(FtpFileSourceFactory.PROP_TIMEOUT, (TIMEOUT_MS / 1000) + "s");
		return p;
	}

	private static FtpServer startServer(FileSource root, int port) throws Exception {
		FtpServer svr = new FtpServer();
		svr.setUserRegistry(new AnonymousUserRegistry(StandardPermissions.READ, StandardPermissions.WRITE));
		svr.setFtpRoot(root);
		svr.setPort(port);
		svr.start();
		long end = System.currentTimeMillis() + 5000;
		while( !svr.isRunning() && System.currentTimeMillis() < end ) {
			Thread.sleep(20);
		}
		assertTrue(svr.isRunning(), "the FTP server didn't start");
		return svr;
	}

	private static void stopServer(FtpServer svr) throws Exception {
		svr.stop();
		long end = System.currentTimeMillis() + 5000;
		while( svr.isRunning() && System.currentTimeMillis() < end ) {
			Thread.sleep(20);
		}
	}

	/** Every thread started during the test has ended (given a moment to). */
	private static void assertNoNewThreads(Set<Thread> before) throws InterruptedException {
		List<Thread> left = new ArrayList<>();
		for (Thread t : Thread.getAllStackTraces().keySet()) {
			if( !before.contains(t) ) {
				t.join(2000);
				if( t.isAlive() ) {
					left.add(t);
				}
			}
		}
		assertTrue(left.isEmpty(), "threads left running: " + left);
	}

	/** Accepts connections on the loopback address and never writes to them. */
	private static final class SilentServer implements AutoCloseable {
		private final ServerSocket server;
		private final List<Socket> sockets = new ArrayList<>();
		private final Thread acceptor;

		SilentServer() throws IOException {
			server = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
			acceptor = new Thread(() -> {
				try {
					while( true ) {
						Socket s = server.accept();
						synchronized (sockets) {
							sockets.add(s);
						}
					}
				} catch (IOException e) {
					// closed
				}
			}, "silent-server");
			acceptor.setDaemon(true);
			acceptor.start();
		}

		int getPort() {
			return server.getLocalPort();
		}

		int accepted() {
			synchronized (sockets) {
				return sockets.size();
			}
		}

		/** True if the client has closed every accepted connection (end of stream seen), within the time. */
		boolean allClosedByClient(int withinMs) throws IOException {
			List<Socket> copy;
			synchronized (sockets) {
				copy = new ArrayList<>(sockets);
			}
			long end = System.currentTimeMillis() + withinMs;
			for (Socket s : copy) {
				InputStream in = s.getInputStream();
				byte[] buf = new byte[4096];
				while( true ) {
					long left = end - System.currentTimeMillis();
					if( left <= 0 ) {
						return false;
					}
					s.setSoTimeout((int) left);
					try {
						// what a TLS client sent (its hello) is read and dropped
						if( in.read(buf) < 0 ) {
							break;
						}
					} catch (SocketTimeoutException e) {
						return false;
					} catch (IOException e) {
						break; // reset by the client: closed too
					}
				}
			}
			return true;
		}

		@Override
		public void close() throws IOException {
			server.close();
			synchronized (sockets) {
				for (Socket s : sockets) {
					s.close();
				}
			}
			try {
				acceptor.join(2000);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}
		}
	}
}
