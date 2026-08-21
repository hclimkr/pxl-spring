[English](README.md) · **한국어**

PXL Spring - PXL 기반 스프링 엑셀 · CSV 업로드 · 다운로드 라이브러리
=============================

[![Build](https://github.com/hclimkr/pxl-spring/actions/workflows/build.yml/badge.svg?branch=main)](https://github.com/hclimkr/pxl-spring/actions/workflows/build.yml)
[![Maven Central](https://img.shields.io/maven-central/v/io.github.hclimkr/pxl-spring-javax?label=Maven%20Central)](https://central.sonatype.com/artifact/io.github.hclimkr/pxl-spring-javax)
[![Javadoc](https://javadoc.io/badge2/io.github.hclimkr/pxl-spring-javax/javadoc.svg)](https://javadoc.io/doc/io.github.hclimkr/pxl-spring-javax)
[![Java](https://img.shields.io/badge/Java-8%2B%20%2F%2017%2B-orange.svg)](#구성)
[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](LICENSE)

PXL Spring은 [PXL](https://github.com/hclimkr/pxl) 기반으로 **스프링에서 스프레드시트-객체 바인딩을 위한 멀티파트 업로드와 다운로드 응답**을 제공한다.
바인딩은 PXL에 맡기고, Java 8 이상을 지원한다.

`.xlsx` · `.xls` · `.csv` 멀티파트 업로드를 곧바로 `List<Employee>`로 읽고, `List<Employee>`를
컨트롤러에서 곧바로 엑셀 · CSV 다운로드로 내려준다 — `Row`/`Cell` 순회도, 손으로 조립하는
`Content-Disposition` 헤더도, 응답으로 복사되는 `byte[]`도 없다. 주입한 빈 하나가 스프링 부트에서도
순수 스프링 MVC에서도 모든 방향을 담당하고, 애노테이션이 붙은 DTO 하나가 양방향을 함께
구동한다.

- Import: Multipart 업로드(XLSX · XLS · CSV) → 자바 객체
- Export: 자바 객체 → 엑셀 · 샘플 엑셀 · CSV · 샘플 CSV · ZIP 다운로드

```java
@GetMapping("/employees/excel")
public ResponseEntity<Resource> download() throws Exception {
    return pxlSpring.exportExcel()
                    .sheet(Employee.class, employees, "Employees")
                    .toResponseEntity("employee-list");
}
```

애노테이션 속성 · 지원 타입 · 전체 옵션 등 PXL 동작은 [PXL 문서](https://github.com/hclimkr/pxl/blob/main/docs/reference_ko.md)를 참고한다.

> [!WARNING]
> **1.0 이전이라 공개 API가 아직 확정되지 않았다.** 유의적 버전(SemVer)에서 `0.y.z`는 호환성을 약속하지 않으며,
> PXL Spring도 그 여지를 쓰고 있다 — 마이너 릴리스에서도 공개 타입·메서드가 deprecation 단계 없이 이름이 바뀌거나
> 옮겨지거나 사라질 수 있고, 실제로 그런 변경이 여러 번 있었다. 바인딩을 맡고 있는 [PXL](https://github.com/hclimkr/pxl)의
> breaking 변경도 이 라이브러리를 거쳐 그대로 전달된다. 버전 범위 대신 정확한 버전을 고정하고, 올리기 전에
> [CHANGELOG](CHANGELOG.md)를 확인한다. 해당 변경은 모두 거기에 breaking으로 표시되어 있다.

## 목차

1. [주요 기능](#주요-기능)
2. [구성](#구성)
3. [런타임 의존성](#런타임-의존성)
4. [`PxlSpring` 주입](#pxlspring-주입)
5. [객체 DTO 정의](#객체-dto-정의)
6. [한눈에 보는 사용법](#한눈에-보는-사용법)
7. [API 사용](#api-사용)
8. [API 레퍼런스](#api-레퍼런스)
9. [유의 사항](#유의-사항)
10. [크기 & 메모리](#크기--메모리)
11. [성능 로깅 (선택)](#성능-로깅-선택)
12. [자주 묻는 질문](#자주-묻는-질문)
13. [빌드 & 기여](#빌드--기여)
14. [라이선스](#라이선스)

---

## 주요 기능

- **빈 하나로 모든 방향** — `PxlSpring`을 주입하면 각 작업이 거기서 시작하는 메서드 체인이 된다.
  `exportExcel()`, `exportSampleExcel()`, `exportZip()`, `exportCsv()`, `exportSampleCsv()`,
  `importExcel()`, `importCsv()`.
- **멀티파트 업로드를 곧바로 객체로** — `fromMultipartFile(...)` / `fromMultipartFiles(...)`가 업로드를
  `List<Employee>`나 워크북 객체 하나로 만들며, 파싱하기 전에 확장자를 먼저 확인한다.
- **업로드만이 아니다** — `fromResource(...)` / `fromResources(...)`는 스프링 `Resource`를 그대로 받으므로
  배치 잡 · 시드 로더 · 테스트도 `MultipartFile` 없이 같은 방식으로 스프레드시트를 읽는다.
- **Exporter마다 목적지 다섯** — `toStream(...)`, `toFile(...)`, `toResponse(...)`,
  `toResponseStreaming(...)`, `toResponseEntity(...)`. 마지막 호출만 바꾸면 같은 구성이 다른 목적지로
  나간다.
- **다운로드 헤더는 라이브러리가 조립한다** — `Content-Disposition`에 ASCII `filename`과 RFC 5987
  `filename*`을 함께 실으므로 비ASCII 다운로드 파일명이 그대로 살아남고, content type과 확장자는 실제로
  기록되는 형식을 따라간다.
- **대용량 다운로드에서도 일정한 Heap** — `toResponseStreaming(...)`이 다운로드 버퍼를 건너뛴다. `SXSSF`
  엔진이나 CSV export의 4 MiB 임시 파일 분할과 함께 쓰면 행 수가 얼마든 export가 쓰는 Heap이 거의
  일정해진다.
- **여러 스프레드시트를 zip 하나로** — `exportZip()`은 다른 익스포터가 만드는 것을 엑셀이든 CSV든
  엔트리로 받는다. 이미 압축된 `.xlsx`는 다시 압축하지 않으며, export가 실패하면 아카이브로 열리는
  파일을 남기지 않는다.
- **클래스만으로 샘플 양식** — `exportSampleExcel()` / `exportSampleCsv()`가 헤더 행과 예시 값이 채워진
  데이터 행 하나를 만들어 준다. 배포한 뒤 채워 받아 importer로 그대로 되읽을 수 있다.
- **경계에서의 검증** — 지원하지 않는 확장자는 파싱 전에 `HttpMediaTypeNotSupportedException`으로,
  `null` 목적지는 스프링 프록시 뒤에서 `ConstraintViolationException`으로 거절되고, 나머지는 PXL 자체의
  `PxlException`이 담당한다.
- **선택적 성능 로깅** — AOP 애스펙트가 모든 실행 시간을 재고 임계치를 넘으면 표시한다.
  `pxl.performance.logging.enabled=true`로 켜지 않으면 꺼져 있다.
- **소스 하나에서 나오는 두 변형** — Java 8+ / Spring 5.x · Boot 2.x용 `pxl-spring-javax`와
  Java 17+ / Spring 6.x · Boot 3.x용 `pxl-spring-jakarta`.

---

## 구성

환경에 맞는 변형 하나만 의존성에 추가한다.
- `pxl-spring-javax` (Java 8+, Spring 5.x / Boot 2.x, `javax.*`)
- `pxl-spring-jakarta` (Java 17+, Spring 6.x / Boot 3.x, `jakarta.*`)

### Maven

```xml
<!-- javax 변형 (Java 8+) -->
<dependency>
    <groupId>io.github.hclimkr</groupId>
    <artifactId>pxl-spring-javax</artifactId>
    <version>0.9.2</version>
</dependency>
```

```xml
<!-- jakarta 변형 (Java 17+) -->
<dependency>
    <groupId>io.github.hclimkr</groupId>
    <artifactId>pxl-spring-jakarta</artifactId>
    <version>0.9.2</version>
</dependency>
```

### Gradle

```groovy
// javax 변형 (Java 8+)
implementation 'io.github.hclimkr:pxl-spring-javax:0.9.2'
```

```groovy
// jakarta 변형 (Java 17+)
implementation 'io.github.hclimkr:pxl-spring-jakarta:0.9.2'
```

---

## 런타임 의존성

### 스프링 부트

대부분의 스프링 부트 애플리케이션은 아래 스타터만 있으면 된다.

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

### 순수 스프링

`spring-context`와 `spring-web`은 이미 갖고 있을 테니, 추가로 필요한 것은 아래 둘뿐이며 각각 그 기능을 쓸 때만 필요하다.

```xml
<!-- @Validated가 null 목적지를 ConstraintViolationException으로 거절하게 하려면 -->
<dependency>
    <groupId>org.hibernate.validator</groupId>
    <artifactId>hibernate-validator</artifactId>
</dependency>
<dependency>
    <groupId>org.glassfish</groupId>
    <artifactId>jakarta.el</artifactId>
</dependency>

<!-- 선택 기능인 성능 로깅을 쓰려면 -->
<dependency>
    <groupId>org.aspectj</groupId>
    <artifactId>aspectjweaver</artifactId>
</dependency>
```

서블릿 API는 컨테이너가 제공하고, 코어 `pxl` 라이브러리는 의존성으로 따라온다.

부트가 대신 해 주던 배선 세 가지는 직접 선언해야 한다. 셋 다 없어도 라이브러리는 동작하며, 각각이 켜 주는 기능만 빠진다.

- **빈 검증** `MethodValidationPostProcessor` 빈을 등록해야 `@Validated`가 발동한다. 없어도 `null` 목적지는 여전히 거절되지만 `ConstraintViolationException`이 아니라 `PxlNullPointerException`이 된다.
- **성능 로깅** 설정 클래스에 `@EnableAspectJAutoProxy`를 붙여야 Aspect가 실제로 위빙된다.
- **멀티파트 업로드** 순수 스프링 MVC 앱이라면 `multipartResolver` 빈을 선언해야 한다.

---

## `PxlSpring` 주입

`PxlSpring`이 진입점이다 — 모든 기능을 담은 빈이다.

| 시작 메서드                          | 역할                                                                   |
|---------------------------------|----------------------------------------------------------------------|
| `pxlSpring.exportExcel()`       | Java 객체 → 엑셀 (Stream/File/Response/ResponseStreaming/ResponseEntity) |
| `pxlSpring.exportSampleExcel()` | 클래스 → 샘플 데이터 한 줄이 든 엑셀                                               |
| `pxlSpring.exportZip()`         | 여러 스프레드시트(엑셀·CSV) → 하나의 zip                                   |
| `pxlSpring.exportCsv()`         | Java 객체 → CSV (목적지 다섯 개는 동일)                                         |
| `pxlSpring.exportSampleCsv()`   | 클래스 → 샘플 데이터 한 줄이 든 CSV                                              |
| `pxlSpring.importExcel()`       | 엑셀 파일 → Java 객체                                                      |
| `pxlSpring.importCsv()`         | CSV 파일 → Java 객체                                                     |

`io.github.hclimkr.pxl.spring` 패키지를 스캔 대상에 넣어야 `PxlSpring`과 성능 로깅 Aspect가 빈으로 등록된다. 하위 패키지도 함께 스캔되므로 이 한 줄이면 전부 포함된다.

```java
@SpringBootApplication
@ComponentScan(basePackages = {"com.example.myapp", "io.github.hclimkr.pxl.spring"})
public class MyApplication {
    public static void main(String[] args) {
        SpringApplication.run(MyApplication.class, args);
    }
}
```

순수 스프링 애플리케이션도 스캔 대상 패키지는 똑같다 — `@ComponentScan`을 아무 `@Configuration` 클래스에나 붙이면 된다.

그 다음 필요한 곳에 주입한다. 이 문서의 모든 예제는 아래 필드를 전제로 한다.

```java
@Autowired
private PxlSpring pxlSpring;

// pxlSpring.exportExcel()
// pxlSpring.exportSampleExcel()
// pxlSpring.exportZip()
// pxlSpring.exportCsv()
// pxlSpring.exportSampleCsv()
// pxlSpring.importExcel()
// pxlSpring.importCsv()
```

---

## 객체 DTO 정의

### 행 클래스

행 클래스는 `@PxlColumn`으로 각 필드를 헤더에 매핑한다.

```java
import io.github.hclimkr.pxl.annotation.PxlColumn;
import io.github.hclimkr.pxl.annotation.PxlRowIndex;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDate;

@Getter                     // (선택) 사용자 편의용 — PXL은 직접 바인딩하기 때문에 Getter가 필요하지는 않다.
@Setter                     // (선택) 사용자 편의용 — PXL은 직접 바인딩하기 때문에 Setter가 필요하지는 않다.
@NoArgsConstructor          // (필수) import 시에 무인자 생성자 필수
public class Employee {

    @PxlRowIndex            // (선택) 1-based 스프레드시트 행 번호. 타입: byte/short/int/long + 래퍼 클래스(Byte/Short/Integer/Long)
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

- `name`을 생략하면 필드명이 열 이름이 된다.
- `name`은 실제 헤더와 일치해야 바인딩된다(공백은 무시, 대소문자는 구분).
- `exportSample`은 [Export 샘플](#pxlsampleexcelexporter)에 들어갈 예시 값이다(일반 export에는 영향 없음).

`Grade`는 예제에서 쓰는 사용자 정의 enum이다.

```java
public enum Grade {
    A, B, C, F
}
```

다중 시트 예제에서 두 번째 시트로 쓰는 행 클래스도 같은 방식으로 정의한다.

```java
import io.github.hclimkr.pxl.annotation.PxlColumn;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter                     // (선택) 사용자 편의용 — PXL은 직접 바인딩하기 때문에 Getter가 필요하지는 않다.
@Setter                     // (선택) 사용자 편의용 — PXL은 직접 바인딩하기 때문에 Setter가 필요하지는 않다.
@NoArgsConstructor          // (필수) import 시에 무인자 생성자 필수
public class Department {

    @PxlColumn(name = "Code")
    private String code;

    @PxlColumn(name = "DepartmentName")
    private String departmentName;

    @PxlColumn(name = "Headcount")
    private int headcount;
}
```

### 워크북 클래스 (다중 시트를 한 객체로)

각 시트 필드는 `Collection` 타입이고 `@PxlSheet`로 바인딩한다.

```java
import io.github.hclimkr.pxl.annotation.PxlSheet;
import io.github.hclimkr.pxl.annotation.PxlWorkbookName;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.List;

@Getter                     // (선택) 사용자 편의용 — PXL은 직접 바인딩하기 때문에 Getter가 필요하지는 않다.
@Setter                     // (선택) 사용자 편의용 — PXL은 직접 바인딩하기 때문에 Setter가 필요하지는 않다.
@NoArgsConstructor          // (필수) import 시에 무인자 생성자 필수
public class Company {

    @PxlWorkbookName        // (선택) 워크북 이름을 담을 String 필드
    private String workbookName;

    @PxlSheet(name = "Employees")
    private List<Employee> employees;

    @PxlSheet(name = "Departments")
    private List<Department> departments;
}
```

---

## 한눈에 보는 사용법

### Export

```java
import io.github.hclimkr.pxl.spring.PxlSpring;

import org.springframework.core.io.Resource;
import org.springframework.http.ResponseEntity;
import java.util.Arrays;
import java.util.List;

// Export: Employees 시트 → 엑셀 다운로드 응답
@GetMapping("/employees/excel")
public ResponseEntity<Resource> download() throws Exception {
    // Employee 행 객체 준비
    Employee alice = new Employee();
    alice.setName("Alice");
    alice.setAge(30);

    Employee bob = new Employee();
    bob.setName("Bob");
    bob.setAge(42);

    // Employees 시트 객체 준비
    List<Employee> employees = Arrays.asList(alice, bob);

    return pxlSpring.exportExcel()
                    .sheet(Employee.class, employees, "Employees")
                    .toResponseEntity("직원목록");
}
```

### Import

```java
import io.github.hclimkr.pxl.spring.PxlSpring;

import org.springframework.web.multipart.MultipartFile;
import java.util.List;

// Import: Multipart 엑셀 업로드 → Employee 행 객체 목록
@PostMapping("/employees/import")
public int upload(@RequestParam MultipartFile file) throws Exception {
    List<Employee> employees = pxlSpring.importExcel()
                                        .sheet(Employee.class, "Employees")
                                        .fromMultipartFile(file);
    return employees.size();
}
```

모든 작업은 위 예제처럼 하나의 메서드 체인으로 처리한다. 시작 메서드가 작업의 방향(내보내기/가져오기)과 형식(엑셀/CSV/샘플/ZIP)을 나타내며, 이어서 대상을 지정한 뒤 마지막 메서드에서 실행된다.

| 용도             | 메서드 체인 (시작 → 구성 → 실행)                                                                                                                                                                                             |
|----------------|--------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| 엑셀 export      | `pxlSpring.exportExcel()`<br/>→ `.workbook(...) / .sheet(...) / .poiWorkbook(...)`<br/>→ `.toStream(OutputStream)` / `.toFile(File)` / `.toResponse(HttpServletResponse, String)` / `.toResponseStreaming(HttpServletResponse, String)` / `.toResponseEntity(String)` |
| 샘플 엑셀 export   | `pxlSpring.exportSampleExcel()`<br/>→ `.workbook(...) / .sheet(...)`<br/>→ `.toStream(OutputStream)` / `.toFile(File)` / `.toResponse(HttpServletResponse, String)` / `.toResponseStreaming(HttpServletResponse, String)` / `.toResponseEntity(String)`                                                                                                    |
| ZIP export       | `pxlSpring.exportZip()`<br/>→ `.workbook(...) / .poiWorkbook(...) / .sampleWorkbook(...) / .csvSheet(...) / .sampleCsvSheet(...)`<br/>→ `.toStream(OutputStream)` / `.toFile(File)` / `.toResponse(HttpServletResponse, String)` / `.toResponseStreaming(HttpServletResponse, String)` / `.toResponseEntity(String)`                                                                                                      |
| CSV export     | `pxlSpring.exportCsv()`<br/>→ `.sheet(...)`<br/>→ `.toStream(OutputStream)` / `.toFile(File)` / `.toResponse(HttpServletResponse, String)` / `.toResponseStreaming(HttpServletResponse, String)` / `.toResponseEntity(String)`                                                                                                              |
| 샘플 CSV export  | `pxlSpring.exportSampleCsv()`<br/>→ `.sheet(...)`<br/>→ `.toStream(OutputStream)` / `.toFile(File)` / `.toResponse(HttpServletResponse, String)` / `.toResponseStreaming(HttpServletResponse, String)` / `.toResponseEntity(String)`                                                                                                        |
| 엑셀 import      | `pxlSpring.importExcel()`<br/>→ `.workbook(...) / .sheet(...)`<br/>→ `.fromMultipartFile(MultipartFile)`                                                                                                   |
| CSV import     | `pxlSpring.importCsv()`<br/>→ `.workbook(...) / .sheet(...)`<br/>→ `.fromMultipartFile(MultipartFile)` / `.fromMultipartFiles(List<MultipartFile>)`                                                          |

- export에서 구성 단계의 `.workbook(...)`과 `.sheet(...)`, `.poiWorkbook(...)`은 서로 배타적이다 — 한 체인에서 둘 이상을 함께 지정하면 `PxlArgumentException`이 발생한다(셋 다 생략해도 같다).
- export는 `.sheet(...)`를 여러 번 호출해 여러 시트를 만든다. `exportSampleExcel()`도 동일하다.
- CSV export는 파일 하나가 시트 하나라 `.workbook(...)` 자체가 없고, `.sheet(...)`를 두 번 불러도 시트가 늘지 않고 마지막 메서드에서 `PxlArgumentException`이 발생한다. `exportSampleCsv()`도 동일하다.
- import는 `.sheet(...)`를 연달아 체인할 수 없다. 여러 시트를 읽는 방법은 두 가지다.
    - 워크북 형태로 한 번에: `@PxlWorkbook` 클래스를 `.workbook(...)`에 주면 `@PxlSheet` 필드별로 여러 시트가 한 번에 바인딩된다.  
    - 시트별로 나눠서: 시트마다 체인을 새로 시작해 각각 `.fromMultipartFile(...)`까지 실행한다.

체인 중간에 `override(...)`(코어 옵션), `workbookName(...)`(워크북명)을 선택적으로 끼워 넣을 수 있다. 순서는 자유이며, 같은 값을 두 번 지정하면 나중 값이 최종 사용된다.

---

## API 사용

아래 예제는 모두 `PxlSpring`을 `pxlSpring`이라는 이름으로 주입했다고 가정한다.

```java
@RestController
public class ExcelController {

    @Autowired
    private PxlSpring pxlSpring;

    // 아래 핸들러 메서드는 모두 이런 컨트롤러 안에 있다고 본다
}
```

### `PxlExcelExporter`

가장 흔한 사용은 컨트롤러에서 바로 다운로드시키는 것이다.

**단일 시트를 `ResponseEntity`로 다운로드**

```java
@GetMapping("/employees/excel")
public ResponseEntity<Resource> downloadEmployees() throws Exception {
    List<Employee> employees = ...;

    return pxlSpring.exportExcel()
                    .sheet(Employee.class, employees, "Employees")
                    .toResponseEntity("보고서");
}
```

**워크북 객체를 `HttpServletResponse`로 다운로드**

대용량에 권장한다.

```java
@GetMapping("/company/excel")
public void downloadCompany(HttpServletResponse response) throws Exception {
    Company company = new Company();
    company.setWorkbookName("회사보고서");
    company.setEmployees(...);
    company.setDepartments(...);

    // 파일명을 비우면 워크북명 → "Pxl" 순으로 대체된다
    pxlSpring.exportExcel()
             .workbook(company)
             .toResponse(response, null);
}
```

**다중 시트 다운로드**

`sheet(...)`를 여러 번 호출한다 — 호출 순서가 곧 시트 순서이고, 시트마다 행 클래스를 달리 줄 수 있다.

```java
@GetMapping("/company/sheets")
public void downloadSheets(HttpServletResponse response) throws Exception {
    List<Employee> employees = ...;
    List<Department> departments = ...;

    pxlSpring.exportExcel()
             .sheet(Employee.class, employees, "Employees")
             .sheet(Department.class, departments, "Departments")
             .toResponse(response, "회사보고서");
}
```

**반복문으로 구성하는 다중 시트 다운로드**

index로 맞춘 평행 리스트에서 만든다. 원소 타입이 와일드카드면 `rows`와 `rowClass`의 타입 파라미터를 맞추기 위한 캐스트가 필요하다.

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

    builder.toResponse(response, "회사보고서");
}
```

**raw POI 워크북 다운로드**

그대로 기록되며, 다운로드 헤더는 그 워크북의 타입을 따른다.

```java
@GetMapping("/employees/excel-poi")
public ResponseEntity<Resource> downloadPoiWorkbook() throws Exception {
    Workbook poiWorkbook = ...;

    return pxlSpring.exportExcel()
                    .poiWorkbook(poiWorkbook, "secret")   // 암호는 선택이다
                    .toResponseEntity("보고서");
}
```

**export 옵션 override**

`override(...)`로 설정한다. 여기서는 `HSSF` 엔진을 지정해 `.xlsx` 대신 `.xls`로 다운로드하는 예제이다.

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
             .toResponse(response, "보고서");   // -> 보고서.xls
}
```

**파일이나 스트림에 저장**

배치 잡이나 스케줄러에서는 같은 체인의 마지막 실행 메서드만 달라진다.

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
             .toStream(baos);   // 스트림은 닫지 않는다 — 소유권은 호출 측에 있다

    return baos.toByteArray();
}
```

마지막 실행 메서드만 바꾸면 같은 설정을 다섯 목적지 어디로든 보낼 수 있다: `toStream(OutputStream)`, `toFile(File)`, `toResponse(HttpServletResponse, String)`, `toResponseStreaming(HttpServletResponse, String)`, `toResponseEntity(String)`. 전체 시그니처는 [API 레퍼런스 — `PxlExcelExporter`](#pxlexcelexporter-1)를 참고한다.

### `PxlSampleExcelExporter`

헤더와 함께 샘플 데이터 한 줄이 들어간 샘플 엑셀을 내려줄 때 쓴다. `@PxlColumn(exportSample=...)` 값이 샘플 행으로 채워진다.

**단일 시트로 다운로드**

```java
@GetMapping("/employees/sample")
public ResponseEntity<Resource> employeeSample() throws Exception {
    // 파일명을 비우면 기본값 "PxlSample"
    return pxlSpring.exportSampleExcel()
                    .sheet(Employee.class, "Employees")
                    .toResponseEntity(null);
}
```

**워크북 클래스로 다중 시트 다운로드**

```java
@GetMapping("/company/sample")
public void companySample(HttpServletResponse response) throws Exception {
    pxlSpring.exportSampleExcel()
             .workbook(Company.class)
             .toResponse(response, "샘플");
}
```

**다중 시트 다운로드**

```java
@GetMapping("/company/sample-sheets")
public ResponseEntity<Resource> companySampleSheets() throws Exception {
    return pxlSpring.exportSampleExcel()
                    .sheet(Employee.class, "Employees")
                    .sheet(Department.class, "Departments")
                    .toResponseEntity("샘플");
}
```

**파일이나 스트림에 저장**

```java
public void writeSampleTemplate(File file) throws Exception {
    pxlSpring.exportSampleExcel()
             .workbook(Company.class)
             .toFile(file);
}
```

### `PxlZipExporter`

**여러 워크북으로 구성된 압축파일 다운로드**

생략하면 워크북명 → `Pxl{index}` 순으로 채워진다.

```java
@GetMapping("/company/zip")
public ResponseEntity<Resource> downloadQuarter() throws Exception {
    Company january = ...;    // workbookName = "january"
    Company february = ...;   // workbookName = "february"

    return pxlSpring.exportZip()
                    .workbook(january)
                    .workbook(february)
                    .toResponseEntity("archive");   // -> archive.zip  (엔트리: january.xlsx, february.xlsx)
}
```

**워크북별 옵션과 이름 설정**

```java
@GetMapping("/company/zip-named")
public void downloadQuarterNamed(HttpServletResponse response) throws Exception {
    Company january = ...;
    Company february = ...;

    PxlExportWorkbookOption hssfOption = PxlExportWorkbookOption.builder()
                                                                .exportExcelEngine(PxlExcelEngine.HSSF)
                                                                .build();

    pxlSpring.exportZip()
             .workbook(january, null, "1월보고서")
             .workbook(february, hssfOption, "2월보고서")
             .toResponse(response, "분기보고서");
}
```

엔트리 확장자는 그 엔트리가 실제로 기록되는 엔진을 따른다 — 엔트리별 옵션이 먼저이고, 없으면 워크북 클래스가 선언한 엔진이다. 그래서 `2월보고서`는 `2월보고서.xls`로 들어가고 이름과 바이트가 어긋나지 않는다. deflate 레벨도 같은 답에서 고르므로, `.xls` 엔트리는 이미 deflate된 `.xlsx`처럼 그냥 저장되지 않고 제대로 압축된다.

**직접 만들어 둔 워크북 묶기**

raw POI `Workbook`은 `exportExcel().poiWorkbook(...)`이 단독으로 기록할 때와 똑같이 있는 그대로 아카이브에 들어간다. 바인딩을 거치지 않으므로 엔트리별 옵션이 없고(암호만 받는다) 이름은 워크북 자신의 타입을 따라 붙는다. 워크북명도 없으므로 이름을 주지 않은 이 종류의 엔트리는 곧바로 `Pxl{index}`가 된다.

```java
@GetMapping("/company/zip-raw")
public void downloadWithRawWorkbook(HttpServletResponse response) throws Exception {
    Company january = ...;

    try (Workbook chart = buildChartWorkbook()) {
        pxlSpring.exportZip()
                 .workbook(january)
                 .poiWorkbook(chart, null, "차트")   // -> 차트.xlsx (XSSF), 차트.xls (HSSF)
                 .toResponse(response, "분기보고서");
    }
}
```

**업로드 양식 묶기**

샘플 템플릿 엔트리는 `exportSampleExcel()`이 만드는 것을 그대로 아카이브에 담는다 — 헤더 행과 `@PxlColumn(exportSample = ...)` 값으로 채운 샘플 데이터 행 하나다. 이름을 주지 않으면 `PxlSample{index}`가 되는데, 클래스에는 워크북명이 없기 때문이다.

```java
@GetMapping("/forms/zip")
public ResponseEntity<Resource> downloadUploadForms() throws Exception {
    return pxlSpring.exportZip()
                    .sampleWorkbook(EmployeeForm.class, null, "직원양식")
                    .sampleWorkbook(DepartmentForm.class, null, "부서양식")
                    .toResponseEntity("업로드양식");   // -> 업로드양식.zip
}
```

**CSV를 함께 묶기**

`csvSheet(...)`와 `sampleCsvSheet(...)`는 `exportCsv()` / `exportSampleCsv()`가 만드는 것을 같은 아카이브에 담는다. CSV 파일 하나가 시트 하나이므로 호출마다 엔트리 하나가 늘고, 이름을 주지 않으면 시트명이 엔트리명이 된다. 문자셋·구분자·BOM은 그 엔트리의 옵션에서 온다.

```java
@GetMapping("/employees/bundle")
public void downloadBundle(HttpServletResponse response) throws Exception {
    List<Employee> employees = ...;

    pxlSpring.exportZip()
             .workbook(report)                                        // report.xlsx
             .csvSheet(Employee.class, employees, "직원명단")            // 직원명단.csv
             .sampleCsvSheet(Employee.class, "업로드양식")               // 업로드양식.csv
             .toResponse(response, "직원자료묶음");
}
```

**압축파일을 파일이나 스트림에 저장**

```java
public void writeQuarterArchive(File zipFile) throws Exception {
    Company january = ...;
    Company february = ...;

    pxlSpring.exportZip()
             .workbook(january)
             .workbook(february)
             .toFile(zipFile);   // 실패한 export는 아카이브로 열 수 없는 바이트만 남긴다
}
```

### `PxlCsvExporter`

같은 DTO, 같은 옵션을 엑셀 대신 CSV로 쓴다. CSV 파일 하나가 시트 하나라 `sheet(...)`만 있다.

**시트 하나를 `ResponseEntity`로 다운로드**

```java
@GetMapping("/employees/csv")
public ResponseEntity<Resource> downloadEmployeesCsv() throws Exception {
    List<Employee> employees = ...;

    // 이름을 비우면 시트명, 그 다음 "Pxl" 순으로 폴백한다
    return pxlSpring.exportCsv()
                    .sheet(Employee.class, employees, "Employees")
                    .toResponseEntity(null);   // -> Employees.csv
}
```

**문자셋 · 구분자 · BOM 지정**

클래스에 `@PxlWorkbook`/`@PxlSheet`로 선언하거나 호출마다 `override(...)`로 준다. BOM은 UTF-8 · UTF-16LE · UTF-16BE에만 기록된다.

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

**파일이나 스트림에 저장**

```java
public void writeMonthlyCsv(File file) throws Exception {
    List<Employee> employees = ...;

    pxlSpring.exportCsv()
             .sheet(Employee.class, employees, "Employees")
             .toFile(file);
}
```

스타일러 · 컬럼 너비 · 틀 고정 · 엑셀 엔진처럼 CSV가 담을 수 없는 설정은 무시된다. 예외는 `exportPassword` 하나로, 무시가 아니라 `PxlArgumentException`으로 거절된다 — CSV는 암호화할 수 없고 평문으로 쓰면 유출이기 때문이다.

### `PxlSampleCsvExporter`

`PxlSampleExcelExporter`의 CSV 판이다. 헤더 레코드 하나와 `@PxlColumn(exportSample=...)`로 채운 샘플 레코드 하나를 쓰며, 받은 쪽이 채워서 `importCsv()`로 되돌려 보낼 수 있다.

```java
@GetMapping("/employees/csv-sample")
public ResponseEntity<Resource> employeeCsvSample() throws Exception {
    // 이름을 비우면 "PxlSample"이 된다
    return pxlSpring.exportSampleCsv()
                    .sheet(Employee.class, "Employees")
                    .toResponseEntity(null);
}
```

### `PxlExcelImporter`

**단일 시트 업로드**

후보 시트명 중 처음 매칭되는 시트를 읽으며, 캐스팅이 필요 없다.

```java
@PostMapping("/employees/import")
public int importEmployees(@RequestParam MultipartFile file) throws Exception {
    List<Employee> employees = pxlSpring.importExcel()
                                        .sheet(Employee.class, "Employees", "직원")
                                        .fromMultipartFile(file);

    return employees.size();
}
```

**엑셀 파일 전체를 워크북 객체로 업로드**

`workbookName`을 생략하면 파일명에서 유추한다.

```java
@PostMapping("/company/import")
public int importCompany(@RequestParam MultipartFile file) throws Exception {
    Company company = pxlSpring.importExcel()
                               .workbook(Company.class)
                               .fromMultipartFile(file);

    return company.getEmployees().size() + company.getDepartments().size();
}
```

**시트의 컬렉션 타입 지정**

`Set.class`가 raw로 묶이므로 결과도 raw로 돌아온다.

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

**암호가 걸린 엑셀 파일 업로드**

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

**다중 시트 업로드**

Import시 `sheet(...)`는 연달아 체인할 수 없으므로 시트마다 체인을 새로 시작한다. 같은 file을 그대로 다시 넘기면 된다 — 호출할 때마다 새 `InputStream`이 열린다.

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

**업로드가 아닌 엑셀 파일**

`fromResource(...)`는 스프링 `Resource`면 무엇이든 받는다 — 디스크의 파일, 클래스패스 항목, 그 추상화 뒤의 무엇이든. 그래서 배치 잡·시드 로더·테스트에 `MultipartFile`이 필요 없다. 다만 리소스에 파일명이 있어야 한다 — 검증 대상인 확장자와 워크북명 폴백이 거기서 나온다.

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

**단일 시트 업로드**

```java
@PostMapping("/employees/import-csv")
public int importEmployeesCsv(@RequestParam MultipartFile csvFile) throws Exception {
    List<Employee> employees = pxlSpring.importCsv()
                                        .sheet(Employee.class)
                                        .fromMultipartFile(csvFile);

    return employees.size();
}
```

**CSV 여러 개를 워크북 객체로 업로드**

CSV 파일 1개가 시트 1개이고, 파일명이 `@PxlSheet(name = ...)`에 매칭된다. CSV의 `sheet(...)` 형식은 파일을 정확히 하나만 받으므로, 여러 시트는 이 워크북 형태로 읽는다.

```java
@PostMapping("/company/import-csv")
public int importCompanyCsv(@RequestParam List<MultipartFile> csvFiles) throws Exception {
    Company company = pxlSpring.importCsv()
                               .workbook(Company.class)
                               .fromMultipartFiles(csvFiles);

    return company.getEmployees().size() + company.getDepartments().size();
}
```

**구분자 등 옵션 지정**

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

**업로드가 아닌 CSV 파일**

`fromResource(...)` / `fromResources(...)`는 스프링 `Resource`에 대한 같은 짝이다. 리소스의 파일명이 곧 시트명이므로 여기서도 파일명이 있어야 한다.

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

## API 레퍼런스

### `PxlSpring`

`PxlSpring`이 주입할 단 하나의 빈이다. 각 메서드는 해당 기능의 빌더를 돌려준다.

```java
PxlExcelExporter.Builder       exportExcel()
PxlSampleExcelExporter.Builder exportSampleExcel()
PxlZipExporter.Builder         exportZip()
PxlCsvExporter.Builder         exportCsv()
PxlSampleCsvExporter.Builder   exportSampleCsv()
PxlExcelImporter.Builder       importExcel()
PxlCsvImporter.Builder         importCsv()
```

### `PxlExcelExporter`

```java
// 시작
PxlExcelExporter.Builder exportExcel()

// 구성 (셋 중 하나만)
    workbook(Object workbookObject)                                         // @PxlWorkbook 객체
<T> sheet(Class<T> rowClass, Collection<T> rows, String sheetName)          // 반복 호출하면 다중 시트
    poiWorkbook(Workbook workbook)                                          // POI 워크북 그대로
    poiWorkbook(Workbook workbook, String password)                         // 암호화해서 내보낸다

// 옵션
    override(PxlExportWorkbookOption option)

// 실행 — 응답 목적지는 다운로드 파일명을 인자로 받는다(공백이면 워크북명 → "Pxl")
void                     toStream(OutputStream outputStream)
void                     toFile(File excelFile)
void                     toResponse(HttpServletResponse response, String excelFilename)
void                     toResponseStreaming(HttpServletResponse response, String excelFilename)  // "크기 & 메모리" 참고
ResponseEntity<Resource> toResponseEntity(String excelFilename)
```

- `sheet(...)`는 호출할 때마다 시트가 하나씩 붙는다 — 호출 순서가 곧 시트 순서이고, 시트마다 행 클래스를 달리 줄 수 있다. 시트 이름이 중복되면 `PxlDataException`이다.
- `poiWorkbook(...)` 형태에서는 `override(...)`가 아무 효과도 없으며, 암호만 두 번째 인자로 받는다.

### `PxlSampleExcelExporter`

워크북 클래스 또는 시트 클래스로부터 샘플 엑셀을 생성한다. 각 컬럼의 `@PxlColumn(exportSample = ...)` 값으로 채워진 샘플 데이터 한 줄이 헤더 행과 함께 들어간다.

```java
// 시작
PxlSampleExcelExporter.Builder exportSampleExcel()

// 구성 (둘 중 하나만)
workbook(Class<?> workbookClass)                    // @PxlWorkbook 클래스
sheet(Class<?> rowClass, String sheetName)          // 반복 호출하면 다중 시트

// 옵션
override(PxlExportWorkbookOption option)

// 실행 — 응답 목적지는 다운로드 파일명을 인자로 받는다(공백이면 "PxlSample")
void                     toStream(OutputStream outputStream)
void                     toFile(File excelFile)
void                     toResponse(HttpServletResponse response, String excelFilename)
void                     toResponseStreaming(HttpServletResponse response, String excelFilename)  // "크기 & 메모리" 참고
ResponseEntity<Resource> toResponseEntity(String excelFilename)
```

- 다중 시트 샘플 엑셀은 `sheet(...)`를 여러 번 호출하면 된다.

### `PxlZipExporter`

여러 스프레드시트를 각각 하나의 엔트리로 만들어 zip 하나로 묶는다.

```java
// 시작
PxlZipExporter.Builder exportZip()

// 구성 (호출할 때마다 엔트리 하나씩 추가)
workbook(Object workbookObject)                                                              // @PxlWorkbook 객체
workbook(Object workbookObject, PxlExportWorkbookOption option)
workbook(Object workbookObject, PxlExportWorkbookOption option, String excelFilename)
poiWorkbook(Workbook workbook)                                                               // POI 워크북 그대로
poiWorkbook(Workbook workbook, String password)                                              // 암호화해서 기록
poiWorkbook(Workbook workbook, String password, String excelFilename)
sampleWorkbook(Class<?> workbookClass)                                                       // 샘플 템플릿
sampleWorkbook(Class<?> workbookClass, PxlExportWorkbookOption option)
sampleWorkbook(Class<?> workbookClass, PxlExportWorkbookOption option, String excelFilename)
<T> csvSheet(Class<T> rowClass, Collection<T> rows, String sheetName)                        // 행을 CSV로
<T> csvSheet(Class<T> rowClass, Collection<T> rows, String sheetName, PxlExportWorkbookOption option)
<T> csvSheet(Class<T> rowClass, Collection<T> rows, String sheetName, PxlExportWorkbookOption option, String csvFilename)
sampleCsvSheet(Class<?> rowClass, String sheetName)                                          // CSV 템플릿
sampleCsvSheet(Class<?> rowClass, String sheetName, PxlExportWorkbookOption option)
sampleCsvSheet(Class<?> rowClass, String sheetName, PxlExportWorkbookOption option, String csvFilename)

// 실행 — 응답 목적지는 아카이브 파일명을 인자로 받는다(필수)
void                     toStream(OutputStream outputStream)
void                     toFile(File zipFile)
void                     toResponse(HttpServletResponse response, String zipFilename)
void                     toResponseStreaming(HttpServletResponse response, String zipFilename)  // "크기 & 메모리" 참고
ResponseEntity<Resource> toResponseEntity(String zipFilename)
```

- 엔트리명은 지정한 이름 → 워크북명 → `Pxl{index}` 순으로 정해진다. 지정한 이름이 공백이면 없는 것으로 보고 다음 순서로 넘어간다.
- 확장자는 엔트리가 기록되는 포맷에서 붙는다 — 엔트리별 옵션의 export 엔진, 없으면 워크북 클래스가 선언한 엔진이다. 이름·바이트·deflate 레벨이 모두 그 하나의 답을 따른다.
- `poiWorkbook(...)`은 바인딩을 거치지 않으므로 엔트리별 옵션도 워크북명도 없다 — 확장자는 워크북 자신의 타입에서 오고, 이름을 주지 않으면 곧바로 `Pxl{index}`가 된다. 암호화해도 확장자는 그대로이며, 이는 `PxlExcelExporter`와 같다.
- `sampleWorkbook(...)`은 `workbook(...)`처럼 옵션을 받지만 인스턴스가 아니라 클래스를 받으므로 읽을 워크북명이 없다 — 이름을 주지 않으면 곧바로 `PxlSample{index}`가 된다. 인덱스가 이름 없는 엔트리들을 갈라 놓는 것이며, 다운로드 하나의 이름을 짓는 `PxlSampleExcelExporter`에는 그것이 필요 없다.
- CSV 두 형태는 언제나 `.csv`를 쓰므로 옵션의 `exportExcelEngine`은 여기서 아무 의미가 없다 — 대신 문자셋·구분자·BOM이 그 옵션에서 온다. 이름을 주지 않으면 시트명을 쓰는데 시트명은 필수이므로 인덱스가 붙는 기본값이 없고, 같은 시트명을 둘 쓰면 충돌한다. 그럴 때는 이름을 줄 것.
- 아카이브명은 필수다.

### `PxlCsvExporter`

자바 객체를 CSV로 쓴다. CSV 파일 하나가 시트 하나라 워크북 형태가 없고, 마지막 메서드는 시트 하나를 기록한다.

```java
// 시작
PxlCsvExporter.Builder exportCsv()

// 구성 (형태는 이것 하나뿐이며, 두 번 부르면 마지막 메서드에서 예외가 난다)
<T> sheet(Class<T> rowClass, Collection<T> rows, String sheetName)

// 옵션
    override(PxlExportWorkbookOption option)

// 실행 — 응답 목적지는 다운로드 파일명을 인자로 받는다(공백이면 시트명 → "Pxl")
void                     toStream(OutputStream outputStream)
void                     toFile(File csvFile)
void                     toResponse(HttpServletResponse response, String csvFilename)
void                     toResponseStreaming(HttpServletResponse response, String csvFilename)  // "크기 & 메모리" 참고
ResponseEntity<Resource> toResponseEntity(String csvFilename)
```

- 문자셋 · 필드 구분자 · BOM은 `@PxlWorkbook`/`@PxlSheet` 또는 대응하는 `exportCsv*` 옵션 필드에서 오며, 기본값은 UTF-8 · `,` · BOM 없음이다.
- CSV가 담을 수 없는 설정은 무시되지만, `exportPassword`만은 `PxlArgumentException`으로 거절된다.

### `PxlSampleCsvExporter`

행 클래스로부터 샘플 CSV를 생성한다. 헤더 레코드 하나와, 각 컬럼의 `@PxlColumn(exportSample = ...)` 값으로 채운 레코드 하나가 들어간다.

```java
// 시작
PxlSampleCsvExporter.Builder exportSampleCsv()

// 구성 (형태는 이것 하나뿐이며, 두 번 부르면 마지막 메서드에서 예외가 난다)
sheet(Class<?> rowClass, String sheetName)

// 옵션
override(PxlExportWorkbookOption option)

// 실행 — 응답 목적지는 다운로드 파일명을 인자로 받는다(공백이면 "PxlSample")
void                     toStream(OutputStream outputStream)
void                     toFile(File csvFile)
void                     toResponse(HttpServletResponse response, String csvFilename)
void                     toResponseStreaming(HttpServletResponse response, String csvFilename)  // "크기 & 메모리" 참고
ResponseEntity<Resource> toResponseEntity(String csvFilename)
```

- `PxlCsvExporter`와 달리 다운로드 파일명에 시트명 폴백이 없다 — 템플릿은 데이터가 아니라 형태를 설명하기 때문이다.

### `PxlExcelImporter`

엑셀 소스(`.xls` / `.xlsx`) — Multipart 업로드 또는 스프링 `Resource` — 를 워크북 객체 또는 시트 컬렉션으로 변환한다.

```java
// 시작
PxlExcelImporter.Builder importExcel()

// 구성 (하나만). 타입이 실린 Source<R>를 돌려준다
<W>                       Source<W>       workbook(Class<W> workbookClass)
<T>                       Source<List<T>> sheet(Class<T> rowClass, String... candidateSheetNames)
<T>                       Source<List<T>> sheet(Class<T> rowClass, List<String> candidateSheetNames)
<C extends Collection<?>> Source<C>       sheet(Class<?> rowClass, Class<C> collectionClass, String... candidateSheetNames)
<C extends Collection<?>> Source<C>       sheet(Class<?> rowClass, Class<C> collectionClass, List<String> candidateSheetNames)

// 옵션 (구성 지정 전후 어디서든. 나중에 지정한 값이 이긴다)
Builder / Source<R> workbookName(String workbookName)
Builder / Source<R> override(PxlImportWorkbookOption option)

// 실행
R fromMultipartFile(MultipartFile excelFile)  // 업로드
R fromResource(Resource excelFile)            // 파일, 클래스패스 항목, 그 밖의 모든 Resource
```

- 컬렉션 타입을 지정하는 형식에는 `List.class` / `Set.class` 등을 넘긴다. 행 클래스만 주는 형식은 `List`로 고정이다.
- `Resource`에는 파일명이 있어야 한다 — 검증 대상인 확장자가 거기서 나온다. 파일명이 없는 것(예: 그냥 만든 `ByteArrayResource`)은 지원하지 않는 확장자와 마찬가지로 `HttpMediaTypeNotSupportedException`으로 거절된다.

### `PxlCsvImporter`

CSV 소스(`.csv`) — Multipart 업로드 또는 스프링 `Resource` — 를 워크북 객체 또는 시트 컬렉션으로 변환한다.

```java
// 시작
PxlCsvImporter.Builder importCsv()

// 구성 (하나만). 타입이 실린 Source<R>를 돌려준다
<W>                       Source<W>       workbook(Class<W> workbookClass)
<T>                       Source<List<T>> sheet(Class<T> rowClass)
<C extends Collection<?>> Source<C>       sheet(Class<?> rowClass, Class<C> collectionClass)

// 옵션 (구성 지정 전후 어디서든. 나중에 지정한 값이 이긴다)
Builder / Source<R> workbookName(String workbookName)
Builder / Source<R> override(PxlImportWorkbookOption option)

// 실행
R fromMultipartFile(MultipartFile csvFile)          // 업로드 1개
R fromMultipartFiles(List<MultipartFile> csvFiles)  // 업로드 여러 개
R fromResource(Resource csvFile)                    // 리소스 1개
R fromResources(List<Resource> csvFiles)            // 리소스 여러 개
```

- `workbook(...)` 형식은 소스 여러 개를 시트별로 나눠 담고, `sheet(...)` 형식은 정확히 하나만 받는다. `sheet(...)` 형식에서 `fromMultipartFiles(...)` / `fromResources(...)`로 파일을 둘 이상 넘기면 `PxlArgumentException`이 발생한다.
- 여기서도 `Resource`에는 파일명이 있어야 하며, 그 이름이 두 몫을 한다 — 검증 대상인 확장자이자 시트명이다.

---

## 유의 사항

- **매핑 클래스 요구사항**  
  무인자 생성자가 있어야 하고, `@PxlColumn(name=...)`의 이름이 실제 헤더 셀 텍스트와 일치해야 한다.
- **빌더 재사용 금지**  
  빌더는 스레드 안전하지 않으며 재사용을 염두에 두고 만들어지지 않았다. 빌더 하나는 실행 한 번에 쓰고, 다음 실행은 시작 메서드(`exportExcel()` 등)를 새로 호출한다. 재사용했을 때의 동작은 보장하지 않으며 버전에 따라 달라질 수 있다.

---

## 크기 & 메모리

아래는 전부 Heap 이야기다. 양방향 모두 기본적으로 워크북 전체를 메모리에 올리며, 평범한 경우에는 문제가 없지만 대용량에서 가장 먼저 한계에 부딪히는 지점이다.

### Export: 워크북 모델

기본 엔진인 `XSSF`는 파일을 쓰기 시작하기도 전에 시트 전체를 POI 객체로 펼쳐 둔다. 이렇게 만들어진 객체 그래프는 대개 **결과 파일보다 훨씬 크다.** `SXSSF` 엔진은 같은 `.xlsx`를 만들되 최근 일정 행만 메모리에 두고 나머지는 임시 파일로 흘린다.

```java
// 워크북 클래스 단위
@PxlWorkbook(exportExcelEngine = PxlExcelEngine.SXSSF, exportSXSSFRowAccessWindowSize = 100)
public class Company { ... }

// 또는 호출 단위
pxlSpring.exportExcel()
        .sheet(Employee.class, employees, "Employees")
        .override(PxlExportWorkbookOption.builder().exportExcelEngine(PxlExcelEngine.SXSSF).build())
        .toResponse(response, null);
```

- `exportSXSSFRowAccessWindowSize`가 메모리에 유지할 행 수다(지정하지 않으면 POI 기본 창 크기).
- `SXSSF`는 `.xlsx` 전용이다. `HSSF` 엔진(`.xls`)에는 적용되지 않는다.
- 너비를 자동 조정하는 열은 추적 대상이 되어야 하고, 추적되는 열은 메모리에 남는다. 자동 너비 열이 많으면 그만큼 이점이 깎인다.
- CSV에는 엔진이 없지만, 이에 해당하는 장치는 따로 있다. CSV export는 목적지를 열기 전에 출력 전체를 렌더링하는데 — 코덱·검증·한도 실패가 파일을 남기지 않는 이유가 이것이다 — 처음 4 MiB(`PxlConstants.EXPORT_MEMORY_THRESHOLD_OF_CSV`)만 메모리에 두고 그 이상은 `java.io.tmpdir` 아래 임시 파일로 이어 쓴 뒤, 성공·실패와 무관하게 호출이 끝나기 전에 그 파일을 지운다. 따라서 CSV export에 필요한 Heap은 출력 크기를 따라 늘지 않는다. 대신 대용량 export에는 디스크 여유 공간이 필요하고, 그 임시 파일은 암호화되지 않은 채 기록된다 — CSV export가 `exportPassword`를 암호화 대신 거부한다는 점과 함께 알아 둘 일이다.

### Export: 목적지

실행 메서드마다 완성된 출력을 얼마나 들고 있는지가 다르다.

| 실행 메서드                          | 메모리에 유지되는 출력 | 비고 |
|---------------------------------|---|---|
| `toStream(...)` / `toFile(...)` | 없음 | 곧바로 흘려보낸다 — 가장 저렴 |
| `toResponse(...)`               | 1벌 | 생성 실패 시 잘린 다운로드가 나가지 않도록 버퍼링한다 |
| `toResponseEntity(...)`         | 1벌 | `Resource` 본문이 그 버퍼를 배열로 복사하지 않고 그대로 읽는다 |

버퍼링은 의도된 설계다. 바이트가 전부 완성된 뒤에야 응답을 건드리므로, 생성 도중 실패해도 응답(상류가 붙인 CORS 헤더 포함)이 그대로 남는다. `200 OK`와 깨진 본문이 함께 나가는 일이 없다.

### Export: `toResponseStreaming(...)`

다섯 Exporter 모두 그 버퍼를 건너뛰고 응답에 바로 쓰는 전용 실행 메서드를 갖는다.

```java
pxlSpring.exportExcel()
        .sheet(Employee.class, employees, "Employees")
        .override(PxlExportWorkbookOption.builder().exportExcelEngine(PxlExcelEngine.SXSSF).build())
        .toResponseStreaming(response, "employee-list");
```

`SXSSF`와 함께 쓰는 것이 좋다. 둘을 같이 쓰면 행 수와 무관하게 Export Heap이 거의 상수가 된다. ZIP은 단독으로도 효과가 가장 크다 — 아카이브 전체 대신 엔트리를 하나씩 만들어 내보내기 때문이다. CSV는 설명에서 받는 인상보다 효과가 크다 — 코어의 렌더링은 이미 4 MiB로 묶여 있으므로, 이 메서드가 없애는 다운로드 버퍼가 출력 크기를 따라 늘던 마지막 하나다. 대용량 CSV의 Heap을 거의 상수로 만드는 것도 결국 이 메서드다.

**무엇을 포기하는지 알고 써야 한다.** 기본 실행 메서드가 `toResponse(...)`인 이유다.

- **도중에 실패하면 되돌릴 수 없다.** 응답이 이미 `200 OK`와 다운로드 헤더로 커밋된 상태라, 클라이언트는 성공한 다운로드처럼 보이는 잘린 파일을 받는다. 이미 나간 바이트는 취소할 수 없다.
- **`Content-Length`가 없다.** 첫 바이트를 보내기 전에는 크기를 알 수 없어 chunked로 나가고, 클라이언트는 진행률을 표시하지 못한다. 대신 중단된 chunked 전송은 "완료"가 아니라 "실패"로 인식된다는 이점이 있다.

### Import: 업로드

업로드된 워크북은 스트리밍을 켜지 않는 한 POI가 전부 파싱한다. 스트리밍을 켜면 시트를 슬라이딩 윈도우로 읽는다.

```java
@PxlWorkbook(importUsingStreamReader = true, importStreamReaderRowCacheSize = 100)
public class Company { ... }
```

- 스트리밍 읽기는 `.xlsx` 전용이다. `.xls` 업로드는 이 설정과 무관하게 전량 파싱으로 처리된다.
- 애초에 들어올 수 있는 크기를 제한하는 편이 낫다. 서블릿 컨테이너의 제한은 이 코드가 실행되기 전에 적용된다.

```properties
spring.servlet.multipart.max-file-size=20MB
spring.servlet.multipart.max-request-size=100MB
```

---

## 성능 로깅 (선택)

일곱 컴포넌트의 실행 메서드에 AOP 기반 성능 로깅이 적용돼 있으나 기본은 비활성화이다.
다음 설정으로 활성화하고 임계값을 조정할 수 있다.

```properties
pxl.performance.logging.enabled=true
pxl.performance.logging.low-performance-in-ms=5000
```

활성화 시 메서드 진입/종료와 소요 시간(ms)을 로깅하며, 임계값을 초과하면 `LowPerformance`로 표시한다.

이 스위치는 Boot 앱과 순수 Spring 앱에서 동일하게 동작한다.

---

## 자주 묻는 질문

**스프링 컨트롤러에서 엑셀 파일을 다운로드로 어떻게 내려주나?**
`PxlSpring`을 주입하고 응답 목적지로 체인을 끝낸다.
`pxlSpring.exportExcel().sheet(Employee.class, employees, "Employees").toResponseEntity("report")`가
다운로드 헤더까지 설정된 `ResponseEntity<Resource>`를 돌려준다 — [API 사용](#api-사용) 참고.

**업로드된 엑셀 파일을 자바 객체 리스트로 어떻게 읽나?**
`pxlSpring.importExcel().sheet(Employee.class, "Employees").fromMultipartFile(file)`을 호출하면 모든 셀이
필드 타입으로 변환된 `List<Employee>`가 반환된다. 업로드의 확장자를 먼저 검증하며, `.xls`도 `.xlsx`와
똑같이 읽는다 — [`PxlExcelImporter`](#pxlexcelimporter) 참고.

**`HttpServletResponse`와 `ResponseEntity` 중 무엇을 쓰나?**
어느 쪽이든 완성된 출력을 1벌 들고 있는 것은 같다. 핸들러가 `void`를 반환하면 `toResponse(...)`를,
본문을 반환하면 `toResponseEntity(...)`를 쓴다. `toResponseStreaming(...)`은 대용량 export에만 쓰되
무엇을 포기하는지 먼저 읽어 볼 것 — [크기 & 메모리](#크기--메모리) 참고.

**메모리를 넘기지 않고 대용량 파일을 export하려면?**
엑셀은 `toResponseStreaming(...)`과 `SXSSF` 엔진을 함께 쓴다. CSV export는 이미 렌더링을 4 MiB로 제한하고
나머지를 임시 파일로 흘린다. Import 쪽은 `@PxlWorkbook(importUsingStreamReader = true)`가 `.xlsx`를
슬라이딩 윈도로 읽는다.

**한글처럼 비ASCII인 다운로드 파일명도 그대로 전달되나?**
그렇다. `Content-Disposition`에 RFC 5987 `filename*`과 ASCII `filename` 폴백을 함께 기록하므로, 전자를
이해하는 클라이언트는 원래 이름을 받고 그렇지 않은 클라이언트는 길이와 확장자가 보존된 안전한 대체
이름을 받는다. 파일명은 준 그대로 쓰이므로 NFC 정규화가 필요하면 호출 측에서 미리 처리해야
한다.

**CSV도 지원하나?**
`exportCsv()` / `importCsv()`로 지원하며 DTO와 애노테이션은 엑셀과 같다. CSV는 파일 하나가 시트 하나라,
업로드된 CSV 여러 개를 `@PxlSheet` 필드마다 하나씩 묶어 워크북 객체 하나로 읽을 수 있다.

**엑셀 파일 여러 개를 한 번에 내려받게 하려면?**
`exportZip()`에 파일마다 구성 메서드를 한 번씩 호출하면 — `workbook(...)`·`csvSheet(...)` 등 — zip 하나로
묶어 내려준다. [`PxlZipExporter`](#pxlzipexporter) 참고.

**웹 요청 밖에서 — 배치 잡이나 테스트에서 — 스프레드시트를 읽을 수 있나?**
읽을 수 있다. `fromResource(...)` / `fromResources(...)`가 스프링 `Resource`를 받으므로 `MultipartFile`도
HTTP도 필요 없다. 다만 확장자를 실어 오는 것이 파일명이므로 리소스는 파일명을 보고할 수 있어야 한다.

**스프링 부트 없이 순수 스프링 MVC에서도 동작하나?**
동작하며 성능 로깅 스위치도 똑같이 작동한다. Boot 자동 설정은 어디에도 쓰지 않으므로
`MethodValidationPostProcessor` 빈과 `@EnableAspectJAutoProxy`, `multipartResolver`를 직접 선언하면
된다 — [런타임 의존성](#런타임-의존성) 참고.

**아무것도 주입되지 않는다. 무엇이 빠진 건가?**
컴포넌트 스캔이다. 자동으로 등록되는 것이 없으므로 `io.github.hclimkr.pxl.spring`이 `@ComponentScan`에
들어가야 한다. 하위 패키지는 함께 스캔된다 — [`PxlSpring` 주입](#pxlspring-주입) 참고.

**`pxl-spring-javax`와 `pxl-spring-jakarta` 중 무엇을 쓰나?**
Java 8+ / Spring 5.x · Boot 2.x면 `pxl-spring-javax`, Java 17+ / Spring 6.x · Boot 3.x면
`pxl-spring-jakarta`다. 같은 라이브러리이므로 정확히 하나만 추가하면 대응하는 `pxl` 코어가 함께 따라온다.

---

## 빌드 & 기여

소스 코드는 `pxl-spring-javax`에만 있고 `pxl-spring-jakarta`는 빌드 시 문자열 치환으로 생성된다.  
이 저장소는 이슈 보고와 제안만 받는다 — [CONTRIBUTING_ko.md](CONTRIBUTING_ko.md) 를 참고한다.

---

## 라이선스

이 프로젝트는 [Apache License 2.0](LICENSE) 하에 배포된다.

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
