package io.github.hclimkr.pxl.spring.component;

import io.github.hclimkr.pxl.exception.PxlArgumentException;
import io.github.hclimkr.pxl.exception.PxlException;
import io.github.hclimkr.pxl.exception.PxlNullPointerException;
import io.github.hclimkr.pxl.option.PxlExportWorkbookOption;
import io.github.hclimkr.pxl.spring.PxlSpring;
import io.github.hclimkr.pxl.spring.tcdata.TestPaths;
import io.github.hclimkr.pxl.spring.tcdata.TestUser;
import io.github.hclimkr.pxl.type.PxlExcelEngine;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.HttpMediaTypeNotSupportedException;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.List;

import static io.github.hclimkr.pxl.spring.component.PxlExcelExporterTests.bodyBytes;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Behavioural tests for {@link PxlSampleCsvExporter}, all driven through the
 * {@link PxlSampleCsvExporter.Builder} fluent API: template generation across every destination (stream /
 * file / response / streaming response / response-entity), the download headers and their {@code .csv}
 * extension, the default {@code PxlSample} file name, and the guards the core terminal raises.
 *
 * <p>The template is not empty - it carries a header record plus a single sample data record populated from
 * each column's {@code @PxlColumn(exportSample = ...)} value, which is what makes it round-trip through
 * {@code importCsv()} as a filled-in form.</p>
 *
 * <p>The builder comes from {@link PxlSpring}, the entry point the documentation guides users to. The
 * facade hands back this component's own builder, so what is exercised here is still the component.</p>
 *
 * <p>Every destination is swept rather than spot-checked, through one of two enums: assertions about the
 * generated records are {@code @ParameterizedTest}s over {@link Dest} - all five terminals, streaming
 * included - and assertions about the download headers are the same thing over {@link Download}, the three
 * of those five that have headers at all. What stays a plain {@code @Test} is the guards, the entity-only
 * invariants, and the comparisons one terminal makes against another.</p>
 */
class PxlSampleCsvExporterTests {

    private final PxlSpring pxlSpring = new PxlSpring();

    private TestInfo testInfo;

    @BeforeEach
    void bindTestInfo(final TestInfo testInfo) {
        this.testInfo = testInfo;
    }

    /**
     * The template's records, split on the CRLF the CSV writer emits.
     */
    private static List<String> linesOf(final byte[] bytes) {
        return Arrays.asList(new String(bytes, StandardCharsets.UTF_8).split("\r\n", -1));
    }

    // ----- HttpServletResponse -----

    @Test
    void sampleSheetToResponse_writesTemplateWithDownloadHeaders() throws PxlException {
        final MockHttpServletResponse response = new MockHttpServletResponse();
        pxlSpring.exportSampleCsv()
                .sheet(TestUser.class, "Users")
                .toResponse(response, "template");

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(response.getHeader(HttpHeaders.CONTENT_DISPOSITION)).contains("template.csv");
        assertThat(response.getContentType()).isEqualTo("text/csv");
        assertThat(linesOf(response.getContentAsByteArray())).startsWith("Name,Age", "Alice,30");
    }

    // ----- ResponseEntity<Resource> -----

