# Remote files

In a connected remote group's **⋯ options** (or right-click its header), choose
**Browse Files…** or **Upload Files…**. These target the directly connected
BossTerm host, not the origins of nested “via host” groups.

Connections with terminal **control** automatically have file browsing, downloads,
and uploads in the host's home folder, without another approval prompt. If the
host already approved a read-only folder, that folder is retained on upgrade.
View-only connections still ask the host to approve a folder for browsing and
downloads. Clicking **Upload** while view-only requests terminal control; approval
continues to the local file picker, denial leaves uploads unavailable. Upload
source files can be selected anywhere on the client's device.

The host rechecks the live control role for every operation. Losing control stops
uploads and removes automatically granted access; a separately approved read-only
folder remains readable. Disconnecting clears file grants, while reconnecting
with a valid saved control grant automatically restores controller file access.

The desktop file browser provides breadcrumbs, Up, Refresh, hidden files, paginated listings,
downloads, and multi-file uploads through a picker or drag-and-drop. It initially
tries the active direct pane's directory if it is inside the approved folder,
otherwise it opens the folder's root. The terminal's directory is not changed.
The filesystem belongs to the host computer, even if its shell is running SSH or
Docker. Local or remote overwrite conflicts require confirmation.

Transfers show progress and support cancellation and retry from the beginning.
A disconnect interrupts them. Closing the browser cancels its running transfer.
Uploads and downloads use staging files and SHA-256 verification before publishing.
Files are limited to 10 GiB each. Folder uploads, editing, rename, delete, daemon hosts, nested file routing, and resume are not implemented.

## Transport and filesystem boundary

- `Hello.capabilities` advertises `filesV1`; `Layout.filesAvailable` advertises host
  support. Defaults remain false/empty for older peers. Unknown new subtypes are
  never sent to a peer that did not opt in.
- File requests are accepted only on authenticated E2E-encrypted connections.
  There is no new HTTP file endpoint or plaintext transfer route.
- Each viewer has its own approval and transfer state. Requests use unique IDs,
  transfer IDs, exact offsets, and acknowledged 32 KiB chunks. One request is in
  flight at a time. Replies use the bounded reliable queue alongside existing
  control traffic rather than the lossy terminal-output queue.
- Paths are relative to an open approved directory. Components cannot traverse
  parents or symbolic links. macOS uses public descriptor-relative Darwin APIs;
  Linux pins a native directory descriptor, opens its JDK secure directory stream
  through /proc/self/fd, and publishes no-overwrite uploads with linkat. Filesystems
  without hard-link support reject these uploads safely. Other host filesystems/platforms,
  including the current Windows JDK backend, leave file actions unavailable.
- Partial uploads are cleaned up on cancellation, disconnect, and inactivity.
  A process crash can leave hidden `.bossterm-upload-*` staging files for manual
  cleanup; the service excludes these from listings and remote access.

## Validation

`RemoteFileStoreTest` covers path traversal, symlinks, read-only permissions,
conflicts (including a file appearing during upload), offsets, chunk bounds,
verification, cancellation, Unicode/empty files, and pagination.
`RemoteFilesClientTest` covers an encrypted multi-chunk roundtrip, denied access,
disconnects, approval cancellation, and integrity-failure cleanup.

Interactive validation checklist: connect two updated apps, approve a test folder,
try both group actions, picker/drop uploads, overwrite/skip, download, cancel/retry,
and sharing shutdown while a transfer runs. Confirm the terminal stays responsive
and check the window at narrow widths.

## Web viewer

The web viewer has session-level **Files** and **Upload** buttons, also shown
before split controls in the direct-host sidebar action row. These use the same
encrypted filesV1 protocol and host permission checks as the desktop client.
Unsupported hosts do not show the buttons. Use an HTTPS share link (or localhost)
for browser Web Crypto support.

**Files** opens the approved root. Click folders to navigate and files to download;
Up, Refresh, Hidden files and Load more support directory browsing. **Upload**
requests control when necessary. After approval, click **Choose files to upload…**
to open the local device picker; browsers require this fresh user gesture.
The displayed host folder is the destination, independent of the local source.

Web transfers support multiple selected files, explicit overwrite confirmation,
progress, cancellation, SHA-256 verification, and disconnect cleanup. Each file
is limited to **64 MB** because browser hashing/downloads use bounded in-memory
buffers. Larger transfers remain available in the desktop client. Files are
saved using the browser's normal download mechanism after verification.
