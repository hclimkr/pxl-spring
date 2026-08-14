# PXL Spring (Unreleased)

Correctness release for **PXL Spring**: a ZIP entry-name collision is caught before the archive is written
rather than surfacing from inside the zip stream, so it can be told apart from a disk failure and leaves no
half-written file or committed response behind. Built against [PXL](https://github.com/hclimkr/pxl) 0.9.4,
unchanged.

Pre-1.0 release carrying a **breaking** change: a collision now raises `PxlArgumentException` where it used to
raise `PxlIOException`, and two entry names differing only in case are refused where they used to produce an
archive — see the highlight below.

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