    @Test
    void sampleSheetToResponseEntity_usesDefaultFilename() throws PxlException {
        final ResponseEntity<Resource> entity = pxlSpring.exportSampleCsv()
                .sheet(TestUser.class, "Users")
                .toResponseEntity(null);

        assertThat(entity.getStatusCode().value()).isEqualTo(200);
        assertThat(entity.getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION)).contains("PxlSample.csv");
        assertThat(entity.getHeaders().getFirst(HttpHeaders.CONTENT_TYPE)).isEqualTo("text/csv");
        // the body is a view over the download buffer rather than a copy of it, so the length the header
        // carries and the length the body reads out come from two different places and must still agree
        assertThat(entity.getHeaders().getContentLength()).isEqualTo(bodyBytes(entity).length);
        assertThat(linesOf(bodyBytes(entity))).startsWith("Name,Age", "Alice,30");
    }

    // ----- override(...) -----
    // What an option does to the template's bytes is swept across every destination in the matrix section
    // below; what is left here is the refusal, which never produces bytes at all, and the two engine tests,
    // which are about download headers.

    @Test
    void passwordOption_isRefusedRatherThanIgnored() {
        final PxlExportWorkbookOption option = PxlExportWorkbookOption.builder()
                .exportPassword("secret")
                .build();

        assertThatThrownBy(() -> pxlSpring.exportSampleCsv()
                .sheet(TestUser.class, "Users")
                .override(option)
                .toStream(new ByteArrayOutputStream()))
                .isInstanceOf(PxlArgumentException.class);
    }

    // ----- builder source guards (delegated to the core builder) -----

    @Test
    void noSheetConfigured_throwsPxlArgument() {
        assertThatThrownBy(() -> pxlSpring.exportSampleCsv().toStream(new ByteArrayOutputStream()))
                .isInstanceOf(PxlArgumentException.class);
    }

    @Test
    void moreThanOneSheet_throwsPxlArgument() {
        // a CSV file holds one sheet, so a second sheet(...) call does not add one - it fails the terminal
        assertThatThrownBy(() -> pxlSpring.exportSampleCsv()
                .sheet(TestUser.class, "Users")
                .sheet(TestUser.class, "Admins")
                .toStream(new ByteArrayOutputStream()))
                .isInstanceOf(PxlArgumentException.class);
    }

    @Test
    void blankSheetName_throwsPxlArgument() {
        assertThatThrownBy(() -> pxlSpring.exportSampleCsv().sheet(TestUser.class, "  "))
                .isInstanceOf(PxlArgumentException.class);
    }

    @Test
    void nullRowClass_throwsPxlNullPointer() {
        assertThatThrownBy(() -> pxlSpring.exportSampleCsv().sheet(null, "Users"))
                .isInstanceOf(PxlNullPointerException.class);
    }

    @Test
    void nullDestinationOnPlainComponent_throwsPxlNullPointerNotRawNpe() {
        // this component is a plain instance, so @NotNull never fires (no Spring proxy). Through a proxy the
        // same calls raise ConstraintViolationException - that half is pinned by PxlValidationTests.
        assertThatThrownBy(() -> pxlSpring.exportSampleCsv()
                .sheet(TestUser.class, "Users").toStream(null))
                .isInstanceOf(PxlNullPointerException.class);

        assertThatThrownBy(() -> pxlSpring.exportSampleCsv()
                .sheet(TestUser.class, "Users").toFile(null))
                .isInstanceOf(PxlNullPointerException.class);

        assertThatThrownBy(() -> pxlSpring.exportSampleCsv()
                .sheet(TestUser.class, "Users").toResponse(null, null))
                .isInstanceOf(PxlNullPointerException.class);

        assertThatThrownBy(() -> pxlSpring.exportSampleCsv()
                .sheet(TestUser.class, "Users").toResponseStreaming(null, null))
                .isInstanceOf(PxlNullPointerException.class);
    }

    // ----- toResponseStreaming(...) -----

    @Test
    void streamingToResponse_writesTheSameBytesWithoutContentLength() throws PxlException {
        final MockHttpServletResponse buffered = new MockHttpServletResponse();
        pxlSpring.exportSampleCsv()
                .sheet(TestUser.class, "Users").toResponse(buffered, "template");

        final MockHttpServletResponse streamed = new MockHttpServletResponse();
        pxlSpring.exportSampleCsv()
                .sheet(TestUser.class, "Users").toResponseStreaming(streamed, "template");

        assertThat(streamed.getContentAsByteArray()).isEqualTo(buffered.getContentAsByteArray());
        assertThat(streamed.getHeader(HttpHeaders.CONTENT_DISPOSITION))
                .isEqualTo(buffered.getHeader(HttpHeaders.CONTENT_DISPOSITION));
        assertThat(streamed.getContentType()).isEqualTo(buffered.getContentType());

        assertThat(buffered.getHeader(HttpHeaders.CONTENT_LENGTH)).isNotNull();
        assertThat(streamed.getHeader(HttpHeaders.CONTENT_LENGTH)).isNull();
    }

    @Test
    void streamingSetsHeadersBeforeTheSourceIsValidated() {
        // as on the other sample exporter, "no sheet" is the core builder's call, made inside its terminal -
        // which on the streaming path is after the headers have gone out
        final MockHttpServletResponse streamed = new MockHttpServletResponse();

        assertThatThrownBy(() -> pxlSpring.exportSampleCsv().toResponseStreaming(streamed, null))
                .isInstanceOf(PxlArgumentException.class);

        assertThat(streamed.getContentAsByteArray()).isEmpty();
        assertThat(streamed.getHeader(HttpHeaders.CONTENT_DISPOSITION)).isNotNull();

        final MockHttpServletResponse buffered = new MockHttpServletResponse();

        assertThatThrownBy(() -> pxlSpring.exportSampleCsv().toResponse(buffered, null))
                .isInstanceOf(PxlArgumentException.class);

        assertThat(buffered.getContentAsByteArray()).isEmpty();
        assertThat(buffered.getHeader(HttpHeaders.CONTENT_DISPOSITION)).isNull();
    }

    // ----- destination matrix -----
    // Everything that asserts on the generated template lives here rather than picking one terminal: the
    // records must not depend on where they are written, and this enum reaches the streaming response too.
    // What stays a plain @Test is what only a response can show - download headers, the PxlSample default
    // name - and the refusals, which never produce records at all.

    /**
     * The five terminal destinations, swept by the matrix tests below.
     */
    enum Dest {
        STREAM, FILE, RESPONSE, RESPONSE_STREAMING, RESPONSE_ENTITY
    }

    /**
     * Runs the configured builder against the given destination and returns the bytes it produced, so one
     * assertion can serve every destination. File artifacts are named per destination to stay inspectable.
     */
    private byte[] emit(final PxlSampleCsvExporter.Builder builder, final Dest dest) throws PxlException, IOException {
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

    /**
     * The three destinations that carry download headers - the subset of {@link Dest} a stream and a file
     * are not part of, since neither has headers to look at.
     */
    enum Download {
        RESPONSE, RESPONSE_STREAMING, RESPONSE_ENTITY
    }

    /**
     * Runs the configured builder against the given download destination and returns the
     * {@code Content-Disposition} it set, so one assertion can serve all three.
     */
    private String contentDisposition(final PxlSampleCsvExporter.Builder builder, final Download dest,
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

    // ----- download headers x response destination -----

    @ParameterizedTest
    @EnumSource(Download.class)
    void theDefaultFilename_isPxlSample(final Download dest) throws PxlException {
        // unlike PxlCsvExporter there is no sheet-name fallback: a template describes a shape, not a data
        // set, so a blank name goes straight to the constant - and an explicit one still outranks it
        assertThat(contentDisposition(pxlSpring.exportSampleCsv()
                .sheet(TestUser.class, "Users"), dest, "  "))
                .contains("PxlSample.csv");

        assertThat(contentDisposition(pxlSpring.exportSampleCsv()
                .sheet(TestUser.class, "Users"), dest, "template"))
                .contains("template.csv");
    }

    @ParameterizedTest
    @EnumSource(Download.class)
    void excelEngineOption_doesNotChangeTheCsvExtension(final Download dest) throws PxlException {
        // the counterpart of the sample Excel exporter's engine tests, inverted: this exporter writes one
        // format, so an engine on the option is ignored rather than switching the headers to .xls
        final PxlExportWorkbookOption option = PxlExportWorkbookOption.builder()
                .exportExcelEngine(PxlExcelEngine.HSSF)
                .build();

        assertThat(contentDisposition(pxlSpring.exportSampleCsv()
                .sheet(TestUser.class, "Users")
                .override(option), dest, "template"))
                .endsWith("template.csv");
    }

    @ParameterizedTest
    @EnumSource(Download.class)
    void koreanFilename_isRfc5987PercentEncoded(final Download dest) throws PxlException {
        // the header is assembled in one place, so all three terminals must produce the same one
        final String contentDisposition = contentDisposition(pxlSpring.exportSampleCsv()
                .sheet(TestUser.class, "사용자"), dest, "템플릿");

        assertThat(contentDisposition).startsWith("attachment; filename=\"___.csv\"; filename*=UTF-8''");
        assertThat(contentDisposition).doesNotContain("템플릿").contains("%");
    }

    @ParameterizedTest
    @EnumSource(Dest.class)
    void sheetSource_producesTheSameTemplateOnEveryDestination(final Dest dest) throws PxlException, IOException {
        final byte[] bytes = emit(pxlSpring.exportSampleCsv()
                .sheet(TestUser.class, "Users"), dest);

        final List<String> lines = linesOf(bytes);
        assertThat(lines.get(0)).isEqualTo("Name,Age");
        // the template is not empty: @PxlColumn(exportSample = ...) on TestUser fills the one sample record
        assertThat(lines.get(1)).isEqualTo("Alice,30");
        assertThat(lines.get(2)).isEmpty();
        assertThat(lines).hasSize(3);
    }

    // ----- override(...) on every destination -----
    // The response destinations already assert that an option cannot switch the download extension; this
    // pins the effect an option has on the template's own bytes, wherever they are written.

    @ParameterizedTest
    @EnumSource(Dest.class)
    void delimiterOption_appliesOnEveryDestination(final Dest dest) throws PxlException, IOException {
        final PxlExportWorkbookOption option = PxlExportWorkbookOption.builder()
                .exportCsvDelimiter(';')
                .build();

        final byte[] bytes = emit(pxlSpring.exportSampleCsv()
                .sheet(TestUser.class, "Users")
                .override(option), dest);

        assertThat(linesOf(bytes)).startsWith("Name;Age", "Alice;30");
    }

    // ----- builder call-order independence -----

    @Test
    void optionsBeforeSource_behaveTheSameAsAfter() throws PxlException {
        final PxlExportWorkbookOption option = PxlExportWorkbookOption.builder()
                .exportCsvDelimiter(';')
                .build();

        final ByteArrayOutputStream optionsFirst = new ByteArrayOutputStream();
        pxlSpring.exportSampleCsv()
                .override(option)
                .sheet(TestUser.class, "Users")
                .toStream(optionsFirst);

        final ByteArrayOutputStream optionsLast = new ByteArrayOutputStream();
        pxlSpring.exportSampleCsv()
                .sheet(TestUser.class, "Users")
                .override(option)
                .toStream(optionsLast);

        assertThat(optionsFirst.toByteArray()).isEqualTo(optionsLast.toByteArray());
        assertThat(linesOf(optionsFirst.toByteArray())).startsWith("Name;Age");
    }

    // ----- option precedence within one chain -----

    @Test
    void repeatedOverrideAndFilename_lastValueWins() throws PxlException {
        final PxlExportWorkbookOption semicolonOption = PxlExportWorkbookOption.builder()
                .exportCsvDelimiter(';')
                .build();
        final PxlExportWorkbookOption pipeOption = PxlExportWorkbookOption.builder()
                .exportCsvDelimiter('|')
                .build();

        final ResponseEntity<Resource> entity = pxlSpring.exportSampleCsv()
                .sheet(TestUser.class, "Users")
                .override(semicolonOption)
                .override(pipeOption)
                .toResponseEntity("second");

        // had the first call won, the records would be semicolon-separated
        assertThat(entity.getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION)).contains("second.csv");
        assertThat(linesOf(bodyBytes(entity))).startsWith("Name|Age", "Alice|30");
    }

    // ----- round trip -----

    @ParameterizedTest
    @EnumSource(Dest.class)
    void theTemplate_readsBackThroughImportCsvAsAFilledInFormOnEveryDestination(final Dest dest)
            throws PxlException, IOException, HttpMediaTypeNotSupportedException {
        // the point of a non-empty template: the recipient fills it in and sends it back, so what comes out
        // here has to parse as one row carrying the declared sample values - whichever terminal wrote it
        final byte[] bytes = emit(pxlSpring.exportSampleCsv()
                .sheet(TestUser.class, "Users"), dest);

        final List<TestUser> back = pxlSpring.importCsv()
                .sheet(TestUser.class)
                .fromMultipartFile(new MockMultipartFile("file", "Users.csv", null, bytes));

        assertThat(back).extracting(TestUser::getName).containsExactly("Alice");
        assertThat(back).extracting(TestUser::getAge).containsExactly(30);
    }
}
