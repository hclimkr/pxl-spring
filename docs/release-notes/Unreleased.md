# PXL Spring (Unreleased)

A release about the ZIP exporter. An entry-name collision is caught before the archive is written rather than
from inside the zip stream, an entry's name now agrees with the bytes in it, and an archive can hold two more
kinds of member: a raw POI workbook you built yourself, and a sample template generated from a class. Built
against [PXL](https://github.com/hclimkr/pxl) 0.9.4, unchanged.

Pre-1.0 release carrying **breaking** changes: a collision raises `PxlArgumentException` where it used to
raise `PxlIOException`, two entry names differing only in case are refused where they used to produce an
archive, and a per-entry `exportExcelEngine` now changes the entry's extension as well as its content — see
the highlights below.

## Highlights

  - **A ZIP entry can be a raw POI workbook.** `PxlExcelZipExporter` bundled one kind of source only, a
    `@PxlWorkbook`-annotated object — so a workbook the application had already built, which
    `exportExcel().poiWorkbook(...)` writes happily on its own, could not go into an archive at all.
    `poiWorkbook(workbook)`, `poiWorkbook(workbook, password)` and
    `poiWorkbook(workbook, password, entryName)` now add one. The workbook is written as-is, with no PXL
    binding and therefore no per-entry export option, and the entry's extension is read back off the workbook
    itself (`HSSFWorkbook` → `.xls`, `XSSFWorkbook`/`SXSSFWorkbook` → `.xlsx`) — the same format its body is
    written in, so name and bytes cannot disagree. Encryption keeps that extension, exactly as on
    `PxlExcelExporter`. This kind has no workbook name to fall back to, so an unnamed entry goes straight to
    `Pxl{index}`; the duplicate-name check below spans every kind of entry alike.
    ```java
    pxlSpring.exportExcelZip()
             .workbook(januaryReport)             // -> january.xlsx, bound from the annotated object
             .poiWorkbook(chart, null, "chart")   // -> chart.xlsx, written as-is
             .toResponse(response, "quarterly-report");
    ```
  - **A ZIP entry can be a sample template.** `sampleWorkbook(workbookClass)`,
    `sampleWorkbook(workbookClass, option)` and `sampleWorkbook(workbookClass, option, entryName)` put what
    `exportSampleExcel()` produces — the header row plus one row of `@PxlColumn(exportSample = ...)` values —
    straight into an archive, so handing out several upload forms at once is one chain rather than one
    download per form. The per-entry option decides the entry's extension as well as its bytes, exactly as on
    `workbook(...)`. What differs is the name: this form is given a class, and a workbook name is read off an
    annotated *instance*, so there is nothing to read it from — an unnamed entry resolves straight to
    `PxlSample{index}`. The index is there because entries share an archive and have to come out distinct;
    `PxlSampleExcelExporter` falls back to a bare `PxlSample` because it names a single download.
    ```java
    pxlSpring.exportExcelZip()
             .sampleWorkbook(EmployeeForm.class, null, "employees")
             .sampleWorkbook(DepartmentForm.class, null, "departments")
             .toResponseEntity("upload-forms");
    ```
  - **A duplicate ZIP entry name fails before anything is written.** Entry names resolve as
    `explicit name` → `@PxlWorkbook` workbook name → `Pxl{index}`, and the index fallback applies only when
    neither of the first two yields a name — so two instances of the same workbook class, the ordinary case,
    resolved to one entry name and were caught only by `ZipOutputStream.putNextEntry`, mid-write, as a
    `PxlIOException` indistinguishable from a full disk. The check now runs in the builder's up-front
    validation, which every destination calls first: `toFile(...)` no longer creates a file it cannot finish
    and `toResponseStreaming(...)` no longer commits the download headers before failing, and the
    `PxlArgumentException` names the entry. Names are compared whole, extension included, so the same base
    name under two engines (`report.xlsx` and `report.xls`) is still two members — but the comparison
    **ignores case**, which `ZipOutputStream` does not: `Report.xlsx` and `report.xlsx` used to go in as two
    members and overwrite one another when extracted on Windows or macOS. Give colliding entries an explicit
    name.
    ```java
    pxlSpring.exportExcelZip()
             .workbook(january, null, "january")    // both declare workbookName = "report"
             .workbook(february, null, "february")
             .toResponse(response, "quarterly-report");
    ```
  - **A per-entry option now names the entry too.** `exportExcelEngine` on a `workbook(object, option)` entry
    decided that entry's bytes but not its extension, which came from the engine the workbook class declares
    — so an `HSSF` option produced OLE2 content under a `.xlsx` name, and the archive then stored it at
    `NO_COMPRESSION` on the assumption that it was already-deflated OOXML: uncompressed data written
    uncompressed. The option is now asked first and the class second, the same priority
    `PxlExcelExporter` already used, and the entry's name, its content and its deflate level all follow that
    one answer. An option carrying no engine still falls through to the class.
    ```java
    pxlSpring.exportExcelZip()
             .workbook(report, hssfOption)   // -> report.xls, OLE2 bytes, deflated
             .toResponse(response, "archive");
    ```
