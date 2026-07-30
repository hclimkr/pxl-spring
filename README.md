**English** · [한국어](README_ko.md)

PXL Spring
=============================

[![Build](https://github.com/hclimkr/pxl-spring/actions/workflows/build.yml/badge.svg?branch=main)](https://github.com/hclimkr/pxl-spring/actions/workflows/build.yml)
[![Maven Central](https://img.shields.io/maven-central/v/io.github.hclimkr/pxl-spring-javax?label=Maven%20Central)](https://central.sonatype.com/artifact/io.github.hclimkr/pxl-spring-javax)
[![Javadoc](https://javadoc.io/badge2/io.github.hclimkr/pxl-spring-javax/javadoc.svg)](https://javadoc.io/doc/io.github.hclimkr/pxl-spring-javax)
[![Java](https://img.shields.io/badge/Java-8%2B%20%2F%2017%2B-orange.svg)](#setup)
[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](LICENSE)

PXL Spring provides **multipart uploads and download responses for spreadsheet-object binding in Spring**, built on [PXL](https://github.com/hclimkr/pxl).
It leaves the binding to PXL and supports Java 8 and later.

- Import: multipart uploads (XLSX · XLS · CSV) → Java objects
- Export: Java objects → Excel · sample Excel · ZIP download

For PXL behavior — annotation attributes, supported types, the full set of options — refer to the [PXL documentation](https://github.com/hclimkr/pxl/blob/main/docs/reference.md).

## Table of Contents

1. [Setup](#setup)
2. [Runtime Dependencies](#runtime-dependencies)
3. [Injecting `PxlSpring`](#injecting-pxlspring)
4. [Defining DTO Classes](#defining-dto-classes)
5. [Usage at a Glance](#usage-at-a-glance)
6. [API Usage](#api-usage)
7. [API Reference](#api-reference)
8. [Notes](#notes)
9. [Size & Memory](#size--memory)
10. [Performance Logging (Optional)](#performance-logging-optional)
11. [Build & Contributing](#build--contributing)
12. [License](#license)

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
    <version>0.9.0</version>
</dependency>
```

```xml
<!-- jakarta variant (Java 17+) -->
<dependency>
    <groupId>io.github.hclimkr</groupId>
    <artifactId>pxl-spring-jakarta</artifactId>
    <version>0.9.0</version>
</dependency>
```

### Gradle

```groovy
// javax variant (Java 8+)
implementation 'io.github.hclimkr:pxl-spring-javax:0.9.0'
```

```groovy
// jakarta variant (Java 17+)
implementation 'io.github.hclimkr:pxl-spring-jakarta:0.9.0'
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
| `pxlSpring.exportExcelZip()`    | Several workbooks → one zip                                                  |
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
// pxlSpring.exportExcelZip()
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

Every operation is handled through a single method chain like the examples above. The start method indicates the direction of the operation (export/import) and the format (Excel/sample/ZIP/CSV), then you specify the target, and it is executed in the final method.

| Use case            | Method chain (start → configure → execute)                                                                                                                                                                 |
|---------------------|------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| Excel export        | `pxlSpring.exportExcel()`<br/>→ `.workbook(...) / .sheet(...) / .poiWorkbook(...)`<br/>→ `.toStream(OutputStream)` / `.toFile(File)` / `.toResponse(HttpServletResponse, String)` / `.toResponseStreaming(HttpServletResponse, String)` / `.toResponseEntity(String)` |
| Sample Excel export | `pxlSpring.exportSampleExcel()`<br/>→ `.workbook(...) / .sheet(...)`<br/>→ `.toStream(OutputStream)` / `.toFile(File)` / `.toResponse(HttpServletResponse, String)` / `.toResponseStreaming(HttpServletResponse, String)` / `.toResponseEntity(String)` |
| Excel ZIP export    | `pxlSpring.exportExcelZip()`<br/>→ `.workbook(...)`<br/>→ `.toStream(OutputStream)` / `.toFile(File)` / `.toResponse(HttpServletResponse, String)` / `.toResponseStreaming(HttpServletResponse, String)` / `.toResponseEntity(String)` |
| Excel import        | `pxlSpring.importExcel()`<br/>→ `.workbook(...) / .sheet(...)`<br/>→ `.fromMultipartFile(MultipartFile)`                                                                                                   |
| CSV import          | `pxlSpring.importCsv()`<br/>→ `.workbook(...) / .sheet(...)`<br/>→ `.fromMultipartFile(MultipartFile)` / `.fromMultipartFiles(List<MultipartFile>)`                                                          |

- For export, the configuration steps `.workbook(...)`, `.sheet(...)` and `.poiWorkbook(...)` are mutually exclusive — specifying more than one in a chain throws `PxlArgumentException` (as does omitting all of them).
- For export, calling `.sheet(...)` multiple times creates multiple sheets. `exportSampleExcel()` works the same way.
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

Set them with `override(...)`. Here the download comes out as `.xls` instead of `.xlsx`.

```java
@GetMapping("/employees/excel-xls")
public void downloadEmployeesAsXls(HttpServletResponse response) throws Exception {
    List<Employee> employees = ...;

    PxlExportWorkbookOption option = PxlExportWorkbookOption.builder()
                                                            .exportFileFormat(PxlFileFormat.HSSF)
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

### `PxlExcelZipExporter`

**A zip of several workbooks downloaded**

Omit them and they fall back to the workbook name, then to `Pxl{index}`.

```java
@GetMapping("/company/zip")
public ResponseEntity<Resource> downloadQuarter() throws Exception {
    Company january = ...;    // workbookName = "january"
    Company february = ...;   // workbookName = "february"

    return pxlSpring.exportExcelZip()
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
                                                                .exportFileFormat(PxlFileFormat.HSSF)
                                                                .build();

    pxlSpring.exportExcelZip()
             .workbook(january, null, "january-report")
             .workbook(february, hssfOption, "february-report")
             .toResponse(response, "quarterly-report");
}
```

**Writing the zip to a file or a stream**

```java
public void writeQuarterArchive(File zipFile) throws Exception {
    Company january = ...;
    Company february = ...;

    pxlSpring.exportExcelZip()
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

---

## API Reference

### `PxlSpring`

The single bean to inject. Each method hands back the builder for that operation.

```java
PxlExcelExporter.Builder       exportExcel()
PxlSampleExcelExporter.Builder exportSampleExcel()
PxlExcelZipExporter.Builder    exportExcelZip()
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

### `PxlExcelZipExporter`

Turns several workbook objects into one Excel entry each and bundles them into a single zip.

```java
// start
PxlExcelZipExporter.Builder exportExcelZip()

// configuration (each call adds one entry)
workbook(Object workbookObject)
workbook(Object workbookObject, PxlExportWorkbookOption option)
workbook(Object workbookObject, PxlExportWorkbookOption option, String excelFilename)

// execution — the response destinations take the archive file name as an argument (required)
void                     toStream(OutputStream outputStream)
void                     toFile(File zipFile)
void                     toResponse(HttpServletResponse response, String zipFilename)
void                     toResponseStreaming(HttpServletResponse response, String zipFilename)  // see "Size & Memory"
ResponseEntity<Resource> toResponseEntity(String zipFilename)
```

- An entry name is resolved as the name you gave → the workbook name → `Pxl{index}`. A blank name is treated as absent, so resolution moves on to the next step.
- The archive name is required.

### `PxlExcelImporter`

Converts a multipart Excel upload (`.xls` / `.xlsx`) into a workbook object or a sheet collection.

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
R fromMultipartFile(MultipartFile excelFile)
```

- For the forms that take a collection type, pass `List.class` / `Set.class` and so on. The forms that take only a row class are fixed to `List`.

### `PxlCsvImporter`

Converts a multipart CSV upload (`.csv`) into a workbook object or a sheet collection.

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
```

- The `workbook(...)` form spreads several uploads across sheets; the `sheet(...)` forms accept exactly one. With a `sheet(...)` form, passing more than one file to `fromMultipartFiles(...)` raises `PxlArgumentException`.

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

`XSSFWorkbook` (the default) builds the entire sheet as an object graph before a single byte is written, and that graph is typically **far larger than the file it produces**. `SXSSF` writes the same `.xlsx` while keeping only a sliding window of rows in memory and spilling the rest to temp files.

```java
// per workbook class
@PxlWorkbook(exportFileFormat = PxlFileFormat.SXSSF, exportSXSSFRowAccessWindowSize = 100)
public class Company { ... }

// or per call
pxlSpring.exportExcel()
        .sheet(Employee.class, employees, "Employees")
        .override(PxlExportWorkbookOption.builder().exportFileFormat(PxlFileFormat.SXSSF).build())
        .toResponse(response, null);
```

- `exportSXSSFRowAccessWindowSize` is how many rows stay in memory (POI's default window if unset).
- `SXSSF` produces `.xlsx` only — it does not apply to `HSSF` (`.xls`).
- Columns using automatic width have to be tracked, and a tracked column stays in memory. Many auto-width columns eat into the saving.

### Export: the destination

Terminals differ in how much of the finished output they hold:

| Final method                    | Output held in memory | Note |
|---------------------------------|---|---|
| `toStream(...)` / `toFile(...)` | none | written straight through — cheapest |
| `toResponse(...)`               | one copy | buffered so a generation failure cannot emit a truncated download |
| `toResponseEntity(...)`         | **two copies** | the buffer is copied into an exactly-sized array before the `Resource` body wraps it |

For a large download prefer `toResponse(...)` over `toResponseEntity(...)`. `PxlExcelZipExporter` buffers the **whole archive** on both response destinations, so the difference grows with the number of entries.

The buffering is deliberate: the response is only touched once the bytes are complete, so a failure mid-generation leaves the response — including any CORS headers added upstream — untouched, instead of committing `200 OK` plus a corrupt body.

### Export: `toResponseStreaming(...)`

All three exporters carry a dedicated final method that skips that buffer and writes straight to the response:

```java
pxlSpring.exportExcel()
        .sheet(Employee.class, employees, "Employees")
        .override(PxlExportWorkbookOption.builder().exportFileFormat(PxlFileFormat.SXSSF).build())
        .toResponseStreaming(response, "employee-list");
```

Pair it with `SXSSF`. Together the two make an export cost roughly constant heap regardless of row count. For a zip it is worth the most on its own — one entry is generated and written at a time instead of the whole archive being held.

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

The execution methods of the five components carry AOP-based performance logging, disabled by default.
Enable it and tune the threshold with the settings below.

```properties
pxl.performance.logging.enabled=true
pxl.performance.logging.low-performance-in-ms=5000
```

When enabled it logs method entry/exit and the elapsed time (ms), flagging `LowPerformance` past the threshold.

The switch behaves the same in a Boot application and in a plain Spring one.

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
