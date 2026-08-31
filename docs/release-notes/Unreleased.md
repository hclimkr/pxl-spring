# PXL Spring (Unreleased)

A maintenance release so far: the ZIP exporter reaches a file through a buffer, the one destination
in the library that was still writing to one unbuffered.

## Highlights

  - **`exportZip().toFile(...)` is buffered.** A `ZipOutputStream` pushes its deflater output down in
    512-byte pieces, so writing straight onto a file meant a write call for every 512 bytes of the
    archive — around twenty thousand of them for a 10 MiB export. `.xlsx` members feel it most: they
    are already-deflated OOXML containers, stored at `NO_COMPRESSION` rather than compressed again,
    so they reach the file at full size. Every other destination here already had a buffer beneath it
    or needed none. Nothing to change at the call site, and nothing else about the destination moves —
    a failed export still leaves an unfinished archive on disk for the caller to deal with.
