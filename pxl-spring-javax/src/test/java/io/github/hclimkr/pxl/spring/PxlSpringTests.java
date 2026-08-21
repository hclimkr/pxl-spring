package io.github.hclimkr.pxl.spring;

import io.github.hclimkr.pxl.exception.PxlException;
import io.github.hclimkr.pxl.spring.component.*;
import io.github.hclimkr.pxl.spring.tcdata.TestUser;
import io.github.hclimkr.pxl.spring.tcdata.TestWorkbook;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.FilterType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.HttpMediaTypeNotSupportedException;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.zip.ZipInputStream;

import static io.github.hclimkr.pxl.spring.component.PxlExcelExporterTests.isXlsx;
import static io.github.hclimkr.pxl.spring.component.PxlExcelExporterTests.users;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Behavioural tests for {@link PxlSpring}, the single-entry-point facade: every operation is reachable
 * through it and behaves as it does on the owning component, and each entry point hands out that
 * component's own builder rather than building one of its own.
 *
 * <p>Mostly plain instances, plus one component-scan test that pins the wiring the README tells consumers
 * to set up. That the facade keeps {@code @Validated} alive is asserted in {@code PxlValidationTests},
 * where a proxying context is already in place.</p>
 */
class PxlSpringTests {

    private static final byte[] USERS_CSV = "Name,Age\nAlice,30\nBob,25\n".getBytes(StandardCharsets.UTF_8);

    private final PxlSpring pxlSpring = new PxlSpring();

    private static MockMultipartFile file(final String filename, final byte[] content) {
        return new MockMultipartFile("file", filename, null, content);
    }

    @Test
    void exportExcelThenImportExcel_roundTripsThroughTheFacade() throws PxlException, HttpMediaTypeNotSupportedException {
        final ByteArrayOutputStream baos = new ByteArrayOutputStream();
        pxlSpring.exportExcel()
                .sheet(TestUser.class, users(), "Users")
                .toStream(baos);

        final List<TestUser> back = pxlSpring.importExcel()
                .sheet(TestUser.class, "Users")
                .fromMultipartFile(file("users.xlsx", baos.toByteArray()));

        assertThat(back).extracting(TestUser::getName).containsExactly("Alice", "Bob");
    }

    @Test
    void importCsv_readsTheUploadThroughTheFacade() throws PxlException, HttpMediaTypeNotSupportedException {
        final List<TestUser> rows = pxlSpring.importCsv()
                .sheet(TestUser.class)
                .fromMultipartFile(file("Users.csv", USERS_CSV));

        assertThat(rows).extracting(TestUser::getName).containsExactly("Alice", "Bob");
    }

    @Test
    void exportSampleExcel_producesTemplateThroughTheFacade() throws PxlException {
        final ByteArrayOutputStream baos = new ByteArrayOutputStream();
        pxlSpring.exportSampleExcel()
                .sheet(TestUser.class, "Users")
                .toStream(baos);

        assertThat(isXlsx(baos.toByteArray())).isTrue();
    }

    @Test
    void exportCsvThenImportCsv_roundTripsThroughTheFacade() throws PxlException, HttpMediaTypeNotSupportedException {
        final ByteArrayOutputStream baos = new ByteArrayOutputStream();
        pxlSpring.exportCsv()
                .sheet(TestUser.class, users(), "Users")
                .toStream(baos);

        final List<TestUser> back = pxlSpring.importCsv()
                .sheet(TestUser.class)
                .fromMultipartFile(file("Users.csv", baos.toByteArray()));

        assertThat(back).extracting(TestUser::getName).containsExactly("Alice", "Bob");
    }

    @Test
    void exportSampleCsv_producesTemplateThroughTheFacade() throws PxlException {
        final ByteArrayOutputStream baos = new ByteArrayOutputStream();
        pxlSpring.exportSampleCsv()
                .sheet(TestUser.class, "Users")
                .toStream(baos);

        // header record plus the one sample record built from @PxlColumn(exportSample = ...)
        assertThat(new String(baos.toByteArray(), StandardCharsets.UTF_8)).contains("Name").contains("Alice");
    }

