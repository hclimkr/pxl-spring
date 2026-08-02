# Changelog

All notable changes to this project are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Added

- Import from a Spring `Resource`, alongside the existing multipart terminals:
  `fromResource(...)` on `importExcel()`, and `fromResource(...)` / `fromResources(...)`
  on `importCsv()`. This covers the non-HTTP paths — a batch job reading a file off disk,
  an initializer reading a classpath seed, a test reading a fixture — which previously had
  no entry point here at all and forced callers to build their own core `Pxl` instance.
  Everything else is unchanged: the same extension validation, the same name derivation
  (a workbook-name fallback for Excel, a sheet name for CSV), the same option handling.
  A resource must report a file name, because that is what the extension is read from; one
  that does not, such as a bare `ByteArrayResource`, is rejected with
  `HttpMediaTypeNotSupportedException` just like an unsupported extension.

### Changed

- The contributing guide (`CONTRIBUTING.md` / `CONTRIBUTING_ko.md`) now states the
  repository's policy — issue reports and suggestions only, pull requests are not
  accepted — and asks for the version and artifact, the Java and Spring (or Spring Boot)
  versions, expected versus actual behavior, and a minimal reproduction: the annotated
  DTO plus the calling code, either the controller method or the `PxlSpring` builder
  chain, with sensitive data stripped from any attached source file.

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

[Unreleased]: https://github.com/hclimkr/pxl-spring/compare/v0.9.0...HEAD
[0.9.0]: https://github.com/hclimkr/pxl-spring/releases/tag/v0.9.0
