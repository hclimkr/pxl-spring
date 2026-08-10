package io.github.hclimkr.pxl.spring.component;

import io.github.hclimkr.pxl.exception.PxlArgumentException;
import io.github.hclimkr.pxl.exception.PxlException;
import io.github.hclimkr.pxl.exception.PxlNullPointerException;
import io.github.hclimkr.pxl.option.PxlExportWorkbookOption;
import io.github.hclimkr.pxl.option.PxlImportWorkbookOption;
import io.github.hclimkr.pxl.spring.PxlSpring;
import io.github.hclimkr.pxl.spring.tcdata.TestPaths;
import io.github.hclimkr.pxl.spring.tcdata.TestUser;
import io.github.hclimkr.pxl.type.PxlExcelEngine;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.HttpMediaTypeNotSupportedException;

import java.io.*;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;

import static io.github.hclimkr.pxl.spring.component.PxlExcelExporterTests.bodyBytes;
import static io.github.hclimkr.pxl.spring.component.PxlExcelExporterTests.users;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Behavioural tests for {@link PxlCsvExporter}, all driven through the {@link PxlCsvExporter.Builder} fluent
 * API: the records written, every destination (stream / file / response / response-entity / streaming
 * response), the download headers and their {@code .csv} extension, the sheet-name fallback for the download
 * name, the charset/delimiter/BOM options, and the guards the core terminal raises - no sheet, more than one
 * sheet, and the password refusal.
 *
 * <p>The builder comes from {@link PxlSpring}, the entry point the documentation guides users to. The
 * facade hands back this component's own builder, so what is exercised here is still the component.</p>
 *
 * <p>Encoding-sensitive expectations are asserted on the raw bytes rather than on a decoded string: decoding
 * first would absorb a byte order mark into U+FEFF and hide whether one was written at all.</p>
 */
class PxlCsvExporterTests {

    private final PxlSpring pxlSpring = new PxlSpring();

    private TestInfo testInfo;

    @BeforeEach
    void bindTestInfo(final TestInfo testInfo) {
        this.testInfo = testInfo;
    }

    /**
     * The output's records, split on the CRLF the CSV writer emits.
     */
    private static List<String> linesOf(final byte[] bytes, final Charset charset) {
        return Arrays.asList(new String(bytes, charset).split("\r\n", -1));
    }

    private static List<String> linesOf(final byte[] bytes) {
        return linesOf(bytes, StandardCharsets.UTF_8);
    }

    // ----- OutputStream -----

    @Test
    void sheetToStream_writesHeaderAndDataRecords() throws PxlException {
        final ByteArrayOutputStream baos = new ByteArrayOutputStream();
        pxlSpring.exportCsv()
                .sheet(TestUser.class, users(), "Users")
                .toStream(baos);

        final List<String> lines = linesOf(baos.toByteArray());
        assertThat(lines.get(0)).isEqualTo("Name,Age");
        assertThat(lines.get(1)).isEqualTo("Alice,30");
        assertThat(lines.get(2)).isEqualTo("Bob,25");
        // trailing record separator only; no extra record
        assertThat(lines.get(3)).isEmpty();
        assertThat(lines).hasSize(4);
    }

    @Test
    void noByteOrderMarkIsWrittenByDefault() throws PxlException {
        final ByteArrayOutputStream baos = new ByteArrayOutputStream();
        pxlSpring.exportCsv()
                .sheet(TestUser.class, users(), "Users")
                .toStream(baos);

        // the default is off, so the first byte is the header's first character rather than EF BB BF
        assertThat(baos.toByteArray()[0]).isEqualTo((byte) 'N');
    }

    // ----- File -----

    @Test
    void sheetToFile_writesTheSameRecords() throws PxlException, IOException {
        final File file = TestPaths.exportFile(testInfo, ".csv");
        pxlSpring.exportCsv()
                .sheet(TestUser.class, users(), "Users")
                .toFile(file);

        assertThat(file).exists();
        assertThat(linesOf(Files.readAllBytes(file.toPath()))).startsWith("Name,Age", "Alice,30", "Bob,25");
    }

