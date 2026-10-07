# Changelog

## parley-files-ftp 1.0.0 (unreleased)

BjlFileSystemFtp (`bringardner:bjl_file_system_ftp` 1.0.0-SNAPSHOT) is now **parley-files-ftp**, part
of the Parley library family. The code is the same; only names changed.

### Changed (needs a code change)

- Maven coordinates: `bringardner:bjl_file_system_ftp` is now `us.bringardner.parley:parley-files-ftp`.
- Packages: `us.bringardner.io.filesource.ftp` and `.ftps` are now `us.bringardner.parley.files.ftp`
  and `us.bringardner.parley.files.ftps`.
- `ServiceLoader` registration: `META-INF/services/us.bringardner.parley.files.FileSourceFactory`.
- Module name (`Automatic-Module-Name`): `us.bringardner.parley.files.ftp`.
- Dependencies: `bjl_net_ftp` and `bjl_file_system` are now `parley-ftp` and `parley-files`.
- `FtpEditPropertiesPanel` is gone (it started with one particular account filled in): the factory
  describes its settings with `getConnectionSettings()` (see parley-files), and the module no longer
  uses Swing. `getConnectProperties()` now includes `timeout`, which `setConnectionProperties` reads.
- `listFiles(ProgressMonitor)` is now `listFiles(FileSourceProgress)`.

