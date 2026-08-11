package io.github.hclimkr.pxl.spring.component;

import io.github.hclimkr.pxl.Pxl;
import io.github.hclimkr.pxl.builder.PxlSampleCsvExportBuilder;
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
import io.github.hclimkr.pxl.type.PxlFileFormat;
import org.apache.commons.io.IOUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.core.io.Resource;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.util.FastByteArrayOutputStream;
import org.springframework.validation.annotation.Validated;

import javax.annotation.Nullable;
import javax.servlet.http.HttpServletResponse;
import javax.validation.constraints.NotNull;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.util.Objects;

/**
 * Spring component that generates sample template CSV files from a row class.
 *
 * <p>The template is <strong>not empty</strong>: it holds a header record plus a single sample data record
 * whose fields come from each column's {@code @PxlColumn(exportSample = ...)} value, so the recipient sees a
 * worked example of the expected format alongside the column headers - and can fill it in and send it back
 * through {@code importCsv()}.</p>
 *
 * <p>Everything is configured through the fluent builder returned by {@link #exportSampleCsv()}, which mirrors
 * the core {@code Pxl.exportSampleCsv()} shape - pick the sheet ({@code sheet(...)}), optionally
 * {@code override(...)} the option, then call a terminal ({@code toStream} / {@code toFile} /
 * {@code toResponse} / {@code toResponseStreaming} / {@code toResponseEntity}); the response terminals take
 * the download file name as an argument:</p>
 *
 * <pre>{@code
 * pxlSpring.exportSampleCsv().sheet(User.class, "Users").toResponse(response, null);   // "PxlSample.csv"
 * pxlSpring.exportSampleCsv().sheet(User.class, "Users").toResponseEntity("sample");
 * pxlSpring.exportSampleCsv().sheet(User.class, "Users").toFile(file);
 * }</pre>
 *
 * <p><strong>A CSV file holds one sheet</strong>, so there is no {@code workbook(...)} form to call and the
 * terminals write a single sheet - configuring more than one fails there. The download file name defaults to
 * {@code PxlSample}, and the format is always {@code .csv}.</p>
 *
 * <p>That builder is the nested {@link Builder}. A fluent chain never has to name it; on the rare occasion
 * you hold one in a variable, spell it {@code PxlSampleCsvExporter.Builder}.</p>
 *
 * <p>The component is stateless and safe to share across threads; the builder it hands back is not - start one
 * per export.</p>
 *
 * <p>Reached through {@link io.github.hclimkr.pxl.spring.PxlSpring PxlSpring}: inject that one bean and call
 * {@code pxlSpring.exportSampleCsv()}, which hands back the builder documented here.</p>
 *
 * <p>The {@code exportSampleCsvTo*} methods below are the builder's execution back-ends. They are
 * {@code public} only because Spring AOP (and {@code @Validated} method validation) can advise public methods
 * only - a terminal has to re-enter this component through its proxy for {@link PxlPerformanceLogging} to fire.
 * Treat them as internal and always go through {@link #exportSampleCsv()}.</p>
 *
 * <p>Because those back-ends' {@code @NotNull} constraints only fire through the proxy, each one re-checks
 * its destination with {@code PxlArgumentSupport} so a plainly constructed component fails the same way at
 * the same point - see that class for why.</p>
 */
@Validated
@Component
public class PxlSampleCsvExporter {

    private static final String TAG = "PxlSampleCsvExporter";

    /**
     * The core entry point, shared with the other components - see {@link PxlCoreSupport} for why it is not
     * one instance per component.
     */
    private final Pxl pxl = PxlCoreSupport.core();

    /**
     * This component's own AOP proxy, injected by Spring where available.
     *
     * <p>The builder's terminals must call back through the proxy, not through {@code this}: a plain
     * {@code this} reference bypasses the proxy, and with it {@link PxlPerformanceLogging} and {@code @Validated}.
     * {@code @Lazy} breaks the self-reference cycle, and {@code required = false} keeps plain
     * {@code new PxlSampleCsvExporter()} usage (outside a Spring context) working - it then falls back to
     * {@code this} and simply produces no performance log.</p>
     */
    @Autowired(required = false)
    @Lazy
    private PxlSampleCsvExporter self;