    @Test
    void sheetToFile_roundTripsThroughAResource() throws PxlException, HttpMediaTypeNotSupportedException {
        // the file destination's round trip, read back the way a batch job would - through fromResource(...)
        // rather than an upload, since nothing here came over HTTP
        final File file = TestPaths.exportFile(testInfo, ".csv");
        pxlSpring.exportCsv()
                .sheet(TestUser.class, users(), "Users")
                .toFile(file);

        final List<TestUser> back = pxlSpring.importCsv()
                .sheet(TestUser.class)
                .fromResource(new FileSystemResource(file));

        assertThat(back).extracting(TestUser::getName).containsExactly("Alice", "Bob");
        assertThat(back).extracting(TestUser::getAge).containsExactly(30, 25);
    }

    @Test
    void exportToFile_underMissingDirectory_staysInsidePxlException() {
        // the parent directory does not exist, so opening the destination fails. Unlike the Excel exporter,
        // which opens the file itself on the raw POI path, this one hands the file to the core - what matters
        // here is only that no raw IOException escapes the "every failure is a PxlException" contract; which
        // subtype it is, is the core's call.
        final File unwritable = new File("target/no-such-dir-for-pxl/x.csv");

        assertThatThrownBy(() -> pxlSpring.exportCsv()
                .sheet(TestUser.class, users(), "Users")
                .toFile(unwritable))
                .isInstanceOf(PxlException.class);
    }

    @Test
    void failedExportToFile_leavesNoFileBehind() {
        // the core renders the whole output before opening the destination, so a refusal never creates a file
        final File file = TestPaths.exportFile(testInfo, ".csv");
        final PxlExportWorkbookOption option = PxlExportWorkbookOption.builder()
                .exportPassword("secret")
                .build();

        assertThatThrownBy(() -> pxlSpring.exportCsv()
                .sheet(TestUser.class, users(), "Users")
                .override(option)
                .toFile(file))
                .isInstanceOf(PxlArgumentException.class);

        assertThat(file).doesNotExist();
    }

    // ----- HttpServletResponse -----

    @Test
    void sheetToResponse_writesCsvWithDownloadHeaders() throws PxlException {
        final MockHttpServletResponse response = new MockHttpServletResponse();
        pxlSpring.exportCsv()
                .sheet(TestUser.class, users(), "Users")
                .toResponse(response, "report");

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(response.getHeader(HttpHeaders.CONTENT_DISPOSITION)).contains("report.csv");
        assertThat(response.getContentType()).isEqualTo("text/csv");
        assertThat(linesOf(response.getContentAsByteArray())).startsWith("Name,Age");
    }

    @Test
    void blankFilename_fallsBackToTheSheetName() throws PxlException {
        // one CSV file is one sheet, so the sheet name is the only name the source carries - it stands in for
        // the Excel exporter's @PxlWorkbook workbook-name fallback
        final MockHttpServletResponse response = new MockHttpServletResponse();
        pxlSpring.exportCsv()
                .sheet(TestUser.class, users(), "Users")
                .toResponse(response, "  ");

        assertThat(response.getHeader(HttpHeaders.CONTENT_DISPOSITION)).contains("Users.csv");
    }

    // ----- ResponseEntity<Resource> -----

