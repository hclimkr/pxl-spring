package io.github.hclimkr.pxl.spring.component;

import io.github.hclimkr.pxl.Pxl;
import io.github.hclimkr.pxl.PxlConstants;
import io.github.hclimkr.pxl.builder.PxlExcelExportBuilder;
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
import io.github.hclimkr.pxl.type.PxlExcelEngine;
import io.github.hclimkr.pxl.type.PxlFileFormat;
import io.github.hclimkr.pxl.util.PxlWorkbookUtils;
import org.apache.commons.io.IOUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.poi.ss.usermodel.Workbook;
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
import java.io.*;
import java.util.Collection;
import java.util.Objects;
import java.util.Optional;

/**
 * Spring component that exports Java objects (or sheet-level data) to Excel.
 *
 * <p>Everything is configured through the fluent builder returned by {@link #exportExcel()}, which mirrors
 * the core {@code Pxl.exportExcel()} shape - pick a source ({@code workbook(...)} / {@code sheet(...)} /
 * {@code poiWorkbook(...)}), optionally {@code override(...)} the option, then call a terminal
 * ({@code toStream} / {@code toFile} / {@code toResponse} / {@code toResponseStreaming} /
 * {@code toResponseEntity}); the response terminals take the download file name as an argument:</p>
 *
 * <pre>{@code
 * pxlSpring.exportExcel().workbook(reportDto).toResponse(response, null);   // name from @PxlWorkbook
 * pxlSpring.exportExcel().sheet(User.class, users, "Users").toResponseEntity("report");
 * pxlSpring.exportExcel().poiWorkbook(workbook).toFile(file);   // written in the workbook's own format
 * }</pre>
 *
 * <p>Output defaults to XLSX. An {@code override(...)} option carrying an {@code exportExcelEngine} switches
 * it (e.g. {@code HSSF}, which writes XLS), and {@code poiWorkbook(...)} writes the given workbook in its own
 * native format - the download headers are read back off that workbook, so they always match the bytes.</p>
 *
 * <p>That builder is the nested {@link Builder}. A fluent chain never has to name it; on the rare occasion
 * you hold one in a variable, spell it {@code PxlExcelExporter.Builder}.</p>
 *
 * <p>The component is stateless and safe to share across threads; the builder it hands back is not - start one
 * per export.</p>
 *
 * <p>Reached through {@link io.github.hclimkr.pxl.spring.PxlSpring PxlSpring}: inject that one bean and call
 * {@code pxlSpring.exportExcel()}, which hands back the builder documented here.</p>
 *
 * <p>The {@code exportExcelTo*} methods below are the builder's execution back-ends. They are {@code public}
 * only because Spring AOP (and {@code @Validated} method validation) can advise public methods only - a
 * terminal has to re-enter this component through its proxy for {@link PxlPerformanceLogging} to fire. Treat
 * them as internal and always go through {@link #exportExcel()}.</p>
 *
 * <p>Because those back-ends' {@code @NotNull} constraints only fire through the proxy, each one re-checks
 * its destination with {@code PxlArgumentSupport} so a plainly constructed component fails the same way at
 * the same point - see that class for why.</p>
 */
@Validated
@Component
public class PxlExcelExporter {

    private static final String TAG = "PxlExcelExporter";

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
     * {@code new PxlExcelExporter()} usage (outside a Spring context) working - it then falls back to
     * {@code this} and simply produces no performance log.</p>
     */
    @Autowired(required = false)
    @Lazy
    private PxlExcelExporter self;

