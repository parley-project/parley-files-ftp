package us.bringardner.parley.files.ftp;

import us.bringardner.parley.files.test.FileSourceTestSupport;
import java.io.IOException;

import org.junit.jupiter.api.BeforeAll;

import us.bringardner.parley.files.test.AbstractTestClass;

/** The shared FileSource tests over FTP. */
public class TestFtp extends AbstractTestClass {

	@BeforeAll
	public static void setUpBeforeAll() throws IOException {
		localTestFileDirPath = FileSourceTestSupport.testFilesDir(TestFtp.class);
		localCacheDirPath = FileSourceTestSupport.scratchDir("CacheFiles");
		remoteTestFileDirPath = "/TestFiles";
		FtpTestServer.startAndConnect();
	}

	/**
	 * The shared test toggles each POSIX permission bit through SITE CHMOD. A Windows server has ACLs:
	 * it can't clear its owner's read permission (java.io.File can't either), so it can't be put back.
	 */
	@Override
	@org.junit.jupiter.api.Test
	@org.junit.jupiter.api.Order(3)
	@org.junit.jupiter.api.condition.DisabledOnOs(value = org.junit.jupiter.api.condition.OS.WINDOWS, disabledReason = "POSIX permission bits")
	public void testPermissions() throws IOException {
		super.testPermissions();
	}
}