    /**
     * Starts a fluent CSV sample template export.
     *
     * @return a new builder bound to this component
     */
    public Builder exportSampleCsv() {

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
     * @throws PxlException if {@code builder} or {@code outputStream} is {@code null}, no sheet or more than
     *                      one sheet was configured, or generation fails
     */
    @PxlPerformanceLogging(TAG)
    public void exportSampleCsvToStream(@NotNull final Builder builder,
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
     * @param builder the configured sample export builder
     * @param csvFile the destination file
     * @throws PxlException if {@code builder} or {@code csvFile} is {@code null}, no sheet or more than one
     *                      sheet was configured, or writing fails
     */
    @PxlPerformanceLogging(TAG)
    public void exportSampleCsvToFile(@NotNull final Builder builder,
                                      @NotNull final File csvFile)
            throws PxlException {

        PxlArgumentSupport.requireNonNull(csvFile, "csvFile");

        builder.coreBuilder.toFile(csvFile);
    }

    /**
     * Writes the builder's configured sample template to the servlet response with download headers, buffering
     * it in full first.
     *
     * <p>Internal: called by {@link Builder#toResponse(HttpServletResponse, String)}.</p>
     *
     * @param builder     the configured sample export builder
     * @param response    the servlet response to write to
     * @param csvFilename the download file name without extension, or {@code null}/blank for the fallback
     * @throws PxlException if {@code builder} or {@code response} is {@code null}, no sheet or more than one
     *                      sheet was configured, or writing the response fails
     */
    @PxlPerformanceLogging(TAG)
    public void exportSampleCsvToResponse(@NotNull final Builder builder,
                                          @NotNull final HttpServletResponse response,
                                          @Nullable final String csvFilename)
            throws PxlException {

        PxlArgumentSupport.requireNonNull(response, "response");

        final String resolvedFilename = builder.resolveFilename(csvFilename);

        // Generate fully in memory first; the response is only written once the bytes are complete, so a
        // generation failure leaves the response - including any CORS headers added upstream - untouched.
        final FastByteArrayOutputStream outputStream = new FastByteArrayOutputStream(PxlSpringConstants.DOWNLOAD_BUFFER_INITIAL_BYTES);
        builder.coreBuilder.toStream(outputStream);

        PxlExportSupport.writeBufferToResponseForExport(outputStream, resolvedFilename, PxlFileFormat.CSV, response);
    }

    /**
     * Writes the builder's configured sample template straight to the servlet response, without buffering it
     * first.
     *
     * <p>Internal: called by {@link Builder#toResponseStreaming(HttpServletResponse, String)}; see that method
     * for what this shape gives up.</p>
     *
     * @param builder     the configured sample export builder
     * @param response    the servlet response to write to
     * @param csvFilename the download file name without extension, or {@code null}/blank for the fallback
     * @throws PxlException if {@code builder} or {@code response} is {@code null}, no sheet or more than one
     *                      sheet was configured, or writing the response fails
     */
    @PxlPerformanceLogging(TAG)
    public void exportSampleCsvToResponseStreaming(@NotNull final Builder builder,
                                                   @NotNull final HttpServletResponse response,
                                                   @Nullable final String csvFilename)
            throws PxlException {

        PxlArgumentSupport.requireNonNull(response, "response");

        final String resolvedFilename = builder.resolveFilename(csvFilename);

        // Headers must go out before the body, so they are set before anything can fail. Past this point the
        // response is committed and a failure cannot be taken back - that is the trade this terminal asks for,
        // and no Content-Length is possible because the size is not known yet.
        PxlExportSupport.setResponseForExport(resolvedFilename, PxlFileFormat.CSV, response);

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
     * @param builder     the configured sample export builder
     * @param csvFilename the download file name without extension, or {@code null}/blank for the fallback
     * @return the response entity carrying the template bytes
     * @throws PxlException if {@code builder} is {@code null}, no sheet or more than one sheet was configured,
     *                      or building the response fails
     */
    @PxlPerformanceLogging(TAG)
    public ResponseEntity<Resource> exportSampleCsvToResponseEntity(@NotNull final Builder builder,
                                                                    @Nullable final String csvFilename)
            throws PxlException {

        final String resolvedFilename = builder.resolveFilename(csvFilename);

        // Closed in the finally, before the entity reaches its caller: the body reads the buffer afterwards,
        // which closing does not prevent, but a later write into a body already handed over now fails.
        FastByteArrayOutputStream outputStream = null;
        try {
            outputStream = new FastByteArrayOutputStream(PxlSpringConstants.DOWNLOAD_BUFFER_INITIAL_BYTES);
            builder.coreBuilder.toStream(outputStream);
            return PxlExportSupport.makeResponseEntityForExport(resolvedFilename, PxlFileFormat.CSV, outputStream);
        } finally {
            IOUtils.closeQuietly(outputStream);
        }
    }

    /**
     * Fluent builder for the CSV sample-template destinations of {@link PxlSampleCsvExporter}. Created via
     * {@link PxlSampleCsvExporter#exportSampleCsv()}.
     *
     * <p>It mirrors the core {@code io.github.hclimkr.pxl.builder.PxlSampleCsvExportBuilder} shape - the sheet
     * source, then {@link #override(PxlExportWorkbookOption)}, then a terminal - and adds the Spring-facing
     * destinations ({@link HttpServletResponse} / {@link ResponseEntity}) plus their download-name handling.</p>
     *
     * <p>There is one source form, {@link #sheet(Class, String)}, because a CSV file holds one sheet. It
     * accumulates like the Excel builder's counterpart, but a terminal writes a single sheet, so calling it
     * more than once fails there rather than here.</p>
     *
     * <p>Terminal methods: {@link #toStream(OutputStream)}, {@link #toFile(File)},
     * {@link #toResponse(HttpServletResponse, String)},
     * {@link #toResponseStreaming(HttpServletResponse, String)}, {@link #toResponseEntity(String)}. The builder
     * holds the collected arguments only; each terminal delegates straight back to the enclosing component so
     * the work still runs inside a Spring-proxied, {@code @PxlPerformanceLogging}-annotated method.</p>
     *
     * <p>Nested in the component on purpose: everything the component reads off the builder - its constructor,
     * {@code coreBuilder}, {@code resolveFilename(String)} - is {@code private} and stays reachable only
     * because the two are nestmates. The public surface is exactly the source, option and terminal methods.</p>
     *
     * <p>Not thread-safe, and single-use per terminal call. Example:
     * {@code pxlSpring.exportSampleCsv().sheet(User.class, "Users").toResponse(response, "sample");}</p>
     */
    public static final class Builder {

        /**
         * Download file name used when the response terminal is given none.
         */
        private static final String DEFAULT_EXPORT_SAMPLE_CSV_FILENAME = "PxlSample";

        /**
         * The owning component; terminals call back into it so the export runs through its AOP proxy.
         */
        private final PxlSampleCsvExporter exporter;

        /**
         * The core sample export builder, used as the store for the sheet source and the export option. Read
         * directly by the enclosing component's back-ends.
         *
         * <p>Source validation (no sheet, or more than one) is left to that builder, which raises
         * {@link PxlArgumentException} from its own terminal.</p>
         */
        private final PxlSampleCsvExportBuilder coreBuilder;

        /**
         * Creates a builder bound to the given core entry point and owning component.
         *
         * @param pxl      the core entry point used to create the underlying sample export builder
         * @param exporter the component the terminal methods delegate back to (its AOP proxy where available)
         */
        private Builder(final Pxl pxl, final PxlSampleCsvExporter exporter) {

            this.coreBuilder = pxl.exportSampleCsv();
            this.exporter = exporter;
        }

        // ----- source -----

        /**
         * Sets the sheet to write a sample for, its columns described by the given row class.
         *
         * <p>A CSV file holds one sheet, so calling this a second time does not add one - it makes the
         * terminal fail.</p>
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
            return this;
        }

        // ----- terminals -----

        /**
         * Writes the configured sample template to the given output stream.
         *
         * @param outputStream the destination stream (not closed by this method)
         * @throws PxlException if {@code outputStream} is {@code null}, no sheet or more than one sheet was
         *                      configured, or generation fails
         */
        public void toStream(final OutputStream outputStream)
                throws PxlException {

            exporter.exportSampleCsvToStream(this, outputStream);
        }

        /**
         * Writes the configured sample template to the given file.
         *
         * @param csvFile the destination file
         * @throws PxlException if {@code csvFile} is {@code null}, no sheet or more than one sheet was
         *                      configured, or writing fails
         */
        public void toFile(final File csvFile)
                throws PxlException {

            exporter.exportSampleCsvToFile(this, csvFile);
        }

        /**
         * Streams the configured sample template to the servlet response with download headers.
         *
         * <p>When {@code csvFilename} is blank the name falls back to {@code PxlSample}. The name is used as
         * given - normalize it (NFC) upstream if needed; RFC 5987 encoding is applied when the header is
         * written.</p>
         *
         * @param response    the servlet response to write to
         * @param csvFilename the download file name without extension, or {@code null}/blank for the fallback
         * @throws PxlException if {@code response} is {@code null}, no sheet or more than one sheet was
         *                      configured, or writing the response fails
         */
        public void toResponse(final HttpServletResponse response,
                               @Nullable final String csvFilename)
                throws PxlException {

            exporter.exportSampleCsvToResponse(this, response, csvFilename);
        }

        /**
         * Streams the configured sample template straight to the servlet response, without buffering it first.
         *
         * <p>Present for consistency across the export builders rather than because templates need it, and on
         * the CSV side it is the thinnest of the five: a template is one header record and one sample record,
         * and the core has already rendered both into memory before this writes anything - so what is saved is
         * the download buffer, on an output measured in bytes.</p>
         *
         * <p>It still gives up what streaming always gives up: a failure part-way through cannot be taken back
         * (the response is committed with {@code 200 OK} and the download headers), and no
         * {@code Content-Length} can be sent, so the response goes out chunked.</p>
         *
         * <p>Note where the line falls. Only the {@code null}-destination guard runs before the headers; this
         * builder has no source check of its own - "no sheet, or more than one" is the core builder's call,
         * made inside its own terminal, which here is <strong>after</strong> the headers have gone out. Such a
         * failure writes no body but does leave the download headers set.</p>
         *
         * @param response    the servlet response to write to
         * @param csvFilename the download file name without extension, or {@code null}/blank for the fallback
         * @throws PxlException if {@code response} is {@code null}, no sheet or more than one sheet was
         *                      configured, or writing the response fails
         */
        public void toResponseStreaming(final HttpServletResponse response,
                                        @Nullable final String csvFilename)
                throws PxlException {

            exporter.exportSampleCsvToResponseStreaming(this, response, csvFilename);
        }

        /**
         * Returns the configured sample template as a {@link ResponseEntity} with download headers.
         *
         * <p>The name is resolved exactly as in {@link #toResponse(HttpServletResponse, String)}.</p>
         *
         * @param csvFilename the download file name without extension, or {@code null}/blank for the fallback
         * @return the response entity carrying the template bytes
         * @throws PxlException if no sheet or more than one sheet was configured, or building the response
         *                      fails
         */
        public ResponseEntity<Resource> toResponseEntity(@Nullable final String csvFilename)
                throws PxlException {

            return exporter.exportSampleCsvToResponseEntity(this, csvFilename);
        }

        // ----- resolution helpers read by PxlSampleCsvExporter -----
        // private: the component is this class's nestmate, so nothing here needs to be exposed.

        /**
         * Resolves the download file name: the name given to the response terminal, else {@code PxlSample}.
         *
         * <p>Unlike {@link PxlCsvExporter.Builder} there is no sheet-name fallback. That one names a file
         * holding real data, where the sheet name is the best description of what is in it; a template
         * describes a shape rather than a data set, so it is named as one - the same reasoning that leaves
         * {@link PxlSampleExcelExporter.Builder} with only its constant.</p>
         *
         * <p>There is no file-format resolution to go with this: CSV is the only format this exporter writes,
         * and no option switches it.</p>
         *
         * @param csvFilename the name given to the response terminal, or {@code null}/blank
         * @return the download file name without extension
         */
        private String resolveFilename(@Nullable final String csvFilename) {

            return StringUtils.isNotBlank(csvFilename) ? csvFilename : DEFAULT_EXPORT_SAMPLE_CSV_FILENAME;
        }

    }

}
