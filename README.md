**English** · [한국어](README_ko.md)

PXL Spring - Spring Excel & CSV Upload / Download Library Built on PXL
=============================

[![Build](https://github.com/hclimkr/pxl-spring/actions/workflows/build.yml/badge.svg?branch=main)](https://github.com/hclimkr/pxl-spring/actions/workflows/build.yml)
[![Maven Central](https://img.shields.io/maven-central/v/io.github.hclimkr/pxl-spring-javax?label=Maven%20Central)](https://central.sonatype.com/artifact/io.github.hclimkr/pxl-spring-javax)
[![Javadoc](https://javadoc.io/badge2/io.github.hclimkr/pxl-spring-javax/javadoc.svg)](https://javadoc.io/doc/io.github.hclimkr/pxl-spring-javax)
[![Java](https://img.shields.io/badge/Java-8%2B%20%2F%2017%2B-orange.svg)](#setup)
[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](LICENSE)

PXL Spring provides **multipart uploads and download responses for spreadsheet-object binding in Spring**, built on [PXL](https://github.com/hclimkr/pxl).
It leaves the binding to PXL and supports Java 8 and later.

Turn a `MultipartFile` upload of `.xlsx`, `.xls` or `.csv` straight into a `List<Employee>`, and return a
`List<Employee>` from a controller method as an Excel or CSV download — no `Row`/`Cell` loops, no
`Content-Disposition` header to assemble by hand, no `byte[]` copied through the response. One injected
bean carries every direction, in Spring Boot and in plain Spring MVC alike, and one annotated DTO drives
both of them.

- Import: multipart uploads (XLSX · XLS · CSV) → Java objects
- Export: Java objects → Excel · sample Excel · CSV · sample CSV · ZIP download

```java
@GetMapping("/employees/excel")
public ResponseEntity<Resource> download() throws Exception {
    return pxlSpring.exportExcel()
                    .sheet(Employee.class, employees, "Employees")
                    .toResponseEntity("employee-list");
}
```

For PXL behavior — annotation attributes, supported types, the full set of options — refer to the [PXL documentation](https://github.com/hclimkr/pxl/blob/main/docs/reference.md).

> [!WARNING]
> **Pre-1.0: the public API is still moving.** Under Semantic Versioning a `0.y.z` release makes no compatibility
> promise, and PXL Spring uses that room: a minor release may rename, move or remove a public type or method
> without a deprecation cycle — several already have. A breaking change in [PXL](https://github.com/hclimkr/pxl)
> reaches your code through this library too, since PXL is what does the binding. Pin an exact version rather
> than a range, and read the [CHANGELOG](CHANGELOG.md) before upgrading; every such change is marked breaking.

## Table of Contents

1. [Features](#features)
2. [Setup](#setup)
3. [Runtime Dependencies](#runtime-dependencies)
4. [Injecting `PxlSpring`](#injecting-pxlspring)
5. [Defining DTO Classes](#defining-dto-classes)
6. [Usage at a Glance](#usage-at-a-glance)
7. [API Usage](#api-usage)
8. [API Reference](#api-reference)
9. [Notes](#notes)
10. [Size & Memory](#size--memory)
11. [Performance Logging (Optional)](#performance-logging-optional)
12. [FAQ](#faq)
13. [Build & Contributing](#build--contributing)
14. [License](#license)

---

## Features

- **One bean for every direction** — inject `PxlSpring` and each operation is a method chain off it:
  `exportExcel()`, `exportSampleExcel()`, `exportCsv()`, `exportSampleCsv()`, `exportZip()`,
  `importExcel()`, `importCsv()`.
- **Multipart upload straight to objects** — `fromMultipartFile(...)` / `fromMultipartFiles(...)` turn an
  upload into a `List<Employee>` or a whole workbook object, with the extension checked before parsing.
- **Not only uploads** — `fromResource(...)` / `fromResources(...)` accept any Spring `Resource`, so batch
  jobs, seed loaders and tests read a spreadsheet the same way without a `MultipartFile`.
- **Five destinations per exporter** — `toStream(...)`, `toFile(...)`, `toResponse(...)`,
  `toResponseStreaming(...)` and `toResponseEntity(...)`. Swap the final call and the same configuration
  goes somewhere else.
- **Download headers assembled for you** — `Content-Disposition` carries both an ASCII `filename` and an
  RFC 5987 `filename*`, so a non-ASCII download name survives, and the content type and extension follow
  the format actually being written.
- **Constant heap for large downloads** — `toResponseStreaming(...)` skips the download buffer; paired with
  the `SXSSF` engine, or with a CSV export's 4 MiB spill to a temporary file, an export costs roughly the
  same heap whatever the row count.
- **Sample templates from a class alone** — `exportSampleExcel()` / `exportSampleCsv()` produce a header
  row plus one filled example row to hand out and collect back through the importers.
- **Several spreadsheets as one zip** — `exportZip()` takes an entry from any of the other exporters,
  Excel or CSV, stores an already-compressed `.xlsx` without recompressing it, and never leaves an
  openable archive behind when the export fails.
- **Validation at the edge** — an unsupported extension is refused as `HttpMediaTypeNotSupportedException`
  before anything is parsed, a `null` destination as `ConstraintViolationException` behind the Spring
  proxy, and PXL's own `PxlException` covers the rest.
- **Optional performance logging** — an AOP aspect times every execution and flags anything past a
  threshold, off unless `pxl.performance.logging.enabled=true`.
- **Two variants, one source** — `pxl-spring-javax` for Java 8+ on Spring 5.x / Boot 2.x, and
  `pxl-spring-jakarta` for Java 17+ on Spring 6.x / Boot 3.x.

---

## Setup

Add only the variant that matches your environment to your dependencies.
- `pxl-spring-javax` (Java 8+, Spring 5.x / Boot 2.x, `javax.*`)
- `pxl-spring-jakarta` (Java 17+, Spring 6.x / Boot 3.x, `jakarta.*`)

### Maven

```xml
<!-- javax variant (Java 8+) -->
<dependency>
    <groupId>io.github.hclimkr</groupId>
    <artifactId>pxl-spring-javax</artifactId>
    <version>0.9.2</version>
</dependency>
```

```xml
<!-- jakarta variant (Java 17+) -->
<dependency>
    <groupId>io.github.hclimkr</groupId>
    <artifactId>pxl-spring-jakarta</artifactId>
    <version>0.9.2</version>
</dependency>
```

### Gradle

```groovy
// javax variant (Java 8+)
implementation 'io.github.hclimkr:pxl-spring-javax:0.9.2'
```

```groovy
// jakarta variant (Java 17+)
implementation 'io.github.hclimkr:pxl-spring-jakarta:0.9.2'
```

---

## Runtime Dependencies

### Spring Boot

Most Spring Boot applications only need the starters below.

```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-web</artifactId>
</dependency>
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-validation</artifactId>
</dependency>
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-aop</artifactId>
</dependency>
```

### Plain Spring

You already have `spring-context` and `spring-web`; only the two optional pieces below are extra, and each is needed only for the feature it backs.

```xml
<!-- only if you want @Validated to reject a null destination as ConstraintViolationException -->
<dependency>
    <groupId>org.hibernate.validator</groupId>
    <artifactId>hibernate-validator</artifactId>
</dependency>
<dependency>
    <groupId>org.glassfish</groupId>
    <artifactId>jakarta.el</artifactId>
</dependency>

<!-- only if you want the optional performance logging -->
<dependency>
    <groupId>org.aspectj</groupId>
    <artifactId>aspectjweaver</artifactId>
</dependency>
```

The servlet API comes from your container, and the core `pxl` library comes in as a dependency.

Boot does three pieces of wiring for you that you now have to declare yourself. All three are optional in the sense that the library still works without them — it just loses the feature each one enables:

- **Bean validation** Register a `MethodValidationPostProcessor` bean, or `@Validated` never fires. Without it a `null` destination is still rejected, as `PxlNullPointerException` rather than `ConstraintViolationException`.
- **Performance logging** Add `@EnableAspectJAutoProxy` to a configuration class, or the aspect is registered but never weaves.
- **Multipart uploads** A plain Spring MVC application must declare a `multipartResolver` bean.

---

## Injecting `PxlSpring`

`PxlSpring` is the entry point — one bean carrying every operation.

| Start method                    | What it does                                                                 |
|---------------------------------|------------------------------------------------------------------------------|
| `pxlSpring.exportExcel()`       | Java objects → Excel (Stream/File/Response/ResponseStreaming/ResponseEntity) |
| `pxlSpring.exportSampleExcel()` | Class → Excel carrying a single sample data row                              |
| `pxlSpring.exportCsv()`         | Java objects → CSV (the same five destinations)                              |
| `pxlSpring.exportSampleCsv()`   | Class → CSV carrying a single sample data record                             |
| `pxlSpring.exportZip()`         | Several spreadsheets (Excel or CSV) → one zip                                |
| `pxlSpring.importExcel()`       | Excel file → Java objects                                                    |
| `pxlSpring.importCsv()`         | CSV file → Java objects                                                      |

The `io.github.hclimkr.pxl.spring` package must be in your component scan for `PxlSpring` and the performance-logging aspect to be registered as beans. Sub-packages are scanned along with it, so this one entry covers everything.

```java
@SpringBootApplication
@ComponentScan(basePackages = {"com.example.myapp", "io.github.hclimkr.pxl.spring"})
public class MyApplication {
    public static void main(String[] args) {
        SpringApplication.run(MyApplication.class, args);
    }
}
```

A plain Spring application scans exactly the same package — put the `@ComponentScan` on any `@Configuration` class.

Then inject it wherever you need it. Every example in this document assumes the field below.

```java
@Autowired
private PxlSpring pxlSpring;

// pxlSpring.exportExcel()
// pxlSpring.exportSampleExcel()
// pxlSpring.exportCsv()
// pxlSpring.exportSampleCsv()
// pxlSpring.exportZip()
// pxlSpring.importExcel()
// pxlSpring.importCsv()
```

---

## Defining DTO Classes

### Row Class

A row class maps each field to a header with `@PxlColumn`.

```java
import io.github.hclimkr.pxl.annotation.PxlColumn;
import io.github.hclimkr.pxl.annotation.PxlRowIndex;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDate;

@Getter                     // (optional) for your convenience — PXL binds fields directly, so a getter is not required.
@Setter                     // (optional) for your convenience — PXL binds fields directly, so a setter is not required.
@NoArgsConstructor          // (required) no-arg constructor is required for import
public class Employee {

    @PxlRowIndex            // (optional) 1-based spreadsheet row number. Types: byte/short/int/long + wrappers (Byte/Short/Integer/Long)
    private Integer rowIndex;

    @PxlColumn(name = "Name", exportSample = "John Doe")
    private String name;

    @PxlColumn(name = "Age", exportSample = "25")
    private Integer age;

    @PxlColumn(name = "Salary", exportSample = "45000")
    private Long salary;

    @PxlColumn(name = "Active", exportSample = "true")
    private Boolean active;

    @PxlColumn(name = "HireDate", pattern = "yyyy-MM-dd", exportSample = "2024-03-01")
    private LocalDate hireDate;

    @PxlColumn(name = "Grade", exportSample = "C")
    private Grade grade;
}
```

- If you omit `name`, the field name becomes the column name.
- `name` must match the actual header for binding to happen (whitespace is ignored, case is significant).
- `exportSample` is the example value that goes into an [export sample](#pxlsampleexcelexporter) (it has no effect on a regular export).

`Grade` is a user-defined enum used in the examples.

```java
public enum Grade {
    A, B, C, F
}
```

The row class used as the second sheet in the multi-sheet examples is defined the same way.

```java
import io.github.hclimkr.pxl.annotation.PxlColumn;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter                     // (optional) for your convenience — PXL binds fields directly, so a getter is not required.
@Setter                     // (optional) for your convenience — PXL binds fields directly, so a setter is not required.
@NoArgsConstructor          // (required) no-arg constructor is required for import
public class Department {

    @PxlColumn(name = "Code")
    private String code;

    @PxlColumn(name = "DepartmentName")
    private String departmentName;

    @PxlColumn(name = "Headcount")
    private int headcount;
}
```

### Workbook Class (Multiple Sheets in One Object)

Each sheet field is a `Collection` type and is bound with `@PxlSheet`.

```java
import io.github.hclimkr.pxl.annotation.PxlSheet;
import io.github.hclimkr.pxl.annotation.PxlWorkbookName;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.List;

@Getter                     // (optional) for your convenience — PXL binds fields directly, so a getter is not required.
@Setter                     // (optional) for your convenience — PXL binds fields directly, so a setter is not required.
@NoArgsConstructor          // (required) no-arg constructor is required for import
public class Company {

    @PxlWorkbookName        // (optional) a String field to hold the workbook name
    private String workbookName;

    @PxlSheet(name = "Employees")
    private List<Employee> employees;

    @PxlSheet(name = "Departments")
    private List<Department> departments;
}
```

---

## Usage at a Glance

### Export

```java
import io.github.hclimkr.pxl.spring.PxlSpring;

import org.springframework.core.io.Resource;
import org.springframework.http.ResponseEntity;
import java.util.Arrays;
import java.util.List;

// Export: Employees sheet → Excel download response
@GetMapping("/employees/excel")
public ResponseEntity<Resource> download() throws Exception {
    // Prepare Employee row objects
    Employee alice = new Employee();
    alice.setName("Alice");
    alice.setAge(30);

    Employee bob = new Employee();
    bob.setName("Bob");
    bob.setAge(42);

    // Prepare the Employees sheet object
    List<Employee> employees = Arrays.asList(alice, bob);

    return pxlSpring.exportExcel()
                    .sheet(Employee.class, employees, "Employees")
                    .toResponseEntity("employee-list");
}
```

### Import

```java
import io.github.hclimkr.pxl.spring.PxlSpring;

import org.springframework.web.multipart.MultipartFile;
import java.util.List;

// Import: multipart Excel upload → list of Employee row objects
@PostMapping("/employees/import")
public int upload(@RequestParam MultipartFile file) throws Exception {
    List<Employee> employees = pxlSpring.importExcel()
                                        .sheet(Employee.class, "Employees")
                                        .fromMultipartFile(file);
    return employees.size();
}
```

Every operation is handled through a single method chain like the examples above. The start method indicates the direction of the operation (export/import) and the format (Excel/CSV/sample/ZIP), then you specify the target, and it is executed in the final method.

| Use case            | Method chain (start → configure → execute)                                                                                                                                                                 |
|---------------------|------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| Excel export        | `pxlSpring.exportExcel()`<br/>→ `.workbook(...) / .sheet(...) / .poiWorkbook(...)`<br/>→ `.toStream(OutputStream)` / `.toFile(File)` / `.toResponse(HttpServletResponse, String)` / `.toResponseStreaming(HttpServletResponse, String)` / `.toResponseEntity(String)` |
| Sample Excel export | `pxlSpring.exportSampleExcel()`<br/>→ `.workbook(...) / .sheet(...)`<br/>→ `.toStream(OutputStream)` / `.toFile(File)` / `.toResponse(HttpServletResponse, String)` / `.toResponseStreaming(HttpServletResponse, String)` / `.toResponseEntity(String)` |
| CSV export          | `pxlSpring.exportCsv()`<br/>→ `.sheet(...)`<br/>→ `.toStream(OutputStream)` / `.toFile(File)` / `.toResponse(HttpServletResponse, String)` / `.toResponseStreaming(HttpServletResponse, String)` / `.toResponseEntity(String)` |
| Sample CSV export   | `pxlSpring.exportSampleCsv()`<br/>→ `.sheet(...)`<br/>→ `.toStream(OutputStream)` / `.toFile(File)` / `.toResponse(HttpServletResponse, String)` / `.toResponseStreaming(HttpServletResponse, String)` / `.toResponseEntity(String)` |
| ZIP export          | `pxlSpring.exportZip()`<br/>→ `.workbook(...) / .poiWorkbook(...) / .sampleWorkbook(...) / .csvSheet(...) / .sampleCsvSheet(...)`<br/>→ `.toStream(OutputStream)` / `.toFile(File)` / `.toResponse(HttpServletResponse, String)` / `.toResponseStreaming(HttpServletResponse, String)` / `.toResponseEntity(String)` |
| Excel import        | `pxlSpring.importExcel()`<br/>→ `.workbook(...) / .sheet(...)`<br/>→ `.fromMultipartFile(MultipartFile)`                                                                                                   |
| CSV import          | `pxlSpring.importCsv()`<br/>→ `.workbook(...) / .sheet(...)`<br/>→ `.fromMultipartFile(MultipartFile)` / `.fromMultipartFiles(List<MultipartFile>)`                                                          |

- For export, the configuration steps `.workbook(...)`, `.sheet(...)` and `.poiWorkbook(...)` are mutually exclusive — specifying more than one in a chain throws `PxlArgumentException` (as does omitting all of them).
- For export, calling `.sheet(...)` multiple times creates multiple sheets. `exportSampleExcel()` works the same way.
- For CSV export, one file is one sheet — there is no `.workbook(...)` to call, and a second `.sheet(...)` call does not add a sheet, it makes the final method throw `PxlArgumentException`. `exportSampleCsv()` works the same way.
- For import, `.sheet(...)` cannot be chained consecutively. There are two ways to read multiple sheets.
    - All at once, in workbook form: passing a `@PxlWorkbook` class to `.workbook(...)` binds multiple sheets at once, one per `@PxlSheet` field.  
    - One sheet at a time: start a fresh chain per sheet and run each through `.fromMultipartFile(...)`.

You can insert `override(...)` (core option) and `workbookName(...)` (workbook name) anywhere in the chain. The order is free, and if you set the same value twice the later one is the one used.

---

## API Usage

Every example below assumes `PxlSpring` was injected under the name `pxlSpring`.

```java
@RestController
public class ExcelController {

    @Autowired
    private PxlSpring pxlSpring;

    // every handler method below lives in a controller like this one
}
```

### `PxlExcelExporter`

The most common use is downloading straight from a controller.

**A single sheet downloaded as a `ResponseEntity`**

```java
@GetMapping("/employees/excel")
public ResponseEntity<Resource> downloadEmployees() throws Exception {
    List<Employee> employees = ...;

    return pxlSpring.exportExcel()
                    .sheet(Employee.class, employees, "Employees")
                    .toResponseEntity("report");
}
```

**A workbook object downloaded through `HttpServletResponse`**

Recommended for large data.

```java
@GetMapping("/company/excel")
public void downloadCompany(HttpServletResponse response) throws Exception {
    Company company = new Company();
    company.setWorkbookName("company-report");
    company.setEmployees(...);
    company.setDepartments(...);

    // leave the name blank and it falls back to the workbook name, then to "Pxl"
    pxlSpring.exportExcel()
             .workbook(company)
             .toResponse(response, null);
}
```

**Multiple sheets downloaded**

Call `sheet(...)` several times — the call order is the sheet order, and each sheet may take a different row class.

```java
@GetMapping("/company/sheets")
public void downloadSheets(HttpServletResponse response) throws Exception {
    List<Employee> employees = ...;
    List<Department> departments = ...;

    pxlSpring.exportExcel()
             .sheet(Employee.class, employees, "Employees")
             .sheet(Department.class, departments, "Departments")
             .toResponse(response, "company-report");
}
```

**Multiple sheets built in a loop**

From index-aligned parallel lists. With wildcard element types a cast is needed to line up the `rows` and `rowClass` type parameters.

```java
@GetMapping("/company/dynamic-sheets")
@SuppressWarnings("unchecked")
public void downloadDynamicSheets(HttpServletResponse response) throws Exception {
    List<String> sheetNames = ...;
    List<Class<?>> rowClasses = ...;
    List<Collection<?>> sheetData = ...;

    PxlExcelExporter.Builder builder = pxlSpring.exportExcel();
    for (int i = 0; i < sheetNames.size(); i++) {
        builder.sheet((Class<Object>) rowClasses.get(i), (Collection<Object>) sheetData.get(i), sheetNames.get(i));
    }

    builder.toResponse(response, "company-report");
}
```

**A raw POI workbook downloaded**

It is written as-is, and the download headers follow the workbook's own type.

```java
@GetMapping("/employees/excel-poi")
public ResponseEntity<Resource> downloadPoiWorkbook() throws Exception {
    Workbook poiWorkbook = ...;

    return pxlSpring.exportExcel()
                    .poiWorkbook(poiWorkbook, "secret")   // the password is optional
                    .toResponseEntity("report");
}
```

**Overriding export options**

Set them with `override(...)`. Here the `HSSF` engine makes the download come out as `.xls` instead of `.xlsx`.

```java
@GetMapping("/employees/excel-xls")
public void downloadEmployeesAsXls(HttpServletResponse response) throws Exception {
    List<Employee> employees = ...;

    PxlExportWorkbookOption option = PxlExportWorkbookOption.builder()
                                                            .exportExcelEngine(PxlExcelEngine.HSSF)
                                                            .build();

    pxlSpring.exportExcel()
             .sheet(Employee.class, employees, "Employees")
             .override(option)
             .toResponse(response, "report");   // -> report.xls
}
```

**Writing to a file or a stream**

In a batch job or a scheduled task the same chain just ends differently.

```java
public void writeMonthlyReport(File file) throws Exception {
    List<Employee> employees = ...;

    pxlSpring.exportExcel()
             .sheet(Employee.class, employees, "Employees")
             .toFile(file);
}

public byte[] reportBytes() throws Exception {
    List<Employee> employees = ...;

    ByteArrayOutputStream baos = new ByteArrayOutputStream();
    pxlSpring.exportExcel()
             .sheet(Employee.class, employees, "Employees")
             .toStream(baos);   // the stream is left open — the caller owns it

    return baos.toByteArray();
}
```

Swap the final method and the same configuration goes to any of the five destinations: `toStream(OutputStream)`, `toFile(File)`, `toResponse(HttpServletResponse, String)`, `toResponseStreaming(HttpServletResponse, String)`, `toResponseEntity(String)`. For the full signatures see [API Reference — `PxlExcelExporter`](#pxlexcelexporter-1).

### `PxlSampleExcelExporter`

Use this to hand out an sample Excel that carries one sample row alongside the headers. The `@PxlColumn(exportSample=...)` values fill the sample row.

**A single sheet downloaded**

```java
@GetMapping("/employees/sample")
public ResponseEntity<Resource> employeeSample() throws Exception {
    // leave the name blank and it defaults to "PxlSample"
    return pxlSpring.exportSampleExcel()
                    .sheet(Employee.class, "Employees")
                    .toResponseEntity(null);
}
```

**Multiple sheets from a workbook class**

```java
@GetMapping("/company/sample")
public void companySample(HttpServletResponse response) throws Exception {
    pxlSpring.exportSampleExcel()
             .workbook(Company.class)
             .toResponse(response, "sample");
}
```

**Multiple sheets downloaded**

```java
@GetMapping("/company/sample-sheets")
public ResponseEntity<Resource> companySampleSheets() throws Exception {
    return pxlSpring.exportSampleExcel()
                    .sheet(Employee.class, "Employees")
                    .sheet(Department.class, "Departments")
                    .toResponseEntity("sample");
}
```

**Writing to a file or a stream**

```java
public void writeSampleTemplate(File file) throws Exception {
    pxlSpring.exportSampleExcel()
             .workbook(Company.class)
             .toFile(file);
}
```

### `PxlCsvExporter`

The same DTOs, the same options, written as CSV instead. One CSV file is one sheet, so there is only `sheet(...)`.

**A sheet downloaded as a `ResponseEntity`**

```java
@GetMapping("/employees/csv")
public ResponseEntity<Resource> downloadEmployeesCsv() throws Exception {
    List<Employee> employees = ...;

    // leave the name blank and it falls back to the sheet name, then to "Pxl"
    return pxlSpring.exportCsv()
                    .sheet(Employee.class, employees, "Employees")
                    .toResponseEntity(null);   // -> Employees.csv
}
```

**Choosing the charset, delimiter and byte order mark**

Declare them on the class with `@PxlWorkbook`/`@PxlSheet`, or per call with `override(...)`. A mark is written only for UTF-8, UTF-16LE and UTF-16BE.

```java
@GetMapping("/employees/csv-legacy")
public void downloadForExcelKorean(HttpServletResponse response) throws Exception {
    List<Employee> employees = ...;

    PxlExportWorkbookOption option = PxlExportWorkbookOption.builder()
                                                            .exportCsvCharset("MS949")
                                                            .exportCsvDelimiter(';')
                                                            .build();

    pxlSpring.exportCsv()
             .sheet(Employee.class, employees, "Employees")
             .override(option)
             .toResponse(response, "employee-list");
}
```

**Writing to a file or a stream**

```java
public void writeMonthlyCsv(File file) throws Exception {
    List<Employee> employees = ...;

    pxlSpring.exportCsv()
             .sheet(Employee.class, employees, "Employees")
             .toFile(file);
}
```

What CSV cannot carry — stylers, column widths, freeze panes, the Excel engine — is ignored. `exportPassword` is the one exception: it is refused with `PxlArgumentException` rather than ignored, because CSV cannot be encrypted and writing plaintext would be a leak.

### `PxlSampleCsvExporter`

The CSV counterpart of `PxlSampleExcelExporter`: a header record plus one sample record filled from `@PxlColumn(exportSample=...)`, which the recipient can fill in and send back through `importCsv()`.

```java
@GetMapping("/employees/csv-sample")
public ResponseEntity<Resource> employeeCsvSample() throws Exception {
    // leave the name blank and it defaults to "PxlSample"
    return pxlSpring.exportSampleCsv()
                    .sheet(Employee.class, "Employees")
                    .toResponseEntity(null);
}
```

### `PxlZipExporter`

**A zip of several spreadsheets downloaded**

An archive takes an entry from any of the exporters - a workbook object, a raw POI workbook, a sample template, a CSV sheet, a CSV template. Leave an entry name out and it falls back to the name that entry's source carries, then to an index-suffixed default.

```java
@GetMapping("/company/zip")
public ResponseEntity<Resource> downloadQuarter() throws Exception {
    Company january = ...;    // workbookName = "january"
    Company february = ...;   // workbookName = "february"

    return pxlSpring.exportZip()
                    .workbook(january)
                    .workbook(february)
                    .toResponseEntity("archive");   // -> archive.zip  (entries: january.xlsx, february.xlsx)
}
```

**Setting a per-workbook option and name**

```java
@GetMapping("/company/zip-named")
public void downloadQuarterNamed(HttpServletResponse response) throws Exception {
    Company january = ...;
    Company february = ...;

    PxlExportWorkbookOption hssfOption = PxlExportWorkbookOption.builder()
                                                                .exportExcelEngine(PxlExcelEngine.HSSF)
                                                                .build();

    pxlSpring.exportZip()
             .workbook(january, null, "january-report")
             .workbook(february, hssfOption, "february-report")
             .toResponse(response, "quarterly-report");
}
```

An entry's extension follows the engine that entry is actually written with — the per-entry option first, then the engine the workbook class declares — so `february-report` goes in as `february-report.xls`, its name and its bytes agreeing. The deflate level is picked from the same answer, so an `.xls` entry is compressed rather than stored the way an already-deflated `.xlsx` is.

**Bundling a workbook you built yourself**

A raw POI `Workbook` goes into the archive as-is, the way `exportExcel().poiWorkbook(...)` writes one on its own. Nothing is bound, so there is no per-entry option — only the password — and the entry is named after the workbook's own type. It carries no workbook name either, so an unnamed entry of this kind goes straight to `Pxl{index}`.

```java
@GetMapping("/company/zip-raw")
public void downloadWithRawWorkbook(HttpServletResponse response) throws Exception {
    Company january = ...;

    try (Workbook chart = buildChartWorkbook()) {
        pxlSpring.exportZip()
                 .workbook(january)
                 .poiWorkbook(chart, null, "chart")   // -> chart.xlsx (XSSF), chart.xls (HSSF)
                 .toResponse(response, "quarterly-report");
    }
}
```

**Bundling upload templates**

A sample template entry is what `exportSampleExcel()` produces, put in an archive — the header row plus one row of `@PxlColumn(exportSample = ...)` values. Unnamed, it falls back to `PxlSample{index}`, since a class carries no workbook name.

```java
@GetMapping("/forms/zip")
public ResponseEntity<Resource> downloadUploadForms() throws Exception {
    return pxlSpring.exportZip()
                    .sampleWorkbook(EmployeeForm.class, null, "employees")
                    .sampleWorkbook(DepartmentForm.class, null, "departments")
                    .toResponseEntity("upload-forms");   // -> upload-forms.zip
}
```

**Mixing CSV into the archive**

`csvSheet(...)` and `sampleCsvSheet(...)` put what `exportCsv()` / `exportSampleCsv()` produce into the same archive. One CSV file is one sheet, so each call adds one entry, and the sheet name is what an unnamed entry is called. The charset, delimiter and byte order mark come from that entry's option.

```java
@GetMapping("/employees/bundle")
public void downloadBundle(HttpServletResponse response) throws Exception {
    List<Employee> employees = ...;

    pxlSpring.exportZip()
             .workbook(report)                                        // report.xlsx
             .csvSheet(Employee.class, employees, "Employees")        // Employees.csv
             .sampleCsvSheet(Employee.class, "Upload form")           // Upload form.csv
             .toResponse(response, "employee-bundle");
}
```

**Writing the zip to a file or a stream**

```java
public void writeQuarterArchive(File zipFile) throws Exception {
    Company january = ...;
    Company february = ...;

    pxlSpring.exportZip()
             .workbook(january)
             .workbook(february)
             .toFile(zipFile);   // a failed export leaves bytes that cannot be opened as an archive
}
```

### `PxlExcelImporter`

**A single sheet uploaded**

The first matching candidate sheet name is read, and no cast is needed.

```java
@PostMapping("/employees/import")
public int importEmployees(@RequestParam MultipartFile file) throws Exception {
    List<Employee> employees = pxlSpring.importExcel()
                                        .sheet(Employee.class, "Employees", "Staff")
                                        .fromMultipartFile(file);

    return employees.size();
}
```

**A whole Excel file uploaded into a workbook object**

Omit `workbookName` and it is derived from the file name.

```java
@PostMapping("/company/import")
public int importCompany(@RequestParam MultipartFile file) throws Exception {
    Company company = pxlSpring.importExcel()
                               .workbook(Company.class)
                               .fromMultipartFile(file);

    return company.getEmployees().size() + company.getDepartments().size();
}
```

**Choosing the sheet's collection type**

`Set.class` binds raw, so the result arrives raw too.

```java
@PostMapping("/employees/import-unique")
public int importUniqueEmployees(@RequestParam MultipartFile file) throws Exception {
    @SuppressWarnings("unchecked")
    final Set<Employee> unique = pxlSpring.importExcel()
                                          .sheet(Employee.class, Set.class, "Employees")
                                          .fromMultipartFile(file);

    return unique.size();
}
```

**A password-protected Excel file uploaded**

```java
@PostMapping("/company/import-locked")
public int importLockedCompany(@RequestParam MultipartFile file) throws Exception {
    Company company = pxlSpring.importExcel()
                               .override(PxlImportWorkbookOption.builder().importPassword("secret").build())
                               .workbook(Company.class)
                               .fromMultipartFile(file);

    return company.getEmployees().size() + company.getDepartments().size();
}
```

**Multiple sheets uploaded**

On import, `sheet(...)` cannot be chained consecutively, so start a fresh chain per sheet. Hand the same file over again — a fresh `InputStream` is opened per call.

```java
@PostMapping("/company/import-sheets")
public int importCompanySheets(@RequestParam MultipartFile file) throws Exception {
    List<Employee> employees = pxlSpring.importExcel()
                                        .sheet(Employee.class, "Employees")
                                        .fromMultipartFile(file);

    List<Department> departments = pxlSpring.importExcel()
                                            .sheet(Department.class, "Departments")
                                            .fromMultipartFile(file);

    return employees.size() + departments.size();
}
```

**An Excel file that is not an upload**

`fromResource(...)` takes any Spring `Resource` — a file on disk, a classpath entry, anything else behind that abstraction — so batch jobs, seed loaders and tests need no `MultipartFile`. The resource must report a file name: it carries the extension that is validated, and the workbook-name fallback.

```java
@Scheduled(cron = "0 0 3 * * *")
public void importNightlyFeed() throws Exception {
    Company company = pxlSpring.importExcel()
                               .workbook(Company.class)
                               .fromResource(new FileSystemResource("/var/feed/company.xlsx"));

    save(company);
}
```

### `PxlCsvImporter`

**A single sheet uploaded**

```java
@PostMapping("/employees/import-csv")
public int importEmployeesCsv(@RequestParam MultipartFile csvFile) throws Exception {
    List<Employee> employees = pxlSpring.importCsv()
                                        .sheet(Employee.class)
                                        .fromMultipartFile(csvFile);

    return employees.size();
}
```

**Several CSVs uploaded into a workbook object**

One CSV file is one sheet, and the file name matches `@PxlSheet(name = ...)`. The CSV `sheet(...)` form takes exactly one file, so several sheets means this workbook form.

```java
@PostMapping("/company/import-csv")
public int importCompanyCsv(@RequestParam List<MultipartFile> csvFiles) throws Exception {
    Company company = pxlSpring.importCsv()
                               .workbook(Company.class)
                               .fromMultipartFiles(csvFiles);

    return company.getEmployees().size() + company.getDepartments().size();
}
```

**Setting options such as the delimiter**

```java
@PostMapping("/employees/import-csv-semicolon")
public int importSemicolonCsv(@RequestParam MultipartFile csvFile) throws Exception {
    List<Employee> employees = pxlSpring.importCsv()
                                        .override(PxlImportWorkbookOption.builder().importCsvDelimiter(';').build())
                                        .sheet(Employee.class)
                                        .fromMultipartFile(csvFile);

    return employees.size();
}
```

**CSV files that are not uploads**

`fromResource(...)` / `fromResources(...)` are the same pair for any Spring `Resource`. A resource's file name still names its sheet, so it has to report one.

```java
@EventListener(ApplicationReadyEvent.class)
public void loadSeedData() throws Exception {
    Company company = pxlSpring.importCsv()
                               .workbook(Company.class)
                               .fromResources(Arrays.asList(
                                       new ClassPathResource("seed/Employees.csv"),
                                       new ClassPathResource("seed/Departments.csv")));

    save(company);
}
```

---

## API Reference

### `PxlSpring`

The single bean to inject. Each method hands back the builder for that operation.

```java
PxlExcelExporter.Builder       exportExcel()
PxlSampleExcelExporter.Builder exportSampleExcel()
PxlCsvExporter.Builder         exportCsv()
PxlSampleCsvExporter.Builder   exportSampleCsv()
PxlZipExporter.Builder         exportZip()
PxlExcelImporter.Builder       importExcel()
PxlCsvImporter.Builder         importCsv()
```

### `PxlExcelExporter`

```java
// start
PxlExcelExporter.Builder exportExcel()

// configuration (exactly one of the three)
    workbook(Object workbookObject)                                         // @PxlWorkbook object
<T> sheet(Class<T> rowClass, Collection<T> rows, String sheetName)          // call repeatedly for multiple sheets
    poiWorkbook(Workbook workbook)                                          // a POI workbook as-is
    poiWorkbook(Workbook workbook, String password)                         // written encrypted

// options
    override(PxlExportWorkbookOption option)

// execution — the response destinations take the download file name as an argument (blank → workbook name → "Pxl")
void                     toStream(OutputStream outputStream)
void                     toFile(File excelFile)
void                     toResponse(HttpServletResponse response, String excelFilename)
void                     toResponseStreaming(HttpServletResponse response, String excelFilename)  // see "Size & Memory"
ResponseEntity<Resource> toResponseEntity(String excelFilename)
```

- Every `sheet(...)` call appends one more sheet — the call order is the sheet order, and each sheet may take a different row class. A duplicated sheet name throws `PxlDataException`.
- With `poiWorkbook(...)`, `override(...)` has no effect at all, and only the password is taken as a second argument.

### `PxlSampleExcelExporter`

Generates a sample Excel from a workbook class or a sheet class. It carries a single sample data row filled from each column's `@PxlColumn(exportSample = ...)` value, alongside the header row.

```java
// start
PxlSampleExcelExporter.Builder exportSampleExcel()

// configuration (exactly one of the two)
workbook(Class<?> workbookClass)                    // @PxlWorkbook class
sheet(Class<?> rowClass, String sheetName)          // call repeatedly for multiple sheets

// options
override(PxlExportWorkbookOption option)

// execution — the response destinations take the download file name as an argument (blank → "PxlSample")
void                     toStream(OutputStream outputStream)
void                     toFile(File excelFile)
void                     toResponse(HttpServletResponse response, String excelFilename)
void                     toResponseStreaming(HttpServletResponse response, String excelFilename)  // see "Size & Memory"
ResponseEntity<Resource> toResponseEntity(String excelFilename)
```

- For a multi-sheet sample Excel, call `sheet(...)` several times.

### `PxlCsvExporter`

Writes Java objects as CSV. One CSV file is one sheet, so there is no workbook form and the final methods write a single sheet.

```java
// start
PxlCsvExporter.Builder exportCsv()

// configuration (the only form; calling it twice makes the final method throw)
<T> sheet(Class<T> rowClass, Collection<T> rows, String sheetName)

// options
    override(PxlExportWorkbookOption option)

// execution — the response destinations take the download file name as an argument (blank → sheet name → "Pxl")
void                     toStream(OutputStream outputStream)
void                     toFile(File csvFile)
void                     toResponse(HttpServletResponse response, String csvFilename)
void                     toResponseStreaming(HttpServletResponse response, String csvFilename)  // see "Size & Memory"
ResponseEntity<Resource> toResponseEntity(String csvFilename)
```

- The charset, field delimiter and byte order mark come from `@PxlWorkbook`/`@PxlSheet` or the matching `exportCsv*` option fields, and default to UTF-8, `,` and no mark.
- Settings CSV cannot carry are ignored, except `exportPassword`, which is refused with `PxlArgumentException`.

### `PxlSampleCsvExporter`

Generates a sample CSV from a row class: a header record plus a single record filled from each column's `@PxlColumn(exportSample = ...)` value.

```java
// start
PxlSampleCsvExporter.Builder exportSampleCsv()

// configuration (the only form; calling it twice makes the final method throw)
sheet(Class<?> rowClass, String sheetName)

// options
override(PxlExportWorkbookOption option)

// execution — the response destinations take the download file name as an argument (blank → "PxlSample")
void                     toStream(OutputStream outputStream)
void                     toFile(File csvFile)
void                     toResponse(HttpServletResponse response, String csvFilename)
void                     toResponseStreaming(HttpServletResponse response, String csvFilename)  // see "Size & Memory"
ResponseEntity<Resource> toResponseEntity(String csvFilename)
```

- Unlike `PxlCsvExporter` the download name has no sheet-name fallback: a template describes a shape rather than a data set.

### `PxlZipExporter`

Turns several spreadsheets into one entry each and bundles them into a single zip.

```java
// start
PxlZipExporter.Builder exportZip()

// configuration (each call adds one entry)
workbook(Object workbookObject)                                                              // @PxlWorkbook object
workbook(Object workbookObject, PxlExportWorkbookOption option)
workbook(Object workbookObject, PxlExportWorkbookOption option, String excelFilename)
poiWorkbook(Workbook workbook)                                                               // a POI workbook as-is
poiWorkbook(Workbook workbook, String password)                                              // written encrypted
poiWorkbook(Workbook workbook, String password, String excelFilename)
sampleWorkbook(Class<?> workbookClass)                                                       // a sample template
sampleWorkbook(Class<?> workbookClass, PxlExportWorkbookOption option)
sampleWorkbook(Class<?> workbookClass, PxlExportWorkbookOption option, String excelFilename)
<T> csvSheet(Class<T> rowClass, Collection<T> rows, String sheetName)                        // rows as CSV
<T> csvSheet(Class<T> rowClass, Collection<T> rows, String sheetName, PxlExportWorkbookOption option)
<T> csvSheet(Class<T> rowClass, Collection<T> rows, String sheetName, PxlExportWorkbookOption option, String csvFilename)
sampleCsvSheet(Class<?> rowClass, String sheetName)                                          // a CSV template
sampleCsvSheet(Class<?> rowClass, String sheetName, PxlExportWorkbookOption option)
sampleCsvSheet(Class<?> rowClass, String sheetName, PxlExportWorkbookOption option, String csvFilename)

// execution — the response destinations take the archive file name as an argument (required)
void                     toStream(OutputStream outputStream)
void                     toFile(File zipFile)
void                     toResponse(HttpServletResponse response, String zipFilename)
void                     toResponseStreaming(HttpServletResponse response, String zipFilename)  // see "Size & Memory"
ResponseEntity<Resource> toResponseEntity(String zipFilename)
```

- An entry name is resolved as the name you gave → the name that entry's source carries → an index-suffixed default. A blank name is treated as absent, so resolution moves on to the next step; what each kind carries is below.
- Its extension is appended from the format the entry is written in: the per-entry option's export engine, else the one the workbook class declares. Name, bytes and deflate level all follow that one answer.
- With `poiWorkbook(...)` nothing is bound, so there is no per-entry option and no workbook name: the extension comes from the workbook's own type and an unnamed entry falls straight to `Pxl{index}`. Encryption keeps that extension, exactly as on `PxlExcelExporter`.
- `sampleWorkbook(...)` takes an option like `workbook(...)`, but is given a class rather than an instance, so there is no workbook name to read: an unnamed entry falls straight to `PxlSample{index}`. The index is what keeps unnamed entries apart — `PxlSampleExcelExporter` needs none, since it names a single download.
- The two CSV forms always write `.csv`, so an option's `exportExcelEngine` means nothing to them; what it does carry is the charset, delimiter and byte order mark. An unnamed entry takes the sheet name, which is required — so there is no index-suffixed default, and two entries under one sheet name collide. Name them.
- Entry names must come out distinct, ignoring case, and must not carry a path separator. Either is refused with `PxlArgumentException` before anything is written, so a failed export creates no file and touches no response. Name entries explicitly where two would otherwise resolve to the same one.
- The archive name is required.

### `PxlExcelImporter`

Converts an Excel source (`.xls` / `.xlsx`) — a multipart upload or a Spring `Resource` — into a workbook object or a sheet collection.

```java
// start
PxlExcelImporter.Builder importExcel()

// configuration (exactly one). Hands back a typed Source<R>
<W>                       Source<W>       workbook(Class<W> workbookClass)
<T>                       Source<List<T>> sheet(Class<T> rowClass, String... candidateSheetNames)
<T>                       Source<List<T>> sheet(Class<T> rowClass, List<String> candidateSheetNames)
<C extends Collection<?>> Source<C>       sheet(Class<?> rowClass, Class<C> collectionClass, String... candidateSheetNames)
<C extends Collection<?>> Source<C>       sheet(Class<?> rowClass, Class<C> collectionClass, List<String> candidateSheetNames)

// options (before or after the configuration; the value set last wins)
Builder / Source<R> workbookName(String workbookName)
Builder / Source<R> override(PxlImportWorkbookOption option)

// execution
R fromMultipartFile(MultipartFile excelFile)  // an upload
R fromResource(Resource excelFile)            // a file, a classpath entry, any Resource
```

- For the forms that take a collection type, pass `List.class` / `Set.class` and so on. The forms that take only a row class are fixed to `List`.
- A `Resource` must report a file name — it carries the extension that is validated. One that does not, such as a bare `ByteArrayResource`, is rejected with `HttpMediaTypeNotSupportedException` just like an unsupported extension.

### `PxlCsvImporter`

Converts a CSV source (`.csv`) — a multipart upload or a Spring `Resource` — into a workbook object or a sheet collection.

```java
// start
PxlCsvImporter.Builder importCsv()

// configuration (exactly one). Hands back a typed Source<R>
<W>                       Source<W>       workbook(Class<W> workbookClass)
<T>                       Source<List<T>> sheet(Class<T> rowClass)
<C extends Collection<?>> Source<C>       sheet(Class<?> rowClass, Class<C> collectionClass)

// options (before or after the configuration; the value set last wins)
Builder / Source<R> workbookName(String workbookName)
Builder / Source<R> override(PxlImportWorkbookOption option)

// execution
R fromMultipartFile(MultipartFile csvFile)          // one upload
R fromMultipartFiles(List<MultipartFile> csvFiles)  // several uploads
R fromResource(Resource csvFile)                    // one resource
R fromResources(List<Resource> csvFiles)            // several resources
```

- The `workbook(...)` form spreads several sources across sheets; the `sheet(...)` forms accept exactly one. With a `sheet(...)` form, passing more than one file to `fromMultipartFiles(...)` / `fromResources(...)` raises `PxlArgumentException`.
- A `Resource` must report a file name here too, and it does double duty: it carries the validated extension, and it names the sheet.

---

## Notes

- **Mapping class requirements**  
  A no-arg constructor is required, and the name in `@PxlColumn(name=...)` must match the actual header cell text.
- **Do not reuse a builder**  
  No builder is thread-safe, and none is meant to be reused. Use one builder for one execution and call the start method (`exportExcel()` and friends) afresh for the next one. What a reused builder does is not guaranteed and may change between versions.

---

## Size & Memory

Everything below is about heap. Both directions hold a whole workbook in memory by default, which is fine for the ordinary case and is what runs out first on a large one.

### Export: the workbook model

The default `XSSF` engine builds the entire sheet as a POI object graph before a single byte is written, and that graph is typically **far larger than the file it produces**. The `SXSSF` engine writes the same `.xlsx` while keeping only a sliding window of rows in memory and spilling the rest to temp files.

```java
// per workbook class
@PxlWorkbook(exportExcelEngine = PxlExcelEngine.SXSSF, exportSXSSFRowAccessWindowSize = 100)
public class Company { ... }

// or per call
pxlSpring.exportExcel()
        .sheet(Employee.class, employees, "Employees")
        .override(PxlExportWorkbookOption.builder().exportExcelEngine(PxlExcelEngine.SXSSF).build())
        .toResponse(response, null);
```

- `exportSXSSFRowAccessWindowSize` is how many rows stay in memory (POI's default window if unset).
- `SXSSF` produces `.xlsx` only — it has no effect on the `HSSF` engine (`.xls`).
- Columns using automatic width have to be tracked, and a tracked column stays in memory. Many auto-width columns eat into the saving.
- CSV has no engine, but it has an equivalent of its own. A CSV export renders its whole output before the destination is opened — that is what keeps a codec, validation or limit failure from leaving a file behind — holding the first 4 MiB (`PxlConstants.EXPORT_MEMORY_THRESHOLD_OF_CSV`) in memory and continuing into a temporary file under `java.io.tmpdir` past that, deleted before the call returns whether it succeeded or failed. So the heap a CSV export needs does not grow with its output. What a large one needs instead is free disk space, and that temporary file is written unencrypted — worth knowing, since a CSV export refuses `exportPassword` rather than encrypting.

### Export: the destination

Terminals differ in how much of the finished output they hold:

| Final method                    | Output held in memory | Note |
|---------------------------------|---|---|
| `toStream(...)` / `toFile(...)` | none | written straight through — cheapest |
| `toResponse(...)`               | one copy | buffered so a generation failure cannot emit a truncated download |
| `toResponseEntity(...)`         | one copy | the `Resource` body reads that same buffer rather than copying it into an array |

The buffering is deliberate: the response is only touched once the bytes are complete, so a failure mid-generation leaves the response — including any CORS headers added upstream — untouched, instead of committing `200 OK` plus a corrupt body.

### Export: `toResponseStreaming(...)`

All five exporters carry a dedicated final method that skips that buffer and writes straight to the response:

```java
pxlSpring.exportExcel()
        .sheet(Employee.class, employees, "Employees")
        .override(PxlExportWorkbookOption.builder().exportExcelEngine(PxlExcelEngine.SXSSF).build())
        .toResponseStreaming(response, "employee-list");
```

Pair it with `SXSSF`. Together the two make an export cost roughly constant heap regardless of row count. For a zip it is worth the most on its own — one entry is generated and written at a time instead of the whole archive being held. On CSV it is worth more than its description suggests: the core's rendering is already capped at 4 MiB, so the download buffer this drops is the last thing that grew with the output — streaming is what makes a large CSV cost roughly constant heap as well.

**Know what you are giving up.** These are the reason `toResponse(...)` is the default final method:

- **A failure part-way through cannot be taken back.** The response is already committed with `200 OK` and the download headers, so the client receives a truncated file that looks like a successful download. Nothing can undo bytes already sent.
- **No `Content-Length`.** The size is not known before the first byte, so the response goes out chunked and clients show no download progress. The upside is that an aborted chunked transfer at least reads as a failed download rather than a complete one.

### Import: the upload

The uploaded workbook is parsed by POI in full unless streaming is switched on, which reads the sheet in a sliding window instead:

```java
@PxlWorkbook(importUsingStreamReader = true, importStreamReaderRowCacheSize = 100)
public class Company { ... }
```

- Streaming reads `.xlsx` only. An `.xls` upload falls back to a full parse regardless of the setting.
- Cap what can arrive in the first place — the servlet container's own limits apply before any of this code runs:

```properties
spring.servlet.multipart.max-file-size=20MB
spring.servlet.multipart.max-request-size=100MB
```

---

## Performance Logging (Optional)

The execution methods of the seven components carry AOP-based performance logging, disabled by default.
Enable it and tune the threshold with the settings below.

```properties
pxl.performance.logging.enabled=true
pxl.performance.logging.low-performance-in-ms=5000
```

When enabled it logs method entry/exit and the elapsed time (ms), flagging `LowPerformance` past the threshold.

The switch behaves the same in a Boot application and in a plain Spring one.

---

## FAQ

**How do I return an Excel file as a download from a Spring controller?**
Inject `PxlSpring` and finish the chain on a response destination:
`pxlSpring.exportExcel().sheet(Employee.class, employees, "Employees").toResponseEntity("report")` hands
back a `ResponseEntity<Resource>` with the download headers already set — see [API Usage](#api-usage).

**How do I read an uploaded Excel file into a list of Java objects?**
`pxlSpring.importExcel().sheet(Employee.class, "Employees").fromMultipartFile(file)` returns a
`List<Employee>` with every cell already converted to the field's type. The upload's extension is validated
first, and an `.xls` is read as readily as an `.xlsx` — see [`PxlExcelImporter`](#pxlexcelimporter).

**`HttpServletResponse` or `ResponseEntity` — which one should I use?**
Either; both hold the same single copy of the finished output. Use `toResponse(...)` when the handler
returns `void` and `toResponseEntity(...)` when it returns a body. Reach for `toResponseStreaming(...)`
only for large exports, and read what it gives up first — see [Size & Memory](#size--memory).

**How do I export a large file without running out of memory?**
Combine `toResponseStreaming(...)` with the `SXSSF` engine for Excel; a CSV export already caps its
rendering at 4 MiB and spills the rest to a temporary file. On the import side,
`@PxlWorkbook(importUsingStreamReader = true)` reads an `.xlsx` in a sliding window.

**Does a non-ASCII download file name — Korean, for example — survive?**
Yes. `Content-Disposition` is written with an RFC 5987 `filename*` alongside an ASCII `filename` fallback,
so a client that understands the former gets the original name and one that does not gets a safe
substitute of the same length and extension. The name is used exactly as given, so normalize it yourself
if you need NFC.

**Does it handle CSV too?**
Yes — `exportCsv()` / `importCsv()`, with the same DTOs and annotations as Excel. One CSV file is one
sheet, so several uploaded CSVs can be read into a single workbook object, one file per `@PxlSheet` field.

**How do I let the user download several Excel files at once?**
`exportZip()` takes one configuration call per file - `workbook(...)`, `csvSheet(...)` and the rest - and
bundles them into a single zip download; see [`PxlZipExporter`](#pxlzipexporter).

**Can I read a spreadsheet outside a web request — in a batch job or a test?**
Yes. `fromResource(...)` / `fromResources(...)` take any Spring `Resource`, so no `MultipartFile` and no
HTTP is involved. The resource has to report a file name, since that is what carries the extension.

**Does it work in plain Spring MVC, without Spring Boot?**
Yes, and the performance-logging switch behaves identically. No Boot autoconfiguration is used anywhere;
you declare a `MethodValidationPostProcessor`, `@EnableAspectJAutoProxy` and a `multipartResolver`
yourself — see [Runtime Dependencies](#runtime-dependencies).

**Nothing is being injected — what is missing?**
Component scanning. Nothing registers itself, so `io.github.hclimkr.pxl.spring` has to be in your
`@ComponentScan`; sub-packages come along with it — see [Injecting `PxlSpring`](#injecting-pxlspring).

**Which artifact do I need, `pxl-spring-javax` or `pxl-spring-jakarta`?**
`pxl-spring-javax` for Java 8+ on Spring 5.x / Boot 2.x, `pxl-spring-jakarta` for Java 17+ on Spring 6.x /
Boot 3.x. They are the same library — add exactly one, and the matching `pxl` core comes in with it.

---

## Build & Contributing

The source lives only in `pxl-spring-javax`, and `pxl-spring-jakarta` is generated by string substitution at build time.  
This repository accepts issue reports and suggestions only — see [CONTRIBUTING.md](CONTRIBUTING.md).

---

## License

This project is distributed under the [Apache License 2.0](LICENSE).

```
Copyright 2026 hclim

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

    https://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
```
