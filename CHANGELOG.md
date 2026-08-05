# Changelog

All notable changes to this project are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

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

[Unreleased]: https://github.com/hclimkr/pxl-spring/compare/v0.9.1...HEAD
[0.9.1]: https://github.com/hclimkr/pxl-spring/compare/v0.9.0...v0.9.1
[0.9.0]: https://github.com/hclimkr/pxl-spring/releases/tag/v0.9.0
