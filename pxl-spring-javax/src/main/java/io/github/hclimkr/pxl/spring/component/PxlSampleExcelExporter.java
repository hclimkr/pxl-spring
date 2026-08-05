package io.github.hclimkr.pxl.spring.component;

import io.github.hclimkr.pxl.Pxl;
import io.github.hclimkr.pxl.PxlConstants;
import io.github.hclimkr.pxl.PxlExcelEngine;
import io.github.hclimkr.pxl.PxlFileFormat;
import io.github.hclimkr.pxl.builder.PxlSampleExcelExportBuilder;
import io.github.hclimkr.pxl.exception.PxlArgumentException;
import io.github.hclimkr.pxl.exception.PxlException;
import io.github.hclimkr.pxl.exception.PxlIOException;
import io.github.hclimkr.pxl.exception.PxlNullPointerException;
import io.github.hclimkr.pxl.option.PxlExportWorkbookOption;
import io.github.hclimkr.pxl.spring.PxlSpringConstants;
import io.github.hclimkr.pxl.spring.internal.support.PxlArgumentSupport;
import io.github.hclimkr.pxl.spring.internal.support.PxlCoreSupport;
import io.github.hclimkr.pxl.spring.internal.support.PxlExportSupport;
import io.github.hclimkr.pxl.spring.logging.PxlPerformanceLogging;
import org.apache.commons.io.IOUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.core.io.Resource;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

import javax.annotation.Nullable;
import javax.servlet.http.HttpServletResponse;
import javax.validation.constraints.NotNull;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.util.Objects;
import java.util.Optional;

/**
 * Spring component that generates sample template Excel files from a workbook class or a sheet class.
 *
 * <p>The template is <strong>not empty</strong>: it contains a single sample data row whose cells are
 * populated from each column's {@code @PxlColumn(exportSample = ...)} value, so the recipient sees a
 * worked example of the expected format alongside the column headers.</p>
 *
 * <p>Everything is configured through the fluent builder returned by {@link #exportSampleExcel()}, which
 * mirrors the core {@code Pxl.exportSampleExcel()} shape — pick a source ({@code workbook(...)} /
 * {@code sheet(...)}), optionally {@code override(...)} the option, then call a terminal
 * ({@code toStream} / {@code toFile} / {@code toResponse} / {@code toResponseStreaming} /
 * {@code toResponseEntity}); the response terminals take the download file name as an argument:</p>
 *
 * <pre>{@code
 * pxlSpring.exportSampleExcel().workbook(ReportDto.class).toResponse(response, null);  // "PxlSample"
 * pxlSpring.exportSampleExcel().sheet(User.class, "Users").toResponseEntity("sample");
 * pxlSpring.exportSampleExcel().sheet(User.class, "Users").sheet(Order.class, "Orders").toFile(file);
 * }</pre>
 *
 * <p>The download file name defaults to {@code PxlSample}.</p>
 *
 * <p>That builder is the nested {@link Builder}. A fluent chain never has to name it; on the rare occasion
 * you hold one in a variable, spell it {@code PxlSampleExcelExporter.Builder}.</p>
 *
 * <p>The component is stateless and safe to share across threads; the builder it hands back is not — start one
 * per export.</p>
 *
 * <p>Reached through {@link io.github.hclimkr.pxl.spring.PxlSpring PxlSpring}: inject that one bean and call
 * {@code pxlSpring.exportSampleExcel()}, which hands back the builder documented here.</p>
 *
 * <p>The {@code exportSampleExcelTo*} methods below are the builder's execution back-ends. They are
 * {@code public} only because Spring AOP (and {@code @Validated} method validation) can advise public methods
 * only — a terminal has to re-enter this component through its proxy for {@link PxlPerformanceLogging} to fire.
 * Treat them as internal and always go through {@link #exportSampleExcel()}.</p>
 *
 * <p>Because those back-ends' {@code @NotNull} constraints only fire through the proxy, each one re-checks
 * its destination with {@code PxlArgumentSupport} so a plainly constructed component fails the same way at
 * the same point — see that class for why.</p>
 */
@Validated
@Component
public class PxlSampleExcelExporter {

    private static final String TAG = "PxlSampleExcelExporter";

    /**
     * The core entry point, shared with the other components — see {@link PxlCoreSupport} for why it is not
     * one instance per component.
     */
    private final Pxl pxl = PxlCoreSupport.core();

