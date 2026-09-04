package io.github.hclimkr.pxl.spring.component;

import io.github.hclimkr.pxl.Pxl;
import io.github.hclimkr.pxl.exception.*;
import io.github.hclimkr.pxl.option.PxlExportWorkbookOption;
import io.github.hclimkr.pxl.option.PxlImportWorkbookOption;
import io.github.hclimkr.pxl.spring.PxlSpring;
import io.github.hclimkr.pxl.spring.tcdata.TestHssfWorkbook;
import io.github.hclimkr.pxl.spring.tcdata.TestPaths;
import io.github.hclimkr.pxl.spring.tcdata.TestUser;
import io.github.hclimkr.pxl.spring.tcdata.TestWorkbook;
import io.github.hclimkr.pxl.type.PxlExcelEngine;
import org.apache.poi.hssf.usermodel.HSSFWorkbook;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.streaming.SXSSFWorkbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.util.StreamUtils;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Behavioural tests for {@link PxlExcelExporter} - round-trip export/import across every destination,
 * download headers, filename resolution/encoding, export-engine switching, password protection, and the
 * raw {@link Workbook} path, all driven through the {@link PxlExcelExporter.Builder} fluent API.
 *
 * <p>The builder comes from {@link PxlSpring}, the entry point the documentation guides users to. The
 * facade hands back this component's own builder, so what is exercised here is still the component.</p>
 *
 * <p>Every destination is swept rather than spot-checked, through one of two enums. Assertions about the
 * exported <em>bytes</em> are {@code @ParameterizedTest}s over {@link Dest} - what a source or an option
 * produces cannot depend on which terminal writes it out, and a single-destination test could only ever
 * show one of the four. Assertions about the <em>download headers</em> are the same thing on the other
 * axis, over {@link Download}: a stream and a file have no headers to look at, so those three terminals
 * (the streaming response included, which {@code Dest} cannot reach) get their own sweep. What stays a
 * plain {@code @Test} is the failure paths and guards, the entity-only invariants, and the comparisons
 * one terminal makes against another.</p>
 */
public class PxlExcelExporterTests {

    private final PxlSpring pxlSpring = new PxlSpring();

    private TestInfo testInfo;

    @BeforeEach
    void bindTestInfo(final TestInfo testInfo) {
        this.testInfo = testInfo;
    }

    /**
     * Public because the facade and validation tests in the parent package reuse it.
     */
    public static List<TestUser> users() {
        return Arrays.asList(new TestUser("Alice", 30), new TestUser("Bob", 25));
    }

    static List<TestUser> admins() {
        return Collections.singletonList(new TestUser("Carol", 40));
    }

    static List<TestUser> guests() {
        return Collections.singletonList(new TestUser("Dave", 35));
    }

    /**
     * XLSX is a ZIP container, so it starts with the {@code PK} signature.
     *
     * <p>Public because the facade test in the parent package reuses it.</p>
     */
    public static boolean isXlsx(final byte[] bytes) {
        return bytes != null && bytes.length > 4 && bytes[0] == 'P' && bytes[1] == 'K';
    }

