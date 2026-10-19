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

FTP has no file locks, so `lock()` and `tryLock()` (see parley-files) keep the default: locks within this JVM
(`getLockScope()` is `PROCESS`). They keep threads and code in one process apart, not other clients of the
server. Applications that need more build it on top (parley-net's locking).

**`createNewFile()` is not atomic over FTP**, although FileSource's contract says it must be: the protocol has no
command that creates a file only if it is missing, so two clients racing for one path can both be told `true`.
(It appends zero bytes, so it never empties a file another client made meanwhile.) `mkdir()` is atomic (MKD fails
if the directory exists); code that needs an atomic create over FTP uses it.

## Tests

The tests start an FTP server from parley-ftp on port 8021 (`-DFtpPort=...` to change it) and run
the shared FileSource test suite from parley-files against it.
