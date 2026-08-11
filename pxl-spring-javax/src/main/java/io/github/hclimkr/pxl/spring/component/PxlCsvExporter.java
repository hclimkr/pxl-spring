package io.github.hclimkr.pxl.spring.component;

import io.github.hclimkr.pxl.Pxl;
import io.github.hclimkr.pxl.PxlConstants;
import io.github.hclimkr.pxl.builder.PxlCsvExportBuilder;
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
import java.util.Collection;
import java.util.Objects;

/**
 * Spring component that exports Java objects to CSV.
 *
 * <p>Everything is configured through the fluent builder returned by {@link #exportCsv()}, which mirrors the
 * core {@code Pxl.exportCsv()} shape - pick the sheet ({@code sheet(...)}), optionally {@code override(...)}
 * the option, then call a terminal ({@code toStream} / {@code toFile} / {@code toResponse} /
 * {@code toResponseStreaming} / {@code toResponseEntity}); the response terminals take the download file name
 * as an argument:</p>
 *
 * <pre>{@code
 * pxlSpring.exportCsv().sheet(User.class, users, "Users").toResponse(response, null);   // "Users.csv"
 * pxlSpring.exportCsv().sheet(User.class, users, "Users").toResponseEntity("report");
 * pxlSpring.exportCsv().sheet(User.class, users, "Users").override(option).toFile(file);
 * }</pre>
 *
 * <p><strong>A CSV file holds one sheet</strong>, so there is no {@code workbook(...)} form to call and the
 * terminals write a single sheet - configuring more than one fails there. The output format is always
 * {@code .csv}: nothing on the option switches it, so the download headers need no resolving.</p>
 *
 * <p>What CSV cannot carry - stylers, column widths, freeze panes, the Excel engine - the core ignores, with
 * one refusal: {@code exportPassword} is rejected rather than ignored, because CSV cannot be encrypted and
 * writing plaintext would be a leak. The charset, field delimiter and byte order mark come from
 * {@code @PxlWorkbook}/{@code @PxlSheet} or the matching option fields. See the core builder for the full
 * list.</p>
 *
 * <p>That builder is the nested {@link Builder}. A fluent chain never has to name it; on the rare occasion
 * you hold one in a variable, spell it {@code PxlCsvExporter.Builder}.</p>
 *
 * <p>The component is stateless and safe to share across threads; the builder it hands back is not - start one
 * per export.</p>
 *
 * <p>Reached through {@link io.github.hclimkr.pxl.spring.PxlSpring PxlSpring}: inject that one bean and call
 * {@code pxlSpring.exportCsv()}, which hands back the builder documented here.</p>
 *
 * <p>The {@code exportCsvTo*} methods below are the builder's execution back-ends. They are {@code public}
 * only because Spring AOP (and {@code @Validated} method validation) can advise public methods only - a
 * terminal has to re-enter this component through its proxy for {@link PxlPerformanceLogging} to fire. Treat
 * them as internal and always go through {@link #exportCsv()}.</p>
 *
 * <p>Because those back-ends' {@code @NotNull} constraints only fire through the proxy, each one re-checks
 * its destination with {@code PxlArgumentSupport} so a plainly constructed component fails the same way at
 * the same point - see that class for why.</p>
 */
@Validated
@Component
public class PxlCsvExporter {

    private static final String TAG = "PxlCsvExporter";

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
     * {@code new PxlCsvExporter()} usage (outside a Spring context) working - it then falls back to
     * {@code this} and simply produces no performance log.</p>
     */
    @Autowired(required = false)
    @Lazy
    private PxlCsvExporter self;

    /**
     * Starts a fluent CSV export.
     *
     * @return a new builder bound to this component
     */
    public Builder exportCsv() {

        return new Builder(pxl, Objects.nonNull(self) ? self : this);
    }

    // ----- builder execution back-ends (internal; reached through the nested Builder) -----

