package io.github.hclimkr.pxl.spring;

import io.github.hclimkr.pxl.exception.PxlArgumentException;
import io.github.hclimkr.pxl.exception.PxlNullPointerException;
import io.github.hclimkr.pxl.spring.component.*;
import io.github.hclimkr.pxl.spring.tcdata.TestUser;
import io.github.hclimkr.pxl.spring.tcdata.TestWorkbook;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.validation.beanvalidation.MethodValidationPostProcessor;

import javax.validation.ConstraintViolationException;
import java.util.Collections;
import java.util.List;

import static io.github.hclimkr.pxl.spring.component.PxlExcelExporterTests.users;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Verifies that the {@code @Validated} bean-validation constraints on the components actually fire -
 * this only happens through the Spring proxy created by {@link MethodValidationPostProcessor}, so these
 * tests run inside a small application context (unlike the other, plain-instance behavioural tests).
 *
 * <p>Bean validation reaches only the back-end arguments - the <em>destination</em> a terminal passes back
 * into the component, and the download file name it passes alongside it; a terminal re-entering through the
 * proxy is what makes that work. Source arguments are collected by the fluent builder, which is a plain
 * object rather than a bean, so those are guarded by the core's own assertions instead - both halves are
 * asserted here.</p>
 *
 * <p>The ZIP exporter's archive name is the one file name that is {@code @NotBlank} rather than nullable: it
 * has nothing to fall back to, so a blank one is a constraint violation here and a
 * {@code PxlArgumentException} on a plain instance.</p>
 */
@SpringJUnitConfig(PxlValidationTests.Config.class)
class PxlValidationTests {

    @Configuration
    static class Config {

        @Bean
        MethodValidationPostProcessor methodValidationPostProcessor() {
            return new MethodValidationPostProcessor();
        }

        @Bean
        PxlExcelExporter pxlExcelExporter() {
            return new PxlExcelExporter();
        }

        @Bean
        PxlSampleExcelExporter pxlSampleExcelExporter() {
            return new PxlSampleExcelExporter();
        }

        @Bean
        PxlExcelImporter pxlExcelImporter() {
            return new PxlExcelImporter();
        }

        @Bean
        PxlCsvImporter pxlCsvImporter() {
            return new PxlCsvImporter();
        }

        @Bean
        PxlExcelZipExporter pxlExcelZipExporter() {
            return new PxlExcelZipExporter();
        }

        @Bean
        PxlCsvExporter pxlCsvExporter() {
            return new PxlCsvExporter();
        }

        @Bean
        PxlSampleCsvExporter pxlSampleCsvExporter() {
            return new PxlSampleCsvExporter();
        }

        @Bean
        PxlSpring pxlSpring(final PxlExcelImporter excelImporter,
                            final PxlCsvImporter csvImporter,
                            final PxlExcelExporter excelExporter,
                            final PxlSampleExcelExporter sampleExcelExporter,
                            final PxlExcelZipExporter excelZipExporter,
                            final PxlCsvExporter csvExporter,
                            final PxlSampleCsvExporter sampleCsvExporter) {
            return new PxlSpring(excelImporter, csvImporter, excelExporter, sampleExcelExporter,
                    excelZipExporter, csvExporter, sampleCsvExporter);
        }
    }

    @Autowired
    private PxlExcelExporter pxlExcelExporter;

    @Autowired
    private PxlSampleExcelExporter pxlSampleExcelExporter;

    @Autowired
    private PxlExcelImporter pxlExcelImporter;

    @Autowired
    private PxlCsvImporter pxlCsvImporter;

    @Autowired
    private PxlExcelZipExporter pxlExcelZipExporter;

    @Autowired
    private PxlCsvExporter pxlCsvExporter;

    @Autowired
    private PxlSampleCsvExporter pxlSampleCsvExporter;

    @Autowired
    private PxlSpring pxlSpring;

