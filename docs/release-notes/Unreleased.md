# PXL Spring (Unreleased)

> Draft notes for the next release. The version is fixed when the release is cut — at that point this file is
> renamed to `vX.Y.Z.md`, its heading takes the version, and the matching `## [Unreleased]` section in
> `CHANGELOG.md` is retitled the same way.

> Also before the release: bump `<pxlRevision>` in the root POM to the PXL version that introduces
> `exportCsv()` / `exportSampleCsv()` and the `io.github.hclimkr.pxl.type` package. This release cannot ship
> against 0.9.3.

CSV release for **PXL Spring**, following the core's: the two directions now agree about which formats they
handle, so a Spring application can hand out a CSV download the same way it hands out an Excel one. One change
asks something of a caller — the import of `PxlFileFormat` / `PxlExcelEngine` moved, in the core.

## Highlights

  - **Objects → CSV, as a download.** `exportCsv()` and `exportSampleCsv()` join the three Excel exports on
    `PxlSpring`, backed by the new `PxlCsvExporter` and `PxlSampleCsvExporter` components. They are the Excel
    exporters' shape, unchanged: the same DTOs, the same `override(...)`, and the same five destinations —
    `toStream(...)`, `toFile(...)`, `toResponse(...)`, `toResponseStreaming(...)`, `toResponseEntity(...)`.
    ```java
    @GetMapping("/employees/csv")
    public ResponseEntity<Resource> downloadEmployeesCsv() {
        return pxlSpring.exportCsv()
                        .sheet(Employee.class, employees, "Employees")
                        .toResponseEntity(null);   // -> Employees.csv, text/csv
    }
    ```
  - **One CSV file is one sheet**, so there is no `workbook(...)` form to call and a second `sheet(...)` call
    does not add a sheet — it makes the final method throw `PxlArgumentException`. The download name follows
    from the same fact: a blank one falls back to the sheet name and then to `Pxl`, the sheet name standing in
    for the Excel exporter's `@PxlWorkbook` workbook-name fallback. `exportSampleCsv()` keeps the plain
    `PxlSample` default, because a template describes a shape rather than a data set.
  - **The charset, delimiter and byte order mark are the core's**, read from `@PxlWorkbook`/`@PxlSheet` or the
    matching `exportCsv*` option fields, and defaulting to UTF-8, `,` and no mark — which is what makes an
    MS949 export for a legacy consumer a one-line `override(...)`. What CSV cannot carry is ignored, with one
    refusal: `exportPassword` raises `PxlArgumentException` rather than being ignored, since CSV cannot be
    encrypted and writing plaintext would be a leak.
  - **Plan for the memory.** A CSV export renders its whole output before the destination is opened — that is
    what keeps a codec, validation or limit failure from leaving a file behind — so it has the memory profile
    of a non-streaming Excel export, not of a lightweight bulk path. `toResponseStreaming(...)` exists on both
    new builders for consistency, but it is worth the least here: it drops the download buffer and nothing
    more, so heap still scales with the output, once instead of twice. See "Size & Memory" in the README.
  - **`PxlFileFormat` and `PxlExcelEngine` moved to `io.github.hclimkr.pxl.type`** *(breaking, from the core)*.
    Update the import; nothing else about either type changed. Application code feels this wherever it names
    either enum — `@PxlWorkbook(exportExcelEngine = PxlExcelEngine.HSSF)`, an option builder, a `PxlFileFormat`
    reference.
  - **`PxlSpring`'s injecting constructor takes two more components** *(breaking)*, so an application that
    builds the facade by hand has to pass them. The documented setup — a component scan of
    `io.github.hclimkr.pxl.spring` — is unaffected, and so is the no-arg constructor.
