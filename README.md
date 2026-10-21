# parley-files-ftp

A [parley-files](https://github.com/parley-project/parley-files) `FileSource` implementation for
FTP and FTPS servers, part of **Parley**, a family of Java libraries for implementing internet
protocols. It uses the [parley-ftp](https://github.com/parley-project/parley-ftp) client.

Put it on the class path and `FileSourceFactory` finds it through `ServiceLoader`
(`FtpFileSourceFactory` and `FtpsFileSourceFactory`); application code keeps working with
`FileSource` and doesn't need to name this module.

Requires Java 11 or later. Depends on `parley-files` and `parley-ftp`.

```xml
<dependency>
    <groupId>us.bringardner.parley</groupId>
    <artifactId>parley-files-ftp</artifactId>
    <version>1.0.0</version>
</dependency>
```

> parley-files-ftp was previously `bringardner:bjl_file_system_ftp` (BjlFileSystemFtp), with packages
> under `us.bringardner.io.filesource.ftp` and `.ftps`. They are now `us.bringardner.parley.files.ftp`
> and `us.bringardner.parley.files.ftps`.

## Links

FTP has no command to create a link, so `createSymbolicLink` and `createLink` throw
`UnsupportedOperationException`. Links that already exist on the server are read when the server says
which entries are links: a `LIST` line `name -> target` or an `MLST`/`MLSD` type `OS.unix=slink:target`.
Many servers (including parley-ftp's) follow links silently and don't say, and for those a link simply
looks like the file or directory it leads to.

When a server does say:

- `getLinkedTo()` returns the target (a relative target is taken from the link's directory, with `.` and
  `..` resolved), and null for anything that isn't a link.
- A link answers for what it leads to in `exists()`, `isFile()`, `isDirectory()`, `length()` and
  `lastModified()`. One that leads nowhere is there but doesn't exist; a loop of links answers no.
- **What a write to a link does is the server's decision**, because FTP has no idea of a link (parley-ftp's
  server, for one, replaces the link with a regular file, so a client never changes what it leads to; see
  its [Links](https://github.com/parley-project/parley-ftp#links)). Unlike the other file systems here,
  the `FileSource` can't tell you which.
- `getCanonicalPath()` is **lexical**: `.` and `..` are resolved and a link is not (FTP has no realpath).
  So a link and the file it leads to have different canonical paths here, and `isChildOfMine()` can't see
  that a link leads outside a directory. Ask `getLinkedTo()` for the target.

## Locking

In short, for `FtpFileSourceFactory` and `FtpsFileSourceFactory`:

| | |
|---|---|
| Locks | `PROCESS`: this JVM only (FTP has no locks). Range locks work within the JVM. |
| Atomic create | `createNewFile()` **is not atomic**; `mkdir()` is. |
| Capabilities (`getCapabilities()`) | `PROCESS` locks; create **not** atomic, mkdir atomic; replace (`renameTo(dest, true)`) not atomic: delete, then rename; sync only sends; operations in one order |
| FileChannel | read and write through random access (see [Random access](#random-access)); writing in the middle needs a server with `RANG`, or `storeAtOffset`; with any server: read, or write a new or emptied file, or append |
| `map()` | `READ_ONLY` maps a local copy of the region; `PRIVATE` and `READ_WRITE` need a read-write channel (as FileChannel) |
| `force()` | only sends the data; it can't be read back before the channel is closed |


FTP has no file locks, so `lock()` and `tryLock()` (see parley-files) keep the default: locks within this JVM
(`getLockScope()` is `PROCESS`). They keep threads and code in one process apart, not other clients of the
server. Applications that need more build it on top (parley-net's locking).

**`createNewFile()` is not atomic over FTP**, although FileSource's contract says it must be: the protocol has no
command that creates a file only if it is missing, so two clients racing for one path can both be told `true`.
(It appends zero bytes, so it never empties a file another client made meanwhile.) `mkdir()` is atomic (MKD fails
if the directory exists); code that needs an atomic create over FTP uses it.

`FileChannel.force()` over FTP only sends the data: FTP has no way to ask the server to sync, and a server stores
an upload when it ends, so what was written can't be read back before the channel is closed. `map()` in
`READ_ONLY` mode maps a local copy of the region (see parley-files); `PRIVATE` and `READ_WRITE` need a channel open
for reading and writing, which needs random access (below).

## Times

`setLastModifiedTime()` / `setLastModified()` use `MFMT` (draft-somers-ftp-mfxx) when the server lists it in `FEAT`
(FileZilla Server, ProFTPD, parley's `FtpServer` and others), else parley's own `SITE` command; with milliseconds when the server takes them, else whole seconds.
`setCreateTime()` uses `MFCT` (or `MFF Create=`) where the server and its file system can set one. FTP has no way to
set the access time, so `setLastAccessTime()` is false.

## Random access

`getRandomAccessStream(mode)` (`r`, `rw`, `rws`, `rwd`, as `RandomAccessFile`) reads and writes the file in chunks
(`FtpRandomAccessIoController`). Standard FTP has no command that reads or writes part of a file, but a transfer can
start at a position. A server that lists `RANG` in `FEAT` ([draft-bryan-ftp-range](https://datatracker.ietf.org/doc/html/draft-bryan-ftp-range-08),
an expired draft that parley-ftp's `FtpServer` has) reads and writes exactly a range, and is used for both:

| | With `RANG` | Without |
|---|---|---|
| Read a chunk | `RANG start end`, `RETR`: just the chunk is sent | `REST start`, `RETR`; the transfer is dropped once the chunk has arrived |
| Write a chunk in the middle, or chunk 0 | `RANG start end`, `STOR`: written in place, nothing else changes. `storeAtOffset` isn't needed | below |

Without `RANG`:

| | How |
|---|---|
| Write a chunk in the middle | `REST start`, `STOR`: the server writes from `start` in place and leaves the rest of the file. **Only with `storeAtOffset`** (below) |
| Write a chunk at the end | `APPE` (with zeros for any gap) |
| Write chunk 0 of a longer file | `REST 0` is no restart (a `STOR` would replace the file), so the rest of the file is copied to a local temporary file and stored again after the chunk |
| `setLength()` longer | `APPE` of zeros |
| `setLength()` shorter | FTP can't truncate: the part that stays is copied to a local temporary file and stored again |

**Writing in the middle is off unless the connection says the server stores in place at a `REST` offset**: the
`storeAtOffset` connection property (`PROP_STORE_AT_OFFSET`, `setStoreAtOffset(true)`; "Server stores at a REST
offset" among the advanced connection settings). The FTP standards don't say what a server does with `REST` then `STOR`,
and servers differ:

| Server | `REST n` then `STOR` |
|---|---|
| parley-ftp `FtpServer` | Refused unless `setStoreAtOffset(true)` (`parley.ftp.storeAtOffset`); then writes in place |
| ProFTPD | `AllowStoreRestart`; its manual warns it lets clients "corrupt or increase the size of previously stored files", but doesn't say how it writes. Not verified here |
| vsftpd | Believed to write in place. Not verified here |
| IIS | Reported to keep only the new data (the file is replaced) |
| FileZilla Server | Reported to add the data to the end; it also forgets `REST` if `PASV` comes between (this client sends `REST` straight before `STOR`) |

None of this applies to a server with `RANG`: its writes say exactly which octets change. Check yours before setting
`storeAtOffset`. Without it, saving a chunk in the middle fails before anything is sent
(`IOException`, saying so). With it:

- a server that refuses the `REST` or the `STOR` leaves the file as it was, and the save fails;
- after every write the file's size is checked against what it should be, so a server that replaced the file or added
  to it instead of writing in place is reported (`IOException` naming `storeAtOffset`). The file is damaged by then.

Reading, writing at the end (`APPE`), writing chunk 0 and `setLength()` don't use `REST` with `STOR`, and work with any
server; their sizes are checked too.

- The chunk size is the factory's buffer size, or `StreamOption.CHUNK_SIZE` (or `BUFFER_SIZE`) when the stream is
  opened with `StreamOptions`. Every chunk read or written is a data connection, so reading or writing at random is
  slow; a bigger chunk makes fewer of them. For reading only, `getSeekableInputStream()` is cheaper: it reads on from
  where it is.
- Nothing is locked or atomic: a chunk is stored when it is saved, a transfer that breaks leaves what arrived, and
  changes another client makes meanwhile can be overwritten.
- Opening checks the file on the server (a directory, a file missing in `r` mode, one that can't be created), not
  `canRead()`/`canWrite()`: over FTP those are guessed from the listing's owner and permission bits, which often aren't
  what the server lets the login do (an anonymous or virtual user isn't the owner the listing shows). A read or write
  the server refuses fails when it is done.

## Tests

The tests start an FTP server from parley-ftp on port 8021 (`-DFtpPort=...` to change it) and run
the shared FileSource test suite from parley-files against it.
