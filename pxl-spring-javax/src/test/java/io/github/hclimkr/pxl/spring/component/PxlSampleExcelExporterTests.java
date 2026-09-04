package io.github.hclimkr.pxl.spring.component;

import io.github.hclimkr.pxl.exception.PxlArgumentException;
import io.github.hclimkr.pxl.exception.PxlException;
import io.github.hclimkr.pxl.exception.PxlNullPointerException;
import io.github.hclimkr.pxl.option.PxlExportWorkbookOption;
import io.github.hclimkr.pxl.spring.PxlSpring;
import io.github.hclimkr.pxl.spring.tcdata.TestHssfWorkbook;
import io.github.hclimkr.pxl.spring.tcdata.TestPaths;
import io.github.hclimkr.pxl.spring.tcdata.TestUser;
import io.github.hclimkr.pxl.spring.tcdata.TestWorkbook;
import io.github.hclimkr.pxl.type.PxlExcelEngine;
import org.apache.poi.ss.usermodel.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletResponse;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.HashSet;
import java.util.Set;

import static io.github.hclimkr.pxl.spring.component.PxlExcelExporterTests.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Behavioural tests for {@link PxlSampleExcelExporter}, all driven through the
 * {@link PxlSampleExcelExporter.Builder} fluent API: template generation for both source forms
 * (workbook class, one or more sheets) across every destination (stream / file / response / streaming
 * response / response-entity), export options, the default {@code PxlSample} file name, and column headers.
 * The template carries a single sample data row populated from each column's
 * {@code @PxlColumn(exportSample = ...)} value.
 *
 * <p>The builder comes from {@link PxlSpring}, the entry point the documentation guides users to. The
 * facade hands back this component's own builder, so what is exercised here is still the component.</p>
 *
 * <p>Every destination is swept rather than spot-checked, through one of two enums: assertions about the
 * generated template are {@code @ParameterizedTest}s over {@link Dest}, and assertions about the download
 * headers are the same thing on the other axis, over {@link Download} - the three terminals that have
 * headers at all, the streaming response included. What stays a plain {@code @Test} is the guards, the
 * entity-only invariants, and the comparisons one terminal makes against another.</p>
 */
class PxlSampleExcelExporterTests {

    private final PxlSpring pxlSpring = new PxlSpring();

    private TestInfo testInfo;

    @BeforeEach
    void bindTestInfo(final TestInfo testInfo) {
        this.testInfo = testInfo;
    }

    private static Set<String> stringCells(final byte[] bytes) throws IOException {
        final Set<String> values = new HashSet<>();
        try (Workbook workbook = WorkbookFactory.create(new ByteArrayInputStream(bytes))) {
            for (final Sheet sheet : workbook) {
                for (final Row row : sheet) {
                    for (final Cell cell : row) {
                        if (cell.getCellType() == CellType.STRING) {
                            values.add(cell.getStringCellValue());
                        }
                    }
                }
            }
        }
        return values;
    }

    // ----- ResponseEntity<Resource> -----