    /**
     * Writes the builder's configured export to the given output stream.
     *
     * <p>Internal: called by {@link Builder#toStream(OutputStream)}.</p>
     *
     * @param builder      the configured export builder
     * @param outputStream the destination stream (not closed by this method)
     * @throws PxlException if {@code builder} or {@code outputStream} is {@code null}, no sheet or more than
     *                      one sheet was configured, a password was requested, or export fails
     */
    @PxlPerformanceLogging(TAG)
    public void exportCsvToStream(@NotNull final Builder builder,
                                  @NotNull final OutputStream outputStream)
            throws PxlException {

        PxlArgumentSupport.requireNonNull(outputStream, "outputStream");

        builder.coreBuilder.toStream(outputStream);
    }

    /**
     * Writes the builder's configured export to the given file.
     *
     * <p>Internal: called by {@link Builder#toFile(File)}.</p>
     *
     * @param builder the configured export builder
     * @param csvFile the destination file
     * @throws PxlException if {@code builder} or {@code csvFile} is {@code null}, no sheet or more than one
     *                      sheet was configured, a password was requested, the file cannot be opened, or
     *                      writing fails
     */
    @PxlPerformanceLogging(TAG)
    public void exportCsvToFile(@NotNull final Builder builder,
                                @NotNull final File csvFile)
            throws PxlException {

        PxlArgumentSupport.requireNonNull(csvFile, "csvFile");

        builder.coreBuilder.toFile(csvFile);
    }

