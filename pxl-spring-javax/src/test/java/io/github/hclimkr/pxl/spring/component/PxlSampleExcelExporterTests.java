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
 * (workbook class, one or more sheets) across every destination (stream / file / response /
 * response-entity), export options, the default {@code PxlSample} file name, and column headers. The
 * template carries a single sample data row populated from each column's
 * {@code @PxlColumn(exportSample = ...)} value.
 *
 * <p>The builder comes from {@link PxlSpring}, the entry point the documentation guides users to. The
 * facade hands back this component's own builder, so what is exercised here is still the component.</p>
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

    // ----- OutputStream -----

    @Test
    void sampleWorkbookClassToStream_isValidXlsx() throws PxlException {
        final ByteArrayOutputStream baos = new ByteArrayOutputStream();
        pxlSpring.exportSampleExcel()
                .workbook(TestWorkbook.class)
                .toStream(baos);

        assertThat(isXlsx(baos.toByteArray())).isTrue();
    }

    @Test
    void sampleSingleSheetToStream_containsHeaders() throws PxlException, IOException {
        final ByteArrayOutputStream baos = new ByteArrayOutputStream();
        pxlSpring.exportSampleExcel()
                .sheet(TestUser.class, "Users")
                .toStream(baos);

        assertThat(stringCells(baos.toByteArray())).contains("Name", "Age");
    }

    @Test
    void sampleMultiSheetToStream_producesEverySheet() throws PxlException, IOException {
        // the fluent builder newly allows several sheet(...) calls; the old overloads were single-sheet only
        final ByteArrayOutputStream baos = new ByteArrayOutputStream();
        pxlSpring.exportSampleExcel()
                .sheet(TestUser.class, "Users")
                .sheet(TestUser.class, "Admins")
                .toStream(baos);

        assertThat(sheetNames(baos.toByteArray())).containsExactly("Users", "Admins");
    }

    // ----- File -----

    @Test
    void sampleWorkbookClassToFile_writesValidXlsx() throws PxlException, IOException {
        final File file = TestPaths.exportFile(testInfo);
        pxlSpring.exportSampleExcel()
                .workbook(TestWorkbook.class)
                .toFile(file);

        assertThat(file).exists();
        assertThat(isXlsx(Files.readAllBytes(file.toPath()))).isTrue();
    }

    @Test
    void sampleSingleSheetToFile_containsHeaders() throws PxlException, IOException {
        final File file = TestPaths.exportFile(testInfo);
        pxlSpring.exportSampleExcel()
                .sheet(TestUser.class, "Users")
                .toFile(file);

        assertThat(file).exists();
        assertThat(stringCells(Files.readAllBytes(file.toPath()))).contains("Name", "Age");
    }

    // ----- HttpServletResponse -----

    @Test
    void sampleWorkbookClassToResponse_writesTemplate() throws PxlException {
        final MockHttpServletResponse response = new MockHttpServletResponse();
        pxlSpring.exportSampleExcel()
                .workbook(TestWorkbook.class)
                .toResponse(response, "wbTemplate");

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(response.getHeader(HttpHeaders.CONTENT_DISPOSITION)).contains("wbTemplate.xlsx");
        assertThat(isXlsx(response.getContentAsByteArray())).isTrue();
    }

    @Test
    void sampleSingleSheetToResponse_writesTemplate() throws PxlException {
        final MockHttpServletResponse response = new MockHttpServletResponse();
        pxlSpring.exportSampleExcel()
                .sheet(TestUser.class, "Users")
                .toResponse(response, "template");

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(response.getHeader(HttpHeaders.CONTENT_DISPOSITION)).contains("template.xlsx");
        assertThat(isXlsx(response.getContentAsByteArray())).isTrue();
    }

    // ----- ResponseEntity<Resource> -----

    @Test
    void sampleWorkbookClassToResponseEntity_usesDefaultFilename() throws PxlException {
        final ResponseEntity<Resource> entity = pxlSpring.exportSampleExcel()
                .workbook(TestWorkbook.class)
                .toResponseEntity(null);

        assertThat(entity.getStatusCode().value()).isEqualTo(200);
        assertThat(entity.getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION)).contains("PxlSample.xlsx");
        // the body is a view over the download buffer rather than a copy of it, so the length the header
        // carries and the length the body reads out come from two different places and must still agree
        assertThat(entity.getHeaders().getContentLength()).isEqualTo(bodyBytes(entity).length);
        assertThat(isXlsx(bodyBytes(entity))).isTrue();
    }

    @Test
    void sampleSingleSheetToResponseEntity_usesDefaultFilename() throws PxlException {
        final ResponseEntity<Resource> entity = pxlSpring.exportSampleExcel()
                .sheet(TestUser.class, "Users")
                .toResponseEntity(null);

        assertThat(entity.getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION)).contains("PxlSample.xlsx");
        assertThat(isXlsx(bodyBytes(entity))).isTrue();
    }

    @Test
    void sampleContainsColumnHeaders() throws PxlException, IOException {
        final ResponseEntity<Resource> entity = pxlSpring.exportSampleExcel()
                .sheet(TestUser.class, "Users")
                .toResponseEntity(null);

        assertThat(stringCells(bodyBytes(entity))).contains("Name", "Age");
    }

    @Test
    void sampleContainsSampleDataRow() throws PxlException, IOException {
        final ResponseEntity<Resource> entity = pxlSpring.exportSampleExcel()
                .sheet(TestUser.class, "Users")
                .toResponseEntity(null);

        // the template is not empty: @PxlColumn(name = "Name", exportSample = "Alice") on TestUser fills
        // the single sample data row, so "Alice" (a value, not a header) must be present in the sheet.
        assertThat(stringCells(bodyBytes(entity))).contains("Alice");
    }

    @Test
    void koreanFilename_isRfc5987PercentEncoded() throws PxlException {
        final ResponseEntity<Resource> entity = pxlSpring.exportSampleExcel()
                .sheet(TestUser.class, "사용자")
                .toResponseEntity("템플릿");

        final String contentDisposition = entity.getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION);
        assertThat(contentDisposition).startsWith("attachment; filename=\"___.xlsx\"; filename*=UTF-8''");
        assertThat(contentDisposition).doesNotContain("템플릿").contains("%");
    }

    // ----- non-null option engine on the response destinations -----
    // These take the option.getExportExcelEngine() branch (the null-option tests above take the fallback),
    // so the download extension is driven by the option rather than the default/workbook engine.

    @Test
    void sampleWorkbookClassToResponse_withHssfOption_switchesExtensionToXls() throws PxlException {
        final PxlExportWorkbookOption option = PxlExportWorkbookOption.builder()
                .exportExcelEngine(PxlExcelEngine.HSSF)
                .build();

        final MockHttpServletResponse response = new MockHttpServletResponse();
        pxlSpring.exportSampleExcel()
                .workbook(TestWorkbook.class)
                .override(option)
                .toResponse(response, "wbTemplate");

        assertThat(response.getHeader(HttpHeaders.CONTENT_DISPOSITION)).endsWith("wbTemplate.xls");
        assertThat(response.getContentAsByteArray()).isNotEmpty();
    }

    @Test
    void sampleSingleSheetToResponse_withHssfOption_switchesExtensionToXls() throws PxlException {
        final PxlExportWorkbookOption option = PxlExportWorkbookOption.builder()
                .exportExcelEngine(PxlExcelEngine.HSSF)
                .build();

        final MockHttpServletResponse response = new MockHttpServletResponse();
        pxlSpring.exportSampleExcel()
                .sheet(TestUser.class, "Users")
                .override(option)
                .toResponse(response, "template");

        assertThat(response.getHeader(HttpHeaders.CONTENT_DISPOSITION)).endsWith("template.xls");
        assertThat(response.getContentAsByteArray()).isNotEmpty();
    }

    @Test
    void sampleWorkbookClassToResponseEntity_withHssfOption_switchesExtensionToXls() throws PxlException {
        final PxlExportWorkbookOption option = PxlExportWorkbookOption.builder()
                .exportExcelEngine(PxlExcelEngine.HSSF)
                .build();

        final ResponseEntity<Resource> entity = pxlSpring.exportSampleExcel()
                .workbook(TestWorkbook.class)
                .override(option)
                .toResponseEntity("wbTemplate");

        assertThat(entity.getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION)).endsWith("wbTemplate.xls");
        assertThat(bodyBytes(entity)).isNotEmpty();
    }

    @Test
    void sampleSingleSheetToResponseEntity_withHssfOption_switchesExtensionToXls() throws PxlException {
        final PxlExportWorkbookOption option = PxlExportWorkbookOption.builder()
                .exportExcelEngine(PxlExcelEngine.HSSF)
                .build();

        final ResponseEntity<Resource> entity = pxlSpring.exportSampleExcel()
                .sheet(TestUser.class, "Users")
                .override(option)
                .toResponseEntity("template");

        assertThat(entity.getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION)).endsWith("template.xls");
        assertThat(bodyBytes(entity)).isNotEmpty();
    }

    // ----- blank filename falls back to the default "PxlSample" on the servlet-response destinations -----

    @Test
    void sampleWorkbookClassToResponse_blankFilename_usesDefaultFilename() throws PxlException {
        final MockHttpServletResponse response = new MockHttpServletResponse();
        pxlSpring.exportSampleExcel()
                .workbook(TestWorkbook.class)
                .toResponse(response, null);

        assertThat(response.getHeader(HttpHeaders.CONTENT_DISPOSITION)).contains("PxlSample.xlsx");
    }

    @Test
    void sampleSingleSheetToResponse_blankFilename_usesDefaultFilename() throws PxlException {
        final MockHttpServletResponse response = new MockHttpServletResponse();
        pxlSpring.exportSampleExcel()
                .sheet(TestUser.class, "Users")
                .toResponse(response, "  ");

        assertThat(response.getHeader(HttpHeaders.CONTENT_DISPOSITION)).contains("PxlSample.xlsx");
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

    // ----- source x destination matrix -----
    // Both source forms are now reachable from all four terminals, and the fluent builder newly allows
    // several sheet(...) calls; these sweep every pairing rather than spot-checking a few.

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
        assertThat(stringCells(bytes)).contains("Name", "Age", "Alice");
    }

    @ParameterizedTest
    @EnumSource(Dest.class)
    void multiSheetSource_producesEverySheetOnEveryDestination(final Dest dest) throws PxlException, IOException {
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
