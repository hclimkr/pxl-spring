# PXL Spring (Unreleased)

Correctness release for **PXL Spring**, both changes in the ZIP exporter: an entry-name collision is caught
before the archive is written rather than from inside the zip stream, and an entry's name now agrees with the
bytes in it. Built against [PXL](https://github.com/hclimkr/pxl) 0.9.4, unchanged.

Pre-1.0 release carrying **breaking** changes: a collision raises `PxlArgumentException` where it used to
raise `PxlIOException`, two entry names differing only in case are refused where they used to produce an
archive, and a per-entry `exportExcelEngine` now changes the entry's extension as well as its content — see
the highlights below.

## Highlights

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