    /**
     * Writes the builder's configured export to the servlet response with download headers, buffering it in
     * full first.
     *
     * <p>Internal: called by {@link Builder#toResponse(HttpServletResponse, String)}.</p>
     *
     * @param builder     the configured export builder
     * @param response    the servlet response to write to
     * @param csvFilename the download file name without extension, or {@code null}/blank for the fallback
     * @throws PxlException if {@code builder} or {@code response} is {@code null}, no sheet or more than one
     *                      sheet was configured, a password was requested, or writing the response fails
     */
    @PxlPerformanceLogging(TAG)
    public void exportCsvToResponse(@NotNull final Builder builder,
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
     * Writes the builder's configured export straight to the servlet response, without buffering it first.
     *
     * <p>Internal: called by {@link Builder#toResponseStreaming(HttpServletResponse, String)}; see that method
     * for what this shape gives up.</p>
     *
     * @param builder     the configured export builder
     * @param response    the servlet response to write to
     * @param csvFilename the download file name without extension, or {@code null}/blank for the fallback
     * @throws PxlException if {@code builder} or {@code response} is {@code null}, no sheet or more than one
     *                      sheet was configured, a password was requested, or writing the response fails
     */
    @PxlPerformanceLogging(TAG)
    public void exportCsvToResponseStreaming(@NotNull final Builder builder,
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
     * Returns the builder's configured export as a {@link ResponseEntity} with download headers.
     *
     * <p>Internal: called by {@link Builder#toResponseEntity(String)}.</p>
     *
     * @param builder     the configured export builder
     * @param csvFilename the download file name without extension, or {@code null}/blank for the fallback
     * @return the response entity carrying the CSV bytes
     * @throws PxlException if {@code builder} is {@code null}, no sheet or more than one sheet was configured,
     *                      a password was requested, or building the response fails
     */
    @PxlPerformanceLogging(TAG)
    public ResponseEntity<Resource> exportCsvToResponseEntity(@NotNull final Builder builder,
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
     * Fluent builder for the CSV export destinations of {@link PxlCsvExporter}. Created via
     * {@link PxlCsvExporter#exportCsv()}.
     *
     * <p>It mirrors the core {@code io.github.hclimkr.pxl.builder.PxlCsvExportBuilder} shape - the sheet
     * source, then {@link #override(PxlExportWorkbookOption)}, then a terminal - and adds the Spring-facing
     * destinations ({@link HttpServletResponse} / {@link ResponseEntity}) plus their download-name handling.</p>
     *
     * <p>There is one source form, {@link #sheet(Class, Collection, String)}, because a CSV file holds one
     * sheet. It accumulates like the Excel builder's counterpart, but a terminal writes a single sheet, so
     * calling it more than once fails there rather than here.</p>
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
     * {@code pxlSpring.exportCsv().sheet(User.class, users, "Users").toResponse(response, "report");}</p>
     */
    public static final class Builder {

        /**
         * Download file name used when neither the name given to a response terminal nor the sheet name
         * yields one.
         */
        private static final String DEFAULT_EXPORT_CSV_FILENAME = "Pxl";

        /**
         * The owning component; terminals call back into it so the export runs through its AOP proxy.
         */
        private final PxlCsvExporter exporter;

        /**
         * The core export builder, used as the store for the sheet source and the export option. Read
         * directly by the enclosing component's back-ends.
         *
         * <p>Source validation (no sheet, or more than one) is left to that builder, which raises
         * {@link PxlArgumentException} from its own terminal.</p>
         */
        private final PxlCsvExportBuilder coreBuilder;

        /**
         * The name of the first sheet configured, kept alongside the core builder because the download name
         * falls back to it. The core exposes no getter, and it is the first sheet the core writes.
         */
        private String sheetName;

        /**
         * Creates a builder bound to the given core entry point and owning component.
         *
         * @param pxl      the core entry point used to create the underlying export builder
         * @param exporter the component the terminal methods delegate back to (its AOP proxy where available)
         */
        private Builder(final Pxl pxl, final PxlCsvExporter exporter) {

            this.coreBuilder = pxl.exportCsv();
            this.exporter = exporter;
        }

        // ----- source -----

        /**
         * Sets the sheet to write from a row collection.
         *
         * <p>A CSV file holds one sheet, so calling this a second time does not add one - it makes the
         * terminal fail. The sheet name is what the file's <em>content</em> is named after in the workbook
         * sense, and on the response destinations it also supplies the download-name fallback.</p>
         *
         * @param rowClass  the row class
         * @param rows      the row objects for this sheet
         * @param sheetName the sheet name; must not be blank
         * @param <T>       the row type
         * @return this builder
         * @throws PxlNullPointerException if {@code rowClass}, {@code rows}, or {@code sheetName} is {@code null}
         * @throws PxlArgumentException    if {@code sheetName} is blank
         */
        public <T> Builder sheet(final Class<T> rowClass,
                                 final Collection<T> rows,
                                 final String sheetName)
                throws PxlNullPointerException, PxlArgumentException {

            this.coreBuilder.sheet(rowClass, rows, sheetName);

            // recorded only once the core accepted it, and only for the first call: the core writes sheet 0,
            // so that is the one the download name may fall back to
            if (Objects.isNull(this.sheetName)) {
                this.sheetName = sheetName;
            }
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
         * Writes the configured export to the given output stream.
         *
         * @param outputStream the destination stream (not closed by this method)
         * @throws PxlException if {@code outputStream} is {@code null}, no sheet or more than one sheet was
         *                      configured, or export fails
         */
        public void toStream(final OutputStream outputStream)
                throws PxlException {

            exporter.exportCsvToStream(this, outputStream);
        }

        /**
         * Writes the configured export to the given file.
         *
         * @param csvFile the destination file
         * @throws PxlException if {@code csvFile} is {@code null}, no sheet or more than one sheet was
         *                      configured, or writing fails
         */
        public void toFile(final File csvFile)
                throws PxlException {

            exporter.exportCsvToFile(this, csvFile);
        }

        /**
         * Streams the configured export to the servlet response with download headers.
         *
         * <p>When {@code csvFilename} is blank the name falls back to the sheet name and then to {@code Pxl}.
         * The name is used as given - normalize it (NFC) upstream if needed; RFC 5987 encoding is applied when
         * the header is written.</p>
         *
         * @param response    the servlet response to write to
         * @param csvFilename the download file name without extension, or {@code null}/blank for the fallback
         * @throws PxlException if {@code response} is {@code null}, no sheet or more than one sheet was
         *                      configured, or writing the response fails
         */
        public void toResponse(final HttpServletResponse response,
                               @Nullable final String csvFilename)
                throws PxlException {

            exporter.exportCsvToResponse(this, response, csvFilename);
        }

        /**
         * Streams the configured export straight to the servlet response, without buffering it first.
         *
         * <p>Be clear about what this buys on the CSV side. The core still renders the whole file before the
         * destination is opened - that is what keeps a codec or validation failure from leaving a half-written
         * file behind - but it holds only the first {@link PxlConstants#EXPORT_MEMORY_THRESHOLD_OF_CSV} of that
         * rendering in memory and spills the rest to a temporary file. So the copy this terminal drops - the
         * download buffer {@link #toResponse(HttpServletResponse, String)} builds - is the last one that grows
         * with the output, and streaming a large CSV costs roughly constant heap. What the spill trades for
         * that is disk: the temporary file needs free space and is written unencrypted under
         * {@code java.io.tmpdir}, though the core removes it before the call returns either way.</p>
         *
         * <p>What it gives up is the same trade as on the Excel exporters:</p>
         * <ul>
         *   <li><strong>A failure part-way through cannot be taken back.</strong> The response is already
         *       committed with {@code 200 OK} and the download headers, so the client receives a truncated
         *       file.</li>
         *   <li>No {@code Content-Length}, so the response goes out chunked and clients show no download
         *       progress.</li>
         * </ul>
         *
         * <p>Note where the line falls. Only the {@code null}-destination guard runs before the headers; this
         * builder has no source check of its own - "no sheet, or more than one" is the core builder's call,
         * made inside its own terminal, which here is <strong>after</strong> the headers have gone out. So is
         * the password refusal, and so is every other check the core makes. Such a failure writes no body but
         * does leave the download headers set.</p>
         *
         * <p>There is no streaming counterpart for the other destinations: {@link #toStream(OutputStream)} and
         * {@link #toFile(File)} already write straight through, and {@link #toResponseEntity(String)} cannot,
         * because its body is produced in full before the entity is returned - the {@link Resource} it carries
         * wraps the finished bytes rather than generating them on demand.</p>
         *
         * @param response    the servlet response to write to
         * @param csvFilename the download file name without extension, or {@code null}/blank for the fallback
         * @throws PxlException if {@code response} is {@code null}, no sheet or more than one sheet was
         *                      configured, or writing the response fails
         */
        public void toResponseStreaming(final HttpServletResponse response,
                                        @Nullable final String csvFilename)
                throws PxlException {

            exporter.exportCsvToResponseStreaming(this, response, csvFilename);
        }

        /**
         * Returns the configured export as a {@link ResponseEntity} with download headers.
         *
         * <p>The name is resolved exactly as in {@link #toResponse(HttpServletResponse, String)}.</p>
         *
         * @param csvFilename the download file name without extension, or {@code null}/blank for the fallback
         * @return the response entity carrying the CSV bytes
         * @throws PxlException if no sheet or more than one sheet was configured, or building the response
         *                      fails
         */
        public ResponseEntity<Resource> toResponseEntity(@Nullable final String csvFilename)
                throws PxlException {

            return exporter.exportCsvToResponseEntity(this, csvFilename);
        }

        // ----- resolution helpers read by PxlCsvExporter -----
        // private: the component is this class's nestmate, so nothing here needs to be exposed.

        /**
         * Resolves the download file name: the name given to the response terminal, else the sheet name, else
         * {@code Pxl}.
         *
         * <p>The sheet name stands in for the Excel exporter's {@code @PxlWorkbook} workbook-name fallback:
         * one CSV file is one sheet, so the sheet name is the only name the source carries. It is always
         * present once {@code sheet(...)} has been called - the core rejects a blank one - which leaves the
         * constant for the chain that reaches a terminal having configured no sheet at all.</p>
         *
         * <p>There is no file-format resolution to go with this: CSV is the only format this exporter writes,
         * and no option switches it.</p>
         *
         * @param csvFilename the name given to the response terminal, or {@code null}/blank
         * @return the download file name without extension
         */
        private String resolveFilename(@Nullable final String csvFilename) {

            if (StringUtils.isNotBlank(csvFilename)) {
                return csvFilename;
            }

            return StringUtils.isNotBlank(sheetName) ? sheetName : DEFAULT_EXPORT_CSV_FILENAME;
        }

    }

}
