package us.bringardner.parley.files.ftp;

import us.bringardner.parley.files.test.FileSourceTestSupport;
import java.io.IOException;

import org.junit.jupiter.api.BeforeAll;

import us.bringardner.parley.files.test.AbstractNioProviderTests;

/** The shared java.nio.file provider tests over FTP. */
public class FtpNioProviderTests extends AbstractNioProviderTests {

	@BeforeAll
	public static void setUpBeforeAll() throws IOException {
		localTestFileDirPath = FileSourceTestSupport.testFilesDir(FtpNioProviderTests.class);
		localCacheDirPath = FileSourceTestSupport.scratchDir("NioCacheFiles");
		remoteTestFileDirPath = "/TestFiles";
		FtpTestServer.startAndConnect();
	}

	@Override
	@org.junit.jupiter.api.Test
	@org.junit.jupiter.api.condition.DisabledOnOs(value = org.junit.jupiter.api.condition.OS.WINDOWS, disabledReason = "POSIX permission bits: the FTP server runs on Windows here, which has none to set")
	public void testFileAttributes() throws Exception {
		super.testFileAttributes();
	}

	@Override
	@org.junit.jupiter.api.Test
	@org.junit.jupiter.api.condition.DisabledOnOs(value = org.junit.jupiter.api.condition.OS.WINDOWS, disabledReason = "POSIX permission bits: the FTP server runs on Windows here, which has none to set")
	public void testCreateDir() throws Exception {
		super.testCreateDir();
	}

	/** FTP stores a file when its upload ends: force() can only send the data, not make it readable. */
	@Override
	protected boolean forcedWritesAreVisibleBeforeClose() {
		return false;
	}
}