    @Test
    void nullDestination_violatesNotNull() {
        // The exporter's own arguments are now just (builder, destination). A terminal re-enters the
        // component through its Spring proxy, so @Validated still fires on the destination argument.
        assertThatThrownBy(() ->
                pxlExcelExporter.exportExcel().sheet(TestUser.class, users(), "Users").toStream(null))
                .isInstanceOf(ConstraintViolationException.class);
    }

    @Test
    void nullWorkbookObject_throwsPxlNullPointer() {
        // Source arguments are collected by the fluent builder, which is a plain object rather than a Spring
        // bean - bean validation cannot reach it, so the core builder's own assertions guard them instead.
        assertThatThrownBy(() ->
                pxlExcelExporter.exportExcel().workbook(null))
                .isInstanceOf(PxlNullPointerException.class);
    }

    @Test
    void blankSheetName_throwsPxlArgument() {
        assertThatThrownBy(() ->
                pxlExcelExporter.exportExcel().sheet(TestUser.class, users(), "  "))
                .isInstanceOf(PxlArgumentException.class);
    }

    @Test
    void nullSampleDestination_violatesNotNull() {
        // same shape as nullDestination_violatesNotNull, for the sample exporter's own (builder, destination)
        // back-ends - proves its terminals really do re-enter the component through the Spring proxy
        assertThatThrownBy(() ->
                pxlSampleExcelExporter.exportSampleExcel().sheet(TestUser.class, "Users").toStream(null))
                .isInstanceOf(ConstraintViolationException.class);
    }

    @Test
    void nullSampleWorkbookClass_throwsPxlNullPointer() {
        assertThatThrownBy(() ->
                pxlSampleExcelExporter.exportSampleExcel().workbook(null))
                .isInstanceOf(PxlNullPointerException.class);
    }

    @Test
    void nullExcelFile_violatesNotNull() {
        // the importer's own arguments are now just (source, upload); the terminal re-enters the component
        // through its Spring proxy, so @Validated still fires on the upload argument
        assertThatThrownBy(() ->
                pxlExcelImporter.importExcel().workbook(TestWorkbook.class).fromMultipartFile(null))
                .isInstanceOf(ConstraintViolationException.class);
    }

    @Test
    void nullExcelWorkbookClass_throwsPxlNullPointer() {
        // parse-target arguments are collected by the fluent builder, which is a plain object rather than a
        // Spring bean - the core builder's own assertions guard them instead
        assertThatThrownBy(() ->
                pxlExcelImporter.importExcel().workbook(null))
                .isInstanceOf(PxlNullPointerException.class);
    }

    @Test
    void nullCsvFile_violatesNotNullElement() {
        // fromMultipartFile(...) wraps the upload in a singleton list, so the null lands on the back-end's
        // container-element constraint List<@NotNull MultipartFile> rather than on @NotEmpty
        assertThatThrownBy(() ->
                pxlCsvImporter.importCsv().sheet(TestUser.class, List.class).fromMultipartFile(null))
                .isInstanceOf(ConstraintViolationException.class);
    }

    @Test
    void emptyCsvFileList_violatesNotEmpty() {
        // the back-end declares @NotEmpty List<@NotNull MultipartFile> - an empty list must be rejected
        assertThatThrownBy(() ->
                pxlCsvImporter.importCsv().workbook(TestWorkbook.class).fromMultipartFiles(Collections.emptyList()))
                .isInstanceOf(ConstraintViolationException.class);
    }

    @Test
    void nullZipDestination_violatesNotNull() {
        // same shape as nullDestination_violatesNotNull, for the zip exporter's own (builder, destination)
        // back-ends - proves its terminals really do re-enter the component through the Spring proxy
        assertThatThrownBy(() ->
                pxlExcelZipExporter.exportExcelZip().workbook(new TestWorkbook()).toStream(null))
                .isInstanceOf(ConstraintViolationException.class);
    }

