package io.github.hclimkr.pxl.spring.component;

import io.github.hclimkr.pxl.Pxl;
import io.github.hclimkr.pxl.exception.PxlArgumentException;
import io.github.hclimkr.pxl.exception.PxlException;
import io.github.hclimkr.pxl.exception.PxlIOException;
import io.github.hclimkr.pxl.exception.PxlNullPointerException;
import io.github.hclimkr.pxl.option.PxlExportWorkbookOption;
import io.github.hclimkr.pxl.spring.PxlSpring;
import io.github.hclimkr.pxl.spring.tcdata.*;
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

import java.io.*;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;

import static io.github.hclimkr.pxl.spring.component.PxlExcelExporterTests.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Behavioural tests for {@link PxlExcelZipExporter}, all driven through the
 * {@link PxlExcelZipExporter.Builder} fluent API: bundling multiple workbook objects into one ZIP across
 * every entry form and destination, entry naming (provided name &rarr; workbook name &rarr; index fallback),
 * per-entry export options, and archive validity via {@link ZipFile} (reads the central directory, not just
 * streamed local headers).
 *
 * <p>The builder comes from {@link PxlSpring}, the entry point the documentation guides users to. The
 * facade hands back this component's own builder, so what is exercised here is still the component.</p>
 */
class PxlExcelZipExporterTests {

    private final PxlSpring pxlSpring = new PxlSpring();

    private TestInfo testInfo;

    @BeforeEach
    void bindTestInfo(final TestInfo testInfo) {
        this.testInfo = testInfo;
    }

    private static TestWorkbook workbook(final String name) {
        final TestWorkbook workbook = new TestWorkbook();
        workbook.setWorkbookName(name);
        workbook.setUsers(users());
        return workbook;
    }

    /**
     * Adds the two named workbooks ("first", "second") as archive entries.
     */
    private PxlExcelZipExporter.Builder named() throws PxlException {
        return pxlSpring.exportExcelZip()
                .workbook(workbook("first"))
                .workbook(workbook("second"));
    }

    /**
     * Adds two workbooks with no workbook name, so entry naming falls through to the index fallback.
     */
    private PxlExcelZipExporter.Builder unnamed() throws PxlException {
        return pxlSpring.exportExcelZip()
                .workbook(workbook(null))
                .workbook(workbook(null));
    }