    @Test
    void sheetToResponseEntity_carriesTheBodyAndHeaders() throws PxlException {
        final ResponseEntity<Resource> entity = pxlSpring.exportCsv()
                .sheet(TestUser.class, users(), "Users")
                .toResponseEntity("report");

        assertThat(entity.getStatusCode().value()).isEqualTo(200);
        assertThat(entity.getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION)).contains("report.csv");
        assertThat(entity.getHeaders().getFirst(HttpHeaders.CONTENT_TYPE)).isEqualTo("text/csv");
        assertThat(linesOf(bodyBytes(entity))).startsWith("Name,Age", "Alice,30");
    }

    @Test
    void blankFilenameOnResponseEntity_fallsBackToTheSheetName() throws PxlException {
        // the same resolveFilename(String) as the servlet-response destination, on the entity one
        final ResponseEntity<Resource> entity = pxlSpring.exportCsv()
                .sheet(TestUser.class, users(), "Users")
                .toResponseEntity(null);

        assertThat(entity.getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION)).contains("Users.csv");
    }

    @Test
    void koreanFilename_isRfc5987PercentEncoded() throws PxlException {
        final ResponseEntity<Resource> entity = pxlSpring.exportCsv()
                .sheet(TestUser.class, users(), "사용자")
                .toResponseEntity("보고서");

        final String contentDisposition = entity.getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION);
        assertThat(contentDisposition).startsWith("attachment; filename=\"___.csv\"; filename*=UTF-8''");
        assertThat(contentDisposition).doesNotContain("보고서").contains("%");
    }

    // ----- override(...) -----

    @Test
    void delimiterOption_changesTheFieldSeparator() throws PxlException {
        final PxlExportWorkbookOption option = PxlExportWorkbookOption.builder()
                .exportCsvDelimiter(';')
                .build();

        final ByteArrayOutputStream baos = new ByteArrayOutputStream();
        pxlSpring.exportCsv()
                .sheet(TestUser.class, users(), "Users")
                .override(option)
                .toStream(baos);

        assertThat(linesOf(baos.toByteArray())).startsWith("Name;Age", "Alice;30");
    }

    @Test
    void bomOption_prefixesTheUtf8Mark() throws PxlException {
        final PxlExportWorkbookOption option = PxlExportWorkbookOption.builder()
                .exportCsvBom(Boolean.TRUE)
                .build();

        final ByteArrayOutputStream baos = new ByteArrayOutputStream();
        pxlSpring.exportCsv()
                .sheet(TestUser.class, users(), "Users")
                .override(option)
                .toStream(baos);

        assertThat(baos.toByteArray()).startsWith((byte) 0xEF, (byte) 0xBB, (byte) 0xBF);
    }

    @Test
    void charsetOption_encodesTheOutputWithIt() throws PxlException {
        final PxlExportWorkbookOption option = PxlExportWorkbookOption.builder()
                .exportCsvCharset("MS949")
                .build();

        final ByteArrayOutputStream baos = new ByteArrayOutputStream();
        pxlSpring.exportCsv()
                .sheet(TestUser.class, users(), "Users")
                .override(option)
                .toStream(baos);

        // ASCII data either way; what this pins is that the named charset is accepted and used to encode
        assertThat(linesOf(baos.toByteArray(), Charset.forName("MS949"))).startsWith("Name,Age");
    }

    @Test
    void unsupportedCharset_throwsPxlArgument() {
        final PxlExportWorkbookOption option = PxlExportWorkbookOption.builder()
                .exportCsvCharset("NoSuchCharset")
                .build();

        assertThatThrownBy(() -> pxlSpring.exportCsv()
                .sheet(TestUser.class, users(), "Users")
                .override(option)
                .toStream(new ByteArrayOutputStream()))
                .isInstanceOf(PxlArgumentException.class);
    }

    @Test
    void passwordOption_isRefusedRatherThanIgnored() {
        // CSV cannot be encrypted, and writing plaintext when encryption was asked for would be a leak
        final PxlExportWorkbookOption option = PxlExportWorkbookOption.builder()
                .exportPassword("secret")
                .build();

        assertThatThrownBy(() -> pxlSpring.exportCsv()
                .sheet(TestUser.class, users(), "Users")
                .override(option)
                .toStream(new ByteArrayOutputStream()))
                .isInstanceOf(PxlArgumentException.class);
    }

    @Test
    void excelEngineOption_doesNotChangeTheCsvExtension() throws PxlException {
        // the counterpart of the Excel exporter's engine tests, inverted: this exporter writes one format, so
        // an engine on the option is ignored rather than switching the download headers to .xls
        final PxlExportWorkbookOption option = PxlExportWorkbookOption.builder()
                .exportExcelEngine(PxlExcelEngine.HSSF)
                .build();

        final MockHttpServletResponse response = new MockHttpServletResponse();
        pxlSpring.exportCsv()
                .sheet(TestUser.class, users(), "Users")
                .override(option)
                .toResponse(response, "report");

        assertThat(response.getHeader(HttpHeaders.CONTENT_DISPOSITION)).endsWith("report.csv");
        assertThat(response.getContentType()).isEqualTo("text/csv");
        assertThat(linesOf(response.getContentAsByteArray())).startsWith("Name,Age");
    }

    // ----- i18n export resource bundle -----

    /**
     * In-memory bundle mapping the {@link TestUser} column keys ({@code Name}/{@code Age}, from
     * {@code @PxlColumn(name = ...)}) to Korean headers. Only the mappings present here are translated;
     * missing keys fall back to the key itself.
     */
    private static ResourceBundle koreanColumnBundle(final boolean translateAge) {
        return new ListResourceBundle() {
            @Override
            protected Object[][] getContents() {
                return translateAge
                        ? new Object[][]{{"Name", "이름"}, {"Age", "나이"}}
                        : new Object[][]{{"Name", "이름"}};
            }
        };
    }

    /**
     * Loads a UTF-8 {@code .properties} bundle from the test classpath. Java 8's default
     * {@code ResourceBundle.getBundle} decodes {@code .properties} as ISO-8859-1, which mangles the
     * Korean values, so we read the stream as UTF-8 explicitly via {@link PropertyResourceBundle}.
     */
    private static ResourceBundle utf8PropertiesBundle(final String resource) throws IOException {
        try (InputStream is = PxlCsvExporterTests.class.getResourceAsStream(resource);
             InputStreamReader reader = new InputStreamReader(Objects.requireNonNull(is), StandardCharsets.UTF_8)) {
            return new PropertyResourceBundle(reader);
        }
    }

    @Test
    void exportResourceBundle_translatesColumnHeaders() throws PxlException {
        // the CSV writer calls the same codec entry point as the Excel one, so column-name translation is
        // shared rather than reimplemented - this pins that it really reaches the header record
        final PxlExportWorkbookOption option = PxlExportWorkbookOption.builder()
                .exportResourceBundle(koreanColumnBundle(true))
                .build();

        final ByteArrayOutputStream baos = new ByteArrayOutputStream();
        pxlSpring.exportCsv()
                .sheet(TestUser.class, users(), "Users")
                .override(option)
                .toStream(baos);

        assertThat(linesOf(baos.toByteArray()).get(0)).isEqualTo("이름,나이");
    }

    @Test
    void exportResourceBundle_missingKeyFallsBackToColumnName() throws PxlException {
        final PxlExportWorkbookOption option = PxlExportWorkbookOption.builder()
                .exportResourceBundle(koreanColumnBundle(false)) // only "Name" is mapped
                .build();

        final ByteArrayOutputStream baos = new ByteArrayOutputStream();
        pxlSpring.exportCsv()
                .sheet(TestUser.class, users(), "Users")
                .override(option)
                .toStream(baos);

        // "Name" is translated; the unmapped "Age" key is emitted unchanged
        assertThat(linesOf(baos.toByteArray()).get(0)).isEqualTo("이름,Age");
    }

    @Test
    void exportResourceBundle_loadedFromPropertiesFile_translatesColumnHeaders() throws PxlException, IOException {
        // src/test/resources/messages_ko.properties maps Name/Age -> 이름/나이 (read as UTF-8)
        final PxlExportWorkbookOption option = PxlExportWorkbookOption.builder()
                .exportResourceBundle(utf8PropertiesBundle("/messages_ko.properties"))
                .build();

        final ByteArrayOutputStream baos = new ByteArrayOutputStream();
        pxlSpring.exportCsv()
                .sheet(TestUser.class, users(), "Users")
                .override(option)
                .toStream(baos);

        assertThat(linesOf(baos.toByteArray()).get(0)).isEqualTo("이름,나이");
    }

    @Test
    void exportResourceBundle_roundTripsWithMatchingImportBundle() throws PxlException, HttpMediaTypeNotSupportedException {
        final PxlExportWorkbookOption exportOption = PxlExportWorkbookOption.builder()
                .exportResourceBundle(koreanColumnBundle(true))
                .build();

        final ByteArrayOutputStream baos = new ByteArrayOutputStream();
        pxlSpring.exportCsv()
                .sheet(TestUser.class, users(), "Users")
                .override(exportOption)
                .toStream(baos);

        // with translated headers, re-import must translate the same way to bind columns back to fields
        final PxlImportWorkbookOption importOption = PxlImportWorkbookOption.builder()
                .importResourceBundle(koreanColumnBundle(true))
                .build();

        final List<TestUser> back = pxlSpring.importCsv()
                .override(importOption)
                .sheet(TestUser.class)
                .fromMultipartFile(new MockMultipartFile("file", "Users.csv", null, baos.toByteArray()));

        assertThat(back).extracting(TestUser::getName).containsExactly("Alice", "Bob");
        assertThat(back).extracting(TestUser::getAge).containsExactly(30, 25);
    }

    // ----- failure path (the response is untouched on a generation failure) -----

    @Test
    void exportToResponse_whenGenerationFails_leavesResponseUntouched() {
        final MockHttpServletResponse response = new MockHttpServletResponse();

        // the password refusal happens inside the core terminal, i.e. after this component has resolved the
        // download name but before it touches the response - so the response must come out untouched
        final PxlExportWorkbookOption option = PxlExportWorkbookOption.builder()
                .exportPassword("secret")
                .build();

        assertThatThrownBy(() -> pxlSpring.exportCsv()
                .sheet(TestUser.class, users(), "Users")
                .override(option)
                .toResponse(response, "data"))
                .isInstanceOf(PxlException.class);

        assertThat(response.getHeader(HttpHeaders.CONTENT_DISPOSITION)).isNull();
        assertThat(response.getContentType()).isNull();
        assertThat(response.getContentAsByteArray()).isEmpty();
    }

    @Test
    void exportToResponse_replacesPreexistingDownloadHeaders() throws PxlException {
        final MockHttpServletResponse response = new MockHttpServletResponse();
        // headers already present (e.g. set by a filter or MVC default before the export runs)
        response.addHeader(HttpHeaders.CONTENT_DISPOSITION, "inline");
        response.setContentType("text/html");

        pxlSpring.exportCsv()
                .sheet(TestUser.class, users(), "Users")
                .toResponse(response, "data");

        // Content-Disposition must be replaced, not appended - a second value would corrupt the download
        assertThat(response.getHeaders(HttpHeaders.CONTENT_DISPOSITION)).hasSize(1);
        assertThat(response.getHeader(HttpHeaders.CONTENT_DISPOSITION)).contains("data.csv");
        // Content-Type reflects the export as a single value
        assertThat(response.getHeaders(HttpHeaders.CONTENT_TYPE)).hasSize(1);
        assertThat(response.getContentType()).isNotEqualTo("text/html");
    }

    // ----- builder source guards (delegated to the core builder) -----

    @Test
    void noSheetConfigured_throwsPxlArgument() {
        assertThatThrownBy(() -> pxlSpring.exportCsv().toStream(new ByteArrayOutputStream()))
                .isInstanceOf(PxlArgumentException.class);
    }

    @Test
    void moreThanOneSheet_throwsPxlArgument() {
        // a CSV file holds one sheet, so a second sheet(...) call does not add one - it fails the terminal
        assertThatThrownBy(() -> pxlSpring.exportCsv()
                .sheet(TestUser.class, users(), "Users")
                .sheet(TestUser.class, users(), "Admins")
                .toStream(new ByteArrayOutputStream()))
                .isInstanceOf(PxlArgumentException.class);
    }

    @Test
    void blankSheetName_throwsPxlArgument() {
        assertThatThrownBy(() -> pxlSpring.exportCsv().sheet(TestUser.class, users(), "  "))
                .isInstanceOf(PxlArgumentException.class);
    }

    @Test
    void nullRowClass_throwsPxlNullPointer() {
        assertThatThrownBy(() -> pxlSpring.exportCsv().sheet(null, users(), "Users"))
                .isInstanceOf(PxlNullPointerException.class);
    }

    @Test
    void nullDestinationOnPlainComponent_throwsPxlNullPointerNotRawNpe() {
        // this component is a plain instance, so @NotNull never fires (no Spring proxy). Through a proxy the
        // same calls raise ConstraintViolationException - that half is pinned by PxlValidationTests.
        assertThatThrownBy(() -> pxlSpring.exportCsv()
                .sheet(TestUser.class, users(), "Users").toStream(null))
                .isInstanceOf(PxlNullPointerException.class);

        assertThatThrownBy(() -> pxlSpring.exportCsv()
                .sheet(TestUser.class, users(), "Users").toFile(null))
                .isInstanceOf(PxlNullPointerException.class);

        assertThatThrownBy(() -> pxlSpring.exportCsv()
                .sheet(TestUser.class, users(), "Users").toResponse(null, null))
                .isInstanceOf(PxlNullPointerException.class);

        assertThatThrownBy(() -> pxlSpring.exportCsv()
                .sheet(TestUser.class, users(), "Users").toResponseStreaming(null, null))
                .isInstanceOf(PxlNullPointerException.class);
    }

    // ----- toResponseStreaming(...) -----

    @Test
    void streamingToResponse_writesTheSameBytesWithoutContentLength() throws PxlException {
        final MockHttpServletResponse buffered = new MockHttpServletResponse();
        pxlSpring.exportCsv()
                .sheet(TestUser.class, users(), "Users").toResponse(buffered, "report");

        final MockHttpServletResponse streamed = new MockHttpServletResponse();
        pxlSpring.exportCsv()
                .sheet(TestUser.class, users(), "Users").toResponseStreaming(streamed, "report");

        // same download, same headers - the only difference is that the size is not known up front, so the
        // streaming response goes out chunked
        assertThat(streamed.getContentAsByteArray()).isEqualTo(buffered.getContentAsByteArray());
        assertThat(streamed.getHeader(HttpHeaders.CONTENT_DISPOSITION))
                .isEqualTo(buffered.getHeader(HttpHeaders.CONTENT_DISPOSITION));
        assertThat(streamed.getContentType()).isEqualTo(buffered.getContentType());

        assertThat(buffered.getHeader(HttpHeaders.CONTENT_LENGTH)).isNotNull();
        assertThat(streamed.getHeader(HttpHeaders.CONTENT_LENGTH)).isNull();
    }

    @Test
    void streamingSetsHeadersBeforeTheSourceIsValidated() {
        // This exporter has no component-level source check - "no sheet, or more than one" is the core
        // builder's call, made inside its terminal. On the streaming path that is *after* the headers have
        // gone out, so a rejected export still throws and writes no body, but the download headers are already
        // on the response. That is the documented cost of streaming. The buffered default has no such window.
        final MockHttpServletResponse streamed = new MockHttpServletResponse();

        assertThatThrownBy(() -> pxlSpring.exportCsv().toResponseStreaming(streamed, null))
                .isInstanceOf(PxlArgumentException.class);

        assertThat(streamed.getContentAsByteArray()).isEmpty();
        assertThat(streamed.getHeader(HttpHeaders.CONTENT_DISPOSITION)).isNotNull();

        final MockHttpServletResponse buffered = new MockHttpServletResponse();

        assertThatThrownBy(() -> pxlSpring.exportCsv().toResponse(buffered, null))
                .isInstanceOf(PxlArgumentException.class);

        assertThat(buffered.getContentAsByteArray()).isEmpty();
        assertThat(buffered.getHeader(HttpHeaders.CONTENT_DISPOSITION)).isNull();
    }

    @Test
    void streamingResolvesTheFilenameLikeTheBufferedTerminal() throws PxlException {
        // the name argument runs through the same resolveFilename(String), so a blank one falls back to the
        // sheet name on this terminal too
        final MockHttpServletResponse fromSheetName = new MockHttpServletResponse();
        pxlSpring.exportCsv()
                .sheet(TestUser.class, users(), "Users").toResponseStreaming(fromSheetName, null);
        assertThat(fromSheetName.getHeader(HttpHeaders.CONTENT_DISPOSITION)).contains("Users.csv");

        final MockHttpServletResponse fromExplicit = new MockHttpServletResponse();
        pxlSpring.exportCsv()
                .sheet(TestUser.class, users(), "Users").toResponseStreaming(fromExplicit, "report");
        assertThat(fromExplicit.getHeader(HttpHeaders.CONTENT_DISPOSITION)).contains("report.csv");
    }

    @Test
    void blankFilenameAndNoSheet_fallsBackToPxl() {
        // The sheet name is the only fallback a CSV source carries, and the core rejects a blank one, so the
        // "Pxl" constant is reachable only from a chain that reaches a terminal having configured no sheet.
        // The streaming terminal is where that is observable: it emits the headers before the core rejects
        // the export.
        final MockHttpServletResponse response = new MockHttpServletResponse();

        assertThatThrownBy(() -> pxlSpring.exportCsv().toResponseStreaming(response, null))
                .isInstanceOf(PxlArgumentException.class);

        assertThat(response.getHeader(HttpHeaders.CONTENT_DISPOSITION)).contains("Pxl.csv");
    }

    // ----- destination matrix -----

    /**
     * The five terminal destinations, swept by the matrix test below.
     */
    enum Dest {
        STREAM, FILE, RESPONSE, RESPONSE_STREAMING, RESPONSE_ENTITY
    }

    /**
     * Runs the configured builder against the given destination and returns the bytes it produced, so one
     * assertion can serve every destination. File artifacts are named per destination to stay inspectable.
     */
    private byte[] emit(final PxlCsvExporter.Builder builder, final Dest dest) throws PxlException, IOException {
        switch (dest) {
            case STREAM: {
                final ByteArrayOutputStream baos = new ByteArrayOutputStream();
                builder.toStream(baos);
                return baos.toByteArray();
            }
            case FILE: {
                final File file = TestPaths.exportFile(testInfo.getTestMethod()
                        .orElseThrow(IllegalStateException::new).getName() + "-" + dest + ".csv");
                builder.toFile(file);
                return Files.readAllBytes(file.toPath());
            }
            case RESPONSE: {
                final MockHttpServletResponse response = new MockHttpServletResponse();
                builder.toResponse(response, null);
                return response.getContentAsByteArray();
            }
            case RESPONSE_STREAMING: {
                final MockHttpServletResponse response = new MockHttpServletResponse();
                builder.toResponseStreaming(response, null);
                return response.getContentAsByteArray();
            }
            default: {
                return bodyBytes(builder.toResponseEntity(null));
            }
        }
    }

    @ParameterizedTest
    @EnumSource(Dest.class)
    void sheetSource_producesTheSameCsvOnEveryDestination(final Dest dest) throws PxlException, IOException {
        final byte[] bytes = emit(pxlSpring.exportCsv()
                .sheet(TestUser.class, users(), "Users"), dest);

        assertThat(linesOf(bytes)).startsWith("Name,Age", "Alice,30", "Bob,25");
    }

    // ----- override(...) on every destination -----
    // The response destinations above already assert that an option cannot switch the download extension;
    // this pins the effect an option has on the bytes themselves, wherever they are written.

    @ParameterizedTest
    @EnumSource(Dest.class)
    void delimiterOption_appliesOnEveryDestination(final Dest dest) throws PxlException, IOException {
        final PxlExportWorkbookOption option = PxlExportWorkbookOption.builder()
                .exportCsvDelimiter(';')
                .build();

        final byte[] bytes = emit(pxlSpring.exportCsv()
                .sheet(TestUser.class, users(), "Users")
                .override(option), dest);

        assertThat(linesOf(bytes)).startsWith("Name;Age", "Alice;30");
    }

    // ----- round trip -----

    @Test
    void exportedCsv_readsBackThroughImportCsv() throws PxlException, HttpMediaTypeNotSupportedException {
        final ByteArrayOutputStream baos = new ByteArrayOutputStream();
        pxlSpring.exportCsv()
                .sheet(TestUser.class, users(), "Users")
                .toStream(baos);

        final List<TestUser> back = pxlSpring.importCsv()
                .sheet(TestUser.class)
                .fromMultipartFile(new MockMultipartFile("file", "Users.csv", null, baos.toByteArray()));

        assertThat(back).extracting(TestUser::getName).containsExactly("Alice", "Bob");
        assertThat(back).extracting(TestUser::getAge).containsExactly(30, 25);
    }

    // ----- builder call-order independence -----

    @Test
    void optionsBeforeSource_behaveTheSameAsAfter() throws PxlException {
        final PxlExportWorkbookOption option = PxlExportWorkbookOption.builder()
                .exportCsvDelimiter(';')
                .build();

        final ByteArrayOutputStream optionsFirst = new ByteArrayOutputStream();
        pxlSpring.exportCsv()
                .override(option)
                .sheet(TestUser.class, users(), "Users")
                .toStream(optionsFirst);

        final ByteArrayOutputStream optionsLast = new ByteArrayOutputStream();
        pxlSpring.exportCsv()
                .sheet(TestUser.class, users(), "Users")
                .override(option)
                .toStream(optionsLast);

        assertThat(optionsFirst.toByteArray()).isEqualTo(optionsLast.toByteArray());
        assertThat(linesOf(optionsFirst.toByteArray())).startsWith("Name;Age");
    }

    // ----- option precedence within one chain -----
    // Each option is a plain last-write-wins slot, and resolveFilename ranks its inputs; these pin what the
    // single-option tests above cannot show.

    @Test
    void repeatedOverrideAndFilename_lastValueWins() throws PxlException {
        final PxlExportWorkbookOption semicolonOption = PxlExportWorkbookOption.builder()
                .exportCsvDelimiter(';')
                .build();
        final PxlExportWorkbookOption pipeOption = PxlExportWorkbookOption.builder()
                .exportCsvDelimiter('|')
                .build();

        final MockHttpServletResponse response = new MockHttpServletResponse();
        pxlSpring.exportCsv()
                .sheet(TestUser.class, users(), "Users")
                .override(semicolonOption)
                .override(pipeOption)
                .toResponse(response, "second");

        // had the first call won, the records would be semicolon-separated
        assertThat(response.getHeader(HttpHeaders.CONTENT_DISPOSITION)).contains("second.csv");
        assertThat(linesOf(response.getContentAsByteArray())).startsWith("Name|Age", "Alice|30");
    }

    @Test
    void sheetWithExplicitFilename_prefersFilenameOverSheetName() throws PxlException {
        // resolveFilename(): the explicit name outranks the sheet name, which only fills in for a blank one
        final MockHttpServletResponse response = new MockHttpServletResponse();
        pxlSpring.exportCsv()
                .sheet(TestUser.class, users(), "Users")
                .toResponse(response, "explicit");

        assertThat(response.getHeader(HttpHeaders.CONTENT_DISPOSITION))
                .contains("explicit.csv")
                .doesNotContain("Users");
    }
}