    @Test
    void nullCsvDestination_violatesNotNull() {
        // same shape as nullDestination_violatesNotNull, for the CSV exporter's own (builder, destination)
        // back-ends - proves its terminals really do re-enter the component through the Spring proxy
        assertThatThrownBy(() ->
                pxlCsvExporter.exportCsv().sheet(TestUser.class, users(), "Users").toStream(null))
                .isInstanceOf(ConstraintViolationException.class);
    }

    @Test
    void nullSampleCsvDestination_violatesNotNull() {
        assertThatThrownBy(() ->
                pxlSampleCsvExporter.exportSampleCsv().sheet(TestUser.class, "Users").toStream(null))
                .isInstanceOf(ConstraintViolationException.class);
    }

    @Test
    void blankCsvSheetName_throwsPxlArgument() {
        // the sheet source is collected by the fluent builder, so the core's own assertions guard it
        assertThatThrownBy(() ->
                pxlCsvExporter.exportCsv().sheet(TestUser.class, users(), "  "))
                .isInstanceOf(PxlArgumentException.class);
        assertThatThrownBy(() ->
                pxlSampleCsvExporter.exportSampleCsv().sheet(null, "Users"))
                .isInstanceOf(PxlNullPointerException.class);
    }

    // ----- the same constraints, reached through the PxlSpring facade -----
    // The facade is the documented default entry point, and it only holds up if the builder it hands out
    // still belongs to the *proxied* component. A facade that built its own builder, or that was handed
    // unproxied components, would silently skip validation instead of failing here.

    @Test
    void facadeExportDestination_violatesNotNull() {
        assertThatThrownBy(() ->
                pxlSpring.exportExcel().sheet(TestUser.class, users(), "Users").toStream(null))
                .isInstanceOf(ConstraintViolationException.class);
        assertThatThrownBy(() ->
                pxlSpring.exportSampleExcel().sheet(TestUser.class, "Users").toStream(null))
                .isInstanceOf(ConstraintViolationException.class);
        assertThatThrownBy(() ->
                pxlSpring.exportExcelZip().workbook(new TestWorkbook()).toStream(null))
                .isInstanceOf(ConstraintViolationException.class);
        assertThatThrownBy(() ->
                pxlSpring.exportCsv().sheet(TestUser.class, users(), "Users").toStream(null))
                .isInstanceOf(ConstraintViolationException.class);
        assertThatThrownBy(() ->
                pxlSpring.exportSampleCsv().sheet(TestUser.class, "Users").toStream(null))
                .isInstanceOf(ConstraintViolationException.class);
    }

    @Test
    void facadeImportDestination_violatesNotNull() {
        assertThatThrownBy(() ->
                pxlSpring.importExcel().workbook(TestWorkbook.class).fromMultipartFile(null))
                .isInstanceOf(ConstraintViolationException.class);
        assertThatThrownBy(() ->
                pxlSpring.importCsv().sheet(TestUser.class, List.class).fromMultipartFile(null))
                .isInstanceOf(ConstraintViolationException.class);
    }

    @Test
    void facadeSourceArgument_stillThrowsPxlNullPointer() {
        // the builder behind the facade is the same plain object, so source arguments keep failing with the
        // core's assertions rather than with ConstraintViolationException
        assertThatThrownBy(() -> pxlSpring.exportExcel().workbook(null))
                .isInstanceOf(PxlNullPointerException.class);
    }

    // ----- every @NotNull destination argument, on every back-end -----
    // Each terminal re-enters its component through a separate annotated method, so the constraint has to
    // be proven per method rather than once per component.

