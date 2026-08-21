package io.github.hclimkr.pxl.spring;

import io.github.hclimkr.pxl.spring.component.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Single entry point for every pxl-spring operation, mirroring the core {@code Pxl} facade.
 *
 * <p>Inject this one bean and pick the operation the same way you would on the core library - the six that
 * have a core counterpart carry its name, so a chain reads the same on both sides. {@link #exportZip()}
 * is the one addition, archive bundling being a pxl-spring concern with nothing to mirror:</p>
 *
 * <pre>{@code
 * pxlSpring.exportExcel().sheet(User.class, users, "Users").toResponse(response, "report");
 * pxlSpring.exportCsv().sheet(User.class, users, "Users").toResponse(response, "report");
 * pxlSpring.exportZip().workbook(first).workbook(second).toResponseEntity("archive");
 * List<User> uploaded = pxlSpring.importExcel().sheet(User.class, "Users").fromMultipartFile(upload);
 * }</pre>
 *
 * <p>Each method just hands back the builder of the component that owns the operation, so everything those
 * components document - sources, options, terminals, validation - applies unchanged. A component scan of
 * {@code io.github.hclimkr.pxl.spring} picks up this facade, the components it delegates to and the
 * performance-logging aspect in one go, because sub-packages are scanned with it.</p>
 *
 * <p>Stateless once built, and safe to share across threads - as are the components behind it. The builders it
 * hands back are not: each belongs to the one operation that started it.</p>
 *
 * <p>Deliberately carries no {@code @PxlPerformanceLogging}: these methods only construct a builder, exactly
 * like the components' own start methods, so timing them would measure nothing. The work still runs inside
 * the owning component's proxied, annotated back-end, because the injected components here <em>are</em>
 * their Spring proxies - a terminal re-enters one of them, not this facade.</p>
 *
 * <p>The no-arg constructor builds plain components for use outside a Spring context (tests, plain
 * {@code new PxlSpring()}); as with the components themselves, that path simply produces no performance
 * log.</p>
 */
@Component
public class PxlSpring {

    private final PxlExcelExporter excelExporter;

    private final PxlSampleExcelExporter sampleExcelExporter;

    private final PxlCsvExporter csvExporter;

    private final PxlSampleCsvExporter sampleCsvExporter;

    private final PxlZipExporter zipExporter;

    private final PxlExcelImporter excelImporter;

    private final PxlCsvImporter csvImporter;

    /**
     * Creates a facade over the given components. Spring injects the container's own (proxied) instances
     * here, which is what keeps {@code @Validated} and the performance log in place.
     *
     * @param excelExporter       the Excel export component
     * @param sampleExcelExporter the Excel sample-template export component
     * @param csvExporter         the CSV export component
     * @param sampleCsvExporter   the CSV sample-template export component
     * @param zipExporter         the ZIP export component
     * @param excelImporter       the Excel import component
     * @param csvImporter         the CSV import component
     */
    @Autowired
    public PxlSpring(final PxlExcelExporter excelExporter,
                     final PxlSampleExcelExporter sampleExcelExporter,
                     final PxlCsvExporter csvExporter,
                     final PxlSampleCsvExporter sampleCsvExporter,
                     final PxlZipExporter zipExporter,
                     final PxlExcelImporter excelImporter,
                     final PxlCsvImporter csvImporter) {

        this.excelExporter = excelExporter;
        this.sampleExcelExporter = sampleExcelExporter;
        this.csvExporter = csvExporter;
        this.sampleCsvExporter = sampleCsvExporter;
        this.zipExporter = zipExporter;
        this.excelImporter = excelImporter;
        this.csvImporter = csvImporter;
    }

    /**
     * Creates a facade over plain, unproxied components - for use outside a Spring context.
     */
    public PxlSpring() {

        this(new PxlExcelExporter(),
                new PxlSampleExcelExporter(),
                new PxlCsvExporter(),
                new PxlSampleCsvExporter(),
                new PxlZipExporter(),
                new PxlExcelImporter(),
                new PxlCsvImporter());
    }

    /**
     * Starts a fluent Excel export.
     *
     * @return a new builder bound to the Excel export component
     * @see PxlExcelExporter#exportExcel()
     */
    public PxlExcelExporter.Builder exportExcel() {

        return excelExporter.exportExcel();
    }

    /**
     * Starts a fluent Excel sample template export.
     *
     * @return a new builder bound to the Excel sample-template export component
     * @see PxlSampleExcelExporter#exportSampleExcel()
     */
    public PxlSampleExcelExporter.Builder exportSampleExcel() {

        return sampleExcelExporter.exportSampleExcel();
    }

    /**
     * Starts a fluent CSV export.
     *
     * @return a new builder bound to the CSV export component
     * @see PxlCsvExporter#exportCsv()
     */
    public PxlCsvExporter.Builder exportCsv() {

        return csvExporter.exportCsv();
    }

    /**
     * Starts a fluent CSV sample template export.
     *
     * @return a new builder bound to the CSV sample-template export component
     * @see PxlSampleCsvExporter#exportSampleCsv()
     */
    public PxlSampleCsvExporter.Builder exportSampleCsv() {

        return sampleCsvExporter.exportSampleCsv();
    }

    /**
     * Starts a fluent ZIP export.
     *
     * @return a new builder bound to the ZIP export component
     * @see PxlZipExporter#exportZip()
     */
    public PxlZipExporter.Builder exportZip() {

        return zipExporter.exportZip();
    }

    /**
     * Starts a fluent Excel import, from a multipart upload or a Spring {@code Resource}.
     *
     * @return a new builder bound to the Excel import component
     * @see PxlExcelImporter#importExcel()
     */
    public PxlExcelImporter.Builder importExcel() {

        return excelImporter.importExcel();
    }

    /**
     * Starts a fluent CSV import, from multipart uploads or Spring {@code Resource}s.
     *
     * @return a new builder bound to the CSV import component
     * @see PxlCsvImporter#importCsv()
     */
    public PxlCsvImporter.Builder importCsv() {

        return csvImporter.importCsv();
    }

}
