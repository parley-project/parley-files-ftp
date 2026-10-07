package us.bringardner.parley.files.ftp;

import java.io.IOException;

import org.junit.jupiter.api.BeforeAll;

import us.bringardner.parley.files.test.AbstractNioProviderTests;

/** The shared java.nio.file provider tests over FTP. */
public class FtpNioProviderTests extends AbstractNioProviderTests {

	@BeforeAll
	public static void setUpBeforeAll() throws IOException {
		localTestFileDirPath = "TestFiles";
		localCacheDirPath = "target/NioCacheFiles";
		remoteTestFileDirPath = "TestFiles";
		FtpTestServer.startAndConnect();
	}
}