    /**
     * This component's own AOP proxy, injected by Spring where available.
     *
     * <p>The builder's terminals must call back through the proxy, not through {@code this}: a plain
     * {@code this} reference bypasses the proxy, and with it {@link PxlPerformanceLogging} and {@code @Validated}.
     * {@code @Lazy} breaks the self-reference cycle, and {@code required = false} keeps plain
     * {@code new PxlSampleExcelExporter()} usage (outside a Spring context) working — it then falls back to
     * {@code this} and simply produces no performance log.</p>
     */
    @Autowired(required = false)
    @Lazy
    private PxlSampleExcelExporter self;

    /**
     * Starts a fluent sample template export.
     *
     * @return a new builder bound to this component
     */
    public Builder exportSampleExcel() {

        return new Builder(pxl, Objects.nonNull(self) ? self : this);
    }

    // ----- builder execution back-ends (internal; reached through the nested Builder) -----

    /**
     * Writes the builder's configured sample template to the given output stream.
     *
     * <p>Internal: called by {@link Builder#toStream(OutputStream)}.</p>
     *
     * @param builder      the configured sample export builder
     * @param outputStream the destination stream (not closed by this method)
     * @throws PxlException if {@code builder} or {@code outputStream} is {@code null}, the builder's source
     *                      combination is invalid, or generation fails
     */
    @PxlPerformanceLogging(TAG)
    public void exportSampleExcelToStream(@NotNull final Builder builder,
                                          @NotNull final OutputStream outputStream)
            throws PxlException {

        PxlArgumentSupport.requireNonNull(outputStream, "outputStream");

        builder.coreBuilder.toStream(outputStream);
    }

    /**
     * Writes the builder's configured sample template to the given file.
     *
     * <p>Internal: called by {@link Builder#toFile(File)}.</p>
     *
     * @param builder   the configured sample export builder
     * @param excelFile the destination file
     * @throws PxlException if {@code builder} or {@code excelFile} is {@code null}, the builder's source
     *                      combination is invalid, or writing fails
     */
    @PxlPerformanceLogging(TAG)
    public void exportSampleExcelToFile(@NotNull final Builder builder,
                                        @NotNull final File excelFile)
            throws PxlException {

        PxlArgumentSupport.requireNonNull(excelFile, "excelFile");

        builder.coreBuilder.toFile(excelFile);
    }

    /**
     * Writes the builder's configured sample template to the servlet response with download headers, buffering
     * it in full first.
     *
     * <p>Internal: called by {@link Builder#toResponse(HttpServletResponse, String)}.</p>
     *
     * @param builder       the configured sample export builder
     * @param response      the servlet response to write to
     * @param excelFilename the download file name without extension, or {@code null}/blank for the fallback
     * @throws PxlException if {@code builder} or {@code response} is {@code null}, the builder's source
     *                      combination is invalid, or writing the response fails
     */
    @PxlPerformanceLogging(TAG)
    public void exportSampleExcelToResponse(@NotNull final Builder builder,
                                            @NotNull final HttpServletResponse response,
                                            @Nullable final String excelFilename)
            throws PxlException {

        PxlArgumentSupport.requireNonNull(response, "response");

        final String resolvedFilename = builder.resolveFilename(excelFilename);
        final PxlFileFormat fileFormat = builder.resolveFileFormat();

        // Generate fully in memory first; the response is only written once the bytes are complete, so a
        // generation failure leaves the response - including any CORS headers added upstream - untouched.
        final ByteArrayOutputStream outputStream = new ByteArrayOutputStream(PxlSpringConstants.DOWNLOAD_BUFFER_INITIAL_BYTES);
        builder.coreBuilder.toStream(outputStream);

        PxlExportSupport.writeBufferToResponseForExportExcel(outputStream, resolvedFilename, fileFormat, response);
    }

