package io.github.hclimkr.pxl.spring.component;

import io.github.hclimkr.pxl.Pxl;
import io.github.hclimkr.pxl.PxlConstants;
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
import org.apache.commons.io.FilenameUtils;
import org.apache.commons.io.output.CloseShieldOutputStream;
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
import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.*;
import java.util.zip.Deflater;
import java.util.zip.ZipEntry;
import java.util.zip.ZipException;
import java.util.zip.ZipOutputStream;

/**
 * Spring component that bundles several spreadsheets into a single ZIP archive.
 *
 * <p>Everything is configured through the fluent builder returned by {@link #exportZip()} - add one
 * entry per call ({@code workbook(...)} for a {@code @PxlWorkbook}-annotated object, {@code poiWorkbook(...)}
 * for a raw POI {@link Workbook} you built yourself, {@code sampleWorkbook(...)} for a sample template
 * generated from a class, {@code csvSheet(...)} / {@code sampleCsvSheet(...)} for the CSV equivalents), then
 * call a terminal ({@code toStream} / {@code toFile} / {@code toResponse} / {@code toResponseStreaming} /
 * {@code toResponseEntity}); the response terminals take the archive's own download file name as an argument,
 * and it is required there - unlike an entry name it has nothing to fall back to:</p>
 *
 * <pre>{@code
 * pxlSpring.exportZip()
 *         .workbook(januaryReport)
 *         .workbook(februaryReport, option, "February")
 *         .poiWorkbook(alreadyBuilt, null, "raw")
 *         .sampleWorkbook(UploadForm.class, null, "template")
 *         .csvSheet(Employee.class, employees, "Employees")
 *         .sampleCsvSheet(Employee.class, "Upload form")
 *         .toResponse(response, "quarterly-report");
 * }</pre>
 *
 * <p>An archive holds any mix of the five - nothing ties one archive to a single kind.</p>
 *
 * <p>An entry's file name falls back to the name its source carries - the workbook object name for
 * {@code workbook(...)}, the sheet name for the two CSV kinds - and then, for the kinds whose source carries
 * none, to an index-suffixed default ({@code Pxl{index}}, or {@code PxlSample{index}} for a sample
 * template). The CSV kinds never reach that last step: a sheet name is required of them, so there is always
 * something to take. It must be a plain name - one carrying a path separator is rejected with
 * {@link PxlArgumentException} before any byte is written, so the archive can never hand a traversal path
 * to whoever extracts it.</p>
 *
 * <p>Only the index-suffixed defaults come out unique, the index being appended every time. A name taken
 * from the source does not: two entries built from the same workbook class, both relying on the same
 * declared workbook name, resolve to one entry name, and so do two CSV entries sharing a sheet name. That
 * is rejected with {@link PxlArgumentException} before any byte is written - see
 * {@code Builder.validateEntries} for why both name checks sit there and why the duplicate one ignores
 * case. Give such entries an explicit name.</p>
 *
 * <p>That builder is the nested {@link Builder}. A fluent chain never has to name it; on the rare occasion
 * you hold one in a variable, spell it {@code PxlZipExporter.Builder}.</p>
 *
 * <p>The component is stateless and safe to share across threads; the builder it hands back is not - start one
 * per archive.</p>
 *
 * <p>Reached through {@link io.github.hclimkr.pxl.spring.PxlSpring PxlSpring}: inject that one bean and call
 * {@code pxlSpring.exportZip()}, which hands back the builder documented here.</p>
 *
 * <p>The {@code exportZipTo*} methods below are the builder's execution back-ends. They are
 * {@code public} only because Spring AOP (and {@code @Validated} method validation) can advise public methods
 * only - a terminal has to re-enter this component through its proxy for {@link PxlPerformanceLogging} to fire.
 * Treat them as internal and always go through {@link #exportZip()}.</p>
 *
 * <p>Because those back-ends' {@code @NotNull} constraints only fire through the proxy, each one re-checks
 * its destination with {@code PxlArgumentSupport} so a plainly constructed component fails the same way at
 * the same point - see that class for why. The archive name's {@code @NotBlank} needs no such guard:
 * {@code resolveZipFilename(String)} rejects a blank one with {@link PxlArgumentException} on either path.</p>
 */
@Validated
@Component
public class PxlZipExporter {

    private static final String TAG = "PxlZipExporter";

    /**
     * The core entry point, shared with the other components - see {@link PxlCoreSupport} for why it is not
     * one instance per component. Used while writing the archive, one export per entry.
     */
    private final Pxl pxl = PxlCoreSupport.core();

    /**
     * This component's own AOP proxy, injected by Spring where available.
     *
     * <p>The builder's terminals must call back through the proxy, not through {@code this}: a plain
     * {@code this} reference bypasses the proxy, and with it {@link PxlPerformanceLogging} and {@code @Validated}.
     * {@code @Lazy} breaks the self-reference cycle, and {@code required = false} keeps plain
     * {@code new PxlZipExporter()} usage (outside a Spring context) working - it then falls back to
     * {@code this} and simply produces no performance log.</p>
     */
    @Autowired(required = false)
    @Lazy
    private PxlZipExporter self;