    /**
     * Reads a {@code toResponseEntity()} body out as bytes.
     *
     * <p>Goes through {@link Resource#getInputStream()} rather than casting to whatever the exporters happen
     * to return - today a view over the download buffer - so these tests keep passing if the body ever
     * becomes file-backed. Wraps the {@code IOException} because reading an in-memory resource cannot
     * realistically fail and declaring it would spread {@code throws} across most of the assertions here.</p>
     *
     * <p>Package-private, unlike {@link #isXlsx} and {@link #users}: the sample and zip test classes reuse it,
     * but they sit in this same package. Only the parent package's facade and validation tests need the wider
     * visibility, and neither of them builds a response entity.</p>
     */
    static byte[] bodyBytes(final ResponseEntity<Resource> entity) {
        try (InputStream inputStream = entity.getBody().getInputStream()) {
            return StreamUtils.copyToByteArray(inputStream);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * An {@link OutputStream} that records what was written to it and whether it was ever closed.
     *
     * <p>Every {@code toStream} back-end promises the caller's stream back open - the core flushes it and
     * leaves closing to whoever opened it - and nothing about the produced bytes shows whether that held.
     * Package-private for the same reason as {@link #bodyBytes}: the other exporter test classes sit in this
     * package and make the same assertion about their own terminal.</p>
     */
    static final class ClosingTrackedStream extends OutputStream {

        private final ByteArrayOutputStream delegate = new ByteArrayOutputStream();

        private boolean closed;

        @Override
        public void write(final int b) {
            delegate.write(b);
        }

        @Override
        public void write(final byte[] b, final int off, final int len) {
            delegate.write(b, off, len);
        }

        @Override
        public void close() {
            closed = true;
        }

        boolean isClosed() {
            return closed;
        }

        byte[] written() {
            return delegate.toByteArray();
        }
    }

    /**
     * The workbook's sheet names, in sheet order - so a test can assert both how many sheets were produced
     * and in which order, which reimporting by name cannot show.
     */
    static List<String> sheetNames(final byte[] bytes) throws IOException {
        final List<String> names = new ArrayList<>();
        try (Workbook workbook = WorkbookFactory.create(new ByteArrayInputStream(bytes))) {
            for (final Sheet sheet : workbook) {
                names.add(sheet.getSheetName());
            }
        }
        return names;
    }

    @SuppressWarnings("unchecked")
    private static List<TestUser> reimportSheet(final byte[] bytes, final String sheetName) throws PxlException {
        return new Pxl().importExcel()
                .sheet(TestUser.class, List.class, Collections.singletonList(sheetName))
                .fromStream(new ByteArrayInputStream(bytes));
    }

    // ----- HttpServletResponse -----

    @Test
    void exportSingleSheetToResponse_writesToServletResponse() throws PxlException {
        final MockHttpServletResponse response = new MockHttpServletResponse();
        pxlSpring.exportExcel()
                .sheet(TestUser.class, users(), "Users")
                .toResponse(response, "data");

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(response.getHeader(HttpHeaders.CONTENT_DISPOSITION)).contains("data.xlsx");
        assertThat(isXlsx(response.getContentAsByteArray())).isTrue();
    }

    // ----- ResponseEntity<Resource> -----

    @Test
    void exportWorkbookObjectToResponseEntity_setsDownloadHeadersAndDerivesFilename() throws PxlException {
        // the entity terminal alone: the body is a view over the download buffer rather than a copy of it,
        // so the length the header carries and the length the body reads out come from two different places
        final TestWorkbook workbook = new TestWorkbook();
        workbook.setWorkbookName("Report");
        workbook.setUsers(users());

        final ResponseEntity<Resource> entity = pxlSpring.exportExcel()
                .workbook(workbook)
                .toResponseEntity(null);

        assertThat(entity.getStatusCode().value()).isEqualTo(200);
        assertThat(entity.getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION))
                .contains("attachment")
                .contains("filename*=UTF-8''Report.xlsx");
        assertThat(entity.getHeaders().getFirst(HttpHeaders.CONTENT_TYPE)).isNotBlank();
        assertThat(entity.getHeaders().getContentLength()).isEqualTo(bodyBytes(entity).length);
        assertThat(isXlsx(bodyBytes(entity))).isTrue();
    }

    // ----- i18n export resource bundle -----
    // A bundle only ever reaches the bytes, so every one of these sweeps the destinations rather than
    // picking one: the translated headers have to survive whichever terminal writes them out.

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
     * Collects every STRING cell value from the given sheet - used to assert which header labels the
     * i18n bundle produced, independent of the exact header row index.
     */
    private static List<String> stringCells(final byte[] bytes, final String sheetName) throws IOException {
        final List<String> values = new ArrayList<>();
        try (Workbook workbook = new XSSFWorkbook(new ByteArrayInputStream(bytes))) {
            final Sheet sheet = workbook.getSheet(sheetName);
            for (final Row row : sheet) {
                for (final Cell cell : row) {
                    if (cell.getCellType() == CellType.STRING) {
                        values.add(cell.getStringCellValue());
                    }
                }
            }
        }
        return values;
    }

    @ParameterizedTest
    @EnumSource(Dest.class)
    void exportResourceBundle_translatesColumnHeadersOnEveryDestination(final Dest dest)
            throws PxlException, IOException {
        final PxlExportWorkbookOption option = PxlExportWorkbookOption.builder()
                .exportResourceBundle(koreanColumnBundle(true))
                .build();

        final byte[] bytes = emit(pxlSpring.exportExcel()
                .sheet(TestUser.class, users(), "Users")
                .override(option), dest);

        // headers are translated via the bundle; the raw annotation keys must not leak into the sheet
        assertThat(stringCells(bytes, "Users"))
                .contains("이름", "나이")
                .doesNotContain("Name", "Age");
    }

    @ParameterizedTest
    @EnumSource(Dest.class)
    void exportResourceBundle_missingKeyFallsBackToColumnNameOnEveryDestination(final Dest dest)
            throws PxlException, IOException {
        final PxlExportWorkbookOption option = PxlExportWorkbookOption.builder()
                .exportResourceBundle(koreanColumnBundle(false)) // only "Name" is mapped
                .build();

        final byte[] bytes = emit(pxlSpring.exportExcel()
                .sheet(TestUser.class, users(), "Users")
                .override(option), dest);

        // "Name" is translated; the unmapped "Age" key is emitted unchanged
        assertThat(stringCells(bytes, "Users")).contains("이름", "Age");
    }

    /**
     * Loads a UTF-8 {@code .properties} bundle from the test classpath. Java 8's default
     * {@code ResourceBundle.getBundle} decodes {@code .properties} as ISO-8859-1, which mangles the
     * Korean values, so we read the stream as UTF-8 explicitly via {@link PropertyResourceBundle}.
     */
    private static ResourceBundle utf8PropertiesBundle(final String resource) throws IOException {
        try (InputStream is = PxlExcelExporterTests.class.getResourceAsStream(resource);
             InputStreamReader reader = new InputStreamReader(Objects.requireNonNull(is), StandardCharsets.UTF_8)) {
            return new PropertyResourceBundle(reader);
        }
    }

    @ParameterizedTest
    @EnumSource(Dest.class)
    void exportResourceBundle_loadedFromPropertiesFile_translatesColumnHeadersOnEveryDestination(final Dest dest)
            throws PxlException, IOException {
        // src/test/resources/messages_ko.properties maps Name/Age -> 이름/나이 (read as UTF-8)
        final ResourceBundle bundle = utf8PropertiesBundle("/messages_ko.properties");

        final PxlExportWorkbookOption option = PxlExportWorkbookOption.builder()
                .exportResourceBundle(bundle)
                .build();

        final byte[] bytes = emit(pxlSpring.exportExcel()
                .sheet(TestUser.class, users(), "Users")
                .override(option), dest);

        assertThat(stringCells(bytes, "Users"))
                .contains("이름", "나이")
                .doesNotContain("Name", "Age");
    }

    @ParameterizedTest
    @EnumSource(Dest.class)
    void exportResourceBundle_roundTripsWithMatchingImportBundleOnEveryDestination(final Dest dest)
            throws PxlException, IOException {
        final PxlExportWorkbookOption exportOption = PxlExportWorkbookOption.builder()
                .exportResourceBundle(koreanColumnBundle(true))
                .build();

        final byte[] bytes = emit(pxlSpring.exportExcel()
                .sheet(TestUser.class, users(), "Users")
                .override(exportOption), dest);

        // with translated headers, re-import must translate the same way to bind columns back to fields
        final PxlImportWorkbookOption importOption = PxlImportWorkbookOption.builder()
                .importResourceBundle(koreanColumnBundle(true))
                .build();
        @SuppressWarnings("unchecked") final List<TestUser> back =
                new Pxl().importExcel()
                        .override(importOption)
                        .sheet(TestUser.class, List.class, Collections.singletonList("Users"))
                        .fromStream(new ByteArrayInputStream(bytes));

        assertThat(back).extracting(TestUser::getName).containsExactly("Alice", "Bob");
        assertThat(back).extracting(TestUser::getAge).containsExactly(30, 25);
    }

    // ----- failure path (Option A: response untouched on generation failure) -----

    @Test
    void exportToResponse_whenGenerationFails_leavesResponseUntouched() {
        final MockHttpServletResponse response = new MockHttpServletResponse();

        final TestWorkbook workbook = new TestWorkbook();
        workbook.setUsers(users());

        // the workbook and sheet source forms are mutually exclusive, and the core builder only detects that
        // while generating - i.e. at terminal time. In the pre-Option-A code the download headers were
        // already written by then; now the response must be left untouched.
        assertThatThrownBy(() -> pxlSpring.exportExcel()
                .sheet(TestUser.class, users(), "Users")
                .workbook(workbook)
                .toResponse(response, "data"))
                .isInstanceOf(PxlException.class);

        assertThat(response.getHeader(HttpHeaders.CONTENT_DISPOSITION)).isNull();
        assertThat(response.getContentType()).isNull();
        assertThat(response.getContentAsByteArray()).isEmpty();
    }

    @Test
    void exportRawWorkbookToFile_underMissingDirectory_throwsPxlIOException() throws IOException {
        try (Workbook workbook = new XSSFWorkbook()) {
            workbook.createSheet("S").createRow(0).createCell(0).setCellValue("hi");
            // parent directory does not exist -> FileOutputStream throws FileNotFoundException, which the
            // exporter wraps as PxlIOException
            final File unwritable = new File("target/no-such-dir-for-pxl/x.xlsx");

            assertThatThrownBy(() -> pxlSpring.exportExcel()
                    .poiWorkbook(workbook)
                    .toFile(unwritable))
                    .isInstanceOf(PxlIOException.class);
        }
    }

    @Test
    void exportToResponse_replacesPreexistingDownloadHeaders() throws PxlException {
        final MockHttpServletResponse response = new MockHttpServletResponse();
        // headers already present (e.g. set by a filter or MVC default before the export runs)
        response.addHeader(HttpHeaders.CONTENT_DISPOSITION, "inline");
        response.setContentType("text/html");

        pxlSpring.exportExcel()
                .sheet(TestUser.class, users(), "Users")
                .toResponse(response, "data");

        // Content-Disposition must be replaced, not appended - a second value would corrupt the download
        assertThat(response.getHeaders(HttpHeaders.CONTENT_DISPOSITION)).hasSize(1);
        assertThat(response.getHeader(HttpHeaders.CONTENT_DISPOSITION)).contains("data.xlsx");
        // Content-Type reflects the export as a single value
        assertThat(response.getHeaders(HttpHeaders.CONTENT_TYPE)).hasSize(1);
        assertThat(response.getContentType()).isNotEqualTo("text/html");
    }

    // ----- builder-only source guards (combinations no overload could previously express) -----

    @Test
    void noSourceConfigured_throwsPxlArgument() {
        assertThatThrownBy(() ->
                pxlSpring.exportExcel().toStream(new ByteArrayOutputStream()))
                .isInstanceOf(PxlArgumentException.class);
    }

    @Test
    void poiWorkbookCombinedWithSheet_throwsPxlArgument() throws IOException {
        try (Workbook workbook = new XSSFWorkbook()) {
            assertThatThrownBy(() -> pxlSpring.exportExcel()
                    .sheet(TestUser.class, users(), "Users")
                    .poiWorkbook(workbook)
                    .toStream(new ByteArrayOutputStream()))
                    .isInstanceOf(PxlArgumentException.class);
        }
    }

    @Test
    void poiWorkbookCombinedWithWorkbookObject_throwsPxlArgument() throws IOException {
        // the exclusivity guard ORs two conditions; the sheet half is covered above, this is the other one
        final TestWorkbook workbookObject = new TestWorkbook();
        workbookObject.setUsers(users());

        try (Workbook workbook = new XSSFWorkbook()) {
            assertThatThrownBy(() -> pxlSpring.exportExcel()
                    .workbook(workbookObject)
                    .poiWorkbook(workbook)
                    .toStream(new ByteArrayOutputStream()))
                    .isInstanceOf(PxlArgumentException.class);
        }
    }

    // ----- toResponseStreaming(...) -----

    @Test
    void streamingToResponse_writesTheSameBytesWithoutContentLength() throws PxlException, IOException {
        final MockHttpServletResponse buffered = new MockHttpServletResponse();
        pxlSpring.exportExcel().sheet(TestUser.class, users(), "Users").toResponse(buffered, "data");

        final MockHttpServletResponse streamed = new MockHttpServletResponse();
        pxlSpring.exportExcel().sheet(TestUser.class, users(), "Users")
                .toResponseStreaming(streamed, "data");

        // same download, same headers - the only difference is that the size is not known up front, so the
        // streaming response goes out chunked
        assertThat(isXlsx(streamed.getContentAsByteArray())).isTrue();
        assertThat(streamed.getHeader(HttpHeaders.CONTENT_DISPOSITION))
                .isEqualTo(buffered.getHeader(HttpHeaders.CONTENT_DISPOSITION));
        assertThat(streamed.getContentType()).isEqualTo(buffered.getContentType());

        assertThat(buffered.getHeader(HttpHeaders.CONTENT_LENGTH)).isNotNull();
        assertThat(streamed.getHeader(HttpHeaders.CONTENT_LENGTH)).isNull();
    }

    @Test
    void streamingSetsHeadersBeforeTheCoreValidates() {
        // A duplicate sheet name is the core's call, made inside its own terminal - which on the streaming
        // path is *after* the headers have gone out. So the export still throws and still writes no body, but
        // the download headers are already on the response. That is the documented cost of this terminal.
        // The buffered one has no such window: it generates into a buffer and only then touches the response.
        final MockHttpServletResponse streamed = new MockHttpServletResponse();

        assertThatThrownBy(() -> pxlSpring.exportExcel()
                .sheet(TestUser.class, users(), "Users")
                .sheet(TestUser.class, users(), "Users")
                .toResponseStreaming(streamed, "data"))
                .isInstanceOf(PxlDataException.class);

        assertThat(streamed.getContentAsByteArray()).isEmpty();
        assertThat(streamed.getHeader(HttpHeaders.CONTENT_DISPOSITION)).isNotNull();

        final MockHttpServletResponse buffered = new MockHttpServletResponse();

        assertThatThrownBy(() -> pxlSpring.exportExcel()
                .sheet(TestUser.class, users(), "Users")
                .sheet(TestUser.class, users(), "Users")
                .toResponse(buffered, "data"))
                .isInstanceOf(PxlDataException.class);

        assertThat(buffered.getContentAsByteArray()).isEmpty();
        assertThat(buffered.getHeader(HttpHeaders.CONTENT_DISPOSITION)).isNull();
    }

    @Test
    void nullPoiWorkbook_throwsPxlNullPointerOnEveryOverload() {
        // the one-arg overload delegates to the two-arg one, so the guard sits in a single place
        assertThatThrownBy(() -> pxlSpring.exportExcel().poiWorkbook(null))
                .isInstanceOf(PxlNullPointerException.class);

        assertThatThrownBy(() -> pxlSpring.exportExcel().poiWorkbook(null, "secret"))
                .isInstanceOf(PxlNullPointerException.class);
    }

    @Test
    void nullDestinationOnPlainComponent_throwsPxlNullPointerNotRawNpe() {
        // this component is a plain instance, so @NotNull never fires (no Spring proxy). Through a proxy the
        // same calls raise ConstraintViolationException - that half is pinned by PxlValidationTests.
        assertThatThrownBy(() -> pxlSpring.exportExcel()
                .sheet(TestUser.class, users(), "Users").toStream(null))
                .isInstanceOf(PxlNullPointerException.class);

        assertThatThrownBy(() -> pxlSpring.exportExcel()
                .sheet(TestUser.class, users(), "Users").toFile(null))
                .isInstanceOf(PxlNullPointerException.class);

        assertThatThrownBy(() -> pxlSpring.exportExcel()
                .sheet(TestUser.class, users(), "Users").toResponse(null, null))
                .isInstanceOf(PxlNullPointerException.class);

        assertThatThrownBy(() -> pxlSpring.exportExcel()
                .sheet(TestUser.class, users(), "Users").toResponseStreaming(null, null))
                .isInstanceOf(PxlNullPointerException.class);
    }

    @Test
    void nullFileOnPlainComponent_withPoiWorkbookSource_throwsPxlNullPointerNotRawNpe() throws IOException {
        // the poiWorkbook branch opens a FileOutputStream itself rather than delegating to the core builder,
        // so before the guard this was the one destination that dereferenced the null file directly
        try (Workbook workbook = new XSSFWorkbook()) {
            workbook.createSheet("S").createRow(0).createCell(0).setCellValue("hi");

            assertThatThrownBy(() -> pxlSpring.exportExcel().poiWorkbook(workbook).toFile(null))
                    .isInstanceOf(PxlNullPointerException.class);
        }
    }

    // ----- OutputStream (the caller keeps ownership of the stream) -----
    // Destination-bound by intent, so a plain @Test rather than a Dest sweep: toStream is the one terminal
    // handed a stream somebody else opened, and its javadoc promises it back open. Both halves of
    // generateToStream get their own, because only one of them goes through the core builder.

    @Test
    void toStream_doesNotCloseGivenStream() throws PxlException {
        final ClosingTrackedStream tracking = new ClosingTrackedStream();

        pxlSpring.exportExcel()
                .sheet(TestUser.class, users(), "Users")
                .toStream(tracking);

        assertThat(tracking.isClosed()).as("caller's stream must be left open").isFalse();
        // and the workbook is complete regardless: the core flushes what it wrote
        assertThat(isXlsx(tracking.written())).isTrue();
    }

    @Test
    void toStream_withPoiWorkbookSource_doesNotCloseGivenStream() throws PxlException, IOException {
        // the raw POI form writes the workbook itself rather than handing the stream to the core builder,
        // so the promise rests on a different call and needs its own guard
        final ClosingTrackedStream tracking = new ClosingTrackedStream();

        try (Workbook workbook = new XSSFWorkbook()) {
            workbook.createSheet("S").createRow(0).createCell(0).setCellValue("hi");

            pxlSpring.exportExcel()
                    .poiWorkbook(workbook)
                    .toStream(tracking);
        }

        assertThat(tracking.isClosed()).as("caller's stream must be left open").isFalse();
        assertThat(isXlsx(tracking.written())).isTrue();
    }

    // ----- source x destination matrix -----
    // The fluent builder makes every source form reachable from every terminal, which the old fixed-arity
    // overloads did not. These sweep each source across all four destinations so no pairing is left untried.
    // Everything that asserts on the exported bytes lives here rather than picking one terminal: the bytes
    // must not depend on where they are written, and a per-destination test could only ever show one of them.
    // What stays a plain @Test is what a stream or a file cannot answer for - download headers, filename
    // resolution, the response left untouched on failure - plus toResponseStreaming, which needs a live
    // response to assert headers on and so keeps its own tests above.

    /**
     * The four terminal destinations, swept by the matrix tests below.
     */
    enum Dest {
        STREAM, FILE, RESPONSE, RESPONSE_ENTITY
    }

    /**
     * Runs the configured builder against the given destination and returns the bytes it produced, so one
     * assertion can serve every destination. File artifacts are named per destination to stay inspectable.
     */
    private byte[] emit(final PxlExcelExporter.Builder builder, final Dest dest) throws PxlException, IOException {
        switch (dest) {
            case STREAM: {
                final ByteArrayOutputStream baos = new ByteArrayOutputStream();
                builder.toStream(baos);
                return baos.toByteArray();
            }
            case FILE: {
                final File file = TestPaths.exportFile(testInfo.getTestMethod()
                        .orElseThrow(IllegalStateException::new).getName() + "-" + dest + ".xlsx");
                builder.toFile(file);
                return Files.readAllBytes(file.toPath());
            }
            case RESPONSE: {
                final MockHttpServletResponse response = new MockHttpServletResponse();
                builder.toResponse(response, null);
                return response.getContentAsByteArray();
            }
            default: {
                return bodyBytes(builder.toResponseEntity(null));
            }
        }
    }

    /**
     * The three destinations that carry download headers. A stream and a file have none to look at, which is
     * why the header tests cannot ride on {@link Dest} - and why {@code toResponseStreaming}, absent from
     * that enum, belongs in this one: it resolves the filename through the same {@code resolveFilename}.
     */
    enum Download {
        RESPONSE, RESPONSE_STREAMING, RESPONSE_ENTITY
    }

    /**
     * Runs the configured builder against the given download destination and returns the
     * {@code Content-Disposition} it set, so one assertion can serve all three.
     */
    private String contentDisposition(final PxlExcelExporter.Builder builder, final Download dest,
                                      final String filename) throws PxlException {
        switch (dest) {
            case RESPONSE: {
                final MockHttpServletResponse response = new MockHttpServletResponse();
                builder.toResponse(response, filename);
                return response.getHeader(HttpHeaders.CONTENT_DISPOSITION);
            }
            case RESPONSE_STREAMING: {
                final MockHttpServletResponse response = new MockHttpServletResponse();
                builder.toResponseStreaming(response, filename);
                return response.getHeader(HttpHeaders.CONTENT_DISPOSITION);
            }
            default: {
                return builder.toResponseEntity(filename)
                        .getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION);
            }
        }
    }

    @ParameterizedTest
    @EnumSource(Dest.class)
    void singleSheetSource_roundTripsOnEveryDestination(final Dest dest) throws PxlException, IOException {
        final byte[] bytes = emit(pxlSpring.exportExcel()
                .sheet(TestUser.class, users(), "Users"), dest);

        assertThat(isXlsx(bytes)).isTrue();
        assertThat(reimportSheet(bytes, "Users")).extracting(TestUser::getName).containsExactly("Alice", "Bob");
    }

    @ParameterizedTest
    @EnumSource(Dest.class)
    void workbookObjectSource_roundTripsOnEveryDestination(final Dest dest) throws PxlException, IOException {
        final TestWorkbook workbook = new TestWorkbook();
        workbook.setWorkbookName("Report");
        workbook.setUsers(users());

        final byte[] bytes = emit(pxlSpring.exportExcel().workbook(workbook), dest);

        assertThat(reimportSheet(bytes, "Users")).extracting(TestUser::getName).containsExactly("Alice", "Bob");
    }

    @ParameterizedTest
    @EnumSource(Dest.class)
    void repeatedSheetSource_roundTripsOnEveryDestination(final Dest dest) throws PxlException, IOException {
        // repeating sheet(...) is the only way to build a multi-sheet export from row collections: every call
        // appends one sheet, none replaces the previous one, and the workbook keeps the call order - on every
        // destination alike, which is why three sheets are worth the sweep rather than two
        final byte[] bytes = emit(pxlSpring.exportExcel()
                .sheet(TestUser.class, users(), "Users")
                .sheet(TestUser.class, admins(), "Admins")
                .sheet(TestUser.class, guests(), "Guests"), dest);

        assertThat(sheetNames(bytes)).containsExactly("Users", "Admins", "Guests");
        assertThat(reimportSheet(bytes, "Users")).extracting(TestUser::getName).containsExactly("Alice", "Bob");
        assertThat(reimportSheet(bytes, "Admins")).extracting(TestUser::getName).containsExactly("Carol");
        assertThat(reimportSheet(bytes, "Guests")).extracting(TestUser::getName).containsExactly("Dave");
    }

    @ParameterizedTest
    @EnumSource(Dest.class)
    void poiWorkbookSource_writesReadableXlsxOnEveryDestination(final Dest dest) throws PxlException, IOException {
        try (Workbook workbook = new XSSFWorkbook()) {
            workbook.createSheet("S").createRow(0).createCell(0).setCellValue("hi");

            final byte[] bytes = emit(pxlSpring.exportExcel()
                    .poiWorkbook(workbook), dest);

            assertThat(isXlsx(bytes)).isTrue();
        }
    }

    // ----- override(...) on the non-response destinations -----
    // The response destinations already assert that an HSSF option switches the download extension; these
    // pin the effect the option has on the bytes themselves, which stream/file destinations never checked.

    @ParameterizedTest
    @EnumSource(Dest.class)
    void hssfOption_producesOle2BodyOnEveryDestination(final Dest dest) throws PxlException, IOException {
        final PxlExportWorkbookOption option = PxlExportWorkbookOption.builder()
                .exportExcelEngine(PxlExcelEngine.HSSF)
                .build();

        final byte[] bytes = emit(pxlSpring.exportExcel()
                .sheet(TestUser.class, users(), "Users")
                .override(option), dest);

        // .xls is an OLE2 compound file, not a ZIP container
        assertThat(isXlsx(bytes)).isFalse();
        assertThat(reimportSheet(bytes, "Users")).extracting(TestUser::getName).containsExactly("Alice", "Bob");
    }

    @ParameterizedTest
    @EnumSource(Dest.class)
    void passwordOption_producesEncryptedBodyOnEveryDestination(final Dest dest) throws PxlException, IOException {
        final PxlExportWorkbookOption option = PxlExportWorkbookOption.builder()
                .exportPassword("secret")
                .build();

        final byte[] bytes = emit(pxlSpring.exportExcel()
                .sheet(TestUser.class, users(), "Users")
                .override(option), dest);

        // an encrypted workbook is an OLE2 container, not a bare ZIP
        assertThat(isXlsx(bytes)).isFalse();

        final PxlImportWorkbookOption importOption = PxlImportWorkbookOption.builder()
                .importPassword("secret")
                .build();
        @SuppressWarnings("unchecked") final List<TestUser> back = new Pxl().importExcel()
                .override(importOption)
                .sheet(TestUser.class, List.class, Collections.singletonList("Users"))
                .fromStream(new ByteArrayInputStream(bytes));

        assertThat(back).extracting(TestUser::getName).containsExactly("Alice", "Bob");
    }

    // ----- poiWorkbook password(...) -----
    // The raw-workbook form carries its own password(...) rather than going through the export option;
    // before these tests the builder's poiPassword was only ever exercised as null.

    @ParameterizedTest
    @EnumSource(Dest.class)
    void poiWorkbookPassword_encryptsBodyOnEveryDestination(final Dest dest) throws PxlException, IOException {
        try (Workbook workbook = new XSSFWorkbook()) {
            workbook.createSheet("S").createRow(0).createCell(0).setCellValue("hi");

            final byte[] bytes = emit(pxlSpring.exportExcel()
                    .poiWorkbook(workbook, "secret"), dest);

            // encryption wraps the XLSX in an OLE2 container, so the bare-ZIP signature is gone
            assertThat(isXlsx(bytes)).isFalse();
            assertThat(bytes).isNotEmpty();
        }
    }

    // ----- download headers x response destination -----
    // A filename and an extension are only observable where headers are, so these sweep Download rather than
    // Dest. All three terminals resolve the name through the same resolveFilename(String) and the format
    // through the same resolveFileFormat(), which is exactly what a per-terminal test could not show - and
    // toResponseStreaming, which the Dest matrix cannot reach, is swept here alongside the other two.

    @ParameterizedTest
    @EnumSource(Download.class)
    void theWorkbookNameFillsInForABlankFilename(final Download dest) throws PxlException {
        // resolveFilename(): an explicit name outranks the @PxlWorkbookName value, which in turn only fills
        // in for a blank one - and with neither, the "Pxl" constant is what is left
        final TestWorkbook named = new TestWorkbook();
        named.setWorkbookName("Report");
        named.setUsers(users());

        assertThat(contentDisposition(pxlSpring.exportExcel().workbook(named), dest, "explicit"))
                .contains("explicit.xlsx")
                .doesNotContain("Report");
        assertThat(contentDisposition(pxlSpring.exportExcel().workbook(named), dest, null))
                .contains("Report.xlsx");

        final TestWorkbook unnamed = new TestWorkbook();  // no workbook name
        unnamed.setUsers(users());

        assertThat(contentDisposition(pxlSpring.exportExcel().workbook(unnamed), dest, null))
                .contains("Pxl.xlsx");
    }

    @ParameterizedTest
    @EnumSource(Download.class)
    void aBlankFilenameOnASheetSource_fallsBackToPxl(final Download dest) throws PxlException {
        // the sheet source carries no name of its own, so a blank one goes straight to the constant - for one
        // sheet and for several alike
        assertThat(contentDisposition(pxlSpring.exportExcel()
                .sheet(TestUser.class, users(), "Users"), dest, null))
                .contains("Pxl.xlsx");

        assertThat(contentDisposition(pxlSpring.exportExcel()
                .sheet(TestUser.class, users(), "Users")
                .sheet(TestUser.class, admins(), "Admins"), dest, null))
                .contains("Pxl.xlsx");
    }

    @ParameterizedTest
    @EnumSource(Download.class)
    void optionExcelEngine_drivesTheDownloadExtension(final Download dest) throws PxlException {
        // the option.getExportExcelEngine() branch: HSSF moves the download to .xls, while SXSSF must not
        // move it off .xlsx - the engine picks a writer, not a format, and the two OOXML writers share one
        final PxlExportWorkbookOption hssfOption = PxlExportWorkbookOption.builder()
                .exportExcelEngine(PxlExcelEngine.HSSF)
                .build();
        final PxlExportWorkbookOption sxssfOption = PxlExportWorkbookOption.builder()
                .exportExcelEngine(PxlExcelEngine.SXSSF)
                .build();

        assertThat(contentDisposition(pxlSpring.exportExcel()
                .sheet(TestUser.class, users(), "Users")
                .override(hssfOption), dest, "data"))
                .endsWith("data.xls");

        assertThat(contentDisposition(pxlSpring.exportExcel()
                .sheet(TestUser.class, users(), "Users")
                .override(sxssfOption), dest, "data"))
                .endsWith("data.xlsx");

        // the workbook-object source resolves its format through the same call
        final TestWorkbook workbook = new TestWorkbook();
        workbook.setWorkbookName("Report");
        workbook.setUsers(users());

        assertThat(contentDisposition(pxlSpring.exportExcel()
                .workbook(workbook)
                .override(hssfOption), dest, null))
                .endsWith("Report.xls");
    }

    @ParameterizedTest
    @EnumSource(Download.class)
    void poiWorkbookHeaders_followTheWorkbookType(final Download dest) throws PxlException, IOException {
        // there is nothing to configure: PxlFileFormat.fromPoiWorkbook reads the type off the workbook, so
        // the announced extension is the one its body is written in. SXSSFWorkbook wraps rather than extends
        // XSSFWorkbook and is therefore its own PxlExcelEngine, but both write the same OOXML container -
        // one PxlFileFormat - so it announces .xlsx too. Encryption changes none of that: it wraps the bytes
        // in an OLE2 container whatever the type, and an encrypted OOXML file is still distributed as .xlsx.
        try (Workbook xssf = new XSSFWorkbook()) {
            xssf.createSheet("S").createRow(0).createCell(0).setCellValue("hi");
            assertThat(contentDisposition(pxlSpring.exportExcel().poiWorkbook(xssf), dest, "raw"))
                    .endsWith("raw.xlsx");
        }

        try (Workbook hssf = new HSSFWorkbook()) {
            hssf.createSheet("S").createRow(0).createCell(0).setCellValue("hi");
            assertThat(contentDisposition(pxlSpring.exportExcel().poiWorkbook(hssf), dest, "raw"))
                    .endsWith("raw.xls");
        }

        try (SXSSFWorkbook sxssf = new SXSSFWorkbook()) {
            sxssf.createSheet("S").createRow(0).createCell(0).setCellValue("hi");
            assertThat(contentDisposition(pxlSpring.exportExcel().poiWorkbook(sxssf), dest, "raw"))
                    .endsWith("raw.xlsx");
        }

        try (Workbook locked = new XSSFWorkbook()) {
            locked.createSheet("S").createRow(0).createCell(0).setCellValue("hi");
            assertThat(contentDisposition(pxlSpring.exportExcel().poiWorkbook(locked, "secret"), dest, "raw"))
                    .endsWith("raw.xlsx");
        }
    }

    @ParameterizedTest
    @EnumSource(Download.class)
    void aBlankFilenameOnARawPoiWorkbook_fallsBackToPxl(final Download dest) throws PxlException, IOException {
        // the raw form has no name to fall back on either - only the format comes from the workbook
        try (Workbook workbook = new XSSFWorkbook()) {
            workbook.createSheet("S").createRow(0).createCell(0).setCellValue("hi");

            assertThat(contentDisposition(pxlSpring.exportExcel().poiWorkbook(workbook), dest, null))
                    .contains("Pxl.xlsx");
        }
    }

    @ParameterizedTest
    @EnumSource(Download.class)
    void koreanFilename_isRfc5987PercentEncoded(final Download dest) throws PxlException {
        // the ASCII fallback comes first (it is what a parser reading only the first parameter should get),
        // and the real name rides in the percent-encoded filename* - assembled in one place, so all three
        // terminals must produce the byte-identical header
        assertThat(contentDisposition(pxlSpring.exportExcel()
                .sheet(TestUser.class, users(), "사용자"), dest, "보고서"))
                .isEqualTo("attachment; filename=\"___.xlsx\"; filename*=UTF-8''%EB%B3%B4%EA%B3%A0%EC%84%9C.xlsx")
                .doesNotContain("보고서");
    }

    // ----- builder call-order independence -----

    @Test
    void optionsBeforeSource_behaveTheSameAsAfter() throws PxlException {
        final PxlExportWorkbookOption option = PxlExportWorkbookOption.builder()
                .exportExcelEngine(PxlExcelEngine.HSSF)
                .build();

        final ResponseEntity<Resource> optionsFirst = pxlSpring.exportExcel()
                .override(option)
                .sheet(TestUser.class, users(), "Users")
                .toResponseEntity("data");

        final ResponseEntity<Resource> optionsLast = pxlSpring.exportExcel()
                .sheet(TestUser.class, users(), "Users")
                .override(option)
                .toResponseEntity("data");

        assertThat(optionsFirst.getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION))
                .isEqualTo(optionsLast.getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION))
                .endsWith("data.xls");
    }

    // ----- option precedence within one chain -----
    // Each option is a plain last-write-wins slot, and the two resolvers rank their inputs; these pin the
    // rankings that the single-option tests above cannot show.

    @Test
    void repeatedOverrideAndFilename_lastValueWins() throws PxlException {
        final PxlExportWorkbookOption hssfOption = PxlExportWorkbookOption.builder()
                .exportExcelEngine(PxlExcelEngine.HSSF)
                .build();
        final PxlExportWorkbookOption xssfOption = PxlExportWorkbookOption.builder()
                .exportExcelEngine(PxlExcelEngine.XSSF)
                .build();

        final MockHttpServletResponse response = new MockHttpServletResponse();
        pxlSpring.exportExcel()
                .sheet(TestUser.class, users(), "Users")
                .override(hssfOption)
                .override(xssfOption)
                .toResponse(response, "second");

        // had the first call won, this would be first.xls with an OLE2 body
        assertThat(response.getHeader(HttpHeaders.CONTENT_DISPOSITION)).contains("second.xlsx");
        assertThat(isXlsx(response.getContentAsByteArray())).isTrue();
    }

    @Test
    void overrideWithNull_clearsAnOptionSetEarlierInTheChain() throws PxlException {
        // override(...) is documented as taking null, and the slot is plain last-write-wins - so a null must
        // clear the earlier option rather than be ignored as "no change". It has to clear it in both places
        // the builder keeps it: the core builder, which writes the body, and the workbookOption field, which
        // resolveFileFormat() reads for the download extension.
        final PxlExportWorkbookOption hssfOption = PxlExportWorkbookOption.builder()
                .exportExcelEngine(PxlExcelEngine.HSSF)
                .build();

        final MockHttpServletResponse response = new MockHttpServletResponse();
        pxlSpring.exportExcel()
                .sheet(TestUser.class, users(), "Users")
                .override(hssfOption)
                .override(null)
                .toResponse(response, "cleared");

        // had the HSSF option survived, this would be cleared.xls with an OLE2 body
        assertThat(response.getHeader(HttpHeaders.CONTENT_DISPOSITION)).contains("cleared.xlsx");
        assertThat(isXlsx(response.getContentAsByteArray())).isTrue();
    }

    @Test
    void workbookObjectDeclaredHssfEngine_appliesWithoutAnyOption() throws PxlException {
        // resolveFileFormat(): with no exportExcelEngine on the option the class's own @PxlWorkbook setting
        // decides, both for the body and for the download headers
        final TestHssfWorkbook workbook = new TestHssfWorkbook();
        workbook.setWorkbookName("Declared");
        workbook.setUsers(users());

        final MockHttpServletResponse response = new MockHttpServletResponse();
        pxlSpring.exportExcel()
                .workbook(workbook)
                .toResponse(response, null);

        assertThat(response.getHeader(HttpHeaders.CONTENT_DISPOSITION)).contains("Declared.xls");
        assertThat(isXlsx(response.getContentAsByteArray())).isFalse();
    }

    @Test
    void poiWorkbookSource_ignoresOverrideOption() throws PxlException, IOException {
        // the raw form writes the workbook as-is, so the export option reaches neither the body nor the
        // headers - both come from the workbook itself, which is XSSF here despite the HSSF option
        final PxlExportWorkbookOption hssfOption = PxlExportWorkbookOption.builder()
                .exportExcelEngine(PxlExcelEngine.HSSF)
                .build();

        try (Workbook workbook = new XSSFWorkbook()) {
            workbook.createSheet("S").createRow(0).createCell(0).setCellValue("hi");

            final MockHttpServletResponse response = new MockHttpServletResponse();
            pxlSpring.exportExcel()
                    .poiWorkbook(workbook)
                    .override(hssfOption)
                    .toResponse(response, "raw");

            assertThat(response.getHeader(HttpHeaders.CONTENT_DISPOSITION)).contains("raw.xlsx");
            assertThat(isXlsx(response.getContentAsByteArray())).isTrue();
        }
    }
}