    @Test
    void sampleWorkbookClassToResponseEntity_usesDefaultFilename() throws PxlException {
        // the entity terminal alone: the body is a view over the download buffer rather than a copy of it,
        // so the length the header carries and the length the body reads out come from two different places
        final ResponseEntity<Resource> entity = pxlSpring.exportSampleExcel()
                .workbook(TestWorkbook.class)
                .toResponseEntity(null);

        assertThat(entity.getStatusCode().value()).isEqualTo(200);
        assertThat(entity.getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION)).contains("PxlSample.xlsx");
        assertThat(entity.getHeaders().getContentLength()).isEqualTo(bodyBytes(entity).length);
        assertThat(isXlsx(bodyBytes(entity))).isTrue();
    }

    // ----- builder source guards (delegated to the core builder) -----

    @Test
    void noSourceConfigured_throwsPxlArgument() {
        assertThatThrownBy(() ->
                pxlSpring.exportSampleExcel().toStream(new ByteArrayOutputStream()))
                .isInstanceOf(PxlArgumentException.class);
    }

    @Test
    void workbookClassCombinedWithSheet_throwsPxlArgument() {
        assertThatThrownBy(() -> pxlSpring.exportSampleExcel()
                .workbook(TestWorkbook.class)
                .sheet(TestUser.class, "Users")
                .toStream(new ByteArrayOutputStream()))
                .isInstanceOf(PxlArgumentException.class);
    }

    @Test
    void nullWorkbookClass_throwsPxlNullPointer() {
        assertThatThrownBy(() -> pxlSpring.exportSampleExcel().workbook(null))
                .isInstanceOf(PxlNullPointerException.class);
    }

    // ----- toResponseStreaming(...) -----

    @Test
    void streamingToResponse_writesTheSameBytesWithoutContentLength() throws PxlException {
        final MockHttpServletResponse buffered = new MockHttpServletResponse();
        pxlSpring.exportSampleExcel()
                .sheet(TestUser.class, "Users").toResponse(buffered, "template");

        final MockHttpServletResponse streamed = new MockHttpServletResponse();
        pxlSpring.exportSampleExcel()
                .sheet(TestUser.class, "Users")
                .toResponseStreaming(streamed, "template");

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
    void streamingSetsHeadersBeforeTheSourceIsValidated() {
        // This exporter has no component-level source check - "both or neither source" is the core builder's
        // call, made inside its terminal. On the streaming path that is *after* the headers have gone out,
        // so a rejected template still throws and writes no body, but the download headers are already on
        // the response. That is the documented cost of streaming. The buffered default has no such window.
        final MockHttpServletResponse streamed = new MockHttpServletResponse();

        assertThatThrownBy(() -> pxlSpring.exportSampleExcel()
                .toResponseStreaming(streamed, null))
                .isInstanceOf(PxlArgumentException.class);

        assertThat(streamed.getContentAsByteArray()).isEmpty();
        assertThat(streamed.getHeader(HttpHeaders.CONTENT_DISPOSITION)).isNotNull();

        final MockHttpServletResponse buffered = new MockHttpServletResponse();

        assertThatThrownBy(() -> pxlSpring.exportSampleExcel()
                .toResponse(buffered, null))
                .isInstanceOf(PxlArgumentException.class);

        assertThat(buffered.getContentAsByteArray()).isEmpty();
        assertThat(buffered.getHeader(HttpHeaders.CONTENT_DISPOSITION)).isNull();
    }

    @Test
    void blankSheetName_throwsPxlArgument() {
        assertThatThrownBy(() -> pxlSpring.exportSampleExcel().sheet(TestUser.class, "  "))
                .isInstanceOf(PxlArgumentException.class);
    }

    @Test
    void nullDestinationOnPlainComponent_throwsPxlNullPointerNotRawNpe() {
        // this component is a plain instance, so @NotNull never fires (no Spring proxy). Through a proxy the
        // same calls raise ConstraintViolationException - that half is pinned by PxlValidationTests.
        assertThatThrownBy(() -> pxlSpring.exportSampleExcel()
                .sheet(TestUser.class, "Users").toStream(null))
                .isInstanceOf(PxlNullPointerException.class);

        assertThatThrownBy(() -> pxlSpring.exportSampleExcel()
                .sheet(TestUser.class, "Users").toFile(null))
                .isInstanceOf(PxlNullPointerException.class);

        assertThatThrownBy(() -> pxlSpring.exportSampleExcel()
                .sheet(TestUser.class, "Users").toResponse(null, null))
                .isInstanceOf(PxlNullPointerException.class);

        assertThatThrownBy(() -> pxlSpring.exportSampleExcel()
                .sheet(TestUser.class, "Users").toResponseStreaming(null, null))
                .isInstanceOf(PxlNullPointerException.class);
    }

    // ----- File -----

    @Test
    void exportToFile_underMissingDirectory_staysInsidePxlException() {
        // the parent directory does not exist, so opening the destination fails. This exporter hands the file
        // to the core rather than opening it itself, so what matters here is only that no raw IOException
        // escapes the "every failure is a PxlException" contract; which subtype it is, is the core's call.
        final File unwritable = new File("target/no-such-dir-for-pxl/x.xlsx");

        assertThatThrownBy(() -> pxlSpring.exportSampleExcel()
                .sheet(TestUser.class, "Users")
                .toFile(unwritable))
                .isInstanceOf(PxlException.class);
    }

    // ----- OutputStream (the caller keeps ownership of the stream) -----

    @Test
    void toStream_doesNotCloseGivenStream() throws PxlException {
        // destination-bound by intent: toStream is the one terminal handed a stream somebody else opened,
        // and its javadoc promises it back open
        final ClosingTrackedStream tracking = new ClosingTrackedStream();

        pxlSpring.exportSampleExcel()
                .sheet(TestUser.class, "Users")
                .toStream(tracking);

        assertThat(tracking.isClosed()).as("caller's stream must be left open").isFalse();
        // and the template is complete regardless: the core flushes what it wrote
        assertThat(isXlsx(tracking.written())).isTrue();
    }

    // ----- pre-existing response headers -----

    @Test
    void exportToResponse_replacesPreexistingDownloadHeaders() throws PxlException {
        final MockHttpServletResponse response = new MockHttpServletResponse();
        // headers already present (e.g. set by a filter or MVC default before the export runs)
        response.addHeader(HttpHeaders.CONTENT_DISPOSITION, "inline");
        response.setContentType("text/html");

        pxlSpring.exportSampleExcel()
                .sheet(TestUser.class, "Users")
                .toResponse(response, "template");

        // Content-Disposition must be replaced, not appended - a second value would corrupt the download
        assertThat(response.getHeaders(HttpHeaders.CONTENT_DISPOSITION)).hasSize(1);
        assertThat(response.getHeader(HttpHeaders.CONTENT_DISPOSITION)).contains("template.xlsx");
        // Content-Type reflects the export as a single value
        assertThat(response.getHeaders(HttpHeaders.CONTENT_TYPE)).hasSize(1);
        assertThat(response.getContentType()).isNotEqualTo("text/html");
    }

    // ----- source x destination matrix -----
    // Both source forms are now reachable from all four terminals, and the fluent builder newly allows
    // several sheet(...) calls; these sweep every pairing rather than spot-checking a few. Every assertion
    // about the generated template lives here rather than picking one terminal - the template cannot depend
    // on where it is written. What stays a plain @Test is what only a response can show.

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
    private byte[] emit(final PxlSampleExcelExporter.Builder builder, final Dest dest) throws PxlException, IOException {
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
    private String contentDisposition(final PxlSampleExcelExporter.Builder builder, final Download dest,
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
    void workbookClassSource_producesTemplateOnEveryDestination(final Dest dest) throws PxlException, IOException {
        final byte[] bytes = emit(pxlSpring.exportSampleExcel()
                .workbook(TestWorkbook.class), dest);

        assertThat(isXlsx(bytes)).isTrue();
        assertThat(stringCells(bytes)).contains("Name", "Age", "Alice");
    }

    @ParameterizedTest
    @EnumSource(Dest.class)
    void singleSheetSource_producesTemplateOnEveryDestination(final Dest dest) throws PxlException, IOException {
        final byte[] bytes = emit(pxlSpring.exportSampleExcel()
                .sheet(TestUser.class, "Users"), dest);

        assertThat(isXlsx(bytes)).isTrue();
        // the template is not empty: @PxlColumn(name = "Name", exportSample = "Alice") on TestUser fills the
        // single sample data row, so "Alice" - a value, not a header - must be in the sheet as well
        assertThat(stringCells(bytes)).contains("Name", "Age", "Alice");
    }

    @ParameterizedTest
    @EnumSource(Dest.class)
    void multiSheetSource_producesEverySheetOnEveryDestination(final Dest dest) throws PxlException, IOException {
        // the fluent builder newly allows several sheet(...) calls; the old overloads were single-sheet only
        final byte[] bytes = emit(pxlSpring.exportSampleExcel()
                .sheet(TestUser.class, "Users")
                .sheet(TestUser.class, "Admins"), dest);

        assertThat(sheetNames(bytes)).containsExactly("Users", "Admins");
    }

    // ----- override(...) on the non-response destinations -----
    // The response destinations already assert that an HSSF option switches the download extension; these
    // pin the effect the option has on the bytes themselves.

    @ParameterizedTest
    @EnumSource(Dest.class)
    void hssfOption_producesOle2BodyOnEveryDestination(final Dest dest) throws PxlException, IOException {
        final PxlExportWorkbookOption option = PxlExportWorkbookOption.builder()
                .exportExcelEngine(PxlExcelEngine.HSSF)
                .build();

        final byte[] bytes = emit(pxlSpring.exportSampleExcel()
                .sheet(TestUser.class, "Users")
                .override(option), dest);

        // .xls is an OLE2 compound file, not a ZIP container
        assertThat(isXlsx(bytes)).isFalse();
        assertThat(stringCells(bytes)).contains("Name", "Age");
    }

    @ParameterizedTest
    @EnumSource(Dest.class)
    void workbookClassHssfOption_producesOle2BodyOnEveryDestination(final Dest dest) throws PxlException, IOException {
        // the workbook-class form resolves its format through a different branch than the sheet form
        final PxlExportWorkbookOption option = PxlExportWorkbookOption.builder()
                .exportExcelEngine(PxlExcelEngine.HSSF)
                .build();

        final byte[] bytes = emit(pxlSpring.exportSampleExcel()
                .workbook(TestWorkbook.class)
                .override(option), dest);

        assertThat(isXlsx(bytes)).isFalse();
    }

    // ----- download headers x response destination -----
    // A filename and an extension are only observable where headers are, so these sweep Download rather than
    // Dest. All three terminals resolve the name through the same resolveFilename(String) and the format
    // through the same resolveFileFormat() - and toResponseStreaming, which the Dest matrix cannot reach, is
    // swept here alongside the other two.

    @ParameterizedTest
    @EnumSource(Download.class)
    void theDefaultFilename_isPxlSample(final Download dest) throws PxlException {
        // unlike PxlExcelExporter there is no name to fall back through: a template describes a shape, so a
        // blank name goes straight to the constant - on both source forms, and whether blank means null or
        // whitespace. An explicit name still outranks it.
        assertThat(contentDisposition(pxlSpring.exportSampleExcel()
                .workbook(TestWorkbook.class), dest, null))
                .contains("PxlSample.xlsx");

        assertThat(contentDisposition(pxlSpring.exportSampleExcel()
                .sheet(TestUser.class, "Users"), dest, "  "))
                .contains("PxlSample.xlsx");

        assertThat(contentDisposition(pxlSpring.exportSampleExcel()
                .sheet(TestUser.class, "Users"), dest, "template"))
                .contains("template.xlsx");
    }

    @ParameterizedTest
    @EnumSource(Download.class)
    void optionExcelEngine_drivesTheDownloadExtension(final Download dest) throws PxlException {
        // the option.getExportExcelEngine() branch (the tests above take the fallback), on both source forms
        final PxlExportWorkbookOption option = PxlExportWorkbookOption.builder()
                .exportExcelEngine(PxlExcelEngine.HSSF)
                .build();

        assertThat(contentDisposition(pxlSpring.exportSampleExcel()
                .workbook(TestWorkbook.class)
                .override(option), dest, "wbTemplate"))
                .endsWith("wbTemplate.xls");

        assertThat(contentDisposition(pxlSpring.exportSampleExcel()
                .sheet(TestUser.class, "Users")
                .override(option), dest, "template"))
                .endsWith("template.xls");
    }

    @ParameterizedTest
    @EnumSource(Download.class)
    void koreanFilename_isRfc5987PercentEncoded(final Download dest) throws PxlException {
        // the header is assembled in one place, so all three terminals must produce the same one
        final String contentDisposition = contentDisposition(pxlSpring.exportSampleExcel()
                .sheet(TestUser.class, "사용자"), dest, "템플릿");

        assertThat(contentDisposition).startsWith("attachment; filename=\"___.xlsx\"; filename*=UTF-8''");
        assertThat(contentDisposition).doesNotContain("템플릿").contains("%");
    }

    // ----- builder call-order independence -----

    @Test
    void optionsBeforeSource_behaveTheSameAsAfter() throws PxlException {
        final PxlExportWorkbookOption option = PxlExportWorkbookOption.builder()
                .exportExcelEngine(PxlExcelEngine.HSSF)
                .build();

        final ResponseEntity<Resource> optionsFirst = pxlSpring.exportSampleExcel()
                .override(option)
                .sheet(TestUser.class, "Users")
                .toResponseEntity("template");

        final ResponseEntity<Resource> optionsLast = pxlSpring.exportSampleExcel()
                .sheet(TestUser.class, "Users")
                .override(option)
                .toResponseEntity("template");

        assertThat(optionsFirst.getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION))
                .isEqualTo(optionsLast.getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION))
                .endsWith("template.xls");
    }

    // ----- option precedence within one chain -----

    @Test
    void repeatedOverrideAndFilename_lastValueWins() throws PxlException {
        final PxlExportWorkbookOption hssfOption = PxlExportWorkbookOption.builder()
                .exportExcelEngine(PxlExcelEngine.HSSF)
                .build();
        final PxlExportWorkbookOption xssfOption = PxlExportWorkbookOption.builder()
                .exportExcelEngine(PxlExcelEngine.XSSF)
                .build();

        final ResponseEntity<Resource> entity = pxlSpring.exportSampleExcel()
                .sheet(TestUser.class, "Users")
                .override(hssfOption)
                .override(xssfOption)
                .toResponseEntity("second");

        // had the first call won, this would be first.xls with an OLE2 body
        assertThat(entity.getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION)).endsWith("second.xlsx");
        assertThat(isXlsx(bodyBytes(entity))).isTrue();
    }

    @Test
    void workbookClassDeclaredHssfEngine_appliesWithoutAnyOption() throws PxlException {
        // resolveFileFormat(): with no exportExcelEngine on the option the class's own @PxlWorkbook setting
        // decides - TestWorkbook carries no such annotation, so only this fixture can tell that branch apart
        final MockHttpServletResponse response = new MockHttpServletResponse();
        pxlSpring.exportSampleExcel()
                .workbook(TestHssfWorkbook.class)
                .toResponse(response, null);

        assertThat(response.getHeader(HttpHeaders.CONTENT_DISPOSITION)).contains("PxlSample.xls");
        assertThat(isXlsx(response.getContentAsByteArray())).isFalse();
    }
}