    /**
     * Writes the builder's configured sample template straight to the servlet response, without buffering it
     * first.
     *
     * <p>Internal: called by {@link Builder#toResponseStreaming(HttpServletResponse, String)}; see that method
     * for what this shape gives up.</p>
     *
     * @param builder       the configured sample export builder
     * @param response      the servlet response to write to
     * @param excelFilename the download file name without extension, or {@code null}/blank for the fallback
     * @throws PxlException if {@code builder} or {@code response} is {@code null}, the builder's source
     *                      combination is invalid, or writing the response fails
     */
    @PxlPerformanceLogging(TAG)
    public void exportSampleExcelToResponseStreaming(@NotNull final Builder builder,
                                                     @NotNull final HttpServletResponse response,
                                                     @Nullable final String excelFilename)
            throws PxlException {

        PxlArgumentSupport.requireNonNull(response, "response");

        final String resolvedFilename = builder.resolveFilename(excelFilename);
        final PxlFileFormat fileFormat = builder.resolveFileFormat();

        // Headers must go out before the body, so they are set before anything can fail. Past this point the
        // response is committed and a failure cannot be taken back - that is the trade this terminal asks for,
        // and no Content-Length is possible because the size is not known yet.
        PxlExportSupport.setResponseForExportExcel(resolvedFilename, fileFormat, response);

        try {
            builder.coreBuilder.toStream(response.getOutputStream());
        } catch (IOException e) {
            throw new PxlIOException(e);
        }
    }

    /**
     * Returns the builder's configured sample template as a {@link ResponseEntity} with download headers.
     *
     * <p>Internal: called by {@link Builder#toResponseEntity(String)}.</p>
     *
     * @param builder       the configured sample export builder
     * @param excelFilename the download file name without extension, or {@code null}/blank for the fallback
     * @return the response entity carrying the template bytes
     * @throws PxlException if {@code builder} is {@code null}, the builder's source combination is invalid,
     *                      or building the response fails
     */
    @PxlPerformanceLogging(TAG)
    public ResponseEntity<Resource> exportSampleExcelToResponseEntity(@NotNull final Builder builder,
                                                                      @Nullable final String excelFilename)
            throws PxlException {

        final String resolvedFilename = builder.resolveFilename(excelFilename);
        final PxlFileFormat fileFormat = builder.resolveFileFormat();

        ByteArrayOutputStream outputStream = null;
        try {
            outputStream = new ByteArrayOutputStream(PxlSpringConstants.DOWNLOAD_BUFFER_INITIAL_BYTES);
            builder.coreBuilder.toStream(outputStream);
            return PxlExportSupport.makeResponseEntityForExportExcel(resolvedFilename, fileFormat, outputStream);
        } finally {
            IOUtils.closeQuietly(outputStream);
        }
    }

    /**
     * Fluent builder for the sample-template destinations of {@link PxlSampleExcelExporter}. Created via
     * {@link PxlSampleExcelExporter#exportSampleExcel()}.
     *
     * <p>It mirrors the core {@code io.github.hclimkr.pxl.builder.PxlSampleExcelExportBuilder} shape — source
     * methods, then {@link #override(PxlExportWorkbookOption)}, then a terminal — and adds the Spring-facing
     * destinations ({@link HttpServletResponse} / {@link ResponseEntity}) plus their download-name handling.</p>
     *
     * <p>There are two mutually exclusive source forms:</p>
     * <ul>
     *   <li>{@code @PxlWorkbook} class form: {@link #workbook(Class)}</li>
     *   <li>Sheet form (call repeatedly for multiple sheets): {@link #sheet(Class, String)}</li>
     * </ul>
     *
     * <p>Terminal methods: {@link #toStream(OutputStream)}, {@link #toFile(File)},
     * {@link #toResponse(HttpServletResponse, String)},
     * {@link #toResponseStreaming(HttpServletResponse, String)}, {@link #toResponseEntity(String)}. The builder
     * holds the collected arguments only; each terminal delegates straight back to the enclosing component so
     * the work still runs inside a Spring-proxied, {@code @PxlPerformanceLogging}-annotated method.</p>
     *
     * <p>Nested in the component on purpose: everything the component reads off the builder — its
     * constructor, {@code coreBuilder}, {@code resolveFilename(String)}, {@code resolveFileFormat()} — is
     * {@code private} and stays reachable only because the two are nestmates. The public surface is exactly
     * the source, option and terminal methods.</p>
     *
     * <p>Not thread-safe, and single-use per terminal call. Example:
     * {@code pxlSpring.exportSampleExcel().sheet(User.class, "Users").toResponse(response, "sample");}</p>
     */
    public static final class Builder {

        /**
         * Download file name used when the response terminal is given none.
         */
        private static final String DEFAULT_EXPORT_SAMPLE_EXCEL_FILENAME = "PxlSample";

