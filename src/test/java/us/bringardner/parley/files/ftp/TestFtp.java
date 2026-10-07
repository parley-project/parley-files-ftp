package us.bringardner.parley.files.ftp;

import java.io.IOException;

import org.junit.jupiter.api.BeforeAll;

import us.bringardner.parley.files.test.AbstractTestClass;

/** The shared FileSource tests over FTP. */
public class TestFtp extends AbstractTestClass {

	@BeforeAll
	public static void setUpBeforeAll() throws IOException {
		localTestFileDirPath = "TestFiles";
		localCacheDirPath = "target/CacheFiles";
		remoteTestFileDirPath = "TestFiles";
		FtpTestServer.startAndConnect();
	}
}