    @Test
    void nullFileAndResponseDestinations_violateNotNull() {
        assertThatThrownBy(() ->
                pxlExcelExporter.exportExcel().sheet(TestUser.class, users(), "Users").toFile(null))
                .isInstanceOf(ConstraintViolationException.class);
        assertThatThrownBy(() ->
                pxlExcelExporter.exportExcel().sheet(TestUser.class, users(), "Users").toResponse(null, null))
                .isInstanceOf(ConstraintViolationException.class);
        assertThatThrownBy(() ->
                pxlExcelExporter.exportExcel().sheet(TestUser.class, users(), "Users").toResponseStreaming(null, null))
                .isInstanceOf(ConstraintViolationException.class);
    }

    @Test
    void nullSampleFileAndResponseDestinations_violateNotNull() {
        assertThatThrownBy(() ->
                pxlSampleExcelExporter.exportSampleExcel().sheet(TestUser.class, "Users").toFile(null))
                .isInstanceOf(ConstraintViolationException.class);
        assertThatThrownBy(() ->
                pxlSampleExcelExporter.exportSampleExcel().sheet(TestUser.class, "Users").toResponse(null, null))
                .isInstanceOf(ConstraintViolationException.class);
        assertThatThrownBy(() ->
                pxlSampleExcelExporter.exportSampleExcel().sheet(TestUser.class, "Users").toResponseStreaming(null, null))
                .isInstanceOf(ConstraintViolationException.class);
    }

    @Test
    void nullZipFileAndResponseDestinations_violateNotNull() {
        assertThatThrownBy(() ->
                pxlExcelZipExporter.exportExcelZip().workbook(new TestWorkbook()).toFile(null))
                .isInstanceOf(ConstraintViolationException.class);
        assertThatThrownBy(() ->
                pxlExcelZipExporter.exportExcelZip().workbook(new TestWorkbook()).toResponse(null, "archive"))
                .isInstanceOf(ConstraintViolationException.class);
        assertThatThrownBy(() ->
                pxlExcelZipExporter.exportExcelZip().workbook(new TestWorkbook()).toResponseStreaming(null, "archive"))
                .isInstanceOf(ConstraintViolationException.class);
    }

    @Test
    void nullCsvFileAndResponseDestinations_violateNotNull() {
        assertThatThrownBy(() ->
                pxlCsvExporter.exportCsv().sheet(TestUser.class, users(), "Users").toFile(null))
                .isInstanceOf(ConstraintViolationException.class);
        assertThatThrownBy(() ->
                pxlCsvExporter.exportCsv().sheet(TestUser.class, users(), "Users").toResponse(null, null))
                .isInstanceOf(ConstraintViolationException.class);
        assertThatThrownBy(() ->
                pxlCsvExporter.exportCsv().sheet(TestUser.class, users(), "Users").toResponseStreaming(null, null))
                .isInstanceOf(ConstraintViolationException.class);
    }

    @Test
    void nullSampleCsvFileAndResponseDestinations_violateNotNull() {
        assertThatThrownBy(() ->
                pxlSampleCsvExporter.exportSampleCsv().sheet(TestUser.class, "Users").toFile(null))
                .isInstanceOf(ConstraintViolationException.class);
        assertThatThrownBy(() ->
                pxlSampleCsvExporter.exportSampleCsv().sheet(TestUser.class, "Users").toResponse(null, null))
                .isInstanceOf(ConstraintViolationException.class);
        assertThatThrownBy(() ->
                pxlSampleCsvExporter.exportSampleCsv().sheet(TestUser.class, "Users").toResponseStreaming(null, null))
                .isInstanceOf(ConstraintViolationException.class);
    }

    @Test
    void blankZipFilename_violatesNotBlank() {
        // the archive name is required, and through the proxy it is the @NotBlank constraint that enforces it -
        // a plain instance falls through to resolveZipFilename(String), which raises PxlArgumentException
        assertThatThrownBy(() ->
                pxlExcelZipExporter.exportExcelZip().workbook(new TestWorkbook())
                        .toResponse(new MockHttpServletResponse(), "  "))
                .isInstanceOf(ConstraintViolationException.class);
        assertThatThrownBy(() ->
                pxlExcelZipExporter.exportExcelZip().workbook(new TestWorkbook()).toResponseEntity(null))
                .isInstanceOf(ConstraintViolationException.class);
        assertThatThrownBy(() ->
                pxlExcelZipExporter.exportExcelZip().workbook(new TestWorkbook())
                        .toResponseStreaming(new MockHttpServletResponse(), null))
                .isInstanceOf(ConstraintViolationException.class);
    }