        /**
         * The owning component; terminals call back into it so the export runs through its AOP proxy.
         */
        private final PxlSampleExcelExporter exporter;

        /**
         * The core sample export builder, used as the store for the workbook/sheet source and the export
         * option. Read directly by the enclosing component's back-ends.
         *
         * <p>Source validation (both forms specified, or neither) is left to that builder, which raises
         * {@link PxlArgumentException} from its own terminal.</p>
         */
        private final PxlSampleExcelExportBuilder coreBuilder;

        /**
         * The {@code @PxlWorkbook} source class, kept alongside the core builder because the export engine
         * behind the download file format falls back to what it declares. {@code null} for the sheet form.
         */
        private Class<?> workbookClass;

        /**
         * Mirrors the option handed to {@code coreBuilder}, so {@code resolveFileFormat()} can read its export
         * engine back.
         */
        private PxlExportWorkbookOption workbookOption;

        /**
         * Creates a builder bound to the given core entry point and owning component.
         *
         * @param pxl      the core entry point used to create the underlying sample export builder
         * @param exporter the component the terminal methods delegate back to (its AOP proxy where available)
         */
        private Builder(final Pxl pxl, final PxlSampleExcelExporter exporter) {

            this.coreBuilder = pxl.exportSampleExcel();
            this.exporter = exporter;
        }

        // ----- source -----

        /**
         * Generates the sample template from a class annotated with {@code @PxlWorkbook}.
         *
         * <p>On the response destinations this form also supplies the file-format fallback: an option without
         * an export engine falls back to the class's declared engine, whose file format drives the headers.</p>
         *
         * @param workbookClass the {@code @PxlWorkbook}-annotated class
         * @return this builder
         * @throws PxlNullPointerException if {@code workbookClass} is {@code null}
         */
        public Builder workbook(final Class<?> workbookClass)
                throws PxlNullPointerException {

            this.coreBuilder.workbook(workbookClass);
            this.workbookClass = workbookClass;
            return this;
        }

        /**
         * Adds a sheet whose columns are described by the given row class. Calling this repeatedly produces
         * multiple sheets.
         *
         * @param rowClass  the row class describing the columns
         * @param sheetName the sheet name; must not be blank
         * @return this builder
         * @throws PxlNullPointerException if {@code rowClass} or {@code sheetName} is {@code null}
         * @throws PxlArgumentException    if {@code sheetName} is blank
         */
        public Builder sheet(final Class<?> rowClass,
                             final String sheetName)
                throws PxlNullPointerException, PxlArgumentException {

            this.coreBuilder.sheet(rowClass, sheetName);
            return this;
        }

        // ----- options -----

        /**
         * Overrides annotation-declared values with the given export option. (Optional)
         *
         * @param option the export option, or {@code null}
         * @return this builder
         */
        public Builder override(@Nullable final PxlExportWorkbookOption option) {

            this.coreBuilder.override(option);
            this.workbookOption = option;
            return this;
        }

        // ----- terminals -----

        /**
         * Writes the configured sample template to the given output stream.
         *
         * @param outputStream the destination stream (not closed by this method)
         * @throws PxlException if {@code outputStream} is {@code null}, both or neither of the source forms
         *                      were configured, or generation fails
         */
        public void toStream(final OutputStream outputStream)
                throws PxlException {

            exporter.exportSampleExcelToStream(this, outputStream);
        }

        /**
         * Writes the configured sample template to the given file.
         *
         * @param excelFile the destination file
         * @throws PxlException if {@code excelFile} is {@code null}, both or neither of the source forms were
         *                      configured, or writing fails
         */
        public void toFile(final File excelFile)
                throws PxlException {

            exporter.exportSampleExcelToFile(this, excelFile);
        }

        /**
         * Streams the configured sample template to the servlet response with download headers.
         *
         * <p>When {@code excelFilename} is blank the name falls back to {@code PxlSample}. The name is used as
         * given — normalize it (NFC) upstream if needed; RFC 5987 encoding is applied when the header is
         * written.</p>
         *
         * @param response      the servlet response to write to
         * @param excelFilename the download file name without extension, or {@code null}/blank for the fallback
         * @throws PxlException if {@code response} is {@code null}, both or neither of the source forms were
         *                      configured, or writing the response fails
         */
        public void toResponse(final HttpServletResponse response,
                               @Nullable final String excelFilename)
                throws PxlException {

            exporter.exportSampleExcelToResponse(this, response, excelFilename);
        }