    /**
     * Starts a fluent Excel export.
     *
     * @return a new builder bound to this component
     */
    public Builder exportExcel() {

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
     * @throws PxlException if {@code builder} or {@code outputStream} is {@code null}, the builder's source
     *                      combination is invalid, encryption is requested but the workbook type does not
     *                      support it, or export fails
     */
    @PxlPerformanceLogging(TAG)
    public void exportExcelToStream(@NotNull final Builder builder,
                                    @NotNull final OutputStream outputStream)
            throws PxlException {

        PxlArgumentSupport.requireNonNull(outputStream, "outputStream");
        builder.validateSource();

        generateToStream(builder, outputStream);
    }

    /**
     * Writes the builder's configured export to the given file.
     *
     * <p>Internal: called by {@link Builder#toFile(File)}.</p>
     *
     * @param builder   the configured export builder
     * @param excelFile the destination file
     * @throws PxlException if {@code builder} or {@code excelFile} is {@code null}, the builder's source
     *                      combination is invalid, encryption is requested but the workbook type does not
     *                      support it, the file cannot be opened, or writing or flushing the file fails
     */
    @PxlPerformanceLogging(TAG)
    public void exportExcelToFile(@NotNull final Builder builder,
                                  @NotNull final File excelFile)
            throws PxlException {

        PxlArgumentSupport.requireNonNull(excelFile, "excelFile");
        builder.validateSource();

        if (Objects.nonNull(builder.poiWorkbook)) {
            try (OutputStream outputStream = new BufferedOutputStream(new FileOutputStream(excelFile))) {
                PxlWorkbookUtils.writeToStream(builder.poiWorkbook, outputStream, builder.poiPassword);
            } catch (IOException e) {
                throw new PxlIOException(e);
            }
        } else {
            builder.coreBuilder.toFile(excelFile);
        }
    }

    /**
     * Writes the builder's configured export to the servlet response with download headers, buffering it in
     * full first.
     *
     * <p>Internal: called by {@link Builder#toResponse(HttpServletResponse, String)}.</p>
     *
     * @param builder       the configured export builder
     * @param response      the servlet response to write to
     * @param excelFilename the download file name without extension, or {@code null}/blank for the fallback
     * @throws PxlException if {@code builder} or {@code response} is {@code null}, the builder's source
     *                      combination is invalid, encryption is requested but the workbook type does not
     *                      support it, or writing the response fails
     */
    @PxlPerformanceLogging(TAG)
    public void exportExcelToResponse(@NotNull final Builder builder,
                                      @NotNull final HttpServletResponse response,
                                      @Nullable final String excelFilename)
            throws PxlException {

        PxlArgumentSupport.requireNonNull(response, "response");
        builder.validateSource();

        final String resolvedFilename = builder.resolveFilename(excelFilename);
        final PxlFileFormat fileFormat = builder.resolveFileFormat();

        // Generate fully in memory first; the response is only written once the bytes are complete, so a
        // generation failure leaves the response - including any CORS headers added upstream - untouched.
        final FastByteArrayOutputStream outputStream = new FastByteArrayOutputStream(PxlSpringConstants.DOWNLOAD_BUFFER_INITIAL_BYTES);
        generateToStream(builder, outputStream);

        PxlExportSupport.writeBufferToResponseForExport(outputStream, resolvedFilename, fileFormat, response);
    }

    /**
     * Writes the builder's configured export straight to the servlet response, without buffering it first.
     *
     * <p>Internal: called by {@link Builder#toResponseStreaming(HttpServletResponse, String)}; see that method
     * for what this shape gives up.</p>
     *
     * @param builder       the configured export builder
     * @param response      the servlet response to write to
     * @param excelFilename the download file name without extension, or {@code null}/blank for the fallback
     * @throws PxlException if {@code builder} or {@code response} is {@code null}, the builder's source
     *                      combination is invalid, encryption is requested but the workbook type does not
     *                      support it, or writing the response fails
     */
    @PxlPerformanceLogging(TAG)
    public void exportExcelToResponseStreaming(@NotNull final Builder builder,
                                               @NotNull final HttpServletResponse response,
                                               @Nullable final String excelFilename)
            throws PxlException {

        PxlArgumentSupport.requireNonNull(response, "response");
        builder.validateSource();

        final String resolvedFilename = builder.resolveFilename(excelFilename);
        final PxlFileFormat fileFormat = builder.resolveFileFormat();

        // Headers must go out before the body, so they are set before anything can fail. Past this point the
        // response is committed and a failure cannot be taken back - that is the trade this terminal asks for,
        // and no Content-Length is possible because the size is not known yet.
        PxlExportSupport.setDownloadHeadersForExport(resolvedFilename, fileFormat, response);

        try {
            generateToStream(builder, response.getOutputStream());
        } catch (IOException e) {
            throw new PxlIOException(e);
        }
    }

    /**
     * Returns the builder's configured export as a {@link ResponseEntity} with download headers.
     *
     * <p>Internal: called by {@link Builder#toResponseEntity(String)}.</p>
     *
     * @param builder       the configured export builder
     * @param excelFilename the download file name without extension, or {@code null}/blank for the fallback
     * @return the response entity carrying the workbook bytes
     * @throws PxlException if {@code builder} is {@code null}, the builder's source combination is invalid,
     *                      encryption is requested but the workbook type does not support it, or building the
     *                      response fails
     */
    @PxlPerformanceLogging(TAG)
    public ResponseEntity<Resource> exportExcelToResponseEntity(@NotNull final Builder builder,
                                                                @Nullable final String excelFilename)
            throws PxlException {

        builder.validateSource();

        final String resolvedFilename = builder.resolveFilename(excelFilename);
        final PxlFileFormat fileFormat = builder.resolveFileFormat();

        // Closed in the finally, before the entity reaches its caller: the body reads the buffer afterwards,
        // which closing does not prevent, but a later write into a body already handed over now fails.
        FastByteArrayOutputStream outputStream = null;
        try {
            outputStream = new FastByteArrayOutputStream(PxlSpringConstants.DOWNLOAD_BUFFER_INITIAL_BYTES);
            generateToStream(builder, outputStream);
            return PxlExportSupport.makeResponseEntityForExport(resolvedFilename, fileFormat, outputStream);
        } finally {
            IOUtils.closeQuietly(outputStream);
        }
    }

    /**
     * Generates the configured export into the given stream, dispatching on the builder's source form.
     *
     * <p>Used both for the caller's own stream and for the in-memory buffer the response destinations build
     * before touching the response, so the two paths cannot drift apart.</p>
     *
     * @param builder      the configured export builder (already source-validated)
     * @param outputStream the destination stream, non-{@code null} (each back-end guards its own destination
     *                     before getting here) and not closed by this method
     * @throws PxlException if encryption is requested but the workbook type does not support it, or export
     *                      fails
     */
    private static void generateToStream(final Builder builder,
                                         final OutputStream outputStream)
            throws PxlException {

        if (Objects.nonNull(builder.poiWorkbook)) {
            PxlWorkbookUtils.writeToStream(builder.poiWorkbook, outputStream, builder.poiPassword);
        } else {
            builder.coreBuilder.toStream(outputStream);
        }
    }

    /**
     * Fluent builder for the Excel export destinations of {@link PxlExcelExporter}. Created via
     * {@link PxlExcelExporter#exportExcel()}.
     *
     * <p>It mirrors the core {@code io.github.hclimkr.pxl.builder.PxlExcelExportBuilder} shape - source
     * methods, then {@link #override(PxlExportWorkbookOption)}, then a terminal - and adds the Spring-facing
     * destinations ({@link HttpServletResponse} / {@link ResponseEntity}) plus their download-name handling.</p>
     *
     * <p>There are three mutually exclusive source forms:</p>
     * <ul>
     *   <li>{@code @PxlWorkbook} object form: {@link #workbook(Object)}</li>
     *   <li>Sheet form (call repeatedly for multiple sheets): {@link #sheet(Class, Collection, String)}</li>
     *   <li>Raw POI workbook form: {@link #poiWorkbook(Workbook)}</li>
     * </ul>
     *
     * <p>Terminal methods: {@link #toStream(OutputStream)}, {@link #toFile(File)},
     * {@link #toResponse(HttpServletResponse, String)},
     * {@link #toResponseStreaming(HttpServletResponse, String)}, {@link #toResponseEntity(String)}. The builder
     * holds the collected arguments only; each terminal delegates straight back to the enclosing component so
     * the work still runs inside a Spring-proxied, {@code @PxlPerformanceLogging}-annotated method.</p>
     *
     * <p>Nested in the component on purpose: everything the component reads off the builder - its constructor,
     * the collected fields, {@code validateSource()}, {@code resolveFilename(String)},
     * {@code resolveFileFormat()} - is {@code private} and stays reachable only because the two are
     * nestmates. The public surface is exactly the source, option and terminal methods.</p>
     *
     * <p>Not thread-safe, and single-use per terminal call. Example:
     * {@code pxlSpring.exportExcel().sheet(User.class, users, "Users").toResponse(response, "report");}</p>
     */
    public static final class Builder {

        /**
         * Download file name used when neither the name given to a response terminal nor the workbook name
         * yields one.
         */
        private static final String DEFAULT_EXPORT_EXCEL_FILENAME = "Pxl";

        /**
         * The owning component; terminals call back into it so the export runs through its AOP proxy.
         */
        private final PxlExcelExporter exporter;

        /**
         * The core export builder, used as the store for the workbook/sheet source and the export option.
         * Left untouched by the {@link #poiWorkbook(Workbook)} form.
         */
        private final PxlExcelExportBuilder coreBuilder;

        /**
         * The {@code @PxlWorkbook} source object, kept alongside the core builder because the download name
         * and the export engine behind the file format fall back to what it declares.
         */
        private Object workbookObject;

        /**
         * Whether at least one {@link #sheet(Class, Collection, String)} call was made. The core builder
         * exposes no getter, and {@code validateSource()} needs to tell "sheet form" from "no source".
         */
        private boolean sheetAdded;

        /**
         * The raw POI source workbook, or {@code null} when one of the bound forms is used. Read directly by
         * {@link PxlExcelExporter}, a nestmate of this class, to pick the write path.
         */
        private Workbook poiWorkbook;

        /**
         * Mirrors the option handed to {@code coreBuilder}, so {@code resolveFileFormat()} can read its export
         * engine back. Not consulted by the raw POI form.
         */
        private PxlExportWorkbookOption workbookOption;

        /**
         * Encryption password for the raw POI form only, or {@code null} for none. The bound forms carry theirs
         * inside the export option instead, because they go through the core.
         */
        private String poiPassword;

        /**
         * Creates a builder bound to the given core entry point and owning component.
         *
         * @param pxl      the core entry point used to create the underlying export builder
         * @param exporter the component the terminal methods delegate back to (its AOP proxy where available)
         */
        private Builder(final Pxl pxl, final PxlExcelExporter exporter) {

            this.coreBuilder = pxl.exportExcel();
            this.exporter = exporter;
        }

        // ----- source -----

        /**
         * Exports from a workbook object annotated with {@code @PxlWorkbook}.
         *
         * <p>On the response destinations this form also supplies the download-name and file-format fallbacks:
         * a blank download name falls back to the workbook's declared name, and an option without an export
         * engine falls back to the workbook's declared engine, whose file format drives the headers.</p>
         *
         * @param workbookObject the {@code @PxlWorkbook}-annotated source object
         * @return this builder
         * @throws PxlNullPointerException if {@code workbookObject} is {@code null}
         */
        public Builder workbook(final Object workbookObject)
                throws PxlNullPointerException {

            this.coreBuilder.workbook(workbookObject);
            this.workbookObject = workbookObject;
            return this;
        }

        /**
         * Adds a sheet built from a row collection. Calling this repeatedly produces multiple sheets.
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
            this.sheetAdded = true;
            return this;
        }

        /**
         * Exports an already-built raw POI {@link Workbook} as-is, without any PXL binding.
         *
         * <p>Mutually exclusive with {@link #workbook(Object)}/{@link #sheet(Class, Collection, String)}. The
         * export option passed to {@link #override(PxlExportWorkbookOption)} does not apply to this form -
         * nothing is bound, so there is nothing for it to override.</p>
         *
         * <p>The download headers need no configuring either: the file format is read back off the workbook
         * itself ({@code HSSFWorkbook} &rarr; {@code .xls}, {@code XSSFWorkbook}/{@code SXSSFWorkbook} &rarr;
         * {@code .xlsx}), which is the same thing the body is written in, so the extension and the bytes cannot
         * disagree.</p>
         *
         * @param workbook the workbook to write
         * @return this builder
         * @throws PxlNullPointerException if {@code workbook} is {@code null}
         */
        public Builder poiWorkbook(final Workbook workbook)
                throws PxlNullPointerException {

            return poiWorkbook(workbook, null);
        }

        /**
         * Exports a raw POI {@link Workbook} encrypted with the given password.
         *
         * <p>The download headers still follow the workbook's own format, exactly as in
         * {@link #poiWorkbook(Workbook)}. Note that encryption wraps the bytes in an OLE2 container whatever
         * the workbook type, so an encrypted XSSF workbook goes out as OLE2 bytes under a {@code .xlsx} name -
         * which is how an encrypted OOXML file is normally distributed.</p>
         *
         * @param workbook the workbook to write
         * @param password the encryption password, or {@code null} for none
         * @return this builder
         * @throws PxlNullPointerException if {@code workbook} is {@code null}
         */
        public Builder poiWorkbook(final Workbook workbook,
                                   @Nullable final String password)
                throws PxlNullPointerException {

            if (Objects.isNull(workbook)) {
                throw new PxlNullPointerException("workbook must not be null");
            }

            this.poiWorkbook = workbook;
            this.poiPassword = password;
            return this;
        }

        // ----- options -----

        /**
         * Overrides annotation-declared values with the given export option. (Optional)
         *
         * <p>Ignored by the {@link #poiWorkbook(Workbook)} form, which writes the workbook as-is.</p>
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
         * Writes the configured export to the given output stream.
         *
         * @param outputStream the destination stream (not closed by this method)
         * @throws PxlException if {@code outputStream} is {@code null}, the configured source combination is
         *                      invalid, or export fails
         */
        public void toStream(final OutputStream outputStream)
                throws PxlException {

            exporter.exportExcelToStream(this, outputStream);
        }

        /**
         * Writes the configured export to the given file.
         *
         * @param excelFile the destination file
         * @throws PxlException if {@code excelFile} is {@code null}, the configured source combination is
         *                      invalid, or writing fails
         */
        public void toFile(final File excelFile)
                throws PxlException {

            exporter.exportExcelToFile(this, excelFile);
        }

        /**
         * Streams the configured export to the servlet response with download headers.
         *
         * <p>When {@code excelFilename} is blank the name falls back to the {@code @PxlWorkbook} workbook name
         * (workbook-object form only) and then to {@code Pxl}. The name is used as given - normalize it (NFC)
         * upstream if needed; RFC 5987 encoding is applied when the header is written.</p>
         *
         * @param response      the servlet response to write to
         * @param excelFilename the download file name without extension, or {@code null}/blank for the fallback
         * @throws PxlException if {@code response} is {@code null}, the configured source combination is
         *                      invalid, or writing the response fails
         */
        public void toResponse(final HttpServletResponse response,
                               @Nullable final String excelFilename)
                throws PxlException {

            exporter.exportExcelToResponse(this, response, excelFilename);
        }

        /**
         * Streams the configured export straight to the servlet response, without buffering it first.
         *
         * <p>{@link #toResponse(HttpServletResponse, String)} generates the whole workbook into memory and only
         * then writes it, which is what lets a generation failure leave the response untouched. This terminal
         * trades that away, and the trade is the whole point of it, so be clear about what is given up:</p>
         * <ul>
         *   <li>Heap no longer holds the finished bytes - the reason to use this at all. Pair it with
         *       {@code PxlExcelEngine.SXSSF} to bound the workbook model too, or the model just takes the
         *       buffer's place as the peak.</li>
         *   <li><strong>A failure part-way through cannot be taken back.</strong> The response is already
         *       committed with {@code 200 OK} and the download headers, so the client receives a truncated
         *       file. Nothing can undo bytes already on the wire.</li>
         *   <li>No {@code Content-Length}: the size is unknown up front, so the response goes out chunked and
         *       clients show no download progress. An aborted chunked transfer at least reads as a failed
         *       download rather than a complete one.</li>
         * </ul>
         *
         * <p>Note where the line falls. {@code validateSource()} and the {@code null}-destination guard are
         * this component's own up-front checks and run before the headers, so those failures still leave the
         * response untouched. Checks the core makes inside its own terminal - a duplicate sheet name, say - do
         * not: they happen after the headers have gone out, so such a failure writes no body but does leave the
         * download headers set.</p>
         *
         * <p>There is no streaming counterpart for the other destinations: {@link #toStream(OutputStream)} and
         * {@link #toFile(File)} already write straight through, and {@link #toResponseEntity(String)} cannot,
         * because its body is produced in full before the entity is returned - the {@link Resource} it carries
         * wraps the finished bytes rather than generating them on demand.</p>
         *
         * @param response      the servlet response to write to
         * @param excelFilename the download file name without extension, or {@code null}/blank for the fallback
         * @throws PxlException if {@code response} is {@code null}, the configured source combination is
         *                      invalid, or writing the response fails
         */
        public void toResponseStreaming(final HttpServletResponse response,
                                        @Nullable final String excelFilename)
                throws PxlException {

            exporter.exportExcelToResponseStreaming(this, response, excelFilename);
        }

        /**
         * Returns the configured export as a {@link ResponseEntity} with download headers.
         *
         * <p>The name is resolved exactly as in {@link #toResponse(HttpServletResponse, String)}.</p>
         *
         * @param excelFilename the download file name without extension, or {@code null}/blank for the fallback
         * @return the response entity carrying the workbook bytes
         * @throws PxlException if the configured source combination is invalid, or building the response fails
         */
        public ResponseEntity<Resource> toResponseEntity(@Nullable final String excelFilename)
                throws PxlException {

            return exporter.exportExcelToResponseEntity(this, excelFilename);
        }

        // ----- validation and resolution helpers read by PxlExcelExporter -----
        // private: the component is this class's nestmate, so nothing here needs to be exposed.

        /**
         * Rejects source combinations the builder cannot honour: none configured, more than one form, or a
         * raw-workbook-only option paired with a non-raw source. Called by every terminal before any work is
         * done.
         *
         * @throws PxlArgumentException if no source, more than one source form, or a POI-only option with a
         *                              non-POI source was configured
         */
        private void validateSource()
                throws PxlArgumentException {

            if (Objects.nonNull(poiWorkbook)) {
                if (Objects.nonNull(workbookObject) || sheetAdded) {
                    throw new PxlArgumentException("poiWorkbook(Workbook) is mutually exclusive with workbook(Object)/sheet(...)");
                }
                return;
            }

            if (Objects.isNull(workbookObject) && !sheetAdded) {
                throw new PxlArgumentException("one of workbook(Object), sheet(...), or poiWorkbook(Workbook) must be specified");
            }
        }

        /**
         * Resolves the download file name: the name given to the response terminal, else the
         * {@code @PxlWorkbook} workbook name (workbook-object form only), else {@code Pxl}.
         *
         * @param excelFilename the name given to the response terminal, or {@code null}/blank
         * @return the download file name without extension
         */
        private String resolveFilename(@Nullable final String excelFilename) {

            if (StringUtils.isNotBlank(excelFilename)) {
                return excelFilename;
            }

            if (Objects.nonNull(workbookObject)) {
                final String workbookName = PxlWorkbookUtils.getWorkbookNameFromWorkbookObject(workbookObject);
                if (StringUtils.isNotBlank(workbookName)) {
                    return workbookName;
                }
            }

            return DEFAULT_EXPORT_EXCEL_FILENAME;
        }

        /**
         * Resolves the file format driving the download headers: for the raw POI form the workbook's own
         * format, otherwise the format written by the option's export engine, then by the
         * {@code @PxlWorkbook} declared engine (workbook-object form only), then the default.
         *
         * <p>Reading the POI form's format back off the workbook is what keeps the extension and the body in
         * step - the body is written in that same native format. It is {@link PxlFileFormat#fromPoiWorkbook}
         * rather than {@link PxlExcelEngine#fromPoiWorkbook} that is asked, because a streaming-reader
         * workbook is a reader and therefore has no engine, yet still holds XLSX bytes. That lookup already
         * falls back to the default for a workbook type it does not recognise; the {@code Optional} around it
         * only guards against a future core that could return {@code null}.</p>
         *
         * @return the file format for the download headers
         */
        private PxlFileFormat resolveFileFormat() {

            if (Objects.nonNull(poiWorkbook)) {
                return Optional.ofNullable(PxlFileFormat.fromPoiWorkbook(poiWorkbook)).orElse(PxlConstants.DEFAULT_EXPORT_FILE_FORMAT);
            }

            final PxlExcelEngine optionExcelEngine = Optional.ofNullable(workbookOption)
                    .flatMap(option -> Optional.ofNullable(option.getExportExcelEngine()))
                    .orElse(null);
            if (Objects.nonNull(optionExcelEngine)) {
                return optionExcelEngine.getFileFormat();
            }

            return Objects.nonNull(workbookObject)
                    ? PxlExcelEngine.fromWorkbookObject(workbookObject.getClass()).getFileFormat()
                    : PxlConstants.DEFAULT_EXPORT_FILE_FORMAT;
        }

    }

}