    @Test
    void nullCsvFileList_violatesNotNull() {
        // fromMultipartFiles(null) reaches the back-end's @NotEmpty, which rejects null as well as empty
        assertThatThrownBy(() ->
                pxlCsvImporter.importCsv().workbook(TestWorkbook.class).fromMultipartFiles(null))
                .isInstanceOf(ConstraintViolationException.class);
    }

    // ----- the same constraints on the Resource source form -----
    // Each source form re-enters its component through a separate annotated back-end, so the multipart
    // constraints above prove nothing about these. Both importers carry two back-ends for that reason.

    @Test
    void nullExcelResource_violatesNotNull() {
        assertThatThrownBy(() ->
                pxlExcelImporter.importExcel().workbook(TestWorkbook.class).fromResource(null))
                .isInstanceOf(ConstraintViolationException.class);
    }

    @Test
    void nullCsvResource_violatesNotNullElement() {
        // fromResource(...) wraps the resource in a singleton list, so the null lands on the back-end's
        // container-element constraint List<@NotNull Resource> rather than on @NotEmpty
        assertThatThrownBy(() ->
                pxlCsvImporter.importCsv().sheet(TestUser.class, List.class).fromResource(null))
                .isInstanceOf(ConstraintViolationException.class);
    }

    @Test
    void nullCsvResourceList_violatesNotNull() {
        assertThatThrownBy(() ->
                pxlCsvImporter.importCsv().workbook(TestWorkbook.class).fromResources(null))
                .isInstanceOf(ConstraintViolationException.class);
    }

    @Test
    void emptyCsvResourceList_violatesNotEmpty() {
        assertThatThrownBy(() ->
                pxlCsvImporter.importCsv().workbook(TestWorkbook.class).fromResources(Collections.emptyList()))
                .isInstanceOf(ConstraintViolationException.class);
    }

    @Test
    void facadeImportResource_violatesNotNull() {
        // the facade hands back the proxied component's builder, so the resource terminals are validated
        // through it too
        assertThatThrownBy(() ->
                pxlSpring.importExcel().workbook(TestWorkbook.class).fromResource(null))
                .isInstanceOf(ConstraintViolationException.class);
        assertThatThrownBy(() ->
                pxlSpring.importCsv().sheet(TestUser.class, List.class).fromResource(null))
                .isInstanceOf(ConstraintViolationException.class);
    }

    @Test
    void nullWorkbookObjectInZipEntry_throwsPxlNullPointer() {
        // the archive entries are collected by the fluent builder, which is a plain object rather than a
        // Spring bean - the container-element constraint List<@NotNull ?> that used to guard them is gone,
        // so the builder rejects a null entry itself.
        assertThatThrownBy(() ->
                pxlExcelZipExporter.exportExcelZip().workbook(null))
                .isInstanceOf(PxlNullPointerException.class);
    }

    @Test
    void nullPoiWorkbookInZipEntry_throwsPxlNullPointer() {
        // the same holds for every kind of entry: adding one is a builder call, so it stays outside bean
        // validation's reach even here, where the component is proxied
        assertThatThrownBy(() ->
                pxlExcelZipExporter.exportExcelZip().poiWorkbook(null))
                .isInstanceOf(PxlNullPointerException.class);

        assertThatThrownBy(() ->
                pxlExcelZipExporter.exportExcelZip().poiWorkbook(null, "secret"))
                .isInstanceOf(PxlNullPointerException.class);
    }
}
