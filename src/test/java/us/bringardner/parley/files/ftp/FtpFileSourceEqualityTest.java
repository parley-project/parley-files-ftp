package us.bringardner.parley.files.ftp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.Set;

import org.junit.jupiter.api.Test;

import us.bringardner.parley.files.FileSource;

/**
 * Like java.io.File, two handles for one file are equal and hash the same, so they can be
 * put in a Set or used as a key. "One file" is the same path on the same server and account.
 * Offline: nothing connects.
 */
public class FtpFileSourceEqualityTest {

	private static FtpFileSourceFactory factory(String host, int port, String user) {
		FtpFileSourceFactory f = new FtpFileSourceFactory();
		f.setHost(host);
		f.setPort(port);
		f.setUser(user);
		return f;
	}

	@Test
	void twoHandlesForOnePathAreEqual() throws Exception {
		FtpFileSourceFactory f = factory("ftp.example.org", 21, "someone");
		FileSource a = f.createFileSource("/data/report.txt");
		FileSource b = f.createFileSource("/data/report.txt");
		assertTrue(a != b, "two handles");
		assertEquals(a, b);
		assertEquals(b, a, "symmetric");
		assertEquals(a.hashCode(), b.hashCode());
		assertEquals(a, a, "reflexive");
	}

	@Test
	void differentPathsAreNotEqual() throws Exception {
		FtpFileSourceFactory f = factory("ftp.example.org", 21, "someone");
		assertNotEquals(f.createFileSource("/data/a.txt"), f.createFileSource("/data/b.txt"));
		assertNotEquals(f.createFileSource("/data/a.txt"), f.createFileSource("/other/a.txt"));
		assertNotEquals(f.createFileSource("/data"), f.createFileSource("/data/a.txt"));
	}

	@Test
	void theSamePathOnAnotherServerOrAccountIsAnotherFile() throws Exception {
		FileSource mine = factory("ftp.example.org", 21, "someone").createFileSource("/data/a.txt");
		assertNotEquals(mine, factory("other.example.org", 21, "someone").createFileSource("/data/a.txt"));
		assertNotEquals(mine, factory("ftp.example.org", 2121, "someone").createFileSource("/data/a.txt"));
		assertNotEquals(mine, factory("ftp.example.org", 21, "somebody").createFileSource("/data/a.txt"));
	}

	@Test
	void theSameServerOnTwoFactoriesIsTheSameFile() throws Exception {
		// two connections to one account: the same files (host names don't differ by case)
		FileSource a = factory("FTP.Example.org", 21, "someone").createFileSource("/data/a.txt");
		FileSource b = factory("ftp.example.org", 21, "someone").createFileSource("/data/a.txt");
		assertEquals(a, b);
		assertEquals(a.hashCode(), b.hashCode());
	}

	@Test
	void notEqualToNullOrToAnotherKindOfObject() throws Exception {
		FileSource a = factory("ftp.example.org", 21, "someone").createFileSource("/data/a.txt");
		assertFalse(a.equals(null));
		assertFalse(a.equals("/data/a.txt"));
		assertFalse(a.equals(new java.io.File("/data/a.txt")));
	}

	@Test
	void worksInSetsAndAsKeys() throws Exception {
		FtpFileSourceFactory f = factory("ftp.example.org", 21, "someone");
		Set<FileSource> set = new HashSet<>();
		set.add(f.createFileSource("/data/a.txt"));
		set.add(f.createFileSource("/data/a.txt"));
		set.add(f.createFileSource("/data/b.txt"));
		assertEquals(2, set.size());
		assertTrue(set.contains(f.createFileSource("/data/b.txt")));
	}

	@Test
	void theRootIsEqualToTheRoot() throws Exception {
		FtpFileSourceFactory f = factory("ftp.example.org", 21, "someone");
		assertEquals(f.createFileSource("/"), f.createFileSource("/"));
		assertEquals(f.createFileSource("/").hashCode(), f.createFileSource("/").hashCode());
	}

	// ------------------------------------------------------------ ordering

	@Test
	void sortsAscendingByPath() throws Exception {
		FtpFileSourceFactory f = factory("ftp.example.org", 21, "someone");
		FileSource a = f.createFileSource("/data/a.txt");
		FileSource b = f.createFileSource("/data/b.txt");
		FileSource c = f.createFileSource("/other/a.txt");
		assertTrue(a.compareTo(b) < 0, "a before b");
		assertTrue(b.compareTo(a) > 0, "and b after a");
		assertTrue(b.compareTo(c) < 0, "/data before /other");

		java.util.TreeSet<FileSource> sorted = new java.util.TreeSet<>(java.util.List.of(c, a, b));
		assertEquals(java.util.List.of(a, b, c), new java.util.ArrayList<>(sorted));
	}

	@Test
	void orderingAgreesWithEquality() throws Exception {
		FtpFileSourceFactory f = factory("ftp.example.org", 21, "someone");
		FileSource a = f.createFileSource("/data/a.txt");
		FileSource same = f.createFileSource("/data/a.txt");
		assertEquals(0, a.compareTo(same));
		assertEquals(0, same.compareTo(a));
		assertEquals(a, same);
		assertEquals(-Integer.signum(a.compareTo(f.createFileSource("/data/b.txt"))),
				Integer.signum(f.createFileSource("/data/b.txt").compareTo(a)), "antisymmetric");
	}

	@Test
	void aPlainStringIsComparedByPath() throws Exception {
		FileSource a = factory("ftp.example.org", 21, "someone").createFileSource("/data/b.txt");
		assertEquals(0, a.compareTo("/data/b.txt"));
		assertTrue(a.compareTo("/data/a.txt") > 0);
		assertTrue(a.compareTo("/data/c.txt") < 0);
	}
}