    /**
     * Starts a fluent ZIP export.
     *
     * @return a new builder bound to this component
     */
    public Builder exportZip() {

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
     *                      entry, an entry name carries a path or collides with another, or writing or
     *                      finishing the archive fails
     */
    @PxlPerformanceLogging(TAG)
    public void exportZipToStream(@NotNull final Builder builder,
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
     *                      an entry name carries a path or collides with another, the file cannot be opened,
     *                      or writing or finishing the archive fails
     */
    @PxlPerformanceLogging(TAG)
    public void exportZipToFile(@NotNull final Builder builder,
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
     *                      an entry name carries a path or collides with another, {@code zipFilename} is
     *                      blank, or writing the archive or response fails
     */
    @PxlPerformanceLogging(TAG)
    public void exportZipToResponse(@NotNull final Builder builder,
                                    @NotNull final HttpServletResponse response,
                                    @NotBlank final String zipFilename)
            throws PxlException {

        PxlArgumentSupport.requireNonNull(response, "response");
        builder.validateEntries();

        final String resolvedFilename = builder.resolveZipFilename(zipFilename);

        // Build the whole archive in memory first; the response is only written once the archive is complete,
        // so a failure leaves the response - including any CORS headers added upstream - untouched.
        final FastByteArrayOutputStream outputStream = writeArchiveToBuffer(builder);

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
     *                      an entry name carries a path or collides with another, {@code zipFilename} is
     *                      blank, or writing the archive or response fails
     */
    @PxlPerformanceLogging(TAG)
    public void exportZipToResponseStreaming(@NotNull final Builder builder,
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
     * {@link #exportZipToResponse(Builder, HttpServletResponse, String)}.</p>
     *
     * @param builder     the configured ZIP export builder
     * @param zipFilename the archive file name without extension; required
     * @return the response entity carrying the archive bytes
     * @throws PxlException if {@code builder} is {@code null}, the builder has no entry, an entry name
     *                      carries a path or collides with another, {@code zipFilename} is blank, or
     *                      building the response fails
     */
    @PxlPerformanceLogging(TAG)
    public ResponseEntity<Resource> exportZipToResponseEntity(@NotNull final Builder builder,
                                                              @NotBlank final String zipFilename)
            throws PxlException {

        builder.validateEntries();

        final String resolvedFilename = builder.resolveZipFilename(zipFilename);

        // Build the whole archive in memory first; the response is only written once the archive is
        // complete, so a failure leaves the response - including any CORS headers added upstream - untouched.
        final FastByteArrayOutputStream outputStream = writeArchiveToBuffer(builder);

        return PxlExportSupport.makeResponseEntityForExportZip(resolvedFilename, outputStream);
    }

    /**
     * Writes each of the builder's entries as a single ZIP entry into the given archive stream.
     *
     * <p>This loop knows nothing about what an entry is made of: each {@code Builder.Entry} names itself and
     * writes its own body, so a new kind of source is added without touching anything here. The name is the
     * same {@code Entry.resolveEntryName} the builder's validation already ran, so the two cannot disagree
     * about what a given entry is called. Nothing about that name is checked here - {@code
     * Builder.validateEntries} has rejected both a duplicate and a path-carrying one before this method is
     * reached. The deflate level is picked per entry from that same name - see
     * {@link #deflateLevelFor(String)}.</p>
     *
     * @param zipOutputStream the archive stream to append entries to
     * @param builder         the configured ZIP export builder (already validated)
     * @throws PxlException if writing an entry fails, or an entry's body fails to generate - which is the
     *                      only kind of failure left by the time this method runs, the builder's own checks
     *                      having all been made before it
     */
    private void writeEntries(final ZipOutputStream zipOutputStream,
                              final Builder builder)
            throws PxlException {

        for (int index = 0; index < builder.entries.size(); index++) {
            final Builder.Entry entry = builder.entries.get(index);

            final String entryName = entry.resolveEntryName(index);

            try {
                zipOutputStream.setLevel(deflateLevelFor(entryName));
                zipOutputStream.putNextEntry(new ZipEntry(entryName));
                entry.writeBody(zipOutputStream, pxl);
                zipOutputStream.closeEntry();
            } catch (IOException e) {
                throw new PxlIOException(e);
            }
        }
    }

    /**
     * Picks the deflate level for the entry about to be written, from the name it is written under.
     *
     * <p>{@code .xlsx} (OOXML) <em>is</em> a deflated ZIP container, so deflating it again costs a full
     * compression pass for essentially no size gain. Those entries are written at
     * {@link Deflater#NO_COMPRESSION}, which emits stored deflate blocks - a few bytes of framing per 64&nbsp;KB
     * in exchange for skipping the pass entirely. Everything else - {@code .xls} (OLE2) and {@code .csv} text
     * alike - is uncompressed to begin with and keeps the default level, where deflate genuinely pays off.
     * Only one extension is on the compressed side, so it is a comparison rather than a set; make it a set
     * when a second one turns up.</p>
     *
     * <p>Asking the name rather than the entry keeps this method from having to know what an entry is made
     * of, and the three answers still come from one: the format an entry writes itself in decides its
     * extension, and the extension decides the level. An entry cannot end up named as one thing, written as
     * another and compressed as a third.</p>
     *
     * <p>Only what follows the last dot counts, so a base name carrying dots of its own ({@code 2026.01})
     * changes nothing - the caller's name reaches this parser, which it did not while the level came
     * straight from the format. No case folding is needed either: the extension is never caller-written,
     * since every kind appends its own through {@code Entry.withExtension}, so what comes back out here is
     * exactly the constant that went in.</p>
     *
     * @param entryName the entry's name inside the archive, extension included
     * @return the deflate level to apply to that entry
     */
    private static int deflateLevelFor(final String entryName) {

        return PxlConstants.FILENAME_EXTENSION_XLSX.equals(FilenameUtils.getExtension(entryName))
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
     * that silently lacks whatever the failure prevented - an empty but perfectly valid ZIP, if the failure
     * came before the first entry - and no consumer can tell that apart from a complete one. Left
     * unfinished, the bytes have no central directory and so cannot be opened at all, which is what makes
     * the failure visible: a servlet response ends without its terminating chunk, and a file on disk fails
     * to open.</p>
     *
     * <p>The given stream is never closed here - every caller owns it (the caller's own stream, the servlet
     * stream, or a file stream closed by its own try-with-resources) - so it is shielded from the archive's
     * {@code close()}.</p>
     *
     * @param outputStream the stream to write the archive to (not closed by this method)
     * @param builder      the configured ZIP export builder (already validated)
     * @throws PxlException if writing the archive fails, or an entry's body fails to generate
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
     * <p>The archive is finished before returning, so the buffer holds the central directory too - the
     * response destinations depend on that, because they must not touch the response until the archive is
     * whole. On a failure nothing is returned and the half-built buffer is simply discarded.</p>
     *
     * @param builder the configured ZIP export builder (already validated)
     * @return a buffer holding the finished archive
     * @throws PxlException if writing the archive fails, or an entry's body fails to generate
     */
    private FastByteArrayOutputStream writeArchiveToBuffer(final Builder builder)
            throws PxlException {

        final FastByteArrayOutputStream outputStream = new FastByteArrayOutputStream(PxlSpringConstants.DOWNLOAD_BUFFER_INITIAL_BYTES);
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
     * Fluent builder for the ZIP export destinations of {@link PxlZipExporter}. Created via
     * {@link PxlZipExporter#exportZip()}.
     *
     * <p>Unlike the other Spring builders there is no core {@code pxl-javax} counterpart to mirror - ZIP
     * bundling is a pxl-spring concern - so this builder simply collects archive entries and the archive's
     * own download name, then hands them to a terminal.</p>
     *
     * <p>Each {@link #workbook(Object)}, {@link #poiWorkbook(Workbook)}, {@link #sampleWorkbook(Class)},
     * {@link #csvSheet(Class, Collection, String)} or {@link #sampleCsvSheet(Class, String)} call adds one
     * archive entry; there are no separate option methods, because an entry's export option and file name are
     * arguments of the overload that adds it - they mean nothing except against that one entry.
     * Terminal methods:
     * {@link #toStream(OutputStream)}, {@link #toFile(File)}, {@link #toResponse(HttpServletResponse, String)},
     * {@link #toResponseStreaming(HttpServletResponse, String)}, {@link #toResponseEntity(String)}. Each
     * terminal delegates straight back to the enclosing component so the work still runs inside a
     * Spring-proxied, {@code @PxlPerformanceLogging}-annotated method.</p>
     *
     * <p>Nested in the component on purpose: everything the component reads off the builder - its constructor,
     * the collected {@code entries} and the whole {@code Entry} hierarchy, {@code validateEntries()},
     * {@code resolveZipFilename(String)} - is {@code private} and stays reachable only because the two are
     * nestmates. The public surface is exactly the entry and terminal methods. The one exception is inside
     * {@code Entry}, whose own methods are package-private because an abstract method cannot be
     * {@code private}; it widens nothing, since the type declaring them is itself {@code private}.</p>
     *
     * <p>Not thread-safe, and single-use per terminal call. Example:</p>
     *
     * <pre>{@code
     * pxlSpring.exportZip()
     *         .workbook(januaryReport)
     *         .workbook(februaryReport, option, "February")
     *         .poiWorkbook(alreadyBuilt, null, "raw")
     *         .sampleWorkbook(UploadForm.class, null, "template")
     *         .csvSheet(Employee.class, employees, "Employees")
     *         .sampleCsvSheet(Employee.class, "Upload form")
     *         .toResponse(response, "quarterly-report");
     * }</pre>
     */
    public static final class Builder {

        /**
         * Entry file name prefix used when no earlier step - an explicit name, or the workbook object name
         * where the kind of entry has one - yields a name; the entry's zero-based index is appended.
         *
         * <p>Only the Excel kinds reach it. A CSV entry is required to have a sheet name, so it always has
         * one to fall back on and never needs a default of its own.</p>
         */
        private static final String DEFAULT_EXPORT_EXCEL_FILENAME = "Pxl";

        /**
         * The same for a sample template entry, which describes a shape rather than a data set.
         *
         * <p>Matches the download name {@code PxlSampleExcelExporter} falls back to, except that the index is
         * appended here: that one names a single download, where uniqueness is nobody's concern, while these
         * share an archive and must come out distinct.</p>
         */
        private static final String DEFAULT_EXPORT_SAMPLE_EXCEL_FILENAME = "PxlSample";

        /**
         * The owning component; terminals call back into it so the export runs through its AOP proxy.
         */
        private final PxlZipExporter exporter;

        /**
         * The collected archive entries, in the order they will be written. Read directly by the enclosing
         * component while it writes the archive.
         *
         * <p>One list holds every kind of entry, and that is what makes {@code validateEntries} whole: it
         * compares each entry against every other one regardless of what they are made of. Kept in a list per
         * kind instead, the duplicate check would run within each list only and a name colliding across two
         * kinds would go into the archive unnoticed.</p>
         */
        private final List<Entry> entries = new ArrayList<>();

        /**
         * Creates a builder bound to the owning component.
         *
         * <p>Takes no {@code Pxl} - there is no core builder to seed; the component generates each entry with
         * its own {@code Pxl} instance while writing the archive.</p>
         *
         * @param exporter the component the terminal methods delegate back to (its AOP proxy where available)
         */
        private Builder(final PxlZipExporter exporter) {

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
         * <p>An {@code exportExcelEngine} in the option decides the entry's extension as well as its bytes,
         * taking precedence over the engine the workbook class declares through {@code @PxlWorkbook} - so an
         * option carrying {@code HSSF} produces an entry named {@code .xls} holding OLE2 bytes. Extension and
         * content cannot disagree, and the entry is compressed as what it actually is.</p>
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
         * <p>The option drives this entry's bytes and extension exactly as in
         * {@link #workbook(Object, PxlExportWorkbookOption)}; the name given here carries no extension, which
         * is appended from the format that option - or, failing that, the workbook class - resolves to.</p>
         *
         * <p>Entry names have to come out distinct, ignoring case. Nothing is checked at this call - the
         * fallbacks are resolved by the terminal's up-front validation - so a collision surfaces from the
         * terminal as {@link PxlArgumentException}, still before any byte is written. Naming entries
         * explicitly is how two workbooks that declare the same name go into one archive.</p>
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

            this.entries.add(new WorkbookEntry(workbookObject, workbookOption, excelFilename));
            return this;
        }

        /**
         * Adds one archive entry for an already-built raw POI {@link Workbook}, written as-is without any PXL
         * binding.
         *
         * @param workbook the workbook to write into the archive
         * @return this builder
         * @throws PxlNullPointerException if {@code workbook} is {@code null}
         */
        public Builder poiWorkbook(final Workbook workbook)
                throws PxlNullPointerException {

            return poiWorkbook(workbook, null, null);
        }

        /**
         * Adds one archive entry for a raw POI {@link Workbook} encrypted with the given password.
         *
         * <p>Encryption wraps the bytes in an OLE2 container whatever the workbook type, while the entry is
         * still named after the workbook's own format - so an encrypted XSSF workbook goes in as OLE2 bytes
         * under a {@code .xlsx} name, which is how an encrypted OOXML file is normally distributed. Same
         * behaviour as {@code PxlExcelExporter.Builder.poiWorkbook(Workbook, String)}, deliberately.</p>
         *
         * @param workbook the workbook to write into the archive
         * @param password the encryption password, or {@code null} for none
         * @return this builder
         * @throws PxlNullPointerException if {@code workbook} is {@code null}
         */
        public Builder poiWorkbook(final Workbook workbook,
                                   @Nullable final String password)
                throws PxlNullPointerException {

            return poiWorkbook(workbook, password, null);
        }

        /**
         * Adds one archive entry for a raw POI {@link Workbook}, with a password and an entry file name applied
         * to that entry only.
         *
         * <p>There is no export option here: nothing is bound, so there would be nothing for one to override -
         * exactly as on {@code PxlExcelExporter}. The entry's extension is not configurable either; it is read
         * back off the workbook itself ({@code HSSFWorkbook} &rarr; {@code .xls},
         * {@code XSSFWorkbook}/{@code SXSSFWorkbook} &rarr; {@code .xlsx}), which is the format its body is
         * written in, so name and bytes cannot disagree.</p>
         *
         * <p>Entry names have to come out distinct, ignoring case, across every kind of entry; a collision
         * surfaces from the terminal as {@link PxlArgumentException}. This kind has no workbook name to fall
         * back to, so an unnamed one always resolves to {@code Pxl{index}}.</p>
         *
         * @param workbook      the workbook to write into the archive
         * @param password      the encryption password, or {@code null} for none
         * @param excelFilename the entry file name without extension; when blank it falls back to
         *                      {@code Pxl{index}}
         * @return this builder
         * @throws PxlNullPointerException if {@code workbook} is {@code null}
         */
        public Builder poiWorkbook(final Workbook workbook,
                                   @Nullable final String password,
                                   @Nullable final String excelFilename)
                throws PxlNullPointerException {

            if (Objects.isNull(workbook)) {
                throw new PxlNullPointerException("workbook must not be null");
            }

            this.entries.add(new PoiWorkbookEntry(workbook, password, excelFilename));
            return this;
        }

        /**
         * Adds one archive entry holding a sample template generated from a class annotated with
         * {@code @PxlWorkbook} - the header row plus one row of {@code @PxlColumn(exportSample = ...)} values,
         * exactly what {@code PxlSampleExcelExporter} produces on its own.
         *
         * @param workbookClass the {@code @PxlWorkbook}-annotated class to describe
         * @return this builder
         * @throws PxlNullPointerException if {@code workbookClass} is {@code null}
         */
        public Builder sampleWorkbook(final Class<?> workbookClass)
                throws PxlNullPointerException {

            return sampleWorkbook(workbookClass, null, null);
        }

        /**
         * Adds one sample template entry, with an export option applied to that entry only.
         *
         * <p>The option drives this entry's extension as well as its bytes, on the same terms as
         * {@link #workbook(Object, PxlExportWorkbookOption)}: an {@code exportExcelEngine} in it takes
         * precedence over the engine the class declares through {@code @PxlWorkbook}.</p>
         *
         * @param workbookClass  the {@code @PxlWorkbook}-annotated class to describe
         * @param workbookOption the export option for this entry, or {@code null}
         * @return this builder
         * @throws PxlNullPointerException if {@code workbookClass} is {@code null}
         */
        public Builder sampleWorkbook(final Class<?> workbookClass,
                                      @Nullable final PxlExportWorkbookOption workbookOption)
                throws PxlNullPointerException {

            return sampleWorkbook(workbookClass, workbookOption, null);
        }

        /**
         * Adds one sample template entry, with an export option and an entry file name applied to that entry
         * only.
         *
         * <p>The name given here carries no extension, which is appended from the format that option - or,
         * failing that, the class - resolves to.</p>
         *
         * <p><strong>An unnamed sample entry falls back to {@code PxlSample{index}}, with no workbook-name
         * step in between.</strong> That step reads a {@code @PxlWorkbookName} field off an annotated
         * <em>instance</em>, and this form is given a class; there is nothing to read it from. Nothing is lost
         * by it: the index is always appended, so unnamed sample entries stay distinct from one another - the
         * one thing an archive needs. Note the difference from {@code PxlSampleExcelExporter}, which falls
         * back to a bare {@code PxlSample}: that names a single download, where uniqueness is nobody's
         * concern.</p>
         *
         * @param workbookClass  the {@code @PxlWorkbook}-annotated class to describe
         * @param workbookOption the export option for this entry, or {@code null}
         * @param excelFilename  the entry file name without extension; when blank it falls back to
         *                       {@code PxlSample{index}}
         * @return this builder
         * @throws PxlNullPointerException if {@code workbookClass} is {@code null}
         */
        public Builder sampleWorkbook(final Class<?> workbookClass,
                                      @Nullable final PxlExportWorkbookOption workbookOption,
                                      @Nullable final String excelFilename)
                throws PxlNullPointerException {

            if (Objects.isNull(workbookClass)) {
                throw new PxlNullPointerException("workbookClass must not be null");
            }

            this.entries.add(new SampleWorkbookEntry(workbookClass, workbookOption, excelFilename));
            return this;
        }

        /**
         * Adds one archive entry holding a row collection written as CSV - what {@code PxlCsvExporter}
         * produces on its own.
         *
         * <p>One CSV file is one sheet, so this adds one member per call rather than one sheet to a shared
         * one, and {@code sheetName} names both the sheet and, unless overridden, the entry.</p>
         *
         * @param rowClass  the row class
         * @param rows      the row objects for this entry
         * @param sheetName the sheet name; must not be blank
         * @param <T>       the row type
         * @return this builder
         * @throws PxlNullPointerException if {@code rowClass}, {@code rows} or {@code sheetName} is
         *                                 {@code null}
         * @throws PxlArgumentException    if {@code sheetName} is blank
         */
        public <T> Builder csvSheet(final Class<T> rowClass,
                                    final Collection<T> rows,
                                    final String sheetName)
                throws PxlNullPointerException, PxlArgumentException {

            return csvSheet(rowClass, rows, sheetName, null, null);
        }

        /**
         * Adds one CSV entry, with an export option applied to that entry only.
         *
         * <p>The option is where a CSV entry's charset, field delimiter and byte order mark come from
         * ({@code exportCsv*}); an {@code exportExcelEngine} in it means nothing here, since the entry is
         * always written as CSV.</p>
         *
         * @param rowClass       the row class
         * @param rows           the row objects for this entry
         * @param sheetName      the sheet name; must not be blank
         * @param workbookOption the export option for this entry, or {@code null}
         * @param <T>            the row type
         * @return this builder
         * @throws PxlNullPointerException if {@code rowClass}, {@code rows} or {@code sheetName} is
         *                                 {@code null}
         * @throws PxlArgumentException    if {@code sheetName} is blank
         */
        public <T> Builder csvSheet(final Class<T> rowClass,
                                    final Collection<T> rows,
                                    final String sheetName,
                                    @Nullable final PxlExportWorkbookOption workbookOption)
                throws PxlNullPointerException, PxlArgumentException {

            return csvSheet(rowClass, rows, sheetName, workbookOption, null);
        }

        /**
         * Adds one CSV entry, with an export option and an entry file name applied to that entry only.
         *
         * <p>The name given here carries no extension; {@code .csv} is appended, and it is the only extension
         * this kind can take - there is no engine to resolve, which is why these two kinds have no
         * {@code resolveFileFormat} of their own. The deflate level follows from that name like any other, so
         * a CSV entry is compressed rather than stored the way an already-deflated {@code .xlsx} is.</p>
         *
         * <p>Unnamed, the entry takes the sheet name. There is no index-suffixed default behind that, because
         * a CSV entry cannot be added without a sheet name in the first place - so unlike the other kinds
         * there is always something to fall back to. Two entries built from the same sheet name therefore
         * collide, and that is rejected before anything is written; name them explicitly.</p>
         *
         * @param rowClass       the row class
         * @param rows           the row objects for this entry
         * @param sheetName      the sheet name; must not be blank
         * @param workbookOption the export option for this entry, or {@code null}
         * @param csvFilename    the entry file name without extension; when blank it falls back to
         *                       {@code sheetName}
         * @param <T>            the row type
         * @return this builder
         * @throws PxlNullPointerException if {@code rowClass}, {@code rows} or {@code sheetName} is
         *                                 {@code null}
         * @throws PxlArgumentException    if {@code sheetName} is blank
         */
        public <T> Builder csvSheet(final Class<T> rowClass,
                                    final Collection<T> rows,
                                    final String sheetName,
                                    @Nullable final PxlExportWorkbookOption workbookOption,
                                    @Nullable final String csvFilename)
                throws PxlNullPointerException, PxlArgumentException {

            if (Objects.isNull(rowClass)) {
                throw new PxlNullPointerException("rowClass must not be null");
            }
            if (Objects.isNull(rows)) {
                throw new PxlNullPointerException("rows must not be null");
            }
            requireSheetName(sheetName);

            this.entries.add(new CsvSheetEntry<>(rowClass, rows, sheetName, workbookOption, csvFilename));
            return this;
        }

        /**
         * Adds one archive entry holding a CSV sample template generated from a row class - the header record
         * plus one record of {@code @PxlColumn(exportSample = ...)} values, what {@code PxlSampleCsvExporter}
         * produces on its own.
         *
         * @param rowClass  the row class describing the columns
         * @param sheetName the sheet name; must not be blank
         * @return this builder
         * @throws PxlNullPointerException if {@code rowClass} or {@code sheetName} is {@code null}
         * @throws PxlArgumentException    if {@code sheetName} is blank
         */
        public Builder sampleCsvSheet(final Class<?> rowClass,
                                      final String sheetName)
                throws PxlNullPointerException, PxlArgumentException {

            return sampleCsvSheet(rowClass, sheetName, null, null);
        }

        /**
         * Adds one CSV sample template entry, with an export option applied to that entry only.
         *
         * <p>The option carries the same {@code exportCsv*} settings as on
         * {@link #csvSheet(Class, Collection, String, PxlExportWorkbookOption)}.</p>
         *
         * @param rowClass       the row class describing the columns
         * @param sheetName      the sheet name; must not be blank
         * @param workbookOption the export option for this entry, or {@code null}
         * @return this builder
         * @throws PxlNullPointerException if {@code rowClass} or {@code sheetName} is {@code null}
         * @throws PxlArgumentException    if {@code sheetName} is blank
         */
        public Builder sampleCsvSheet(final Class<?> rowClass,
                                      final String sheetName,
                                      @Nullable final PxlExportWorkbookOption workbookOption)
                throws PxlNullPointerException, PxlArgumentException {

            return sampleCsvSheet(rowClass, sheetName, workbookOption, null);
        }

        /**
         * Adds one CSV sample template entry, with an export option and an entry file name applied to that
         * entry only.
         *
         * <p>Named exactly as {@link #csvSheet(Class, Collection, String, PxlExportWorkbookOption, String)}:
         * {@code .csv} is appended, and an unnamed entry takes the sheet name, which this kind is likewise
         * required to have. It is {@link #sampleWorkbook(Class)} that needs a {@code PxlSample{index}}
         * default: given a class and nothing else, it has no name of its own to take.</p>
         *
         * @param rowClass       the row class describing the columns
         * @param sheetName      the sheet name; must not be blank
         * @param workbookOption the export option for this entry, or {@code null}
         * @param csvFilename    the entry file name without extension; when blank it falls back to
         *                       {@code sheetName}
         * @return this builder
         * @throws PxlNullPointerException if {@code rowClass} or {@code sheetName} is {@code null}
         * @throws PxlArgumentException    if {@code sheetName} is blank
         */
        public Builder sampleCsvSheet(final Class<?> rowClass,
                                      final String sheetName,
                                      @Nullable final PxlExportWorkbookOption workbookOption,
                                      @Nullable final String csvFilename)
                throws PxlNullPointerException, PxlArgumentException {

            if (Objects.isNull(rowClass)) {
                throw new PxlNullPointerException("rowClass must not be null");
            }
            requireSheetName(sheetName);

            this.entries.add(new SampleCsvSheetEntry(rowClass, sheetName, workbookOption, csvFilename));
            return this;
        }

        /**
         * Rejects a missing sheet name at the call that adds the entry.
         *
         * <p>The core would reject it too, but only from inside {@code writeBody} - by then the file
         * destination has created its file and the streaming one has sent its headers. It has to be here for
         * a second reason as well: a CSV entry's name falls back to its sheet name, so {@code validateEntries}
         * reads this value long before the core sees it. Same exception types the core raises, so the two
         * paths cannot be told apart by what they throw.</p>
         *
         * @param sheetName the sheet name given to a CSV entry method
         * @throws PxlNullPointerException if {@code sheetName} is {@code null}
         * @throws PxlArgumentException    if {@code sheetName} is blank
         */
        private static void requireSheetName(final String sheetName)
                throws PxlNullPointerException, PxlArgumentException {

            if (Objects.isNull(sheetName)) {
                throw new PxlNullPointerException("sheetName must not be null");
            }
            if (StringUtils.isBlank(sheetName)) {
                throw new PxlArgumentException("sheetName must not be blank");
            }
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
         * @throws PxlException if {@code outputStream} is {@code null}, no entry was added, an entry name
         *                      carries a path or collides with another, or writing the archive fails
         */
        public void toStream(final OutputStream outputStream)
                throws PxlException {

            exporter.exportZipToStream(this, outputStream);
        }

        /**
         * Writes the configured entries as a ZIP archive to the given file.
         *
         * @param zipFile the destination ZIP file
         * @throws PxlException if {@code zipFile} is {@code null}, no entry was added, an entry name carries
         *                      a path or collides with another, or writing the archive fails
         */
        public void toFile(final File zipFile)
                throws PxlException {

            exporter.exportZipToFile(this, zipFile);
        }

        /**
         * Streams the configured entries as a ZIP archive to the servlet response with download headers.
         *
         * <p>{@code zipFilename} is required - unlike the per-entry names there is nothing to fall back to, so
         * a blank one raises {@link PxlArgumentException}. The name is used as given - normalize it (NFC)
         * upstream if needed; RFC 5987 encoding is applied when the header is written.</p>
         *
         * @param response    the servlet response to write to
         * @param zipFilename the archive file name without extension; required
         * @throws PxlException if {@code response} is {@code null}, no entry was added, an entry name carries
         *                      a path or collides with another, {@code zipFilename} is blank, or writing the
         *                      archive or response fails
         */
        public void toResponse(final HttpServletResponse response,
                               final String zipFilename)
                throws PxlException {

            exporter.exportZipToResponse(this, response, zipFilename);
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
         * <p>Note where the line falls. <strong>Every check this builder makes itself runs before the
         * headers</strong> - the empty archive, an entry name carrying a path, a duplicate entry name and the
         * archive name - so those failures still leave the response untouched. What does not is anything the
         * core raises while an entry is being generated: a workbook whose export metadata will not resolve, a
         * CSV entry given a password. Those happen inside the write loop, after the headers have gone out.
         * Such a failure does put bytes on the wire - {@link ZipOutputStream#putNextEntry} writes the entry's
         * local file header before the entry's body is generated - so the client receives a download that
         * starts and stops. What it never receives is a <em>readable</em> one: the central directory is
         * written only once every entry is in, so a failed download can never be mistaken for a complete
         * archive - see {@code writeArchive}.</p>
         *
         * <p>There is no streaming counterpart for the other destinations: {@link #toStream(OutputStream)} and
         * {@link #toFile(File)} already write straight through, and {@link #toResponseEntity(String)} cannot,
         * because its body is produced in full before the entity is returned - the {@link Resource} it carries
         * wraps the finished bytes rather than generating them on demand.</p>
         *
         * @param response    the servlet response to write to
         * @param zipFilename the archive file name without extension; required
         * @throws PxlException if {@code response} is {@code null}, no entry was added, an entry name carries
         *                      a path or collides with another, {@code zipFilename} is blank, or writing the
         *                      archive or response fails
         */
        public void toResponseStreaming(final HttpServletResponse response,
                                        final String zipFilename)
                throws PxlException {

            exporter.exportZipToResponseStreaming(this, response, zipFilename);
        }

        /**
         * Returns the configured entries as a ZIP archive in a {@link ResponseEntity} with download headers.
         *
         * <p>The name is required exactly as in {@link #toResponse(HttpServletResponse, String)}.</p>
         *
         * @param zipFilename the archive file name without extension; required
         * @return the response entity carrying the archive bytes
         * @throws PxlException if no entry was added, an entry name carries a path or collides with another,
         *                      {@code zipFilename} is blank, or building the response fails
         */
        public ResponseEntity<Resource> toResponseEntity(final String zipFilename)
                throws PxlException {

            return exporter.exportZipToResponseEntity(this, zipFilename);
        }

        // ----- validation and resolution helpers read by PxlZipExporter -----
        // private: the component is this class's nestmate, so nothing here needs to be exposed.

        /**
         * Rejects an archive with no members, one whose members carry a path in their name, and one whose
         * members would collide by name. Called by every terminal before any work is done.
         *
         * <p><strong>Both name checks belong here rather than in {@code writeEntries} because here they are
         * still early enough to matter.</strong> Every terminal calls this first, so either failure comes
         * before the file destination has created its file and before the streaming destination has sent its
         * download headers - neither of which can be taken back once writing has begun. The loop resolves
         * each entry's name once and both checks read that one value, so what is reported is what would have
         * been written.</p>
         *
         * <p><strong>The path check.</strong> A {@link ZipEntry} name may legally hold a path, and this one
         * can come from application data - a workbook name or a sheet name the application filled in - so an
         * unchecked separator would put a traversal path inside an archive we hand out.
         * {@link FilenameUtils#getName} strips everything up to the last {@code '/'} or {@code '\'} (and any
         * drive prefix), so a name that survives it unchanged carries no path. Only separators matter: every
         * kind of entry ends its name with its format's extension, so a bare {@code ".."} can never come out
         * as {@code ".."} here.</p>
         *
         * <p><strong>The duplicate check.</strong> Comparison is on the whole entry name, extension included,
         * so the same base name written by two different engines ({@code report.xlsx} and {@code report.xls})
         * stays two distinct members. It <strong>ignores case</strong>, which is stricter than
         * {@code ZipOutputStream} - that one compares exactly, so it would let {@code Report.xlsx} and
         * {@code report.xlsx} both into the archive, and extracting them on a case-insensitive file system
         * (Windows, macOS by default) would silently overwrite one with the other. Folding with
         * {@link Locale#ROOT} keeps the fold from following the default locale, where {@code I} does not
         * lower-case to {@code i}. Left to {@link ZipOutputStream#putNextEntry}, the same collision would
         * surface mid-write as a {@link ZipException} wrapped in {@link PxlIOException}, indistinguishable
         * from a disk failure.</p>
         *
         * @throws PxlArgumentException if no entry was added, an entry name carries a path, or two entries
         *                              resolve to the same file name
         */
        private void validateEntries()
                throws PxlArgumentException {

            if (entries.isEmpty()) {
                throw new PxlArgumentException("at least one entry must be specified: workbook(...), poiWorkbook(...), sampleWorkbook(...), csvSheet(...) or sampleCsvSheet(...)");
            }

            final Set<String> seenEntryNames = new HashSet<>();

            for (int index = 0; index < entries.size(); index++) {
                final String entryName = entries.get(index).resolveEntryName(index);

                if (!entryName.equals(FilenameUtils.getName(entryName))) {
                    throw new PxlArgumentException("entry file name must not carry a path: '" + entryName + "'");
                }

                if (!seenEntryNames.add(entryName.toLowerCase(Locale.ROOT))) {
                    throw new PxlArgumentException("duplicate entry file name: '" + entryName + "'");
                }
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
         * One archive member, whatever it is made of: it names itself and writes its own body.
         *
         * <p>Abstract rather than a single struct with a nullable field per kind of source, so that
         * {@code writeEntries} never has to ask what kind of entry it is holding and adding a kind cannot
         * change how the existing ones behave.</p>
         *
         * <p>{@link #resolveEntryName(int)} is declared here rather than computed here because a kind decides
         * for itself what its name falls back to when none was given - a workbook name, a sheet name, an
         * index-suffixed default. What must stay true is not the formula but the <strong>single call
         * site</strong>: {@code validateEntries} asks an entry for its name to run both name checks on it,
         * and {@code writeEntries} asks the same entry for it to name the {@link ZipEntry}, so the name a
         * failure was reported for is the name that would have been written. The part every kind does
         * share - the format's extension on the end - lives in
         * {@link #withExtension(String, PxlFileFormat)}, so no kind spells it out again.</p>
         *
         * <p>{@code resolveFileFormat()} is deliberately <em>not</em> part of this contract. Two kinds have
         * no format to resolve - CSV is all they write - so it stays a private detail of the kinds that do
         * have a choice.</p>
         *
         * <p>Private throughout: instances are created only by the enclosing builder and read only by
         * {@link PxlZipExporter}, a nestmate of both. The methods below are package-private rather than
         * {@code private} only because an abstract method cannot be {@code private}, and the helper matches
         * them; the type declaring them is itself {@code private}, so nothing is reachable from outside.</p>
         */
        private abstract static class Entry {

            /**
             * Resolves the name this entry takes inside the archive, extension included.
             *
             * @param index the entry's zero-based index in the archive; a kind whose source always carries
             *              a name of its own ignores it
             * @return the entry name, extension included
             */
            abstract String resolveEntryName(int index);

            /**
             * Writes this entry's body into the open archive stream.
             *
             * <p>Called once the archive stream is positioned on this entry, so it writes bytes and nothing
             * else - it neither opens nor closes the entry, and never closes the archive stream.</p>
             *
             * @param outputStream the open archive stream, positioned on this entry
             * @param pxl          the shared core entry point, handed in so an entry holds no core instance
             *                     of its own
             * @throws PxlException if generating or writing this entry's bytes fails
             */
            abstract void writeBody(OutputStream outputStream, Pxl pxl) throws PxlException;

            /**
             * Appends a format's extension to an entry's base file name.
             *
             * <p>The one place a format becomes an extension. {@code deflateLevelFor} reads that extension
             * back off the finished name, so a kind that spelled the extension out itself could be
             * compressed as something it is not.</p>
             *
             * @param filename   the entry file name without extension
             * @param fileFormat the format this entry is written in
             * @return the entry name, extension included
             */
            final String withExtension(final String filename,
                                       final PxlFileFormat fileFormat) {

                return filename
                        + FilenameUtils.EXTENSION_SEPARATOR_STR
                        + fileFormat.getFilenameExtension();
            }

        }

        /**
         * An archive member built from a {@code @PxlWorkbook}-annotated object: the object to export, its
         * optional per-entry export option, and its optional entry file name.
         */
        private static final class WorkbookEntry extends Entry {

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
            private WorkbookEntry(final Object workbookObject,
                                  final PxlExportWorkbookOption workbookOption,
                                  final String excelFilename) {

                this.workbookObject = workbookObject;
                this.workbookOption = workbookOption;
                this.excelFilename = excelFilename;
            }

            /**
             * Resolves the name this entry takes inside the archive: its file name plus the extension of the
             * format it is written in - see {@link #resolveFileFormat()}.
             *
             * @param index the entry's zero-based index in the archive
             * @return the entry name, extension included
             */
            @Override
            String resolveEntryName(final int index) {

                return withExtension(resolveExcelFilename(index), resolveFileFormat());
            }

            /**
             * Resolves the format this entry is written in: the engine carried by its own export option, else
             * the one its workbook class declares through {@code @PxlWorkbook}.
             *
             * <p>The option comes first because it is what decides the bytes - the core is handed the same
             * option when the entry is generated, so asking the class instead would name the entry after a
             * format it is not written in. Same priority as {@code PxlExcelExporter.Builder.resolveFileFormat}
             * for the equivalent single-workbook export; the two are deliberately alike.</p>
             *
             * <p>This answer reaches the deflate level too, by way of the extension it puts on the entry
             * name - see {@code deflateLevelFor}. So the compression follows the bytes that are actually
             * written rather than the ones the class would have produced.</p>
             *
             * @return the entry's file format
             */
            private PxlFileFormat resolveFileFormat() {

                final PxlExcelEngine optionExcelEngine = Objects.nonNull(workbookOption)
                        ? workbookOption.getExportExcelEngine()
                        : null;

                return Objects.nonNull(optionExcelEngine)
                        ? optionExcelEngine.getFileFormat()
                        : PxlExcelEngine.fromWorkbookObject(workbookObject.getClass()).getFileFormat();
            }

            /**
             * Exports the workbook object straight into the archive stream, applying this entry's own option.
             *
             * @param outputStream the open archive stream, positioned on this entry
             * @param pxl          the shared core entry point
             * @throws PxlException if the workbook fails to export
             */
            @Override
            void writeBody(final OutputStream outputStream,
                           final Pxl pxl)
                    throws PxlException {

                pxl.exportExcel()
                        .workbook(workbookObject)
                        .override(workbookOption)
                        .toStream(outputStream);
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

        /**
         * An archive member built from an already-open POI {@link Workbook}: the workbook to write, its
         * optional encryption password, and its optional entry file name.
         *
         * <p>Carries no export option, unlike {@code WorkbookEntry}: nothing is bound here, so there would be
         * nothing for one to override - the same reason {@code PxlExcelExporter.Builder.override} does not
         * reach its raw-workbook form.</p>
         */
        private static final class PoiWorkbookEntry extends Entry {

            private final Workbook poiWorkbook;

            private final String poiPassword;

            private final String excelFilename;

            /**
             * Creates an archive entry.
             *
             * @param poiWorkbook   the workbook (never {@code null})
             * @param poiPassword   the encryption password, or {@code null} for none
             * @param excelFilename the entry file name without extension, or {@code null}/blank for the
             *                      fallback
             */
            private PoiWorkbookEntry(final Workbook poiWorkbook,
                                     final String poiPassword,
                                     final String excelFilename) {

                this.poiWorkbook = poiWorkbook;
                this.poiPassword = poiPassword;
                this.excelFilename = excelFilename;
            }

            /**
             * Resolves the name this entry takes inside the archive: its file name plus the extension of the
             * format it is written in - see {@link #resolveFileFormat()}.
             *
             * @param index the entry's zero-based index in the archive
             * @return the entry name, extension included
             */
            @Override
            String resolveEntryName(final int index) {

                return withExtension(resolveExcelFilename(index), resolveFileFormat());
            }

            /**
             * Resolves the format this entry is written in: the workbook's own.
             *
             * <p>Read back off the workbook because that is what the body is written in, so the extension and
             * the bytes cannot disagree and there is nothing for the caller to configure - or to get wrong.
             * It is {@link PxlFileFormat#fromPoiWorkbook} rather than {@link PxlExcelEngine#fromPoiWorkbook}
             * that is asked, because a streaming-reader workbook is a reader and therefore has no engine, yet
             * still holds XLSX bytes. Same reasoning as
             * {@code PxlExcelExporter.Builder.resolveFileFormat}; that lookup already falls back to the
             * default for a workbook type it does not recognise, and the {@link Optional} around it only
             * guards against a future core that could return {@code null}.</p>
             *
             * <p>An encrypted workbook is an exception in appearance only: encryption wraps the bytes in an
             * OLE2 container whatever the type, but the entry keeps the workbook's own extension, which is
             * how an encrypted OOXML file is normally distributed.</p>
             *
             * @return the entry's file format
             */
            private PxlFileFormat resolveFileFormat() {

                return Optional.ofNullable(PxlFileFormat.fromPoiWorkbook(poiWorkbook))
                        .orElse(PxlConstants.DEFAULT_EXPORT_FILE_FORMAT);
            }

            /**
             * Writes the workbook straight into the archive stream, encrypting it where a password was given.
             *
             * @param outputStream the open archive stream, positioned on this entry
             * @param pxl          the shared core entry point; unused, because this form goes nowhere near
             *                     the binding layer
             * @throws PxlException if encryption is requested but the workbook type does not support it, or
             *                      writing the workbook fails
             */
            @Override
            void writeBody(final OutputStream outputStream,
                           final Pxl pxl)
                    throws PxlException {

                PxlWorkbookUtils.writeToStream(poiWorkbook, outputStream, poiPassword);
            }

            /**
             * Resolves this entry's file name: the explicit name, else {@code Pxl} followed by the entry
             * index.
             *
             * <p>Shorter than {@code WorkbookEntry.resolveExcelFilename} by one step, and not by choice: a
             * workbook name is read off a {@code @PxlWorkbook}-annotated object, and a raw POI workbook is
             * not one.</p>
             *
             * @param index the entry's zero-based index in the archive
             * @return the entry file name without extension
             */
            private String resolveExcelFilename(final int index) {

                return StringUtils.isNotBlank(excelFilename)
                        ? excelFilename
                        : DEFAULT_EXPORT_EXCEL_FILENAME + index;
            }

        }

        /**
         * An archive member holding a sample template generated from a {@code @PxlWorkbook}-annotated class:
         * the class to describe, its optional per-entry export option, and its optional entry file name.
         *
         * <p>Holds a {@link Class} where {@code WorkbookEntry} holds an instance, which is the whole of the
         * difference between the two - see {@link #resolveExcelFilename(int)} for what that costs.</p>
         */
        private static final class SampleWorkbookEntry extends Entry {

            private final Class<?> workbookClass;

            private final PxlExportWorkbookOption workbookOption;

            private final String excelFilename;

            /**
             * Creates an archive entry.
             *
             * @param workbookClass  the class to describe (never {@code null})
             * @param workbookOption the export option for this entry, or {@code null}
             * @param excelFilename  the entry file name without extension, or {@code null}/blank for the
             *                       fallback
             */
            private SampleWorkbookEntry(final Class<?> workbookClass,
                                        final PxlExportWorkbookOption workbookOption,
                                        final String excelFilename) {

                this.workbookClass = workbookClass;
                this.workbookOption = workbookOption;
                this.excelFilename = excelFilename;
            }

            /**
             * Resolves the name this entry takes inside the archive: its file name plus the extension of the
             * format it is written in - see {@link #resolveFileFormat()}.
             *
             * @param index the entry's zero-based index in the archive
             * @return the entry name, extension included
             */
            @Override
            String resolveEntryName(final int index) {

                return withExtension(resolveExcelFilename(index), resolveFileFormat());
            }

            /**
             * Resolves the format this entry is written in: the engine carried by its own export option, else
             * the one its class declares through {@code @PxlWorkbook}.
             *
             * <p>Same priority, and for the same reason, as {@code WorkbookEntry.resolveFileFormat}: the core
             * is handed that option when the template is generated, so it is the option that decides the
             * bytes. A template is generated from the class either way, so there is no third source to
             * consider.</p>
             *
             * @return the entry's file format
             */
            private PxlFileFormat resolveFileFormat() {

                final PxlExcelEngine optionExcelEngine = Objects.nonNull(workbookOption)
                        ? workbookOption.getExportExcelEngine()
                        : null;

                return Objects.nonNull(optionExcelEngine)
                        ? optionExcelEngine.getFileFormat()
                        : PxlExcelEngine.fromWorkbookObject(workbookClass).getFileFormat();
            }

            /**
             * Generates the sample template straight into the archive stream, applying this entry's own
             * option.
             *
             * @param outputStream the open archive stream, positioned on this entry
             * @param pxl          the shared core entry point
             * @throws PxlException if the template fails to generate
             */
            @Override
            void writeBody(final OutputStream outputStream,
                           final Pxl pxl)
                    throws PxlException {

                pxl.exportSampleExcel()
                        .workbook(workbookClass)
                        .override(workbookOption)
                        .toStream(outputStream);
            }

            /**
             * Resolves this entry's file name: the explicit name, else {@code PxlSample} followed by the entry
             * index.
             *
             * <p>No workbook-name step, and not by choice: {@code PxlWorkbookUtils} reads a workbook name off
             * an annotated <em>instance</em>, and this kind is given a class. The index is always appended, so
             * unnamed sample entries are distinct from one another regardless.</p>
             *
             * @param index the entry's zero-based index in the archive
             * @return the entry file name without extension
             */
            private String resolveExcelFilename(final int index) {

                return StringUtils.isNotBlank(excelFilename)
                        ? excelFilename
                        : DEFAULT_EXPORT_SAMPLE_EXCEL_FILENAME + index;
            }

        }

        /**
         * An archive member holding a row collection written as CSV: the row class and its rows, the sheet
         * name they are written under, an optional per-entry export option, and an optional entry file name.
         *
         * <p>Generic so that the row class and the collection stay paired the way the core's
         * {@code sheet(Class<T>, Collection<T>, String)} requires. Held as {@code Class<?>} plus
         * {@code Collection<?>} instead, that pairing would be lost at the field and {@code writeBody} would
         * need a cast; the entry list is {@code List<Entry>} either way, so nothing is given up for it.</p>
         */
        private static final class CsvSheetEntry<T> extends Entry {

            private final Class<T> rowClass;

            private final Collection<T> rows;

            private final String sheetName;

            private final PxlExportWorkbookOption workbookOption;

            private final String csvFilename;

            /**
             * Creates an archive entry.
             *
             * @param rowClass       the row class (never {@code null})
             * @param rows           the row objects (never {@code null})
             * @param sheetName      the sheet name (never blank)
             * @param workbookOption the export option for this entry, or {@code null}
             * @param csvFilename    the entry file name without extension, or {@code null}/blank for the
             *                       fallback
             */
            private CsvSheetEntry(final Class<T> rowClass,
                                  final Collection<T> rows,
                                  final String sheetName,
                                  final PxlExportWorkbookOption workbookOption,
                                  final String csvFilename) {

                this.rowClass = rowClass;
                this.rows = rows;
                this.sheetName = sheetName;
                this.workbookOption = workbookOption;
                this.csvFilename = csvFilename;
            }

            /**
             * Resolves the name this entry takes inside the archive: the explicit name, else the sheet name,
             * plus {@code .csv}.
             *
             * <p>The format is a constant rather than something to resolve - CSV is the only thing this kind
             * writes - which is why {@code resolveFileFormat} is not part of {@code Entry}'s contract but a
             * private detail of the kinds that do have a choice.</p>
             *
             * <p>{@code index} goes unused, the only kind where it does: the other four fall back to an
             * index-suffixed default because their source may carry no name, while a CSV entry cannot be added
             * without a sheet name. There is consequently nothing to make an unnamed entry unique, so two
             * entries under one sheet name collide - {@code validateEntries} rejects that before anything is
             * written.</p>
             *
             * @param index the entry's zero-based index in the archive; unused by this kind
             * @return the entry name, extension included
             */
            @Override
            String resolveEntryName(final int index) {

                return withExtension(resolveCsvFilename(), PxlFileFormat.CSV);
            }

            /**
             * Writes the rows straight into the archive stream as CSV, applying this entry's own option.
             *
             * @param outputStream the open archive stream, positioned on this entry
             * @param pxl          the shared core entry point
             * @throws PxlException if the rows fail to export
             */
            @Override
            void writeBody(final OutputStream outputStream,
                           final Pxl pxl)
                    throws PxlException {

                pxl.exportCsv()
                        .sheet(rowClass, rows, sheetName)
                        .override(workbookOption)
                        .toStream(outputStream);
            }

            /**
             * Resolves this entry's file name: the explicit name, else the sheet name.
             *
             * @return the entry file name without extension
             */
            private String resolveCsvFilename() {

                return StringUtils.isNotBlank(csvFilename) ? csvFilename : sheetName;
            }

        }

        /**
         * An archive member holding a CSV sample template generated from a row class: the class to describe,
         * the sheet name, an optional per-entry export option, and an optional entry file name.
         *
         * <p>Not generic, unlike {@code CsvSheetEntry}: there are no rows to keep paired with the class.</p>
         */
        private static final class SampleCsvSheetEntry extends Entry {

            private final Class<?> rowClass;

            private final String sheetName;

            private final PxlExportWorkbookOption workbookOption;

            private final String csvFilename;

            /**
             * Creates an archive entry.
             *
             * @param rowClass       the row class describing the columns (never {@code null})
             * @param sheetName      the sheet name (never blank)
             * @param workbookOption the export option for this entry, or {@code null}
             * @param csvFilename    the entry file name without extension, or {@code null}/blank for the
             *                       fallback
             */
            private SampleCsvSheetEntry(final Class<?> rowClass,
                                        final String sheetName,
                                        final PxlExportWorkbookOption workbookOption,
                                        final String csvFilename) {

                this.rowClass = rowClass;
                this.sheetName = sheetName;
                this.workbookOption = workbookOption;
                this.csvFilename = csvFilename;
            }

            /**
             * Resolves the name this entry takes inside the archive: the explicit name, else the sheet name,
             * plus {@code .csv}.
             *
             * <p>{@code index} goes unused for the same reason as on {@code CsvSheetEntry} - a sheet name is
             * required, so there is always a fallback and never a need for one built from the index.</p>
             *
             * @param index the entry's zero-based index in the archive; unused by this kind
             * @return the entry name, extension included
             */
            @Override
            String resolveEntryName(final int index) {

                return withExtension(resolveCsvFilename(), PxlFileFormat.CSV);
            }

            /**
             * Generates the CSV sample template straight into the archive stream, applying this entry's own
             * option.
             *
             * @param outputStream the open archive stream, positioned on this entry
             * @param pxl          the shared core entry point
             * @throws PxlException if the template fails to generate
             */
            @Override
            void writeBody(final OutputStream outputStream,
                           final Pxl pxl)
                    throws PxlException {

                pxl.exportSampleCsv()
                        .sheet(rowClass, sheetName)
                        .override(workbookOption)
                        .toStream(outputStream);
            }

            /**
             * Resolves this entry's file name: the explicit name, else the sheet name.
             *
             * @return the entry file name without extension
             */
            private String resolveCsvFilename() {

                return StringUtils.isNotBlank(csvFilename) ? csvFilename : sheetName;
            }

        }

    }

}
