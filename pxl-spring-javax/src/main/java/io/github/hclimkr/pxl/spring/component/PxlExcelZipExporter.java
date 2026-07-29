package io.github.hclimkr.pxl.spring.component;

import io.github.hclimkr.pxl.Pxl;
import io.github.hclimkr.pxl.PxlConstants;
import io.github.hclimkr.pxl.PxlFileFormat;
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
import io.github.hclimkr.pxl.util.PxlWorkbookUtils;
import org.apache.commons.io.FilenameUtils;
import org.apache.commons.io.output.CloseShieldOutputStream;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.core.io.Resource;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

import javax.annotation.Nullable;
import javax.servlet.http.HttpServletResponse;
import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;
import java.io.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.zip.Deflater;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Spring component that bundles multiple workbook objects into a single ZIP archive.
 *
 * <p>Everything is configured through the fluent builder returned by {@link #exportExcelZip()} — add one
 * entry per {@code workbook(...)} call, then call a terminal ({@code toStream} / {@code toFile} /
 * {@code toResponse} / {@code toResponseStreaming} / {@code toResponseEntity}); the response terminals take
 * the archive's own download file name as an argument, and it is required there — unlike an entry name it has
 * nothing to fall back to:</p>
 *
 * <pre>{@code
 * pxlSpring.exportExcelZip()
 *         .workbook(januaryReport)
 *         .workbook(februaryReport, option, "2월보고서")
 *         .toResponse(response, "분기보고서");
 * }</pre>
 *
 * <p>An entry's file name falls back to the workbook object name, then to {@code Pxl{index}}. It must be a
 * plain name — one carrying a path separator is rejected with {@link PxlArgumentException}, so the archive
 * can never hand a traversal path to whoever extracts it.</p>
 *
 * <p>The index fallback only applies when neither of the earlier two yields a name, so it does not make
 * names unique: two entries built from the same workbook class, both relying on the same declared workbook
 * name, resolve to the same entry name. {@link ZipOutputStream} rejects the second one, which comes out as
 * {@link PxlIOException}. Give such entries an explicit name.</p>
 *
 * <p>That builder is the nested {@link Builder}. A fluent chain never has to name it; on the rare occasion
 * you hold one in a variable, spell it {@code PxlExcelZipExporter.Builder}.</p>
 *
 * <p>The component is stateless and safe to share across threads; the builder it hands back is not — start one
 * per archive.</p>
 *
 * <p>Reached through {@link io.github.hclimkr.pxl.spring.PxlSpring PxlSpring}: inject that one bean and call
 * {@code pxlSpring.exportExcelZip()}, which hands back the builder documented here.</p>
 *
 * <p>The {@code exportExcelZipTo*} methods below are the builder's execution back-ends. They are
 * {@code public} only because Spring AOP (and {@code @Validated} method validation) can advise public methods
 * only — a terminal has to re-enter this component through its proxy for {@link PxlPerformanceLogging} to fire.
 * Treat them as internal and always go through {@link #exportExcelZip()}.</p>
 *
 * <p>Because those back-ends' {@code @NotNull} constraints only fire through the proxy, each one re-checks
 * its destination with {@code PxlArgumentSupport} so a plainly constructed component fails the same way at
 * the same point — see that class for why. The archive name's {@code @NotBlank} needs no such guard:
 * {@code resolveZipFilename(String)} rejects a blank one with {@link PxlArgumentException} on either path.</p>
 */
@Validated
@Component
public class PxlExcelZipExporter {

    private static final String TAG = "PxlExcelZipExporter";

    /**
     * The core entry point, shared with the other components — see {@link PxlCoreSupport} for why it is not
     * one instance per component. Used while writing the archive, one export per entry.
     */
    private final Pxl pxl = PxlCoreSupport.core();

    /**
     * This component's own AOP proxy, injected by Spring where available.
     *
     * <p>The builder's terminals must call back through the proxy, not through {@code this}: a plain
     * {@code this} reference bypasses the proxy, and with it {@link PxlPerformanceLogging} and {@code @Validated}.
     * {@code @Lazy} breaks the self-reference cycle, and {@code required = false} keeps plain
     * {@code new PxlExcelZipExporter()} usage (outside a Spring context) working — it then falls back to
     * {@code this} and simply produces no performance log.</p>
     */
    @Autowired(required = false)
    @Lazy
    private PxlExcelZipExporter self;

    /**
     * Starts a fluent ZIP export.
     *
     * @return a new builder bound to this component
     */
    public Builder exportExcelZip() {

        return new Builder(Objects.nonNull(self) ? self : this);
    }

    // ----- builder execution back-ends (internal; reached through the nested Builder) -----

    /**
     * Writes the builder's entries as a ZIP archive to the given output stream.
     *
     * <p>On success the ZIP stream is finished (its central directory flushed) so the archive is complete,
     * but the given {@code outputStream} is left open for the caller to close - consistent with the other
     * {@code ...ToStream} methods. On failure the archive is deliberately left unfinished, so what was
     * written cannot be opened as one - see {@code writeArchive}.</p>
     *
     * <p>Internal: called by {@link Builder#toStream(OutputStream)}.</p>
     *
     * @param builder      the configured ZIP export builder
     * @param outputStream the destination stream (not closed by this method)
     * @throws PxlException if {@code builder} or {@code outputStream} is {@code null}, the builder has no
     *                      entry, or writing or finishing the archive fails
     */
    @PxlPerformanceLogging(TAG)
    public void exportExcelZipToStream(@NotNull final Builder builder,
                                       @NotNull final OutputStream outputStream)
            throws PxlException {

        PxlArgumentSupport.requireNonNull(outputStream, "outputStream");
        builder.validateEntries();

        writeArchive(outputStream, builder);
    }

    /**
     * Writes the builder's entries as a ZIP archive to the given file.
     *
     * <p>A failed export leaves the archive unfinished, so the file it wrote cannot be opened as one. It is
     * not deleted - the caller decides what to do with it.</p>
     *
     * <p>Internal: called by {@link Builder#toFile(File)}.</p>
     *
     * @param builder the configured ZIP export builder
     * @param zipFile the destination ZIP file
     * @throws PxlException if {@code builder} or {@code zipFile} is {@code null}, the builder has no entry,
     *                      the file cannot be opened, or writing or finishing the archive fails
     */
    @PxlPerformanceLogging(TAG)
    public void exportExcelZipToFile(@NotNull final Builder builder,
                                     @NotNull final File zipFile)
            throws PxlException {

        PxlArgumentSupport.requireNonNull(zipFile, "zipFile");
        builder.validateEntries();

        // The file stream is closed here rather than by the archive: on a failure the archive is abandoned
        // without being finished, and the handle still has to be released.
        try (OutputStream fileOutputStream = new FileOutputStream(zipFile)) {
            writeArchive(fileOutputStream, builder);
        } catch (IOException e) {
            throw new PxlIOException(e);
        }
    }

    /**
     * Streams the builder's entries as a ZIP archive to the servlet response with download headers.
     *
     * <p>Internal: called by {@link Builder#toResponse(HttpServletResponse, String)}.</p>
     *
     * <p>Unlike the per-entry names the archive name has nothing to fall back to, so it is required. The
     * {@code @NotBlank} constraint only fires when the call arrives through the Spring proxy; on a plain
     * instance {@code resolveZipFilename(String)} rejects the same value with {@link PxlArgumentException}.</p>
     *
     * @param builder     the configured ZIP export builder
     * @param response    the servlet response to write to
     * @param zipFilename the archive file name without extension; required
     * @throws PxlException if {@code builder} or {@code response} is {@code null}, the builder has no entry,
     *                      {@code zipFilename} is blank, or writing the archive or response fails
     */
    @PxlPerformanceLogging(TAG)
    public void exportExcelZipToResponse(@NotNull final Builder builder,
                                         @NotNull final HttpServletResponse response,
                                         @NotBlank final String zipFilename)
            throws PxlException {

        PxlArgumentSupport.requireNonNull(response, "response");
        builder.validateEntries();

        final String resolvedFilename = builder.resolveZipFilename(zipFilename);

        // Build the whole archive in memory first; the response is only written once the archive is complete,
        // so a failure leaves the response - including any CORS headers added upstream - untouched.
        final ByteArrayOutputStream outputStream = writeArchiveToBuffer(builder);

        PxlExportSupport.writeBufferToResponseForExportZip(outputStream, resolvedFilename, response);
    }

    /**
     * Writes the builder's entries as a ZIP archive straight to the servlet response, without buffering the
     * archive first.
     *
     * <p>Internal: called by {@link Builder#toResponseStreaming(HttpServletResponse, String)}; see that method
     * for what this shape gives up.</p>
     *
     * @param builder     the configured ZIP export builder
     * @param response    the servlet response to write to
     * @param zipFilename the archive file name without extension; required
     * @throws PxlException if {@code builder} or {@code response} is {@code null}, the builder has no entry,
     *                      {@code zipFilename} is blank, or writing the archive or response fails
     */
    @PxlPerformanceLogging(TAG)
    public void exportExcelZipToResponseStreaming(@NotNull final Builder builder,
                                                  @NotNull final HttpServletResponse response,
                                                  @NotBlank final String zipFilename)
            throws PxlException {

        PxlArgumentSupport.requireNonNull(response, "response");
        builder.validateEntries();

        final String resolvedFilename = builder.resolveZipFilename(zipFilename);

        // Headers must go out before the body, so they are set before anything can fail. Past this point the
        // response is committed and a failure cannot be taken back - that is the trade this terminal asks for,
        // and no Content-Length is possible because the size is not known yet.
        PxlExportSupport.setResponseForExportZip(resolvedFilename, response);

        try {
            writeArchive(response.getOutputStream(), builder);
        } catch (IOException e) {
            throw new PxlIOException(e);
        }
    }

    /**
     * Returns the builder's entries as a ZIP archive in a {@link ResponseEntity} with download headers.
     *
     * <p>Internal: called by {@link Builder#toResponseEntity(String)}.</p>
     *
     * <p>The archive name is required on the same terms as
     * {@link #exportExcelZipToResponse(Builder, HttpServletResponse, String)}.</p>
     *
     * @param builder     the configured ZIP export builder
     * @param zipFilename the archive file name without extension; required
     * @return the response entity carrying the archive bytes
     * @throws PxlException if {@code builder} is {@code null}, the builder has no entry, {@code zipFilename}
     *                      is blank, or building the response fails
     */
    @PxlPerformanceLogging(TAG)
    public ResponseEntity<Resource> exportExcelZipToResponseEntity(@NotNull final Builder builder,
                                                                   @NotBlank final String zipFilename)
            throws PxlException {

        builder.validateEntries();

        final String resolvedFilename = builder.resolveZipFilename(zipFilename);

        // Build the whole archive in memory first; the response is only written once the archive is
        // complete, so a failure leaves the response - including any CORS headers added upstream - untouched.
        final ByteArrayOutputStream outputStream = writeArchiveToBuffer(builder);

        return PxlExportSupport.makeResponseEntityForExportZip(resolvedFilename, outputStream);
    }

    /**
     * Writes each of the builder's entries as a single ZIP entry into the given archive stream.
     *
     * <p>An entry's file name comes from the builder ({@code explicit name → workbook name → Pxl{index}})
     * and its extension from the workbook class's declared export format. A name carrying a path separator
     * is rejected. The deflate level is picked per entry — see {@link #deflateLevelFor(PxlFileFormat)}.</p>
     *
     * @param zipOutputStream the archive stream to append entries to
     * @param builder         the configured ZIP export builder (already validated)
     * @throws PxlException if a workbook object is {@code null} (the builder rejects those up front, so this
     *                      only surfaces the core builder's own guard), an entry name carries a path,
     *                      writing an entry fails, or a workbook fails to export
     */
    private void writeEntries(final ZipOutputStream zipOutputStream,
                              final Builder builder)
            throws PxlException {

        for (int index = 0; index < builder.entries.size(); index++) {
            final Builder.Entry entry = builder.entries.get(index);
            final Object workbookObject = entry.workbookObject;

            final String excelFilename = entry.resolveExcelFilename(index);
            final PxlFileFormat fileFormat = PxlFileFormat.fromWorkbookObject(workbookObject.getClass());

            final String entryName = excelFilename + FilenameUtils.EXTENSION_SEPARATOR_STR + fileFormat.getFilenameExtension();

            // A ZipEntry name may legally hold a path, and this one can come from application data, so an
            // unchecked separator would put a traversal path inside an archive we hand out. getName strips
            // everything up to the last '/' or '\' (and any drive prefix), so a name that survives it
            // unchanged carries no path. Only separators matter: the extension is always appended, so a bare
            // ".." can never come out as ".." here.
            if (!entryName.equals(FilenameUtils.getName(entryName))) {
                throw new PxlArgumentException("entry file name must not carry a path: '" + entryName + "'");
            }

            try {
                zipOutputStream.setLevel(deflateLevelFor(fileFormat));
                zipOutputStream.putNextEntry(new ZipEntry(entryName));
                pxl.exportExcel()
                        .workbook(workbookObject)
                        .override(entry.workbookOption)
                        .toStream(zipOutputStream);
                zipOutputStream.closeEntry();
            } catch (IOException e) {
                throw new PxlIOException(e);
            }
        }
    }

    /**
     * Picks the deflate level for an entry of the given format.
     *
     * <p>{@code .xlsx} (OOXML) <em>is</em> a deflated ZIP container, so deflating it again costs a full
     * compression pass for essentially no size gain. Those entries are written at
     * {@link Deflater#NO_COMPRESSION}, which emits stored deflate blocks — a few bytes of framing per 64&nbsp;KB
     * in exchange for skipping the pass entirely. {@code .xls} (OLE2) is not compressed, so it keeps the
     * default level, where deflate genuinely pays off.</p>
     *
     * <p>Matched on the extension rather than the enum constant so that {@code XSSF} and {@code SXSSF} — and
     * any future OOXML-based format — are covered by the one rule.</p>
     *
     * @param fileFormat the entry's export format
     * @return the deflate level to apply to that entry
     */
    private static int deflateLevelFor(final PxlFileFormat fileFormat) {

        return PxlConstants.FILENAME_EXTENSION_XLSX.equals(fileFormat.getFilenameExtension())
                ? Deflater.NO_COMPRESSION
                : Deflater.DEFAULT_COMPRESSION;
    }

    /**
     * Writes the builder's entries into a new archive over the given stream, and finishes the archive only
     * if every one of them was written.
     *
     * <p>The distinction matters because {@link ZipOutputStream#close()} does two things: {@code finish()},
     * which writes the central directory, and {@code Deflater.end()}, which frees the native zlib stream.
     * After a failure only the second is wanted. Finishing would hand out a <em>well-formed</em> archive
     * that silently lacks whatever the failure prevented — an empty but perfectly valid ZIP, if the failure
     * came before the first entry — and no consumer can tell that apart from a complete one. Left
     * unfinished, the bytes have no central directory and so cannot be opened at all, which is what makes
     * the failure visible: a servlet response ends without its terminating chunk, and a file on disk fails
     * to open.</p>
     *
     * <p>The given stream is never closed here — every caller owns it (the caller's own stream, the servlet
     * stream, or a file stream closed by its own try-with-resources) — so it is shielded from the archive's
     * {@code close()}.</p>
     *
     * @param outputStream the stream to write the archive to (not closed by this method)
     * @param builder      the configured ZIP export builder (already validated)
     * @throws PxlException if a workbook object is {@code null}, an entry name carries a path, writing the
     *                      archive fails, or a workbook fails to export
     */
    private void writeArchive(final OutputStream outputStream,
                              final Builder builder)
            throws PxlException {

        final Archive archive = new Archive(CloseShieldOutputStream.wrap(outputStream));

        boolean finished = false;
        try {
            writeEntries(archive, builder);
            archive.close();
            finished = true;
        } catch (IOException e) {
            throw new PxlIOException(e);
        } finally {
            if (!finished) {
                archive.abandon();
            }
        }
    }

    /**
     * Builds the complete ZIP archive into an in-memory buffer.
     *
     * <p>The archive is finished before returning, so the buffer holds the central directory too — the
     * response destinations depend on that, because they must not touch the response until the archive is
     * whole. On a failure nothing is returned and the half-built buffer is simply discarded.</p>
     *
     * @param builder the configured ZIP export builder (already validated)
     * @return a buffer holding the finished archive
     * @throws PxlException if a workbook object is {@code null}, writing the archive fails, or a workbook
     *                      fails to export
     */
    private ByteArrayOutputStream writeArchiveToBuffer(final Builder builder)
            throws PxlException {

        final ByteArrayOutputStream outputStream = new ByteArrayOutputStream(PxlSpringConstants.DOWNLOAD_BUFFER_INITIAL_BYTES);
        writeArchive(outputStream, builder);

        return outputStream;
    }

    /**
     * A {@link ZipOutputStream} that can release its deflater without finishing the archive.
     *
     * <p>{@code ZipOutputStream} offers no way to do that: {@code close()} always finishes first, and
     * skipping {@code close()} altogether would leave the deflater's native zlib stream to be freed
     * whenever the GC and its {@code Cleaner} get round to it. The deflater field is {@code protected}
     * though, so a subclass can end it directly. See {@link #writeArchive} for why the two have to be
     * separable.</p>
     */
    private static final class Archive extends ZipOutputStream {

        /**
         * Opens an archive over the given stream.
         *
         * @param outputStream the stream the archive is written to; {@code writeArchive} hands in a
         *                     close-shielded one, because closing it is the caller's business
         */
        private Archive(final OutputStream outputStream) {

            super(outputStream);
        }

        /**
         * Frees the native deflater without writing the central directory, leaving the bytes written so far
         * unopenable as an archive.
         *
         * <p>Safe to call after {@code close()} has already ended the deflater: {@code Deflater.end()}
         * delegates to a {@code Cleaner.Cleanable}, whose action runs at most once.</p>
         */
        private void abandon() {

            def.end();
        }

    }

    /**
     * Fluent builder for the ZIP export destinations of {@link PxlExcelZipExporter}. Created via
     * {@link PxlExcelZipExporter#exportExcelZip()}.
     *
     * <p>Unlike the other Spring builders there is no core {@code pxl-javax} counterpart to mirror — ZIP
     * bundling is a pxl-spring concern — so this builder simply collects archive entries and the archive's
     * own download name, then hands them to a terminal.</p>
     *
     * <p>Each {@link #workbook(Object)} call adds one archive entry; there are no separate option methods,
     * because an entry's export option and file name are arguments of the {@code workbook(...)} overload that
     * adds it — they mean nothing except against that one entry. Terminal methods:
     * {@link #toStream(OutputStream)}, {@link #toFile(File)}, {@link #toResponse(HttpServletResponse, String)},
     * {@link #toResponseStreaming(HttpServletResponse, String)}, {@link #toResponseEntity(String)}. Each
     * terminal delegates straight back to the enclosing component so the work still runs inside a
     * Spring-proxied, {@code @PxlPerformanceLogging}-annotated method.</p>
     *
     * <p>Nested in the component on purpose: everything the component reads off the builder — its constructor,
     * the collected {@code entries} and their {@code Entry} type, {@code validateEntries()},
     * {@code resolveZipFilename(String)} — is {@code private} and stays reachable only because the two are
     * nestmates. The public surface is exactly the entry and terminal methods.</p>
     *
     * <p>Not thread-safe, and single-use per terminal call. Example:</p>
     *
     * <pre>{@code
     * pxlSpring.exportExcelZip()
     *         .workbook(januaryReport)
     *         .workbook(februaryReport, option, "2월보고서")
     *         .toResponse(response, "분기보고서");
     * }</pre>
     */
    public static final class Builder {

        /**
         * Entry file name prefix used when neither an explicit name nor the workbook object name yields one;
         * the entry's zero-based index is appended.
         */
        private static final String DEFAULT_EXPORT_EXCEL_FILENAME = "Pxl";

        /**
         * The owning component; terminals call back into it so the export runs through its AOP proxy.
         */
        private final PxlExcelZipExporter exporter;

        /**
         * The collected archive entries, in the order they will be written. Read directly by the enclosing
         * component while it writes the archive.
         */
        private final List<Entry> entries = new ArrayList<>();

        /**
         * Creates a builder bound to the owning component.
         *
         * <p>Takes no {@code Pxl} — there is no core builder to seed; the component generates each entry with
         * its own {@code Pxl} instance while writing the archive.</p>
         *
         * @param exporter the component the terminal methods delegate back to (its AOP proxy where available)
         */
        private Builder(final PxlExcelZipExporter exporter) {

            this.exporter = exporter;
        }

        // ----- entries -----

        /**
         * Adds one archive entry for a workbook object annotated with {@code @PxlWorkbook}.
         *
         * @param workbookObject the {@code @PxlWorkbook}-annotated source object
         * @return this builder
         * @throws PxlNullPointerException if {@code workbookObject} is {@code null}
         */
        public Builder workbook(final Object workbookObject)
                throws PxlNullPointerException {

            return workbook(workbookObject, null, null);
        }

        /**
         * Adds one archive entry for a workbook object, with an export option applied to that entry only.
         *
         * @param workbookObject the {@code @PxlWorkbook}-annotated source object
         * @param workbookOption the export option for this entry, or {@code null}
         * @return this builder
         * @throws PxlNullPointerException if {@code workbookObject} is {@code null}
         */
        public Builder workbook(final Object workbookObject,
                                @Nullable final PxlExportWorkbookOption workbookOption)
                throws PxlNullPointerException {

            return workbook(workbookObject, workbookOption, null);
        }

        /**
         * Adds one archive entry for a workbook object, with an export option and an entry file name applied
         * to that entry only.
         *
         * @param workbookObject the {@code @PxlWorkbook}-annotated source object
         * @param workbookOption the export option for this entry, or {@code null}
         * @param excelFilename  the entry file name without extension; when blank it falls back to the
         *                       {@code @PxlWorkbook} workbook name and then to {@code Pxl{index}}
         * @return this builder
         * @throws PxlNullPointerException if {@code workbookObject} is {@code null}
         */
        public Builder workbook(final Object workbookObject,
                                @Nullable final PxlExportWorkbookOption workbookOption,
                                @Nullable final String excelFilename)
                throws PxlNullPointerException {

            if (Objects.isNull(workbookObject)) {
                throw new PxlNullPointerException("workbookObject must not be null");
            }

            this.entries.add(new Entry(workbookObject, workbookOption, excelFilename));
            return this;
        }

        // ----- terminals -----

        /**
         * Writes the configured entries as a ZIP archive to the given output stream.
         *
         * <p>On success the ZIP stream is finished (its central directory flushed) so the archive is
         * complete, but the given {@code outputStream} is left open for the caller to close. On failure the
         * archive is left unfinished, so what was written cannot be opened as one.</p>
         *
         * @param outputStream the destination stream (not closed by this method)
         * @throws PxlException if {@code outputStream} is {@code null}, no entry was added, or writing the
         *                      archive fails
         */
        public void toStream(final OutputStream outputStream)
                throws PxlException {

            exporter.exportExcelZipToStream(this, outputStream);
        }

        /**
         * Writes the configured entries as a ZIP archive to the given file.
         *
         * @param zipFile the destination ZIP file
         * @throws PxlException if {@code zipFile} is {@code null}, no entry was added, or writing the archive
         *                      fails
         */
        public void toFile(final File zipFile)
                throws PxlException {

            exporter.exportExcelZipToFile(this, zipFile);
        }

        /**
         * Streams the configured entries as a ZIP archive to the servlet response with download headers.
         *
         * <p>{@code zipFilename} is required — unlike the per-entry names there is nothing to fall back to, so
         * a blank one raises {@link PxlArgumentException}. The name is used as given — normalize it (NFC)
         * upstream if needed; RFC 5987 encoding is applied when the header is written.</p>
         *
         * @param response    the servlet response to write to
         * @param zipFilename the archive file name without extension; required
         * @throws PxlException if {@code response} is {@code null}, no entry was added, {@code zipFilename} is
         *                      blank, or writing the archive or response fails
         */
        public void toResponse(final HttpServletResponse response,
                               final String zipFilename)
                throws PxlException {

            exporter.exportExcelZipToResponse(this, response, zipFilename);
        }

        /**
         * Streams the configured entries as a ZIP archive straight to the servlet response, without buffering
         * the archive first.
         *
         * <p>This is where streaming pays off most. With {@link #toResponse(HttpServletResponse, String)} the
         * peak is the <em>whole archive</em>; here one entry is generated and written at a time, so heap stops
         * scaling with the entry count.</p>
         *
         * <p>What it gives up is the same trade as on {@code PxlExcelExporter}: a failure part-way through
         * leaves a truncated archive already on the wire under {@code 200 OK}, and no {@code Content-Length}
         * can be sent.</p>
         *
         * <p>Note where the line falls. The empty-archive and archive-name checks run before the headers, so
         * those failures still leave the response untouched. <strong>Entry-name validation does not</strong> —
         * it happens per entry inside the write loop, which is after the headers have gone out. Such a failure
         * writes no body but does leave the download headers set.</p>
         *
         * <p>What a failure never produces is a readable archive: the central directory is written only once
         * every entry is in, so a client can never mistake a failed download for a complete one — see
         * {@code writeArchive}.</p>
         *
         * <p>There is no streaming counterpart for the other destinations: {@link #toStream(OutputStream)} and
         * {@link #toFile(File)} already write straight through, and {@link #toResponseEntity(String)} cannot,
         * because its body is produced in full before the entity is returned — the {@link Resource} it carries
         * wraps the finished bytes rather than generating them on demand.</p>
         *
         * @param response    the servlet response to write to
         * @param zipFilename the archive file name without extension; required
         * @throws PxlException if {@code response} is {@code null}, no entry was added, {@code zipFilename} is
         *                      blank, or writing the archive or response fails
         */
        public void toResponseStreaming(final HttpServletResponse response,
                                        final String zipFilename)
                throws PxlException {

            exporter.exportExcelZipToResponseStreaming(this, response, zipFilename);
        }

        /**
         * Returns the configured entries as a ZIP archive in a {@link ResponseEntity} with download headers.
         *
         * <p>The name is required exactly as in {@link #toResponse(HttpServletResponse, String)}.</p>
         *
         * @param zipFilename the archive file name without extension; required
         * @return the response entity carrying the archive bytes
         * @throws PxlException if no entry was added, {@code zipFilename} is blank, or building the response
         *                      fails
         */
        public ResponseEntity<Resource> toResponseEntity(final String zipFilename)
                throws PxlException {

            return exporter.exportExcelZipToResponseEntity(this, zipFilename);
        }

        // ----- validation and resolution helpers read by PxlExcelZipExporter -----
        // private: the component is this class's nestmate, so nothing here needs to be exposed.

        /**
         * Rejects an archive with no members. Called by every terminal before any work is done.
         *
         * @throws PxlArgumentException if no entry was added
         */
        private void validateEntries()
                throws PxlArgumentException {

            if (entries.isEmpty()) {
                throw new PxlArgumentException("at least one workbook(...) entry must be specified");
            }
        }

        /**
         * Resolves the archive's own download file name.
         *
         * <p>Called only by the response destinations; the stream/file destinations emit no headers and so
         * never need a name.</p>
         *
         * @param zipFilename the name given to the response terminal
         * @return the archive file name without extension
         * @throws PxlArgumentException if {@code zipFilename} is blank
         */
        private String resolveZipFilename(final String zipFilename)
                throws PxlArgumentException {

            if (StringUtils.isBlank(zipFilename)) {
                throw new PxlArgumentException("zipFilename is required for the response destinations");
            }

            return zipFilename;
        }

        /**
         * One archive member: the workbook object to export, its optional per-entry export option, and its
         * optional entry file name.
         *
         * <p>Private throughout: instances are created only by the enclosing builder and read only by
         * {@link PxlExcelZipExporter}, a nestmate of both.</p>
         */
        private static final class Entry {

            private final Object workbookObject;

            private final PxlExportWorkbookOption workbookOption;

            private final String excelFilename;

            /**
             * Creates an archive entry.
             *
             * @param workbookObject the workbook object (never {@code null})
             * @param workbookOption the export option for this entry, or {@code null}
             * @param excelFilename  the entry file name without extension, or {@code null}/blank for the
             *                       fallback
             */
            private Entry(final Object workbookObject,
                          final PxlExportWorkbookOption workbookOption,
                          final String excelFilename) {

                this.workbookObject = workbookObject;
                this.workbookOption = workbookOption;
                this.excelFilename = excelFilename;
            }

            /**
             * Resolves this entry's file name: the explicit name, else the {@code @PxlWorkbook} workbook name,
             * else {@code Pxl} followed by the entry index.
             *
             * @param index the entry's zero-based index in the archive
             * @return the entry file name without extension
             */
            private String resolveExcelFilename(final int index) {

                if (StringUtils.isNotBlank(excelFilename)) {
                    return excelFilename;
                }

                final String workbookName = PxlWorkbookUtils.getWorkbookNameFromWorkbookObject(workbookObject);
                if (StringUtils.isNotBlank(workbookName)) {
                    return workbookName;
                }

                return DEFAULT_EXPORT_EXCEL_FILENAME + index;
            }

        }

    }

}