    @Test
    void exportZip_producesArchiveThroughTheFacade() throws PxlException, IOException {
        final TestWorkbook workbook = new TestWorkbook();
        workbook.setWorkbookName("first");
        workbook.setUsers(users());

        final ByteArrayOutputStream baos = new ByteArrayOutputStream();
        pxlSpring.exportZip()
                .workbook(workbook)
                .toStream(baos);

        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(baos.toByteArray()))) {
            assertThat(zip.getNextEntry()).isNotNull();
        }
    }

    @Test
    void everyEntryPoint_delegatesToTheInjectedComponent() {
        // Spring injects the container's proxied components here, so the facade must return *their* builders:
        // a builder built any other way would re-enter an unproxied component and silently lose @Validated
        // and the performance log
        final boolean[] delegated = new boolean[7];

        final PxlSpring facade = new PxlSpring(
                new PxlExcelImporter() {
                    @Override
                    public Builder importExcel() {
                        delegated[0] = true;
                        return super.importExcel();
                    }
                },
                new PxlCsvImporter() {
                    @Override
                    public Builder importCsv() {
                        delegated[1] = true;
                        return super.importCsv();
                    }
                },
                new PxlExcelExporter() {
                    @Override
                    public Builder exportExcel() {
                        delegated[2] = true;
                        return super.exportExcel();
                    }
                },
                new PxlSampleExcelExporter() {
                    @Override
                    public Builder exportSampleExcel() {
                        delegated[3] = true;
                        return super.exportSampleExcel();
                    }
                },
                new PxlCsvExporter() {
                    @Override
                    public Builder exportCsv() {
                        delegated[4] = true;
                        return super.exportCsv();
                    }
                },
                new PxlSampleCsvExporter() {
                    @Override
                    public Builder exportSampleCsv() {
                        delegated[5] = true;
                        return super.exportSampleCsv();
                    }
                },
                new PxlZipExporter() {
                    @Override
                    public Builder exportZip() {
                        delegated[6] = true;
                        return super.exportZip();
                    }
                });

        assertThat(facade.importExcel()).isNotNull();
        assertThat(facade.importCsv()).isNotNull();
        assertThat(facade.exportExcel()).isNotNull();
        assertThat(facade.exportSampleExcel()).isNotNull();
        assertThat(facade.exportCsv()).isNotNull();
        assertThat(facade.exportSampleCsv()).isNotNull();
        assertThat(facade.exportZip()).isNotNull();

        assertThat(delegated).containsOnly(true);
    }

    /**
     * Scans the library package the way the README tells consumers to. Nested test configurations living in
     * this same package (this one included) are filtered out, so the scan yields exactly the library's own
     * beans.
     */
    @Configuration
    @ComponentScan(basePackages = "io.github.hclimkr.pxl.spring",
            excludeFilters = @ComponentScan.Filter(type = FilterType.REGEX, pattern = ".*Tests\\$.*"))
    static class ScanConfig {
    }

    @Test
    void componentScan_registersTheFacadeAlongsideTheComponents() throws PxlException {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext(ScanConfig.class)) {
            // one bean each: the facade must not need any manual wiring beyond the documented scan
            final PxlSpring facade = context.getBean(PxlSpring.class);
            assertThat(context.getBean(PxlExcelImporter.class)).isNotNull();
            assertThat(context.getBean(PxlCsvImporter.class)).isNotNull();
            assertThat(context.getBean(PxlExcelExporter.class)).isNotNull();
            assertThat(context.getBean(PxlSampleExcelExporter.class)).isNotNull();
            assertThat(context.getBean(PxlCsvExporter.class)).isNotNull();
            assertThat(context.getBean(PxlSampleCsvExporter.class)).isNotNull();
            assertThat(context.getBean(PxlZipExporter.class)).isNotNull();

            final ByteArrayOutputStream baos = new ByteArrayOutputStream();
            facade.exportExcel()
                    .sheet(TestUser.class, users(), "Users")
                    .toStream(baos);

            assertThat(isXlsx(baos.toByteArray())).isTrue();
        }
    }

    @Test
    void eachEntryPoint_returnsAFreshBuilder() {
        // builders are single-use and not thread-safe, so two calls must never hand back the same instance
        assertThat(pxlSpring.importExcel()).isNotSameAs(pxlSpring.importExcel());
        assertThat(pxlSpring.importCsv()).isNotSameAs(pxlSpring.importCsv());
        assertThat(pxlSpring.exportExcel()).isNotSameAs(pxlSpring.exportExcel());
        assertThat(pxlSpring.exportSampleExcel()).isNotSameAs(pxlSpring.exportSampleExcel());
        assertThat(pxlSpring.exportCsv()).isNotSameAs(pxlSpring.exportCsv());
        assertThat(pxlSpring.exportSampleCsv()).isNotSameAs(pxlSpring.exportSampleCsv());
        assertThat(pxlSpring.exportZip()).isNotSameAs(pxlSpring.exportZip());
    }
}
