# Changelog

All notable changes to this project are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Changed

- The contributing guide (`CONTRIBUTING.md` / `CONTRIBUTING_ko.md`) now states the
  repository's policy — issue reports and suggestions only, pull requests are not
  accepted — and asks for the version and artifact, the Java and Spring (or Spring Boot)
  versions, expected versus actual behavior, and a minimal reproduction: the annotated
  DTO plus the calling code, either the controller method or the `PxlSpring` builder
  chain, with sensitive data stripped from any attached source file. Vulnerabilities are
  pointed at `SECURITY.md`. The developer-facing sections it used to carry (repository
  structure, build and test commands, test authoring and code conventions) are gone, and
  the `Build & Contributing` paragraph in both READMEs now points at the policy instead
  of those sections.

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
