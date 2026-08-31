# Changelog

All notable changes to this project are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Changed

- `exportZip().toFile(...)` writes through a buffer. The archive hands its deflater output down
  512 bytes at a time, and with the file directly beneath that, an export cost one write call per
  512 bytes of archive — worst for `.xlsx` members, which are stored uncompressed and so are not
  shrunk on the way past. It was the one destination in the library without a buffer under it.
  Nothing else moves: a failed export still leaves the archive unfinished and undeleted.

## [0.9.3] - 2026-08-22

Built against [pxl](https://github.com/hclimkr/pxl) 0.9.5, up from 0.9.4.

### Added

- An already-built raw POI `Workbook` can go into a ZIP archive:
  `exportZip().poiWorkbook(workbook)`, with `poiWorkbook(workbook, password)` and
  `poiWorkbook(workbook, password, entryName)` alongside it. The workbook is written as-is, the
  way `exportExcel().poiWorkbook(...)` already writes one on its own, so there is no per-entry
  export option — nothing is bound for one to override. The entry's extension is read back off
  the workbook itself (`HSSFWorkbook` → `.xls`, `XSSFWorkbook`/`SXSSFWorkbook` → `.xlsx`), which
  is the format its body is written in; encryption keeps that extension, as it does on
  `PxlExcelExporter`. This kind carries no workbook name, so an unnamed entry falls straight to
  `Pxl{index}`. Duplicate entry names are still rejected across every kind of entry alike.
- A sample template can go into a ZIP archive too: `exportZip().sampleWorkbook(workbookClass)`,
  with `sampleWorkbook(workbookClass, option)` and
  `sampleWorkbook(workbookClass, option, entryName)` alongside it — the header row plus one row of
  `@PxlColumn(exportSample = ...)` values, exactly what `exportSampleExcel()` produces on its own,
  so "download all three upload forms" is one chain. The per-entry option drives the entry's
  extension as well as its bytes, as on `workbook(...)`. This form is given a class rather than an
  instance, so there is no workbook name to read: an unnamed entry falls straight to
  `PxlSample{index}` — with the index, unlike `PxlSampleExcelExporter`'s bare `PxlSample`, because
  entries share an archive and must come out distinct.
- CSV can go into a ZIP archive as well, completing the set: `exportZip().csvSheet(rowClass,
  rows, sheetName)` writes a row collection as a `.csv` member and
  `sampleCsvSheet(rowClass, sheetName)` a CSV template, each with the same option and entry-name
  overloads as the other kinds — so "the spreadsheet plus the same data as CSV", or a set of CSV
  upload forms, is one chain. One CSV file is one sheet, so every call adds a member rather than a
  sheet. These two always write `.csv`, so an option's `exportExcelEngine` means nothing to them;
  what it does carry is the charset, field delimiter and byte order mark. An unnamed entry takes
  its sheet name, which is required — there is no index-suffixed default behind it, so two entries
  under one sheet name collide and are rejected before anything is written. A blank or `null` sheet
  name is refused at the call that adds the entry rather than mid-write.
- **Through the core.** `java.util.UUID` is a column type in both directions now,
  `Collection<UUID>` included, so a DTO carrying one no longer fails while its column metadata is
  resolved. Export wrote the canonical lower-case form already, through the custom-object path, and
  writes exactly the same text; import takes that form only, refusing the hyphen-less, braced and
  `urn:uuid:` spellings.

### Changed

- **Breaking.** `PxlSpring`'s `@Autowired` constructor takes its components in a new order — the
  five exporters first (Excel, sample Excel, CSV, sample CSV, ZIP), then the two importers (Excel,
  CSV) — so an application that builds the facade by hand rather than by component scan has to
  reorder those arguments. Every argument has a distinct type, so the compiler catches it rather
  than the wiring going quietly wrong. The documented setup — scanning
  `io.github.hclimkr.pxl.spring` — is unaffected, and so is the no-arg constructor. The start
  methods are declared in that order too, and the READMEs list them that way: export ahead of
  import throughout, and ZIP last among the exporters because its members come from the other four.

- **Breaking.** `PxlExcelZipExporter` is now `PxlZipExporter`, and its start method
  `exportExcelZip()` is now `exportZip()` — on the `PxlSpring` facade too. The archive holds CSV
  members as well as Excel ones now, so `Excel` in the name claimed something that is no longer
  true. Nothing else about the type moves: the builder is still the nested `PxlZipExporter.Builder`,
  every configuration and terminal method keeps its name, and component scanning is unaffected.
  Rename the call if you go through the facade, and the injection point as well if you inject the
  component directly.

- A ZIP entry name carrying a path separator is now rejected before anything is written, alongside
  the duplicate-name check it belongs with. It used to be checked per entry inside the write loop,
  which meant `toFile(...)` had already created a file it could not finish and
  `toResponseStreaming(...)` had already committed the download headers. The exception and its
  message are unchanged — only the point of failure is earlier, so `toFile(...)` now leaves nothing
  on disk and `toResponseStreaming(...)` leaves the response untouched. Every check the ZIP builder
  makes itself now runs before the headers; what still lands after them is a failure the core
  raises while generating an entry, which is the trade `toResponseStreaming(...)` asks for.

- **Breaking.** A ZIP entry-name collision is rejected before anything is written, as
  `PxlArgumentException` naming the offending entry, where it used to surface mid-write as
  `PxlIOException` — the same exception a disk failure produces, so callers could not tell the
  two apart. The check runs in the builder's up-front validation, which every destination
  calls first, so `toFile(...)` no longer creates a file it cannot finish and
  `toResponseStreaming(...)` no longer commits the download headers before failing. Entry
  names are still resolved the same way (`explicit name` → `@PxlWorkbook` workbook name →
  `Pxl{index}`); give colliding entries an explicit name through
  `workbook(object, option, name)`.

- **Breaking.** Two ZIP entries whose names differ only in case are now refused as well. They
  used to go into the archive as distinct members, because `ZipOutputStream` compares names
  exactly — but extracting them on a case-insensitive file system (Windows, macOS by default)
  silently overwrites one with the other. Names are compared whole, extension included, so the
  same base name written by two different engines (`report.xlsx` and `report.xls`) is still
  two members.

- **Breaking.** A per-entry export option's `exportExcelEngine` now sets that ZIP entry's
  extension, not just its bytes, taking precedence over the engine the workbook class declares
  — so `workbook(report, hssfOption)` produces `report.xls` where it used to produce
  `report.xlsx` holding OLE2 bytes. The deflate level follows the same answer, so such an entry
  is compressed instead of being stored as if it were already-deflated OOXML.

- **Breaking, through the core.** On import, a numeric or `java.util.Date` `pattern` has to match
  the whole cell value now. A value it could read only the front of used to bind silently —
  `"123abc"` as `123` under `"#,##0"`, `"2024-01-02 xxx"` as 2 January 2024 under `"yyyy-MM-dd"` —
  and raises `PxlCellCodecException` instead. Values a pattern reads end to end are unaffected,
  prefixes and suffixes included. Worth checking against your data: with `importTrim = false`,
  trailing whitespace is itself unconsumed input and is now rejected.

- **Through the core.** A `@PxlSheet` field marked `@Valid` no longer has its rows validated
  twice. An export violation is therefore reported with its sheet name, which the duplicate pass
  used to report without one, and a sheet the binder skips (`exportEnabled = false` on export,
  `importEnabled = false` on import) has its rows left unvalidated even when the field carries
  `@Valid`.

## [0.9.2] - 2026-08-11

Built against [pxl](https://github.com/hclimkr/pxl) 0.9.4, up from 0.9.3.

### Added

- Export to CSV, alongside the existing Excel exports: `exportCsv()` and `exportSampleCsv()`
  on `PxlSpring`, backed by the new components `PxlCsvExporter` and `PxlSampleCsvExporter`.
  Both reach the same five destinations as their Excel counterparts — `toStream(...)`,
  `toFile(...)`, `toResponse(...)`, `toResponseStreaming(...)`, `toResponseEntity(...)` —
  and the response destinations emit `.csv` with `text/csv`. A CSV file holds one sheet, so
  there is no `workbook(...)` form and a second `sheet(...)` call makes the final method
  throw rather than adding a sheet.
- The CSV download file name falls back to the sheet name before `Pxl` on `exportCsv()`,
  since one CSV file is one sheet and that is the only name the source carries.
  `exportSampleCsv()` keeps the plain `PxlSample` default, a template describing a shape
  rather than a data set.

### Changed

- **Breaking, through the core.** `PxlFileFormat` and `PxlExcelEngine` moved from
  `io.github.hclimkr.pxl` to `io.github.hclimkr.pxl.type`. Neither type changed otherwise,
  so migration is the import alone. Application code feels this wherever it names either
  enum — `@PxlWorkbook(exportExcelEngine = PxlExcelEngine.HSSF)`, an option builder, or a
  `PxlFileFormat` reference.

- **Breaking.** `PxlSpring`'s `@Autowired` constructor takes the two new components as well,
  so an application that builds the facade by hand rather than by component scan has to pass
  them. The documented setup — scanning `io.github.hclimkr.pxl.spring` — is unaffected, and
  so is the no-arg constructor.

- A download body is held once rather than twice. `toResponseEntity(...)` used to copy the
  finished buffer into an exactly-sized array before wrapping it as a `Resource`; the body now
  reads that buffer where it lies, so the entity destinations cost what `toResponse(...)` costs.
  The buffer is Spring's `FastByteArrayOutputStream` as well, which grows by adding blocks
  instead of reallocating, so the peak no longer carries a transient second copy of everything
  written so far. Nothing in the API moves — the body is still `ResponseEntity<Resource>`.

- Internal: the shared download helpers in `PxlExportSupport` dropped `Excel` from their
  names (`setResponseForExport`, `writeBufferToResponseForExport`,
  `makeResponseEntityForExport`) now that CSV goes through the same `PxlFileFormat`-driven
  path. They are `public` only because their callers sit in another package; treat them as
  internal, as `internal.support` says.

## [0.9.1] - 2026-08-05

Built against [pxl](https://github.com/hclimkr/pxl) 0.9.3, up from 0.9.2.

### Added

- Import from a Spring `Resource`, alongside the existing multipart terminals:
  `fromResource(...)` on `importExcel()`, and `fromResource(...)` / `fromResources(...)`
  on `importCsv()`. This covers the non-HTTP paths — a batch job, a seed loader, a test —
  which had no entry point here at all. A resource must report a file name, since that is
  what the extension is read from; one that does not is rejected with
  `HttpMediaTypeNotSupportedException` just like an unsupported extension.

### Changed

- **Breaking, through the core.** `@PxlWorkbook(exportFileFormat)` and
  `PxlExportWorkbookOption.exportFileFormat` are now `exportExcelEngine` and take a
  `PxlExcelEngine` (`HSSF` / `XSSF` / `SXSSF`), since pxl split `PxlFileFormat` into a
  physical format (`XLS` / `XLSX` / `CSV`) and the engine that writes it. No method here
  changed; the exporters reach the download format through the engine's `getFileFormat()`.
  Migration is the rename plus `PxlFileFormat.HSSF` → `PxlExcelEngine.HSSF`.

- Documented that a ZIP entry's extension comes from the workbook class's declared export
  engine and that a per-entry option does not override it — the option changes the bytes
  only, so an `HSSF` option leaves the entry named `.xlsx`. The behavior is unchanged.

- The contributing guide (`CONTRIBUTING.md` / `CONTRIBUTING_ko.md`) now states the
  repository's policy — issue reports and suggestions only, pull requests are not
  accepted — and asks what an issue should carry: the versions, expected versus actual
  behavior, and a minimal reproduction.

## [0.9.0] - 2026-07-29

First public release. Built against [pxl](https://github.com/hclimkr/pxl) 0.9.2 — `pxl-javax` for the
javax variant, `pxl-jakarta` for the jakarta one.

### Added

- Import: multipart uploads (XLSX, XLS, CSV) into Java objects, via `importExcel()`
  and `importCsv()`.
- Export: Java objects into Excel, a sample template, or a multi-workbook ZIP, via
  `exportExcel()`, `exportSampleExcel()` and `exportExcelZip()` — to a stream, a file,
  or a download response.
- `PxlSpring`, one bean fronting all five entry points, with Bean Validation, RFC 5987
  download names, and opt-in AOP performance logging.

[Unreleased]: https://github.com/hclimkr/pxl-spring/compare/v0.9.3...HEAD
[0.9.3]: https://github.com/hclimkr/pxl-spring/compare/v0.9.2...v0.9.3
[0.9.2]: https://github.com/hclimkr/pxl-spring/compare/v0.9.1...v0.9.2
[0.9.1]: https://github.com/hclimkr/pxl-spring/compare/v0.9.0...v0.9.1
[0.9.0]: https://github.com/hclimkr/pxl-spring/releases/tag/v0.9.0