    private static List<String> entryNames(final byte[] bytes) throws IOException {
        final List<String> names = new ArrayList<>();
        try (ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(bytes))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                names.add(entry.getName());
                zis.closeEntry();
            }
        }
        return names;
    }

    private static File writeTempZip(final byte[] bytes) throws IOException {
        // Files.createTempFile, not File.createTempFile: on systems whose temp directory is shared
        // between local users the latter creates a world-readable file (CodeQL
        // java/local-temp-file-or-directory-information-disclosure).
        final File tmp = Files.createTempFile("pxl-zip-", ".zip").toFile();
        tmp.deleteOnExit();
        Files.write(tmp.toPath(), bytes);
        return tmp;
    }

    /**
     * Reads the entry names from the ZIP <em>central directory</em> via {@link ZipFile}. Unlike
     * {@link #entryNames}, which streams local file headers, opening a {@code ZipFile} requires the
     * end-of-central-directory record, so this fails on an archive whose central directory was never
     * flushed (e.g. bytes captured before the {@code ZipOutputStream} was closed).
     */
    private static List<String> centralDirectoryEntryNames(final byte[] bytes) throws IOException {
        final File tmp = writeTempZip(bytes);
        try (ZipFile zipFile = new ZipFile(tmp)) {
            final List<String> names = new ArrayList<>();
            final Enumeration<? extends ZipEntry> entries = zipFile.entries();
            while (entries.hasMoreElements()) {
                names.add(entries.nextElement().getName());
            }
            return names;
        } finally {
            tmp.delete();
        }
    }

    @Test
    void zipToResponseEntity_containsOneEntryPerWorkbook() throws PxlException, IOException {
        final ResponseEntity<Resource> entity = named()
                .toResponseEntity("archive");

        assertThat(entity.getStatusCode().value()).isEqualTo(200);
        assertThat(entity.getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION)).contains("archive.zip");
        assertThat(entity.getHeaders().getFirst(HttpHeaders.CONTENT_TYPE)).isEqualTo("application/zip");
        assertThat(entity.getHeaders().getContentLength()).isEqualTo(bodyBytes(entity).length);
        assertThat(entryNames(bodyBytes(entity))).hasSize(2);
    }

    @Test
    void zipToResponse_writesToServletResponse() throws PxlException, IOException {
        final MockHttpServletResponse response = new MockHttpServletResponse();
        named().toResponse(response, "archive");

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(response.getHeader(HttpHeaders.CONTENT_DISPOSITION)).contains("archive.zip");
        assertThat(response.getContentType()).isEqualTo("application/zip");
        assertThat(entryNames(response.getContentAsByteArray())).hasSize(2);
    }

    @Test
    void zipToStream_isValidZip() throws PxlException, IOException {
        final ByteArrayOutputStream baos = new ByteArrayOutputStream();
        named().toStream(baos);

        assertThat(entryNames(baos.toByteArray())).hasSize(2);
    }

    @Test
    void zipToFile_writesValidZip() throws PxlException, IOException {
        final File zipFile = TestPaths.exportFile(testInfo, ".zip");
        named().toFile(zipFile);

        assertThat(zipFile).exists();
        assertThat(entryNames(Files.readAllBytes(zipFile.toPath()))).hasSize(2);
    }

    @Test
    void providedFilenames_nameTheEntries() throws PxlException, IOException {
        final ByteArrayOutputStream baos = new ByteArrayOutputStream();
        pxlSpring.exportExcelZip()
                .workbook(workbook("first"), null, "a")
                .workbook(workbook("second"), null, "b")
                .toStream(baos);

        assertThat(entryNames(baos.toByteArray())).containsExactly("a.xlsx", "b.xlsx");
    }

    @Test
    void missingFilenamesAndNames_fallBackToPxlIndex() throws PxlException, IOException {
        final ByteArrayOutputStream baos = new ByteArrayOutputStream();
        unnamed().toStream(baos);

        assertThat(entryNames(baos.toByteArray())).containsExactly("Pxl0.xlsx", "Pxl1.xlsx");
    }

    @Test
    void blankProvidedFilename_fallsBackToWorkbookName() throws PxlException, IOException {
        // a blank per-entry name is treated as absent, so the workbook name still wins
        final ByteArrayOutputStream baos = new ByteArrayOutputStream();
        pxlSpring.exportExcelZip()
                .workbook(workbook("first"), null, "  ")
                .toStream(baos);

        assertThat(entryNames(baos.toByteArray())).containsExactly("first.xlsx");
    }

    @Test
    void workbookName_usedWhenNoFilenameProvided() throws PxlException, IOException {
        final ByteArrayOutputStream baos = new ByteArrayOutputStream();
        named().toStream(baos);

        assertThat(entryNames(baos.toByteArray())).containsExactly("first.xlsx", "second.xlsx");
    }

    // ----- ZipFile (central-directory) validity -----
    // These fail if the archive bytes are captured before the ZipOutputStream is closed (missing central
    // directory) - the streaming ZipInputStream checks above would not catch that.

    @Test
    void zipToResponseEntity_isValidArchiveViaZipFile() throws PxlException, IOException {
        final ResponseEntity<Resource> entity = named().toResponseEntity("archive");

        assertThat(centralDirectoryEntryNames(bodyBytes(entity))).containsExactly("first.xlsx", "second.xlsx");
    }

    @Test
    void zipToResponse_isValidArchiveViaZipFile() throws PxlException, IOException {
        final MockHttpServletResponse response = new MockHttpServletResponse();
        named().toResponse(response, "archive");

        assertThat(centralDirectoryEntryNames(response.getContentAsByteArray()))
                .containsExactly("first.xlsx", "second.xlsx");
    }

    @Test
    void zipToStream_isValidArchiveViaZipFile() throws PxlException, IOException {
        final ByteArrayOutputStream baos = new ByteArrayOutputStream();
        named().toStream(baos);

        assertThat(centralDirectoryEntryNames(baos.toByteArray())).containsExactly("first.xlsx", "second.xlsx");
    }

    @Test
    void zipToFile_isValidArchiveViaZipFile() throws PxlException, IOException {
        final File zipFile = TestPaths.exportFile(testInfo, ".zip");
        named().toFile(zipFile);

        assertThat(centralDirectoryEntryNames(Files.readAllBytes(zipFile.toPath())))
                .containsExactly("first.xlsx", "second.xlsx");
    }

    @Test
    void zipEntryContent_isReadableXlsx_andRoundTrips() throws PxlException, IOException {
        final ResponseEntity<Resource> entity = named().toResponseEntity("archive");

        final File tmp = writeTempZip(bodyBytes(entity));
        try (ZipFile zipFile = new ZipFile(tmp)) {
            final ZipEntry entry = zipFile.getEntry("first.xlsx");
            assertThat(entry).as("central-directory entry first.xlsx").isNotNull();

            try (InputStream in = zipFile.getInputStream(entry)) {
                @SuppressWarnings("unchecked") final List<TestUser> back = new Pxl().importExcel()
                        .sheet(TestUser.class, List.class, Collections.singletonList("Users"))
                        .fromStream(in);
                assertThat(back).extracting(TestUser::getName).containsExactly("Alice", "Bob");
            }
        } finally {
            tmp.delete();
        }
    }

    @Test
    void zipToStream_doesNotCloseGivenStream() throws PxlException, IOException {
        final ByteArrayOutputStream delegate = new ByteArrayOutputStream();
        final AtomicBoolean closed = new AtomicBoolean(false);
        final OutputStream tracking = new OutputStream() {
            @Override
            public void write(final int b) {
                delegate.write(b);
            }

            @Override
            public void write(final byte[] b, final int off, final int len) {
                delegate.write(b, off, len);
            }

            @Override
            public void close() {
                closed.set(true);
            }
        };

        named().toStream(tracking);

        assertThat(closed).as("caller's stream must be left open").isFalse();
        // the archive is still complete despite the caller's stream not being closed
        assertThat(centralDirectoryEntryNames(delegate.toByteArray())).containsExactly("first.xlsx", "second.xlsx");
    }

    @Test
    void zipToStream_whenUnderlyingStreamFails_throwsPxlIOException() {
        // an OutputStream that fails on every write: the ZipOutputStream propagates the IOException out of
        // writeEntries, which the exporter wraps as PxlIOException
        final OutputStream failing = new OutputStream() {
            @Override
            public void write(final int b) throws IOException {
                throw new IOException("simulated write failure");
            }
        };

        assertThatThrownBy(() -> named().toStream(failing))
                .isInstanceOf(PxlIOException.class);
    }

    @Test
    void zipToStream_whenWritingTheCentralDirectoryFails_throwsPxlIOException() {
        // Regression guard: the central directory is written by ZipOutputStream.close(), i.e. after
        // writeEntries has returned. Swallowing that failure (the old closeQuietly in a finally block) let
        // the exporter report success while handing the caller a structurally invalid archive.
        assertThatThrownBy(() -> named().toStream(failsWhenTheCentralDirectoryStarts()))
                .isInstanceOf(PxlIOException.class);
    }

    /**
     * An {@link OutputStream} that accepts the whole archive body but fails the moment the central directory
     * begins.
     *
     * <p>{@code ZipOutputStream} emits header fields one byte at a time and entry payload in chunks, so
     * watching the byte-wise writes for the central-directory signature ({@code 50 4B 01 02}) pins the
     * failure to {@code close()}/{@code finish()} exactly: the local file header ({@code 50 4B 03 04})
     * and the data descriptor ({@code 50 4B 07 08}) carry different signatures, and both are written
     * while entries are still being added.</p>
     */
    private static OutputStream failsWhenTheCentralDirectoryStarts() {
        return new OutputStream() {

            private int b1 = -1;

            private int b2 = -1;

            private int b3 = -1;

            private int b4 = -1;

            @Override
            public void write(final int b) throws IOException {
                b1 = b2;
                b2 = b3;
                b3 = b4;
                b4 = b & 0xff;

                if (b1 == 'P' && b2 == 'K' && b3 == 0x01 && b4 == 0x02) {
                    throw new IOException("simulated failure while writing the central directory");
                }
            }

            @Override
            public void write(final byte[] b, final int off, final int len) {
                // entry payload (and header byte runs such as entry names): discard it, and reset the window
                // so a payload byte sequence can never be mistaken for the signature
                b1 = b2 = b3 = b4 = -1;
            }
        };
    }

    @Test
    void zipToFile_underMissingDirectory_throwsPxlIOException() {
        // parent directory does not exist -> FileOutputStream throws FileNotFoundException, which the
        // exporter wraps as PxlIOException
        final File unwritable = new File("target/no-such-dir-for-pxl/archive.zip");

        assertThatThrownBy(() -> named().toFile(unwritable))
                .isInstanceOf(PxlIOException.class);
    }

    @Test
    void zipToResponse_whenGenerationFails_leavesResponseUntouched() {
        final MockHttpServletResponse response = new MockHttpServletResponse();

        // TestBadNameWorkbook fails while the core resolves its export metadata, i.e. during generation.
        // In the pre-Option-A code the download headers were already written by then; now the whole archive
        // is built into a buffer first, so the response must be left untouched.
        assertThatThrownBy(() -> pxlSpring.exportExcelZip()
                .workbook(new TestBadNameWorkbook(1), null, "a")
                .toResponse(response, "archive"))
                .isInstanceOf(PxlException.class);

        assertThat(response.getHeader(HttpHeaders.CONTENT_DISPOSITION)).isNull();
        assertThat(response.getContentType()).isNull();
        assertThat(response.getContentAsByteArray()).isEmpty();
    }

    @Test
    void zipToResponse_replacesPreexistingDownloadHeaders() throws PxlException {
        final MockHttpServletResponse response = new MockHttpServletResponse();
        response.addHeader(HttpHeaders.CONTENT_DISPOSITION, "inline");
        response.setContentType("text/html");

        named().toResponse(response, "archive");

        // Content-Disposition must be replaced, not appended
        assertThat(response.getHeaders(HttpHeaders.CONTENT_DISPOSITION)).hasSize(1);
        assertThat(response.getHeader(HttpHeaders.CONTENT_DISPOSITION)).contains("archive.zip");
        assertThat(response.getHeaders(HttpHeaders.CONTENT_TYPE)).hasSize(1);
        assertThat(response.getContentType()).isEqualTo("application/zip");
    }

    // ----- builder guards -----

    @Test
    void noEntry_throwsPxlArgument() {
        assertThatThrownBy(() ->
                pxlSpring.exportExcelZip().toStream(new ByteArrayOutputStream()))
                .isInstanceOf(PxlArgumentException.class);
    }

    @Test
    void nullWorkbookObject_throwsPxlNullPointer() {
        assertThatThrownBy(() -> pxlSpring.exportExcelZip().workbook(null))
                .isInstanceOf(PxlNullPointerException.class);
    }

    @Test
    void xlsxEntriesAreNotRecompressed_whileXlsEntriesStillAre() throws PxlException, IOException {
        final ByteArrayOutputStream baos = new ByteArrayOutputStream();
        pxlSpring.exportExcelZip()
                .workbook(workbook("ooxml"))                          // -> ooxml.xlsx
                .workbook(new TestHssfWorkbook("ole2", users()))      // -> ole2.xls
                .toStream(baos);

        final File tmp = writeTempZip(baos.toByteArray());
        try (ZipFile zipFile = new ZipFile(tmp)) {
            // .xlsx is itself a deflated container, so it is written at NO_COMPRESSION to skip a pointless
            // compression pass. A deflate stream carries that as stored blocks (5 bytes of framing per 64KB),
            // which is observable here: the entry ends up marginally larger than its raw bytes. Re-deflating
            // it - what happened before - shrinks it instead, so this assertion pins the change.
            final ZipEntry xlsx = zipFile.getEntry("ooxml.xlsx");
            assertThat(xlsx.getCompressedSize()).isGreaterThan(xlsx.getSize());

            // .xls is uncompressed OLE2, where deflate genuinely pays off, so it keeps the default level
            final ZipEntry xls = zipFile.getEntry("ole2.xls");
            assertThat(xls.getCompressedSize()).isLessThan(xls.getSize());
        } finally {
            tmp.delete();
        }
    }

    @Test
    void nullDestinationOnPlainComponent_throwsPxlNullPointerNotRawNpe() {
        // this component is a plain instance, so @NotNull never fires (no Spring proxy). Through a proxy the
        // same calls raise ConstraintViolationException - that half is pinned by PxlValidationTests.
        assertThatThrownBy(() -> named().toStream(null))
                .isInstanceOf(PxlNullPointerException.class);

        assertThatThrownBy(() -> named().toFile(null))
                .isInstanceOf(PxlNullPointerException.class);

        assertThatThrownBy(() -> named().toResponse(null, "archive"))
                .isInstanceOf(PxlNullPointerException.class);

        assertThatThrownBy(() -> named().toResponseStreaming(null, "archive"))
                .isInstanceOf(PxlNullPointerException.class);
    }

    // ----- toResponseStreaming(...) -----

    @Test
    void streamingToResponse_writesAValidArchiveWithoutContentLength() throws PxlException, IOException {
        final MockHttpServletResponse buffered = new MockHttpServletResponse();
        named().toResponse(buffered, "archive");

        final MockHttpServletResponse streamed = new MockHttpServletResponse();
        named().toResponseStreaming(streamed, "archive");

        // read through the central directory, so this also proves close() finished the archive even though
        // the servlet stream was shielded from being closed
        assertThat(centralDirectoryEntryNames(streamed.getContentAsByteArray()))
                .containsExactly("first.xlsx", "second.xlsx");
        assertThat(streamed.getHeader(HttpHeaders.CONTENT_DISPOSITION))
                .isEqualTo(buffered.getHeader(HttpHeaders.CONTENT_DISPOSITION));
        assertThat(streamed.getContentType()).isEqualTo("application/zip");

        assertThat(buffered.getHeader(HttpHeaders.CONTENT_LENGTH)).isNotNull();
        assertThat(streamed.getHeader(HttpHeaders.CONTENT_LENGTH)).isNull();
    }

    @Test
    void streamingSetsHeadersBeforeEntryNamesAreValidated() {
        // The path-separator check on an entry name sits inside writeEntries, which on the streaming path runs
        // *after* the headers have gone out. So a rejected name still throws and still writes no body, but the
        // download headers are already on the response - that is the documented cost of streaming, not a bug
        // to assert away. The buffered path (the default) has no such window: it validates while writing to a
        // buffer. Not every entry-name failure lands here: duplicates are caught by validateEntries, which
        // runs before the headers on either path - see duplicateEntryNames_areRejectedBeforeHeadersGoOut.
        //
        // "no body" is not free: it holds because writeArchive finishes the archive only once every entry is
        // in. Closing the ZipOutputStream as the exception unwound would emit the end-of-central-directory
        // record even with zero entries written, handing the client a well-formed *empty* archive.
        final MockHttpServletResponse streamed = new MockHttpServletResponse();

        assertThatThrownBy(() -> pxlSpring.exportExcelZip()
                .workbook(workbook("first"), null, "sub/report")

                .toResponseStreaming(streamed, "archive"))
                .isInstanceOf(PxlArgumentException.class);

        assertThat(streamed.getContentAsByteArray()).isEmpty();
        assertThat(streamed.getHeader(HttpHeaders.CONTENT_DISPOSITION)).isNotNull();

        final MockHttpServletResponse buffered = new MockHttpServletResponse();

        assertThatThrownBy(() -> pxlSpring.exportExcelZip()
                .workbook(workbook("first"), null, "sub/report")
                .toResponse(buffered, "archive"))
                .isInstanceOf(PxlArgumentException.class);

        assertThat(buffered.getContentAsByteArray()).isEmpty();
        assertThat(buffered.getHeader(HttpHeaders.CONTENT_DISPOSITION)).isNull();
    }

    @Test
    void streamingStillRejectsAnEmptyArchiveBeforeTouchingTheResponse() {
        // validateEntries and resolveZipFilename are pxl-spring's own up-front guards: they run before the
        // headers on either path, so these failures do leave the response untouched
        final MockHttpServletResponse response = new MockHttpServletResponse();

        assertThatThrownBy(() -> pxlSpring.exportExcelZip()
                .toResponseStreaming(response, "archive"))
                .isInstanceOf(PxlArgumentException.class);

        assertThat(response.getHeader(HttpHeaders.CONTENT_DISPOSITION)).isNull();
        assertThat(response.getContentAsByteArray()).isEmpty();
    }

    @Test
    void blankZipFilename_throwsPxlArgumentOnResponseDestinations() {
        // the archive has no name to fall back to, so the response destinations require a non-blank name
        assertThatThrownBy(() -> named().toResponseEntity(null))
                .isInstanceOf(PxlArgumentException.class);

        assertThatThrownBy(() -> named().toResponse(new MockHttpServletResponse(), "  "))
                .isInstanceOf(PxlArgumentException.class);
    }

    @Test
    void entryNameCarryingAPath_throwsPxlArgument() {
        // a ZipEntry name may legally hold a path, so an unchecked separator would put a traversal path
        // inside an archive we hand out - Zip Slip for whoever extracts it
        assertThatThrownBy(() -> pxlSpring.exportExcelZip()
                .workbook(workbook("first"), null, "../../evil")
                .toStream(new ByteArrayOutputStream()))
                .isInstanceOf(PxlArgumentException.class);

        // '\' is a literal character in a ZIP name rather than a separator, so it does not traverse, but it
        // produces a name that extractors disagree about; FilenameUtils.getName rejects both alike
        assertThatThrownBy(() -> pxlSpring.exportExcelZip()
                .workbook(workbook("first"), null, "sub\\report")
                .toStream(new ByteArrayOutputStream()))
                .isInstanceOf(PxlArgumentException.class);
    }

    @Test
    void failedExportToFile_leavesNothingOpenableAsAnArchive() {
        // The worst place for a "finished anyway" archive is a file, because it persists. writeArchive
        // finishes only once every entry is in, so a failure leaves bytes with no central directory; were it
        // finished on the way out instead, this would be a valid, empty ZIP that every tool reports as a
        // perfectly fine export of nothing.
        final File zipFile = TestPaths.exportFile(testInfo, ".zip");

        assertThatThrownBy(() -> pxlSpring.exportExcelZip()
                .workbook(workbook("first"), null, "sub/report")
                .toFile(zipFile))
                .isInstanceOf(PxlArgumentException.class);

        assertThat(zipFile).exists();
        assertThatThrownBy(() -> centralDirectoryEntryNames(Files.readAllBytes(zipFile.toPath())))
                .isInstanceOf(IOException.class);
    }

    @Test
    void pathCarryingWorkbookName_isRejectedToo() {
        // the guard has to cover the fallback source as well: a workbook name is data the application filled
        // in, not an argument the caller wrote at the call site
        assertThatThrownBy(() -> pxlSpring.exportExcelZip()
                .workbook(workbook("sub/report"))
                .toStream(new ByteArrayOutputStream()))
                .isInstanceOf(PxlArgumentException.class);
    }

    // ----- duplicate entry names -----
    // The Pxl{index} fallback applies only when neither an explicit name nor a workbook name is there, so two
    // instances of the same workbook class - the ordinary case - resolve to the same entry name.
    // validateEntries rejects that up front; left to ZipOutputStream.putNextEntry it would have surfaced
    // mid-write as a ZipException wrapped in PxlIOException, indistinguishable from a disk failure.

    @Test
    void duplicateEntryNames_throwPxlArgumentNamingTheOffender() {
        assertThatThrownBy(() -> pxlSpring.exportExcelZip()
                .workbook(workbook("same"))
                .workbook(workbook("same"))
                .toStream(new ByteArrayOutputStream()))
                .isInstanceOf(PxlArgumentException.class)
                // the message carries the resolved entry name, extension included - that is what would have
                // been written, and it is the whole point of failing here instead of inside the zip stream
                .hasMessageContaining("same.xlsx");
    }

    @Test
    void duplicateEntryNames_areComparedAcrossEveryNameSource() {
        // the check compares resolved names, not one source against itself: an explicit name on one entry and
        // a workbook-name fallback on another collide just the same
        assertThatThrownBy(() -> pxlSpring.exportExcelZip()
                .workbook(workbook("report"))                       // fallback  -> report.xlsx
                .workbook(workbook("other"), null, "report")        // explicit  -> report.xlsx
                .toStream(new ByteArrayOutputStream()))
                .isInstanceOf(PxlArgumentException.class);
    }

    @Test
    void entryNamesDifferingOnlyInCase_areRejectedToo() {
        // Stricter than ZipOutputStream on purpose: it compares exactly, so both would go into the archive,
        // and extracting them on a case-insensitive file system (Windows, macOS by default) would silently
        // overwrite one with the other.
        assertThatThrownBy(() -> pxlSpring.exportExcelZip()
                .workbook(workbook("Report"))
                .workbook(workbook("report"))
                .toStream(new ByteArrayOutputStream()))
                .isInstanceOf(PxlArgumentException.class);
    }

    @Test
    void theCaseFoldDoesNotFollowTheDefaultLocale() throws Exception {
        // Turkish is the reason the fold names Locale.ROOT: there 'I' lower-cases to the dotless 'ı', so
        // "FILE" and "file" would fold apart and the collision would slip through on a Turkish JVM only.
        final Locale previous = Locale.getDefault();
        try {
            Locale.setDefault(new Locale("tr", "TR"));

            assertThatThrownBy(() -> pxlSpring.exportExcelZip()
                    .workbook(workbook("FILE"))
                    .workbook(workbook("file"))
                    .toStream(new ByteArrayOutputStream()))
                    .isInstanceOf(PxlArgumentException.class);
        } finally {
            Locale.setDefault(previous);
        }
    }

    @Test
    void duplicateEntryNames_areRejectedBeforeTheFileIsCreated() {
        // What hoisting the check into validateEntries buys: the file destination fails before opening its
        // FileOutputStream, so no unreadable leftover is on disk at all. Contrast
        // failedExportToFile_leavesNothingOpenableAsAnArchive, where the later path check does leave one.
        final File zipFile = TestPaths.exportFile(testInfo, ".zip");

        assertThatThrownBy(() -> pxlSpring.exportExcelZip()
                .workbook(workbook("same"))
                .workbook(workbook("same"))
                .toFile(zipFile))
                .isInstanceOf(PxlArgumentException.class);

        assertThat(zipFile).doesNotExist();
    }

    @Test
    void duplicateEntryNames_areRejectedBeforeHeadersGoOut() {
        // The other half of the same win: on the streaming path validateEntries runs before
        // setResponseForExportZip, so this failure leaves no download headers behind - unlike the
        // path-separator check, which is inside the write loop (streamingSetsHeadersBeforeEntryNamesAreValidated).
        final MockHttpServletResponse response = new MockHttpServletResponse();

        assertThatThrownBy(() -> pxlSpring.exportExcelZip()
                .workbook(workbook("same"))
                .workbook(workbook("same"))
                .toResponseStreaming(response, "archive"))
                .isInstanceOf(PxlArgumentException.class);

        assertThat(response.getHeader(HttpHeaders.CONTENT_DISPOSITION)).isNull();
        assertThat(response.getContentAsByteArray()).isEmpty();
    }

    @Test
    void anExplicitNameSeparatesWorkbooksDeclaringTheSameName() throws PxlException, IOException {
        // the documented way out of the collision, pinned so the advice in the workbook(...) javadoc keeps
        // working
        final ByteArrayOutputStream baos = new ByteArrayOutputStream();
        pxlSpring.exportExcelZip()
                .workbook(workbook("same"), null, "january")
                .workbook(workbook("same"), null, "february")
                .toStream(baos);

        assertThat(centralDirectoryEntryNames(baos.toByteArray()))
                .containsExactly("january.xlsx", "february.xlsx");
    }

    @Test
    void differingExtensions_makeTheSameBaseNameTwoDistinctEntries() throws PxlException, IOException {
        // the collision above is on the full entry name, extension included: the same base name under two
        // declared engines is two members, not a clash
        final ByteArrayOutputStream baos = new ByteArrayOutputStream();
        pxlSpring.exportExcelZip()
                .workbook(workbook("report"))                            // -> report.xlsx
                .workbook(new TestHssfWorkbook("report", users()))       // -> report.xls
                .toStream(baos);

        assertThat(centralDirectoryEntryNames(baos.toByteArray()))
                .containsExactly("report.xlsx", "report.xls");
    }

    // ----- entry-form x destination matrix -----
    // Three workbook(...) arities, each reachable from all four terminals.

    /**
     * The four terminal destinations, swept by the matrix tests below.
     */
    enum Dest {
        STREAM, FILE, RESPONSE, RESPONSE_ENTITY
    }

    /**
     * Runs the configured builder against the given destination and returns the archive bytes, so one
     * assertion can serve every destination. File artifacts are named per destination to stay inspectable.
     */
    private byte[] emit(final PxlExcelZipExporter.Builder builder, final Dest dest) throws PxlException, IOException {
        switch (dest) {
            case STREAM: {
                final ByteArrayOutputStream baos = new ByteArrayOutputStream();
                builder.toStream(baos);
                return baos.toByteArray();
            }
            case FILE: {
                final File file = TestPaths.exportFile(testInfo.getTestMethod()
                        .orElseThrow(IllegalStateException::new).getName() + "-" + dest + ".zip");
                builder.toFile(file);
                return Files.readAllBytes(file.toPath());
            }
            case RESPONSE: {
                final MockHttpServletResponse response = new MockHttpServletResponse();
                builder.toResponse(response, "archive");
                return response.getContentAsByteArray();
            }
            default: {
                return bodyBytes(builder.toResponseEntity("archive"));
            }
        }
    }

    @ParameterizedTest
    @EnumSource(Dest.class)
    void workbookWithoutOption_namesEntriesOnEveryDestination(final Dest dest) throws PxlException, IOException {
        final byte[] bytes = emit(pxlSpring.exportExcelZip()
                .workbook(workbook("first"))
                .workbook(workbook("second")), dest);

        assertThat(centralDirectoryEntryNames(bytes)).containsExactly("first.xlsx", "second.xlsx");
    }

    @ParameterizedTest
    @EnumSource(Dest.class)
    void workbookWithOption_namesEntriesOnEveryDestination(final Dest dest) throws PxlException, IOException {
        // the two-argument workbook(object, option) overload had no coverage at all
        final byte[] bytes = emit(pxlSpring.exportExcelZip()
                .workbook(workbook("first"), hssfOption())
                .workbook(workbook("second"), null), dest);

        assertThat(centralDirectoryEntryNames(bytes)).containsExactly("first.xlsx", "second.xlsx");
    }

    @ParameterizedTest
    @EnumSource(Dest.class)
    void workbookWithOptionAndName_namesEntriesOnEveryDestination(final Dest dest) throws PxlException, IOException {
        final byte[] bytes = emit(pxlSpring.exportExcelZip()
                .workbook(workbook("first"), null, "a")
                .workbook(workbook("second"), null, "b"), dest);

        assertThat(centralDirectoryEntryNames(bytes)).containsExactly("a.xlsx", "b.xlsx");
    }

    @ParameterizedTest
    @EnumSource(Dest.class)
    void singleEntryArchive_isValidOnEveryDestination(final Dest dest) throws PxlException, IOException {
        final byte[] bytes = emit(pxlSpring.exportExcelZip().workbook(workbook("only")), dest);

        assertThat(centralDirectoryEntryNames(bytes)).containsExactly("only.xlsx");
    }

    // ----- per-entry export options -----

    @Test
    void perEntryOption_changesTheEntryBodyButNotItsExtension() throws PxlException, IOException {
        // the entry extension comes from the workbook class's declared engine, not from the per-entry
        // option - so an HSSF option yields OLE2 bytes still stored under a .xlsx entry name
        final ByteArrayOutputStream baos = new ByteArrayOutputStream();
        pxlSpring.exportExcelZip()
                .workbook(workbook("hssf"), hssfOption())
                .workbook(workbook("xssf"))
                .toStream(baos);

        assertThat(centralDirectoryEntryNames(baos.toByteArray())).containsExactly("hssf.xlsx", "xssf.xlsx");
        assertThat(isXlsx(entryBytes(baos.toByteArray(), "hssf.xlsx"))).isFalse();
        assertThat(isXlsx(entryBytes(baos.toByteArray(), "xssf.xlsx"))).isTrue();
    }

    @Test
    void mixedEntryForms_areWrittenInCallOrder() throws PxlException, IOException {
        final ByteArrayOutputStream baos = new ByteArrayOutputStream();
        pxlSpring.exportExcelZip()
                .workbook(workbook("one"))
                .workbook(workbook("two"))
                .workbook(workbook("ignored"), null, "three")
                .toStream(baos);

        assertThat(centralDirectoryEntryNames(baos.toByteArray()))
                .containsExactly("one.xlsx", "two.xlsx", "three.xlsx");
    }

    @Test
    void koreanEntryAndArchiveNames_areCarriedThrough() throws PxlException, IOException {
        final ResponseEntity<Resource> entity = pxlSpring.exportExcelZip()
                .workbook(workbook("무시됨"), null, "사용자")
                .toResponseEntity("보고서모음");

        final String contentDisposition = entity.getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION);
        assertThat(contentDisposition).startsWith("attachment; filename=\"_____.zip\"; filename*=UTF-8''");
        assertThat(contentDisposition).doesNotContain("보고서모음").contains("%");
        // the ZIP entry name is not a header, so it keeps its Korean characters
        assertThat(centralDirectoryEntryNames(bodyBytes(entity))).containsExactly("사용자.xlsx");
    }

    private static PxlExportWorkbookOption hssfOption() {
        return PxlExportWorkbookOption.builder()
                .exportExcelEngine(PxlExcelEngine.HSSF)
                .build();
    }

    /**
     * Reads one entry's bytes out of the archive via its central directory.
     */
    private static byte[] entryBytes(final byte[] archive, final String entryName) throws IOException {
        final File tmp = writeTempZip(archive);
        try (ZipFile zipFile = new ZipFile(tmp)) {
            final ZipEntry entry = zipFile.getEntry(entryName);
            assertThat(entry).as("central-directory entry %s", entryName).isNotNull();

            try (InputStream in = zipFile.getInputStream(entry)) {
                final ByteArrayOutputStream out = new ByteArrayOutputStream();
                final byte[] chunk = new byte[8192];
                int read;
                while ((read = in.read(chunk)) != -1) {
                    out.write(chunk, 0, read);
                }
                return out.toByteArray();
            }
        } finally {
            tmp.delete();
        }
    }
}