        /**
         * Streams the configured sample template straight to the servlet response, without buffering it first.
         *
         * <p>Trades away the two things {@link #toResponse(HttpServletResponse, String)} buys by buffering:</p>
         * <ul>
         *   <li><strong>A failure part-way through cannot be taken back.</strong> The response is already
         *       committed with {@code 200 OK} and the download headers, so the client receives a truncated
         *       file.</li>
         *   <li>No {@code Content-Length}, so the response goes out chunked and clients show no download
         *       progress.</li>
         * </ul>
         *
         * <p>Note where the line falls, because it sits further back here than on the other two exporters.
         * Only the {@code null}-destination guard runs before the headers; this builder has no source check of
         * its own at all — "both or neither source form" is the core builder's call, made inside its own
         * terminal, which here is <strong>after</strong> the headers have gone out. Such a failure writes no
         * body but does leave the download headers set.</p>
         *
         * <p>Present for consistency across the export builders rather than because templates need it: a
         * template is a header row plus one sample row per sheet, so the buffer is rarely what hurts here.
         * Reach for it when a workbook class produces many sheets and you would rather not hold the result
         * at all. There is no streaming counterpart for the other destinations:
         * {@link #toStream(OutputStream)} and {@link #toFile(File)} already write straight through, and
         * {@link #toResponseEntity(String)} cannot, because its body is produced in full before the entity is
         * returned.</p>
         *
         * @param response      the servlet response to write to
         * @param excelFilename the download file name without extension, or {@code null}/blank for the fallback
         * @throws PxlException if {@code response} is {@code null}, both or neither of the source forms were
         *                      configured, or writing the response fails
         */
        public void toResponseStreaming(final HttpServletResponse response,
                                        @Nullable final String excelFilename)
                throws PxlException {

            exporter.exportSampleExcelToResponseStreaming(this, response, excelFilename);
        }

        /**
         * Returns the configured sample template as a {@link ResponseEntity} with download headers.
         *
         * <p>The name is resolved exactly as in {@link #toResponse(HttpServletResponse, String)}.</p>
         *
         * @param excelFilename the download file name without extension, or {@code null}/blank for the fallback
         * @return the response entity carrying the template bytes
         * @throws PxlException if both or neither of the source forms were configured, or building the
         *                      response fails
         */
        public ResponseEntity<Resource> toResponseEntity(@Nullable final String excelFilename)
                throws PxlException {

            return exporter.exportSampleExcelToResponseEntity(this, excelFilename);
        }

        // ----- resolution helpers read by PxlSampleExcelExporter -----
        // private: the component is this class's nestmate, so nothing here needs to be exposed.

        /**
         * Resolves the download file name: the name given to the response terminal, else {@code PxlSample}.
         *
         * <p>Unlike the Excel exporter's builder there is no {@code @PxlWorkbook} workbook-name fallback: that
         * name is an instance field value and this builder only ever holds a class.</p>
         *
         * @param excelFilename the name given to the response terminal, or {@code null}/blank
         * @return the download file name without extension
         */
        private String resolveFilename(@Nullable final String excelFilename) {

            return StringUtils.isNotBlank(excelFilename) ? excelFilename : DEFAULT_EXPORT_SAMPLE_EXCEL_FILENAME;
        }

        /**
         * Resolves the file format driving the download headers: the format written by the option's export
         * engine, then by the {@code @PxlWorkbook} declared engine (workbook-class form only), then the
         * default.
         *
         * @return the file format for the download headers
         */
        private PxlFileFormat resolveFileFormat() {

            final PxlExcelEngine optionExcelEngine = Optional.ofNullable(workbookOption)
                    .flatMap(option -> Optional.ofNullable(option.getExportExcelEngine()))
                    .orElse(null);
            if (Objects.nonNull(optionExcelEngine)) {
                return optionExcelEngine.getFileFormat();
            }

            return Objects.nonNull(workbookClass)
                    ? PxlExcelEngine.fromWorkbookObject(workbookClass).getFileFormat()
                    : PxlConstants.DEFAULT_EXPORT_FILE_FORMAT;
        }

    }

}
