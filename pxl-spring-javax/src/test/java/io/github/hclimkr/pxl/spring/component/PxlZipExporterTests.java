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
import io.github.hclimkr.pxl.util.PxlWorkbookUtils;
import org.apache.poi.hssf.usermodel.HSSFWorkbook;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.streaming.SXSSFWorkbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.HttpMediaTypeNotSupportedException;

import java.io.*;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
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
 * Behavioural tests for {@link PxlZipExporter}, all driven through the
 * {@link PxlZipExporter.Builder} fluent API: bundling spreadsheets into one ZIP across every kind of entry,
 * every overload that adds one and every destination, entry naming (provided name &rarr; the name the source
 * carries &rarr; index fallback), per-entry export options, and archive validity via {@link ZipFile} (reads
 * the central directory, not just streamed local headers). Five kinds of source reach an entry - a
 * {@code @PxlWorkbook}-annotated object, a raw POI {@link Workbook}, a sample template generated from a class,
 * and the two CSV equivalents - and the invariants that hold them together (one resolved name per entry, an
 * extension that follows the bytes, a duplicate check spanning every kind) are pinned across all of them.
 *
 * <p>CSV expectations that depend on encoding are asserted on the raw entry bytes rather than on a decoded
 * string: decoding first would absorb a byte order mark into U+FEFF and hide whether one was written.</p>
 *
 * <p>The builder comes from {@link PxlSpring}, the entry point the documentation guides users to. The
 * facade hands back this component's own builder, so what is exercised here is still the component.</p>
 *
 * <p>The last section is different in kind: an archive entry is a polymorphic type, and the two properties
 * that split rests on - one shared entry list, and entry types that stay private nestmates - are structural.
 * Splitting the list per kind would still pass every behavioural test that adds entries of one kind, and
 * widening a nestmate would pass all of them; both are therefore pinned reflectively. The roster assertion
 * there also fixes the list of kinds itself, so a sixth one has to be added to it deliberately.</p>
 */
class PxlZipExporterTests {

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
    private PxlZipExporter.Builder named() throws PxlException {
        return pxlSpring.exportZip()
                .workbook(workbook("first"))
                .workbook(workbook("second"));
    }

    /**
     * Adds two workbooks with no workbook name, so entry naming falls through to the index fallback.
     */
    private PxlZipExporter.Builder unnamed() throws PxlException {
        return pxlSpring.exportZip()
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
        pxlSpring.exportZip()
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
        pxlSpring.exportZip()
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
        assertThatThrownBy(() -> pxlSpring.exportZip()
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
                pxlSpring.exportZip().toStream(new ByteArrayOutputStream()))
                .isInstanceOf(PxlArgumentException.class);
    }

    @Test
    void nullWorkbookObject_throwsPxlNullPointer() {
        assertThatThrownBy(() -> pxlSpring.exportZip().workbook(null))
                .isInstanceOf(PxlNullPointerException.class);
    }

    @Test
    void xlsxEntriesAreNotRecompressed_whileXlsEntriesStillAre() throws PxlException, IOException {
        final ByteArrayOutputStream baos = new ByteArrayOutputStream();
        pxlSpring.exportZip()
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
    void aDotInTheEntryBaseName_doesNotFoolTheDeflateLevel() throws PxlException, IOException {
        // The level is read back off the finished entry name, so the caller's own base name now reaches the
        // extension parser - it did not while the level came straight from the format. A base name carrying
        // dots ("2026.01") is ordinary, and everything after the last one is what decides here, so both
        // entries must be treated exactly as the plain-named ones above.
        final ByteArrayOutputStream baos = new ByteArrayOutputStream();
        pxlSpring.exportZip()
                .workbook(workbook("ignored"), null, "2026.01")                          // -> 2026.01.xlsx
                .workbook(new TestHssfWorkbook("ignored", users()), null, "2026.02")     // -> 2026.02.xls
                .toStream(baos);

        assertThat(centralDirectoryEntryNames(baos.toByteArray()))
                .containsExactly("2026.01.xlsx", "2026.02.xls");

        final File tmp = writeTempZip(baos.toByteArray());
        try (ZipFile zipFile = new ZipFile(tmp)) {
            final ZipEntry xlsx = zipFile.getEntry("2026.01.xlsx");
            assertThat(xlsx.getCompressedSize()).isGreaterThan(xlsx.getSize());

            final ZipEntry xls = zipFile.getEntry("2026.02.xls");
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
    void everyEntryKindInOneArchive_streamsAsAValidArchive() throws PxlException, IOException {
        // The Dest matrix cannot reach this terminal - asserting on headers needs a live response - so every
        // streaming test above hands it workbook(...) entries and nothing else. It is the one destination
        // that writes into the servlet stream rather than into a buffer or a file, and a CSV entry reaches it
        // through the core's own spill-to-temp-file rendering, so the full mix belongs here too.
        final MockHttpServletResponse streamed = new MockHttpServletResponse();

        try (Workbook raw = oneCell(new XSSFWorkbook())) {
            pxlSpring.exportZip()
                    .workbook(workbook("bound"))
                    .poiWorkbook(raw, null, "raw")
                    .sampleWorkbook(TestWorkbook.class, null, "template")
                    .csvSheet(TestUser.class, users(), "Users")
                    .sampleCsvSheet(TestUser.class, "Forms")
                    .toResponseStreaming(streamed, "archive");
        }

        final byte[] onTheWire = streamed.getContentAsByteArray();
        assertThat(centralDirectoryEntryNames(onTheWire))
                .containsExactly("bound.xlsx", "raw.xlsx", "template.xlsx", "Users.csv", "Forms.csv");
        // not merely named: the bodies survive the unbuffered path as well
        assertThat(linesOf(entryBytes(onTheWire, "Users.csv")))
                .containsExactly("Name,Age", "Alice,30", "Bob,25", "");
        assertThat(streamed.getHeader(HttpHeaders.CONTENT_LENGTH)).isNull();
    }

    @Test
    void pathCarryingEntryName_isRejectedBeforeHeadersGoOut() {
        // The path check moved into validateEntries, which every terminal calls first, so it now runs before
        // the headers on the streaming path too - the response is left untouched on either path. It used to
        // sit inside the write loop, where a rejected name still wrote no body but left the download headers
        // committed; that window is gone for every check this builder makes itself.
        final MockHttpServletResponse streamed = new MockHttpServletResponse();

        assertThatThrownBy(() -> pxlSpring.exportZip()
                .workbook(workbook("first"), null, "sub/report")
                .toResponseStreaming(streamed, "archive"))
                .isInstanceOf(PxlArgumentException.class);

        assertThat(streamed.getContentAsByteArray()).isEmpty();
        assertThat(streamed.getHeader(HttpHeaders.CONTENT_DISPOSITION)).isNull();

        final MockHttpServletResponse buffered = new MockHttpServletResponse();

        assertThatThrownBy(() -> pxlSpring.exportZip()
                .workbook(workbook("first"), null, "sub/report")
                .toResponse(buffered, "archive"))
                .isInstanceOf(PxlArgumentException.class);

        assertThat(buffered.getContentAsByteArray()).isEmpty();
        assertThat(buffered.getHeader(HttpHeaders.CONTENT_DISPOSITION)).isNull();
    }

    @Test
    void pathCarryingEntryName_isRejectedBeforeTheFileIsCreated() {
        // the other half of the same move: the file destination fails before opening its FileOutputStream, so
        // nothing is left on disk at all. It used to leave an unreadable half-archive behind.
        final File zipFile = TestPaths.exportFile(testInfo, ".zip");

        assertThatThrownBy(() -> pxlSpring.exportZip()
                .workbook(workbook("first"), null, "sub/report")
                .toFile(zipFile))
                .isInstanceOf(PxlArgumentException.class);

        assertThat(zipFile).doesNotExist();
    }

    @Test
    void streamingStillCommitsHeadersBeforeACoreLevelFailure() throws IOException {
        // What the move does not buy back: the streaming window is still real, because generating an entry
        // happens after the headers have gone out and only the core can tell that it will fail. This is the
        // documented cost of the terminal, and it must stay observable - if it ever stops being, the
        // toResponseStreaming javadoc is the thing to fix, not this test.
        //
        // Note what does reach the client. The builder's own checks failed before putNextEntry, so they left
        // nothing at all; a core-level failure does not, because putNextEntry writes the entry's local file
        // header before writeBody is even called. So the wire carries a started entry and stops. What it can
        // never carry is a readable archive: writeArchive finishes only once every entry is in, so there is
        // no central directory and the download fails to open. Were it finished on the way out instead, the
        // client would get a well-formed archive holding nothing.
        final MockHttpServletResponse streamed = new MockHttpServletResponse();

        // TestBadNameWorkbook fails while the core resolves its export metadata, i.e. inside writeBody
        assertThatThrownBy(() -> pxlSpring.exportZip()
                .workbook(new TestBadNameWorkbook(1), null, "a")
                .toResponseStreaming(streamed, "archive"))
                .isInstanceOf(PxlException.class);

        assertThat(streamed.getHeader(HttpHeaders.CONTENT_DISPOSITION)).isNotNull();

        final byte[] onTheWire = streamed.getContentAsByteArray();
        assertThat(onTheWire).as("the entry's local file header was already written").isNotEmpty();
        assertThatThrownBy(() -> centralDirectoryEntryNames(onTheWire))
                .as("but it cannot be opened as an archive")
                .isInstanceOf(IOException.class);
    }

    @Test
    void streamingStillRejectsAnEmptyArchiveBeforeTouchingTheResponse() {
        // validateEntries and resolveZipFilename are pxl-spring's own up-front guards: they run before the
        // headers on either path, so these failures do leave the response untouched
        final MockHttpServletResponse response = new MockHttpServletResponse();

        assertThatThrownBy(() -> pxlSpring.exportZip()
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

        // the streaming terminal resolves the name before it sets a single header, so this failure leaves the
        // response untouched like the builder's other up-front checks - the one thing that separates it from
        // a core-level failure on the same terminal
        final MockHttpServletResponse streamed = new MockHttpServletResponse();

        assertThatThrownBy(() -> named().toResponseStreaming(streamed, "  "))
                .isInstanceOf(PxlArgumentException.class);

        assertThat(streamed.getHeader(HttpHeaders.CONTENT_DISPOSITION)).isNull();
        assertThat(streamed.getContentAsByteArray()).isEmpty();
    }

    @Test
    void entryNameCarryingAPath_throwsPxlArgument() {
        // a ZipEntry name may legally hold a path, so an unchecked separator would put a traversal path
        // inside an archive we hand out - Zip Slip for whoever extracts it
        assertThatThrownBy(() -> pxlSpring.exportZip()
                .workbook(workbook("first"), null, "../../evil")
                .toStream(new ByteArrayOutputStream()))
                .isInstanceOf(PxlArgumentException.class);

        // '\' is a literal character in a ZIP name rather than a separator, so it does not traverse, but it
        // produces a name that extractors disagree about; FilenameUtils.getName rejects both alike
        assertThatThrownBy(() -> pxlSpring.exportZip()
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
        //
        // The trigger has to be a failure the builder cannot see coming, now that both of its own entry-name
        // checks run before the file is opened: TestBadNameWorkbook fails while the core resolves its export
        // metadata, which is inside writeBody and therefore inside the open archive.
        final File zipFile = TestPaths.exportFile(testInfo, ".zip");

        assertThatThrownBy(() -> pxlSpring.exportZip()
                .workbook(new TestBadNameWorkbook(1), null, "a")
                .toFile(zipFile))
                .isInstanceOf(PxlException.class);

        assertThat(zipFile).exists();
        assertThatThrownBy(() -> centralDirectoryEntryNames(Files.readAllBytes(zipFile.toPath())))
                .isInstanceOf(IOException.class);
    }

    @Test
    void pathCarryingWorkbookName_isRejectedToo() {
        // the guard has to cover the fallback source as well: a workbook name is data the application filled
        // in, not an argument the caller wrote at the call site
        assertThatThrownBy(() -> pxlSpring.exportZip()
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
        assertThatThrownBy(() -> pxlSpring.exportZip()
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
        assertThatThrownBy(() -> pxlSpring.exportZip()
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
        assertThatThrownBy(() -> pxlSpring.exportZip()
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

            assertThatThrownBy(() -> pxlSpring.exportZip()
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
        // FileOutputStream, so no unreadable leftover is on disk at all. The path check now sits beside it
        // and buys the same - see pathCarryingEntryName_isRejectedBeforeTheFileIsCreated. What still leaves
        // a leftover is a core-level failure, which nothing up front can foresee
        // (failedExportToFile_leavesNothingOpenableAsAnArchive).
        final File zipFile = TestPaths.exportFile(testInfo, ".zip");

        assertThatThrownBy(() -> pxlSpring.exportZip()
                .workbook(workbook("same"))
                .workbook(workbook("same"))
                .toFile(zipFile))
                .isInstanceOf(PxlArgumentException.class);

        assertThat(zipFile).doesNotExist();
    }

    @Test
    void duplicateEntryNames_areRejectedBeforeHeadersGoOut() {
        // The other half of the same win: on the streaming path validateEntries runs before
        // setResponseForExportZip, so this failure leaves no download headers behind. Every check this
        // builder makes itself is now on that side of the line; what is not is a core-level failure
        // (streamingStillCommitsHeadersBeforeACoreLevelFailure).
        final MockHttpServletResponse response = new MockHttpServletResponse();

        assertThatThrownBy(() -> pxlSpring.exportZip()
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
        pxlSpring.exportZip()
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
        pxlSpring.exportZip()
                .workbook(workbook("report"))                            // -> report.xlsx
                .workbook(new TestHssfWorkbook("report", users()))       // -> report.xls
                .toStream(baos);

        assertThat(centralDirectoryEntryNames(baos.toByteArray()))
                .containsExactly("report.xlsx", "report.xls");
    }

    // ----- entry-kind x destination matrix -----
    // Every overload of every kind has to be reachable from every terminal, so each one gets its own sweep -
    // the sweeps for the four later kinds sit in their own sections below and share this enum. Only
    // toResponseStreaming is missing from it: it needs a live response to assert headers on, so it is
    // exercised by the dedicated tests above instead.

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
    private byte[] emit(final PxlZipExporter.Builder builder, final Dest dest) throws PxlException, IOException {
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
        final byte[] bytes = emit(pxlSpring.exportZip()
                .workbook(workbook("first"))
                .workbook(workbook("second")), dest);

        assertThat(centralDirectoryEntryNames(bytes)).containsExactly("first.xlsx", "second.xlsx");
    }

    @ParameterizedTest
    @EnumSource(Dest.class)
    void workbookWithOption_namesEntriesOnEveryDestination(final Dest dest) throws PxlException, IOException {
        // the two-argument workbook(object, option) overload had no coverage at all. The HSSF option names
        // its entry .xls on every destination alike - the extension is resolved once, before any of them.
        final byte[] bytes = emit(pxlSpring.exportZip()
                .workbook(workbook("first"), hssfOption())
                .workbook(workbook("second"), null), dest);

        assertThat(centralDirectoryEntryNames(bytes)).containsExactly("first.xls", "second.xlsx");
    }

    @ParameterizedTest
    @EnumSource(Dest.class)
    void workbookWithOptionAndName_namesEntriesOnEveryDestination(final Dest dest) throws PxlException, IOException {
        final byte[] bytes = emit(pxlSpring.exportZip()
                .workbook(workbook("first"), null, "a")
                .workbook(workbook("second"), null, "b"), dest);

        assertThat(centralDirectoryEntryNames(bytes)).containsExactly("a.xlsx", "b.xlsx");
    }

    @ParameterizedTest
    @EnumSource(Dest.class)
    void singleEntryArchive_isValidOnEveryDestination(final Dest dest) throws PxlException, IOException {
        final byte[] bytes = emit(pxlSpring.exportZip().workbook(workbook("only")), dest);

        assertThat(centralDirectoryEntryNames(bytes)).containsExactly("only.xlsx");
    }

    @ParameterizedTest
    @EnumSource(Dest.class)
    void everyEntryKindInOneArchive_isValidOnEveryDestination(final Dest dest) throws PxlException, IOException {
        // Each kind has its own sweep above, and eachEntryKindNamesItselfAfterTheBytesItWrites mixes all five
        // - but only into a stream. This is the case the component exists for, so it gets the full matrix: an
        // archive of every kind at once has to open through its central directory on every destination, with
        // the names in call order.
        try (Workbook raw = oneCell(new XSSFWorkbook())) {
            final byte[] bytes = emit(pxlSpring.exportZip()
                    .workbook(workbook("bound"))
                    .poiWorkbook(raw, null, "raw")
                    .sampleWorkbook(TestWorkbook.class, null, "template")
                    .csvSheet(TestUser.class, users(), "Users")
                    .sampleCsvSheet(TestUser.class, "Forms"), dest);

            assertThat(centralDirectoryEntryNames(bytes))
                    .containsExactly("bound.xlsx", "raw.xlsx", "template.xlsx", "Users.csv", "Forms.csv");
        }
    }

    // ----- per-entry export options -----

    @Test
    void perEntryOption_drivesTheEntryExtensionAsWellAsItsBody() throws PxlException, IOException {
        // The option decides the bytes, so it decides the extension - it is asked before the workbook class's
        // declared engine, the same priority PxlExcelExporter.resolveFileFormat() uses. It used to be asked
        // second, which put OLE2 bytes under a .xlsx entry name.
        final ByteArrayOutputStream baos = new ByteArrayOutputStream();
        pxlSpring.exportZip()
                .workbook(workbook("hssf"), hssfOption())
                .workbook(workbook("xssf"))
                .toStream(baos);

        assertThat(centralDirectoryEntryNames(baos.toByteArray())).containsExactly("hssf.xls", "xssf.xlsx");
        assertThat(isXlsx(entryBytes(baos.toByteArray(), "hssf.xls"))).isFalse();
        assertThat(isXlsx(entryBytes(baos.toByteArray(), "xssf.xlsx"))).isTrue();
    }

    @Test
    void perEntryOption_overridesADeclaredEngineInEitherDirection() throws PxlException, IOException {
        // the other way round: a class declaring HSSF, turned back into XLSX by its entry's option
        final ByteArrayOutputStream baos = new ByteArrayOutputStream();
        pxlSpring.exportZip()
                .workbook(new TestHssfWorkbook("declared-hssf", users()), xssfOption())
                .toStream(baos);

        assertThat(centralDirectoryEntryNames(baos.toByteArray())).containsExactly("declared-hssf.xlsx");
        assertThat(isXlsx(entryBytes(baos.toByteArray(), "declared-hssf.xlsx"))).isTrue();
    }

    @Test
    void anOptionCarryingNoEngine_leavesTheExtensionToTheClass() throws PxlException, IOException {
        // only an exportExcelEngine takes over; an option without one falls through to the class declaration,
        // which is what keeps the null branch of resolveFileFormat honest
        final ByteArrayOutputStream baos = new ByteArrayOutputStream();
        pxlSpring.exportZip()
                .workbook(workbook("xssf-class"), PxlExportWorkbookOption.builder().build())
                .workbook(new TestHssfWorkbook("hssf-class", users()), PxlExportWorkbookOption.builder().build())
                .toStream(baos);

        assertThat(centralDirectoryEntryNames(baos.toByteArray()))
                .containsExactly("xssf-class.xlsx", "hssf-class.xls");
    }

    @Test
    void anOptionDrivenXlsEntry_isCompressedAsTheOle2ItActuallyIs() throws PxlException, IOException {
        // deflateLevelFor reads the same resolved format, so the compression follows the bytes that are
        // written. While the extension came from the class, this entry was OLE2 stored at NO_COMPRESSION -
        // uncompressed data written uncompressed, the worst of both.
        final ByteArrayOutputStream baos = new ByteArrayOutputStream();
        pxlSpring.exportZip()
                .workbook(workbook("ole2"), hssfOption())
                .toStream(baos);

        final File tmp = writeTempZip(baos.toByteArray());
        try (ZipFile zipFile = new ZipFile(tmp)) {
            final ZipEntry xls = zipFile.getEntry("ole2.xls");
            assertThat(xls.getCompressedSize()).isLessThan(xls.getSize());
        } finally {
            tmp.delete();
        }
    }

    @Test
    void theOptionDrivenExtension_feedsTheDuplicateCheck() throws PxlException, IOException {
        // the duplicate check compares whole entry names, so whichever extension the option resolves to is
        // part of that comparison. One base name under two engines is two members...
        final ByteArrayOutputStream baos = new ByteArrayOutputStream();
        pxlSpring.exportZip()
                .workbook(workbook("report"))                   // -> report.xlsx
                .workbook(workbook("report"), hssfOption())     // -> report.xls
                .toStream(baos);

        assertThat(centralDirectoryEntryNames(baos.toByteArray()))
                .containsExactly("report.xlsx", "report.xls");

        // ...and two classes that declare different engines collide once an option lines their formats up
        assertThatThrownBy(() -> pxlSpring.exportZip()
                .workbook(workbook("report"))                                        // -> report.xlsx
                .workbook(new TestHssfWorkbook("report", users()), xssfOption())     // -> report.xlsx too
                .toStream(new ByteArrayOutputStream()))
                .isInstanceOf(PxlArgumentException.class);
    }

    @Test
    void workbookArities_areWrittenInCallOrder() throws PxlException, IOException {
        // one kind, three ways of adding it: an explicit name does not move an entry, it only renames it.
        // mixedEntryKinds_areWrittenInCallOrder is the same assertion across all five kinds.
        final ByteArrayOutputStream baos = new ByteArrayOutputStream();
        pxlSpring.exportZip()
                .workbook(workbook("one"))
                .workbook(workbook("two"))
                .workbook(workbook("ignored"), null, "three")
                .toStream(baos);

        assertThat(centralDirectoryEntryNames(baos.toByteArray()))
                .containsExactly("one.xlsx", "two.xlsx", "three.xlsx");
    }

    @Test
    void koreanEntryAndArchiveNames_areCarriedThrough() throws PxlException, IOException {
        final ResponseEntity<Resource> entity = pxlSpring.exportZip()
                .workbook(workbook("무시됨"), null, "사용자")
                .toResponseEntity("보고서모음");

        final String contentDisposition = entity.getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION);
        assertThat(contentDisposition).startsWith("attachment; filename=\"_____.zip\"; filename*=UTF-8''");
        assertThat(contentDisposition).doesNotContain("보고서모음").contains("%");
        // the ZIP entry name is not a header, so it keeps its Korean characters
        assertThat(centralDirectoryEntryNames(bodyBytes(entity))).containsExactly("사용자.xlsx");
    }

    // ----- raw POI workbook entries -----
    // The second kind of source: a workbook the application already built, written into the archive as-is.
    // It goes nowhere near the binding layer, so it carries no export option and has no workbook name to fall
    // back to - the two ways it differs from a @PxlWorkbook entry.

    /**
     * Puts one cell in the given workbook so an entry made from it holds real bytes, and hands it back.
     */
    private static <W extends Workbook> W oneCell(final W workbook) {
        return oneCell(workbook, "hi");
    }

    /**
     * As {@link #oneCell(Workbook)}, with the cell value given - so a round-trip can tell one workbook's
     * bytes from another's.
     */
    private static <W extends Workbook> W oneCell(final W workbook, final String value) {
        workbook.createSheet("S").createRow(0).createCell(0).setCellValue(value);
        return workbook;
    }

    /**
     * Reopens Excel bytes taken out of an archive and returns the one cell {@link #oneCell} put there,
     * decrypting with {@code password} where one was used. Reading the value back is what proves the entry
     * body arrived whole rather than merely starting with the right magic bytes.
     */
    private static String firstCellOf(final byte[] excelBytes, final String password)
            throws PxlException, IOException {
        try (Workbook workbook = PxlWorkbookUtils.openWorkbook(new ByteArrayInputStream(excelBytes), password)) {
            return workbook.getSheetAt(0).getRow(0).getCell(0).getStringCellValue();
        }
    }

    // The three arities, each swept across every destination - the same matrix the workbook(...) forms get.
    // Without it the one- and two-argument overloads would only ever be reached by a single stream test.

    @ParameterizedTest
    @EnumSource(Dest.class)
    void poiWorkbookWithoutPassword_namesEntriesOnEveryDestination(final Dest dest) throws PxlException, IOException {
        // no name given and none to fall back to: the workbook-name step reads the @PxlWorkbookName field off
        // an annotated instance, and a raw POI workbook is not one. The index is always appended, so unnamed
        // entries of this kind stay distinct from one another.
        try (Workbook xssf = oneCell(new XSSFWorkbook()); Workbook hssf = oneCell(new HSSFWorkbook())) {
            final byte[] bytes = emit(pxlSpring.exportZip()
                    .poiWorkbook(xssf)
                    .poiWorkbook(hssf), dest);

            assertThat(centralDirectoryEntryNames(bytes)).containsExactly("Pxl0.xlsx", "Pxl1.xls");
        }
    }

    @ParameterizedTest
    @EnumSource(Dest.class)
    void poiWorkbookWithPassword_namesEntriesOnEveryDestination(final Dest dest) throws PxlException, IOException {
        // the two-argument overload, and the only place a password reaches the destination matrix: an
        // encrypted entry is named exactly as an unencrypted one of the same workbook type
        try (Workbook xssf = oneCell(new XSSFWorkbook()); Workbook hssf = oneCell(new HSSFWorkbook())) {
            final byte[] bytes = emit(pxlSpring.exportZip()
                    .poiWorkbook(xssf, "secret")
                    .poiWorkbook(hssf, null), dest);

            assertThat(centralDirectoryEntryNames(bytes)).containsExactly("Pxl0.xlsx", "Pxl1.xls");
        }
    }

    @ParameterizedTest
    @EnumSource(Dest.class)
    void poiWorkbookWithPasswordAndName_namesEntriesOnEveryDestination(final Dest dest) throws PxlException, IOException {
        try (Workbook xssf = oneCell(new XSSFWorkbook()); Workbook hssf = oneCell(new HSSFWorkbook())) {
            final byte[] bytes = emit(pxlSpring.exportZip()
                    .poiWorkbook(xssf, null, "ooxml")
                    .poiWorkbook(hssf, null, "ole2"), dest);

            assertThat(centralDirectoryEntryNames(bytes)).containsExactly("ooxml.xlsx", "ole2.xls");
        }
    }

    @Test
    void blankEntryNameOnARawPoiWorkbook_fallsBackToPxlIndex() throws PxlException, IOException {
        // a blank name is treated as absent, exactly as on a workbook(...) entry - only here there is no
        // workbook name in between, so it goes straight to the index
        try (Workbook xssf = oneCell(new XSSFWorkbook())) {
            final ByteArrayOutputStream baos = new ByteArrayOutputStream();
            pxlSpring.exportZip()
                    .poiWorkbook(xssf, null, "  ")
                    .toStream(baos);

            assertThat(centralDirectoryEntryNames(baos.toByteArray())).containsExactly("Pxl0.xlsx");
        }
    }

    @Test
    void poiWorkbookEntryBytes_reopenAsTheWorkbooksThatWentIn() throws PxlException, IOException {
        // The counterpart of zipEntryContent_isReadableXlsx_andRoundTrips for this kind, and the check that
        // costs the least to get wrong: PxlWorkbookUtils writes straight into the open archive stream, so a
        // body that were truncated - or an entry that closed the stream out from under the next one - would
        // still start with the right magic bytes. Distinct cell values also pin each entry to its own
        // workbook rather than to whichever one was written last.
        try (Workbook plain = oneCell(new XSSFWorkbook(), "plain");
             Workbook locked = oneCell(new XSSFWorkbook(), "locked");
             Workbook ole2 = oneCell(new HSSFWorkbook(), "ole2")) {

            final ByteArrayOutputStream baos = new ByteArrayOutputStream();
            pxlSpring.exportZip()
                    .poiWorkbook(plain, null, "plain")
                    .poiWorkbook(locked, "secret", "locked")
                    .poiWorkbook(ole2, null, "ole2")
                    .toStream(baos);

            final byte[] archive = baos.toByteArray();
            assertThat(firstCellOf(entryBytes(archive, "plain.xlsx"), null)).isEqualTo("plain");
            assertThat(firstCellOf(entryBytes(archive, "locked.xlsx"), "secret")).isEqualTo("locked");
            assertThat(firstCellOf(entryBytes(archive, "ole2.xls"), null)).isEqualTo("ole2");
        }
    }

    @Test
    void poiWorkbookEntrySxssf_isNamedXlsxLikeItsXssfBase() throws PxlException, IOException {
        // SXSSFWorkbook wraps rather than extends XSSFWorkbook, so it is its own PxlExcelEngine - but both
        // write the same OOXML container, one PxlFileFormat, and the entry is named off the format
        try (SXSSFWorkbook sxssf = oneCell(new SXSSFWorkbook())) {
            final ByteArrayOutputStream baos = new ByteArrayOutputStream();
            pxlSpring.exportZip()
                    .poiWorkbook(sxssf, null, "streamed")
                    .toStream(baos);

            assertThat(centralDirectoryEntryNames(baos.toByteArray())).containsExactly("streamed.xlsx");
            assertThat(isXlsx(entryBytes(baos.toByteArray(), "streamed.xlsx"))).isTrue();
        }
    }

    @Test
    void poiWorkbookEntryWithPassword_keepsTheWorkbookTypeInTheEntryName() throws PxlException, IOException {
        // Paired with PxlExcelExporterTests.poiWorkbookPassword_keepsTheWorkbookTypeInTheHeaders: encryption
        // wraps the bytes in an OLE2 container whatever the workbook type, but the name still follows the
        // workbook - which is how an encrypted OOXML file is normally distributed. The entry kind inherits
        // that property rather than deciding its own.
        try (Workbook xssf = oneCell(new XSSFWorkbook())) {
            final ByteArrayOutputStream baos = new ByteArrayOutputStream();
            pxlSpring.exportZip()
                    .poiWorkbook(xssf, "secret", "locked")
                    .toStream(baos);

            final byte[] archive = baos.toByteArray();
            assertThat(centralDirectoryEntryNames(archive)).containsExactly("locked.xlsx");
            assertThat(isXlsx(entryBytes(archive, "locked.xlsx"))).isFalse();
            assertThat(entryBytes(archive, "locked.xlsx")).isNotEmpty();
        }
    }

    @Test
    void nullPoiWorkbook_throwsPxlNullPointer() {
        // every arity guards the workbook, not just the one the others delegate to - the delegation is an
        // implementation detail a later refactor could undo
        assertThatThrownBy(() -> pxlSpring.exportZip().poiWorkbook(null))
                .isInstanceOf(PxlNullPointerException.class);

        assertThatThrownBy(() -> pxlSpring.exportZip().poiWorkbook(null, "secret"))
                .isInstanceOf(PxlNullPointerException.class);

        assertThatThrownBy(() -> pxlSpring.exportZip().poiWorkbook(null, "secret", "name"))
                .isInstanceOf(PxlNullPointerException.class);
    }

    // ----- sample template entries -----
    // The third kind: a template generated from a class rather than bytes bound from an instance. It takes a
    // per-entry option like a workbook(...) entry, but names itself like neither - there is no instance to
    // read a workbook name off, so it falls to PxlSample{index}.

    /**
     * Every string cell in the given Excel bytes, so a template's header row and its sample row can be
     * checked at once - the same shape {@code PxlSampleExcelExporterTests} uses.
     */
    private static Set<String> stringCellsOf(final byte[] excelBytes) throws PxlException, IOException {
        final Set<String> values = new HashSet<>();
        try (Workbook workbook = PxlWorkbookUtils.openWorkbook(new ByteArrayInputStream(excelBytes), null)) {
            for (final Sheet sheet : workbook) {
                for (final Row row : sheet) {
                    for (final Cell cell : row) {
                        if (cell.getCellType() == CellType.STRING) {
                            values.add(cell.getStringCellValue());
                        }
                    }
                }
            }
        }
        return values;
    }

    @ParameterizedTest
    @EnumSource(Dest.class)
    void sampleWorkbookWithoutOption_namesEntriesOnEveryDestination(final Dest dest) throws PxlException, IOException {
        // no name given: straight to PxlSample{index}, since a class carries no workbook name. The extension
        // still follows what the class declares, so the HSSF one comes out .xls.
        final byte[] bytes = emit(pxlSpring.exportZip()
                .sampleWorkbook(TestWorkbook.class)
                .sampleWorkbook(TestHssfWorkbook.class), dest);

        assertThat(centralDirectoryEntryNames(bytes)).containsExactly("PxlSample0.xlsx", "PxlSample1.xls");
    }

    @ParameterizedTest
    @EnumSource(Dest.class)
    void sampleWorkbookWithOption_namesEntriesOnEveryDestination(final Dest dest) throws PxlException, IOException {
        // the option decides the bytes, so it decides the extension - asked before the class, exactly as on a
        // workbook(...) entry
        final byte[] bytes = emit(pxlSpring.exportZip()
                .sampleWorkbook(TestWorkbook.class, hssfOption())
                .sampleWorkbook(TestWorkbook.class, null), dest);

        assertThat(centralDirectoryEntryNames(bytes)).containsExactly("PxlSample0.xls", "PxlSample1.xlsx");
    }

    @ParameterizedTest
    @EnumSource(Dest.class)
    void sampleWorkbookWithOptionAndName_namesEntriesOnEveryDestination(final Dest dest) throws PxlException, IOException {
        final byte[] bytes = emit(pxlSpring.exportZip()
                .sampleWorkbook(TestWorkbook.class, null, "form-a")
                .sampleWorkbook(TestWorkbook.class, null, "form-b"), dest);

        assertThat(centralDirectoryEntryNames(bytes)).containsExactly("form-a.xlsx", "form-b.xlsx");
    }

    @Test
    void blankEntryNameOnASampleWorkbook_fallsBackToPxlSampleIndex() throws PxlException, IOException {
        final ByteArrayOutputStream baos = new ByteArrayOutputStream();
        pxlSpring.exportZip()
                .sampleWorkbook(TestWorkbook.class, null, "  ")
                .toStream(baos);

        assertThat(centralDirectoryEntryNames(baos.toByteArray())).containsExactly("PxlSample0.xlsx");
    }

    @Test
    void sampleEntryDefault_isPxlSampleIndexRatherThanPxlIndex() throws PxlException, IOException {
        // Two things at once. The prefixes differ, so an unnamed template is not mistaken for an unnamed
        // export; and the index is appended to both, which is what keeps unnamed entries of either kind
        // distinct - the reason this default carries one where PxlSampleExcelExporter's bare "PxlSample"
        // does not.
        final ByteArrayOutputStream baos = new ByteArrayOutputStream();
        pxlSpring.exportZip()
                .workbook(workbook(null))
                .sampleWorkbook(TestWorkbook.class)
                .sampleWorkbook(TestWorkbook.class)
                .toStream(baos);

        assertThat(centralDirectoryEntryNames(baos.toByteArray()))
                .containsExactly("Pxl0.xlsx", "PxlSample1.xlsx", "PxlSample2.xlsx");
    }

    @Test
    void sampleWorkbookEntryBytes_carryTheHeaderAndTheSampleRow() throws PxlException, IOException {
        // the body is a real template, not an empty workbook: PxlSampleExcelExporter's own promise, inherited
        // here because the entry generates itself through the same core builder
        final ByteArrayOutputStream baos = new ByteArrayOutputStream();
        pxlSpring.exportZip()
                .sampleWorkbook(TestWorkbook.class, null, "form")
                .toStream(baos);

        assertThat(stringCellsOf(entryBytes(baos.toByteArray(), "form.xlsx")))
                .contains("Name", "Age", "Alice");
    }

    @Test
    void sampleWorkbookOption_overridesADeclaredEngineInEitherDirection() throws PxlException, IOException {
        // the direction sampleWorkbookWithOption_... does not take: a class declaring HSSF, turned back into
        // XLSX by its entry's option. Both branches of resolveFileFormat's option check have to work, or the
        // priority is only half right.
        final ByteArrayOutputStream baos = new ByteArrayOutputStream();
        pxlSpring.exportZip()
                .sampleWorkbook(TestHssfWorkbook.class, xssfOption(), "declared-hssf")
                .toStream(baos);

        assertThat(centralDirectoryEntryNames(baos.toByteArray())).containsExactly("declared-hssf.xlsx");
        assertThat(isXlsx(entryBytes(baos.toByteArray(), "declared-hssf.xlsx"))).isTrue();
    }

    @Test
    void aSampleOptionCarryingNoEngine_leavesTheExtensionToTheClass() throws PxlException, IOException {
        // Only an exportExcelEngine takes over. Passing null for the whole option skips the inner null check
        // entirely, so an option that exists but carries no engine is the case that keeps it honest - the
        // same gap anOptionCarryingNoEngine_leavesTheExtensionToTheClass closes for workbook(...).
        final ByteArrayOutputStream baos = new ByteArrayOutputStream();
        pxlSpring.exportZip()
                .sampleWorkbook(TestWorkbook.class, PxlExportWorkbookOption.builder().build())
                .sampleWorkbook(TestHssfWorkbook.class, PxlExportWorkbookOption.builder().build())
                .toStream(baos);

        assertThat(centralDirectoryEntryNames(baos.toByteArray()))
                .containsExactly("PxlSample0.xlsx", "PxlSample1.xls");
    }

    @Test
    void theSampleOptionDrivenExtension_feedsTheDuplicateCheck() throws PxlException, IOException {
        // the duplicate check compares whole names, so whichever extension a sample entry's option resolves
        // to is part of the comparison. One base name under two engines is two members...
        final ByteArrayOutputStream baos = new ByteArrayOutputStream();
        pxlSpring.exportZip()
                .sampleWorkbook(TestWorkbook.class, null, "form")           // -> form.xlsx
                .sampleWorkbook(TestWorkbook.class, hssfOption(), "form")   // -> form.xls
                .toStream(baos);

        assertThat(centralDirectoryEntryNames(baos.toByteArray())).containsExactly("form.xlsx", "form.xls");

        // ...and two classes declaring different engines collide once an option lines their formats up
        assertThatThrownBy(() -> pxlSpring.exportZip()
                .sampleWorkbook(TestWorkbook.class, null, "form")                    // -> form.xlsx
                .sampleWorkbook(TestHssfWorkbook.class, xssfOption(), "form")        // -> form.xlsx too
                .toStream(new ByteArrayOutputStream()))
                .isInstanceOf(PxlArgumentException.class);
    }

    @Test
    void sampleWorkbookEntries_eachCarryTheirOwnClassTemplate() throws PxlException, IOException {
        // writeBody opens a fresh core builder per entry, so nothing carries over between them. Shared, the
        // second entry would inherit the first one's sheets - which the sheet names make visible in a way the
        // extension never could, since both classes resolve to XLSX.
        final ByteArrayOutputStream baos = new ByteArrayOutputStream();
        pxlSpring.exportZip()
                .sampleWorkbook(TestWorkbook.class, null, "one-sheet")
                .sampleWorkbook(TestMultiSheetWorkbook.class, null, "two-sheets")
                .toStream(baos);

        final byte[] archive = baos.toByteArray();
        assertThat(sheetNames(entryBytes(archive, "one-sheet.xlsx"))).containsExactly("Users");
        assertThat(sheetNames(entryBytes(archive, "two-sheets.xlsx"))).containsExactly("Users", "Admins");
    }

    @Test
    void nullSampleWorkbookClass_throwsPxlNullPointer() {
        assertThatThrownBy(() -> pxlSpring.exportZip().sampleWorkbook(null))
                .isInstanceOf(PxlNullPointerException.class);

        assertThatThrownBy(() -> pxlSpring.exportZip().sampleWorkbook(null, hssfOption()))
                .isInstanceOf(PxlNullPointerException.class);

        assertThatThrownBy(() -> pxlSpring.exportZip().sampleWorkbook(null, hssfOption(), "name"))
                .isInstanceOf(PxlNullPointerException.class);
    }

    // ----- CSV entries -----
    // The two kinds whose bytes are not a workbook at all. They have no format to resolve - CSV is the only
    // thing they write - and no index-suffixed default either, because a sheet name is required and so always
    // there to fall back on.

    /**
     * The entry's records, split on the CRLF the CSV writer emits - the same helper
     * {@code PxlCsvExporterTests} uses. Only for record-level assertions; anything encoding-sensitive is
     * asserted on the raw bytes instead, since decoding absorbs a byte order mark into U+FEFF.
     */
    private static List<String> linesOf(final byte[] csvBytes) {
        return Arrays.asList(new String(csvBytes, StandardCharsets.UTF_8).split("\r\n", -1));
    }

    @ParameterizedTest
    @EnumSource(Dest.class)
    void csvSheetWithoutOption_namesEntriesOnEveryDestination(final Dest dest) throws PxlException, IOException {
        // no entry name given: the sheet name is the fallback, and there is no index-suffixed default behind
        // it because a CSV entry cannot be added without one
        final byte[] bytes = emit(pxlSpring.exportZip()
                .csvSheet(TestUser.class, users(), "Users")
                .csvSheet(TestUser.class, users(), "Admins"), dest);

        assertThat(centralDirectoryEntryNames(bytes)).containsExactly("Users.csv", "Admins.csv");
    }

    @ParameterizedTest
    @EnumSource(Dest.class)
    void csvSheetWithOption_namesEntriesOnEveryDestination(final Dest dest) throws PxlException, IOException {
        // an option changes the bytes here, never the extension - CSV is all this kind writes, so even an
        // exportExcelEngine in it leaves the name alone
        final byte[] bytes = emit(pxlSpring.exportZip()
                .csvSheet(TestUser.class, users(), "Users", semicolonOption())
                .csvSheet(TestUser.class, users(), "Admins", hssfOption()), dest);

        assertThat(centralDirectoryEntryNames(bytes)).containsExactly("Users.csv", "Admins.csv");
    }

    @ParameterizedTest
    @EnumSource(Dest.class)
    void csvSheetWithOptionAndName_namesEntriesOnEveryDestination(final Dest dest) throws PxlException, IOException {
        final byte[] bytes = emit(pxlSpring.exportZip()
                .csvSheet(TestUser.class, users(), "Users", null, "a")
                .csvSheet(TestUser.class, users(), "Users", null, "b"), dest);

        assertThat(centralDirectoryEntryNames(bytes)).containsExactly("a.csv", "b.csv");
    }

    @ParameterizedTest
    @EnumSource(Dest.class)
    void sampleCsvSheetWithoutOption_namesEntriesOnEveryDestination(final Dest dest) throws PxlException, IOException {
        final byte[] bytes = emit(pxlSpring.exportZip()
                .sampleCsvSheet(TestUser.class, "Users")
                .sampleCsvSheet(TestRequiredUser.class, "Required"), dest);

        assertThat(centralDirectoryEntryNames(bytes)).containsExactly("Users.csv", "Required.csv");
    }

    @ParameterizedTest
    @EnumSource(Dest.class)
    void sampleCsvSheetWithOption_namesEntriesOnEveryDestination(final Dest dest) throws PxlException, IOException {
        final byte[] bytes = emit(pxlSpring.exportZip()
                .sampleCsvSheet(TestUser.class, "Users", semicolonOption())
                .sampleCsvSheet(TestUser.class, "Admins", null), dest);

        assertThat(centralDirectoryEntryNames(bytes)).containsExactly("Users.csv", "Admins.csv");
    }

    @ParameterizedTest
    @EnumSource(Dest.class)
    void sampleCsvSheetWithOptionAndName_namesEntriesOnEveryDestination(final Dest dest) throws PxlException, IOException {
        final byte[] bytes = emit(pxlSpring.exportZip()
                .sampleCsvSheet(TestUser.class, "Users", null, "form-a")
                .sampleCsvSheet(TestUser.class, "Users", null, "form-b"), dest);

        assertThat(centralDirectoryEntryNames(bytes)).containsExactly("form-a.csv", "form-b.csv");
    }

    @Test
    void blankEntryNameOnACsvEntry_fallsBackToTheSheetName() throws PxlException, IOException {
        final ByteArrayOutputStream baos = new ByteArrayOutputStream();
        pxlSpring.exportZip()
                .csvSheet(TestUser.class, users(), "Users", null, "  ")
                .sampleCsvSheet(TestUser.class, "Forms", null, "  ")
                .toStream(baos);

        assertThat(centralDirectoryEntryNames(baos.toByteArray())).containsExactly("Users.csv", "Forms.csv");
    }

    @Test
    void csvEntryBytes_carryTheRecordsThatWentIn() throws PxlException, IOException {
        // Distinct rows per entry, so this also pins that each entry opens its own core builder: shared, the
        // second entry would carry the first one's rows, which the entry names could never show.
        final ByteArrayOutputStream baos = new ByteArrayOutputStream();
        pxlSpring.exportZip()
                .csvSheet(TestUser.class, users(), "Users")
                .csvSheet(TestUser.class, Collections.singletonList(new TestUser("Carol", 41)), "Admins")
                .sampleCsvSheet(TestUser.class, "Forms")
                .toStream(baos);

        final byte[] archive = baos.toByteArray();
        assertThat(linesOf(entryBytes(archive, "Users.csv")))
                .containsExactly("Name,Age", "Alice,30", "Bob,25", "");
        assertThat(linesOf(entryBytes(archive, "Admins.csv")))
                .containsExactly("Name,Age", "Carol,41", "");
        // the template is not empty: @PxlColumn(exportSample = ...) fills the one sample record
        assertThat(linesOf(entryBytes(archive, "Forms.csv")))
                .containsExactly("Name,Age", "Alice,30", "");
    }

    @Test
    void csvEntryContent_isReadableCsv_andRoundTrips() throws PxlException, IOException, HttpMediaTypeNotSupportedException {
        // The counterpart of zipEntryContent_isReadableXlsx_andRoundTrips: what came out of the archive is a
        // CSV this library reads back, not merely bytes that split on CRLF the way we expected. A
        // FileSystemResource because importCsv refuses a resource with no file name - there would be no
        // extension to check.
        final ByteArrayOutputStream baos = new ByteArrayOutputStream();
        pxlSpring.exportZip()
                .csvSheet(TestUser.class, users(), "Users")
                .toStream(baos);

        final File extracted = TestPaths.exportFile(testInfo, ".csv");
        Files.write(extracted.toPath(), entryBytes(baos.toByteArray(), "Users.csv"));

        final List<TestUser> back = pxlSpring.importCsv()
                .sheet(TestUser.class)
                .fromResource(new FileSystemResource(extracted));

        assertThat(back).extracting(TestUser::getName).containsExactly("Alice", "Bob");
        assertThat(back).extracting(TestUser::getAge).containsExactly(30, 25);
    }

    @Test
    void emptyRowsOnACsvEntry_stillWriteTheHeader() throws PxlException, IOException {
        // an export with nothing in it is ordinary - a report for a quiet month - and it must still produce a
        // well-formed member rather than an empty one
        final ByteArrayOutputStream baos = new ByteArrayOutputStream();
        pxlSpring.exportZip()
                .csvSheet(TestUser.class, Collections.<TestUser>emptyList(), "Users")
                .toStream(baos);

        assertThat(centralDirectoryEntryNames(baos.toByteArray())).containsExactly("Users.csv");
        assertThat(linesOf(entryBytes(baos.toByteArray(), "Users.csv"))).containsExactly("Name,Age", "");
    }

    @Test
    void csvEntryNames_carryNoIndexUnlikeTheSampleDefaults() throws PxlException, IOException {
        // The mirror of sampleEntryDefault_isPxlSampleIndexRatherThanPxlIndex. Two kinds append the entry
        // index to their default because their source may carry no name; the CSV pair never does, because a
        // sheet name is required and is therefore always there to take. Placed last on purpose - at index 2 an
        // appended index would be visible.
        final ByteArrayOutputStream baos = new ByteArrayOutputStream();
        pxlSpring.exportZip()
                .workbook(workbook(null))
                .sampleWorkbook(TestWorkbook.class)
                .csvSheet(TestUser.class, users(), "Users")
                .sampleCsvSheet(TestUser.class, "Forms")
                .toStream(baos);

        assertThat(centralDirectoryEntryNames(baos.toByteArray()))
                .containsExactly("Pxl0.xlsx", "PxlSample1.xlsx", "Users.csv", "Forms.csv");
    }

    @Test
    void aPasswordOnACsvEntry_isRefusedByTheCoreMidWrite() {
        // CSV cannot be encrypted, and the core refuses the option rather than ignoring it. That refusal
        // happens inside writeBody, so unlike the builder's own guards it lands in the middle of the write
        // loop - the file is already open by then, which is exactly why the archive is never finished after a
        // failure: what stays on disk cannot be opened as one.
        final PxlExportWorkbookOption passwordOption = PxlExportWorkbookOption.builder()
                .exportPassword("secret")
                .build();

        final File zipFile = TestPaths.exportFile(testInfo, ".zip");

        assertThatThrownBy(() -> pxlSpring.exportZip()
                .csvSheet(TestUser.class, users(), "Users", passwordOption)
                .toFile(zipFile))
                .isInstanceOf(PxlArgumentException.class);

        assertThat(zipFile).exists();
        assertThatThrownBy(() -> centralDirectoryEntryNames(Files.readAllBytes(zipFile.toPath())))
                .isInstanceOf(IOException.class);

        // the sample kind refuses it on the same terms
        assertThatThrownBy(() -> pxlSpring.exportZip()
                .sampleCsvSheet(TestUser.class, "Forms", passwordOption)
                .toStream(new ByteArrayOutputStream()))
                .isInstanceOf(PxlArgumentException.class);
    }

    @Test
    void csvEntryBytes_carryTheBom() throws PxlException, IOException {
        // Asserted on the raw bytes, never on a decoded string: decoding first absorbs the mark into U+FEFF
        // and hides whether one was written at all. The mark is per entry, so the second one must not have it.
        final PxlExportWorkbookOption bomOption = PxlExportWorkbookOption.builder()
                .exportCsvBom(Boolean.TRUE)
                .build();

        final ByteArrayOutputStream baos = new ByteArrayOutputStream();
        pxlSpring.exportZip()
                .csvSheet(TestUser.class, users(), "Marked", bomOption)
                .csvSheet(TestUser.class, users(), "Plain")
                .toStream(baos);

        final byte[] archive = baos.toByteArray();
        assertThat(entryBytes(archive, "Marked.csv")).startsWith((byte) 0xEF, (byte) 0xBB, (byte) 0xBF);
        assertThat(entryBytes(archive, "Plain.csv")).startsWith((byte) 'N');
    }

    @Test
    void csvEntryOption_reachesTheBytesWithoutTouchingTheName() throws PxlException, IOException {
        // The CSV kinds have no format axis, so the whole of what an option does here is inside the body -
        // which makes it worth pinning that it arrives at all, and that the entry is still named .csv.
        final ByteArrayOutputStream baos = new ByteArrayOutputStream();
        pxlSpring.exportZip()
                .csvSheet(TestUser.class, users(), "Users", semicolonOption())
                .sampleCsvSheet(TestUser.class, "Forms", semicolonOption())
                .toStream(baos);

        final byte[] archive = baos.toByteArray();
        assertThat(centralDirectoryEntryNames(archive)).containsExactly("Users.csv", "Forms.csv");
        assertThat(linesOf(entryBytes(archive, "Users.csv"))).startsWith("Name;Age", "Alice;30");
        assertThat(linesOf(entryBytes(archive, "Forms.csv"))).startsWith("Name;Age", "Alice;30");
    }

    @Test
    void anExcelEngineOption_doesNotChangeACsvEntry() throws PxlException, IOException {
        // resolveFileFormat does not exist on these kinds, so an exportExcelEngine has nothing to act on -
        // the same promise PxlSampleCsvExporter.excelEngineOption_doesNotChangeTheCsvExtension makes
        final ByteArrayOutputStream baos = new ByteArrayOutputStream();
        pxlSpring.exportZip()
                .csvSheet(TestUser.class, users(), "Users", hssfOption())
                .sampleCsvSheet(TestUser.class, "Forms", hssfOption())
                .toStream(baos);

        assertThat(centralDirectoryEntryNames(baos.toByteArray())).containsExactly("Users.csv", "Forms.csv");
    }

    /**
     * Enough rows for deflate to have something to work with. A three-record CSV comes out of the compressor
     * larger than it went in - true of any encoder on a few dozen bytes, and it would say nothing about which
     * level was picked.
     */
    private static List<TestUser> manyUsers() {
        final List<TestUser> rows = new ArrayList<>();
        for (int index = 0; index < 200; index++) {
            rows.add(new TestUser("User" + index, 20 + (index % 50)));
        }
        return rows;
    }

    @Test
    void csvEntriesAreCompressed_whileXlsxIsNot() throws PxlException, IOException {
        // The level is read off the entry name, so a .csv entry needed no code of its own: it is not .xlsx,
        // therefore it is deflated - and text deflates well, which is what makes the contrast visible.
        final ByteArrayOutputStream baos = new ByteArrayOutputStream();
        pxlSpring.exportZip()
                .csvSheet(TestUser.class, manyUsers(), "Users")
                .workbook(workbook("ooxml"))
                .toStream(baos);

        final File tmp = writeTempZip(baos.toByteArray());
        try (ZipFile zipFile = new ZipFile(tmp)) {
            final ZipEntry csv = zipFile.getEntry("Users.csv");
            assertThat(csv.getCompressedSize()).isLessThan(csv.getSize());

            final ZipEntry xlsx = zipFile.getEntry("ooxml.xlsx");
            assertThat(xlsx.getCompressedSize()).isGreaterThan(xlsx.getSize());
        } finally {
            tmp.delete();
        }
    }

    @Test
    void twoCsvEntriesUnderOneSheetName_collide() throws PxlException, IOException {
        // With no index-suffixed default there is nothing to make unnamed entries unique, so the ordinary
        // case - the same sheet name twice - is a collision, rejected before anything is written...
        assertThatThrownBy(() -> pxlSpring.exportZip()
                .csvSheet(TestUser.class, users(), "Users")
                .csvSheet(TestUser.class, users(), "Users")
                .toStream(new ByteArrayOutputStream()))
                .isInstanceOf(PxlArgumentException.class)
                .hasMessageContaining("Users.csv");

        // ...and an explicit name is the documented way out, exactly as for the workbook kinds
        final ByteArrayOutputStream baos = new ByteArrayOutputStream();
        pxlSpring.exportZip()
                .csvSheet(TestUser.class, users(), "Users", null, "january")
                .csvSheet(TestUser.class, users(), "Users", null, "february")
                .toStream(baos);

        assertThat(centralDirectoryEntryNames(baos.toByteArray()))
                .containsExactly("january.csv", "february.csv");
    }

    @Test
    void aCsvEntryAndAnExcelEntry_shareABaseNameWithoutColliding() throws PxlException, IOException {
        // the comparison is on the whole name, and .csv is simply another extension in it
        final ByteArrayOutputStream baos = new ByteArrayOutputStream();
        pxlSpring.exportZip()
                .workbook(workbook("report"))                                   // -> report.xlsx
                .csvSheet(TestUser.class, users(), "ignored", null, "report")   // -> report.csv
                .toStream(baos);

        assertThat(centralDirectoryEntryNames(baos.toByteArray()))
                .containsExactly("report.xlsx", "report.csv");
    }

    @Test
    void pathCarryingSheetName_isRejectedToo() {
        // the sheet name is a name source like the workbook name, so it reaches the path check as well -
        // the counterpart of pathCarryingWorkbookName_isRejectedToo
        assertThatThrownBy(() -> pxlSpring.exportZip()
                .csvSheet(TestUser.class, users(), "sub/Users")
                .toStream(new ByteArrayOutputStream()))
                .isInstanceOf(PxlArgumentException.class);

        assertThatThrownBy(() -> pxlSpring.exportZip()
                .sampleCsvSheet(TestUser.class, "sub/Forms")
                .toStream(new ByteArrayOutputStream()))
                .isInstanceOf(PxlArgumentException.class);
    }

    @Test
    void nullCsvEntrySource_throwsPxlNullPointer() {
        assertThatThrownBy(() -> pxlSpring.exportZip().csvSheet(null, users(), "Users"))
                .isInstanceOf(PxlNullPointerException.class);

        assertThatThrownBy(() -> pxlSpring.exportZip().csvSheet(TestUser.class, null, "Users"))
                .isInstanceOf(PxlNullPointerException.class);

        assertThatThrownBy(() -> pxlSpring.exportZip().csvSheet(TestUser.class, users(), null))
                .isInstanceOf(PxlNullPointerException.class);

        assertThatThrownBy(() -> pxlSpring.exportZip().sampleCsvSheet(null, "Users"))
                .isInstanceOf(PxlNullPointerException.class);

        assertThatThrownBy(() -> pxlSpring.exportZip().sampleCsvSheet(TestUser.class, null))
                .isInstanceOf(PxlNullPointerException.class);
    }

    @Test
    void blankCsvSheetName_throwsPxlArgumentAtTheCallThatAddsTheEntry() {
        // Rejected here rather than by the core inside writeBody, for two reasons: by then the file
        // destination has created its file and the streaming one has sent its headers, and validateEntries
        // reads this value as the entry's name long before the core would see it. Every arity guards it.
        assertThatThrownBy(() -> pxlSpring.exportZip().csvSheet(TestUser.class, users(), "  "))
                .isInstanceOf(PxlArgumentException.class);

        assertThatThrownBy(() -> pxlSpring.exportZip().csvSheet(TestUser.class, users(), "  ", null))
                .isInstanceOf(PxlArgumentException.class);

        assertThatThrownBy(() -> pxlSpring.exportZip().csvSheet(TestUser.class, users(), "  ", null, "a"))
                .isInstanceOf(PxlArgumentException.class);

        assertThatThrownBy(() -> pxlSpring.exportZip().sampleCsvSheet(TestUser.class, "  "))
                .isInstanceOf(PxlArgumentException.class);

        assertThatThrownBy(() -> pxlSpring.exportZip().sampleCsvSheet(TestUser.class, "  ", null))
                .isInstanceOf(PxlArgumentException.class);

        assertThatThrownBy(() -> pxlSpring.exportZip().sampleCsvSheet(TestUser.class, "  ", null, "a"))
                .isInstanceOf(PxlArgumentException.class);
    }

    @Test
    void aBlankSheetNameFailsBeforeTheFileIsCreated() {
        // what guarding at the call site buys, in the same terms as the duplicate check: nothing is on disk
        final File zipFile = TestPaths.exportFile(testInfo, ".zip");

        assertThatThrownBy(() -> pxlSpring.exportZip()
                .csvSheet(TestUser.class, users(), "  ")
                .toFile(zipFile))
                .isInstanceOf(PxlArgumentException.class);

        assertThat(zipFile).doesNotExist();
    }

    @Test
    void eachEntryKindNamesItselfAfterTheBytesItWrites() throws PxlException, IOException {
        // The extension follows the format the entry actually writes itself in, whichever kind resolves it:
        // a bound entry asks its option then its class, a raw one asks the workbook, a sample one its option
        // then its class, and the two CSV kinds do not resolve anything - CSV is all they write. All five end
        // up named after the bytes inside them, which is what the magic-byte assertions check.
        try (Workbook xssf = oneCell(new XSSFWorkbook()); Workbook hssf = oneCell(new HSSFWorkbook())) {
            final ByteArrayOutputStream baos = new ByteArrayOutputStream();
            pxlSpring.exportZip()
                    .workbook(workbook("bound"))                              // -> bound.xlsx
                    .workbook(new TestHssfWorkbook("bound-ole2", users()))    // -> bound-ole2.xls
                    .poiWorkbook(xssf, null, "raw")                           // -> raw.xlsx
                    .poiWorkbook(hssf, null, "raw-ole2")                      // -> raw-ole2.xls
                    .sampleWorkbook(TestWorkbook.class, null, "sample")       // -> sample.xlsx
                    .sampleWorkbook(TestWorkbook.class, hssfOption(), "sample-ole2")   // -> sample-ole2.xls
                    .csvSheet(TestUser.class, users(), "text")                // -> text.csv
                    .sampleCsvSheet(TestUser.class, "text-sample")            // -> text-sample.csv
                    .toStream(baos);

            final byte[] archive = baos.toByteArray();
            assertThat(centralDirectoryEntryNames(archive))
                    .containsExactly("bound.xlsx", "bound-ole2.xls", "raw.xlsx", "raw-ole2.xls",
                            "sample.xlsx", "sample-ole2.xls", "text.csv", "text-sample.csv");

            assertThat(isXlsx(entryBytes(archive, "bound.xlsx"))).isTrue();
            assertThat(isXlsx(entryBytes(archive, "bound-ole2.xls"))).isFalse();
            assertThat(isXlsx(entryBytes(archive, "raw.xlsx"))).isTrue();
            assertThat(isXlsx(entryBytes(archive, "raw-ole2.xls"))).isFalse();
            assertThat(isXlsx(entryBytes(archive, "sample.xlsx"))).isTrue();
            assertThat(isXlsx(entryBytes(archive, "sample-ole2.xls"))).isFalse();
            // the CSV pair is neither container: plain text, so it starts with the header record
            assertThat(entryBytes(archive, "text.csv")).startsWith((byte) 'N');
            assertThat(entryBytes(archive, "text-sample.csv")).startsWith((byte) 'N');
        }
    }

    @Test
    void rawPoiEntries_areCompressedByWhatTheyActuallyHold() throws PxlException, IOException {
        // the third thing hanging off that one answer: the deflate level is read back off the entry name, so
        // a raw .xlsx is stored (it is already a deflated container) and a raw .xls is compressed
        try (Workbook xssf = oneCell(new XSSFWorkbook()); Workbook hssf = oneCell(new HSSFWorkbook())) {
            final ByteArrayOutputStream baos = new ByteArrayOutputStream();
            pxlSpring.exportZip()
                    .poiWorkbook(xssf, null, "ooxml")
                    .poiWorkbook(hssf, null, "ole2")
                    .toStream(baos);

            final File tmp = writeTempZip(baos.toByteArray());
            try (ZipFile zipFile = new ZipFile(tmp)) {
                final ZipEntry storedXlsx = zipFile.getEntry("ooxml.xlsx");
                assertThat(storedXlsx.getCompressedSize()).isGreaterThan(storedXlsx.getSize());

                final ZipEntry deflatedXls = zipFile.getEntry("ole2.xls");
                assertThat(deflatedXls.getCompressedSize()).isLessThan(deflatedXls.getSize());
            } finally {
                tmp.delete();
            }
        }
    }

    @Test
    void duplicateEntryNames_areDetectedAcrossEveryEntryKind() throws IOException {
        // What the single entry list buys: validateEntries compares every entry against every other one
        // regardless of what it is made of, so a bound entry and a raw POI entry resolving to the same name
        // collide exactly as two bound ones do. Split into a list per kind, this would go into the archive.
        try (Workbook xssf = oneCell(new XSSFWorkbook())) {
            assertThatThrownBy(() -> pxlSpring.exportZip()
                    .workbook(workbook("report"))               // fallback -> report.xlsx
                    .poiWorkbook(xssf, null, "report")          // explicit -> report.xlsx too
                    .toStream(new ByteArrayOutputStream()))
                    .isInstanceOf(PxlArgumentException.class)
                    .hasMessageContaining("report.xlsx");
        }

        assertThatThrownBy(() -> pxlSpring.exportZip()
                .workbook(workbook("form"))                          // fallback -> form.xlsx
                .sampleWorkbook(TestWorkbook.class, null, "form")    // explicit -> form.xlsx too
                .toStream(new ByteArrayOutputStream()))
                .isInstanceOf(PxlArgumentException.class)
                .hasMessageContaining("form.xlsx");

        // the CSV pair collides on its own terms: a sheet-name fallback against an explicit name
        assertThatThrownBy(() -> pxlSpring.exportZip()
                .csvSheet(TestUser.class, users(), "Users")                     // fallback -> Users.csv
                .sampleCsvSheet(TestUser.class, "ignored", null, "Users")       // explicit -> Users.csv too
                .toStream(new ByteArrayOutputStream()))
                .isInstanceOf(PxlArgumentException.class)
                .hasMessageContaining("Users.csv");
    }

    @Test
    void entryNamesDifferingOnlyInCase_areRejectedAcrossKindsToo() throws IOException {
        // the fold is in validateEntries, which never asks what an entry is made of - so it spans kinds as
        // well, and one archive cannot carry Report.xlsx alongside a raw report.xlsx
        try (Workbook xssf = oneCell(new XSSFWorkbook())) {
            assertThatThrownBy(() -> pxlSpring.exportZip()
                    .workbook(workbook("Report"))
                    .poiWorkbook(xssf, null, "report")
                    .toStream(new ByteArrayOutputStream()))
                    .isInstanceOf(PxlArgumentException.class);
        }
    }

    @Test
    void oneBaseNameUnderTwoWorkbookTypes_staysTwoRawEntries() throws PxlException, IOException {
        // the other direction: the comparison is on the whole name, so the extension each workbook type
        // resolves to keeps these apart - the raw counterpart of
        // differingExtensions_makeTheSameBaseNameTwoDistinctEntries
        try (Workbook xssf = oneCell(new XSSFWorkbook()); Workbook hssf = oneCell(new HSSFWorkbook())) {
            final ByteArrayOutputStream baos = new ByteArrayOutputStream();
            pxlSpring.exportZip()
                    .poiWorkbook(xssf, null, "report")     // -> report.xlsx
                    .poiWorkbook(hssf, null, "report")     // -> report.xls
                    .toStream(baos);

            assertThat(centralDirectoryEntryNames(baos.toByteArray()))
                    .containsExactly("report.xlsx", "report.xls");
        }
    }

    @Test
    void pathCarryingEntryName_isRejectedForEveryKind() throws IOException {
        // the guard is in validateEntries, whose loop never asks what an entry is made of - so a new kind is
        // covered by construction. Pinned anyway, because the archive would otherwise hand out a traversal
        // path.
        try (Workbook xssf = oneCell(new XSSFWorkbook())) {
            assertThatThrownBy(() -> pxlSpring.exportZip()
                    .poiWorkbook(xssf, null, "sub/report")
                    .toStream(new ByteArrayOutputStream()))
                    .isInstanceOf(PxlArgumentException.class);
        }

        assertThatThrownBy(() -> pxlSpring.exportZip()
                .sampleWorkbook(TestWorkbook.class, null, "sub/form")
                .toStream(new ByteArrayOutputStream()))
                .isInstanceOf(PxlArgumentException.class);

        assertThatThrownBy(() -> pxlSpring.exportZip()
                .csvSheet(TestUser.class, users(), "ignored", null, "sub/text")
                .toStream(new ByteArrayOutputStream()))
                .isInstanceOf(PxlArgumentException.class);

        assertThatThrownBy(() -> pxlSpring.exportZip()
                .sampleCsvSheet(TestUser.class, "ignored", null, "sub/text")
                .toStream(new ByteArrayOutputStream()))
                .isInstanceOf(PxlArgumentException.class);
    }

    @Test
    void mixedEntryKinds_areWrittenInCallOrder() throws PxlException, IOException {
        // one list, one order: kinds interleave rather than being grouped by what they are made of
        try (Workbook xssf = oneCell(new XSSFWorkbook())) {
            final ByteArrayOutputStream baos = new ByteArrayOutputStream();
            pxlSpring.exportZip()
                    .poiWorkbook(xssf, null, "one")
                    .workbook(workbook("two"))
                    .csvSheet(TestUser.class, users(), "ignored", null, "three")
                    .sampleWorkbook(TestWorkbook.class, null, "four")
                    .sampleCsvSheet(TestUser.class, "ignored", null, "five")
                    .workbook(workbook("ignored"), null, "six")
                    .toStream(baos);

            assertThat(centralDirectoryEntryNames(baos.toByteArray()))
                    .containsExactly("one.xlsx", "two.xlsx", "three.csv", "four.xlsx", "five.csv", "six.xlsx");
        }
    }

    // ----- entry kinds (structure) -----
    // An archive entry answers for itself: Builder.Entry is abstract, and a kind of source decides both what
    // its entry is called and how its body is written. That buys nothing on its own - with one kind the
    // behaviour is exactly what it was - so what is worth guarding is the shape the split depends on, the way
    // PxlCoreSupportTests guards the shared core it cannot observe either.

    @Test
    void theBuilderKeepsExactlyOneEntryCollection() {
        // validateEntries walks one list, which is what makes the duplicate-name check whole: every entry is
        // compared against every other entry regardless of what it is made of. Kept in a list per kind
        // instead, the check would run within each list only and a name colliding across two kinds would go
        // straight into the archive - the collision the check exists to catch.
        final List<Field> collectionFields = new ArrayList<>();
        for (final Field field : PxlZipExporter.Builder.class.getDeclaredFields()) {
            if (!field.isSynthetic()
                    && !Modifier.isStatic(field.getModifiers())
                    && Collection.class.isAssignableFrom(field.getType())) {
                collectionFields.add(field);
            }
        }

        assertThat(collectionFields).extracting(Field::getName).containsExactly("entries");
    }

    @Test
    void everyEntryKind_staysAPrivateNestmateOfTheBuilder() {
        // The component reads an entry without a single getter only because the two are nestmates. Publishing
        // a kind - or moving it to its own file - would put an implementation type on the builder's surface,
        // which is meant to be the configuration and terminal methods and nothing else.
        final Class<?> entryType = nestedTypeOfBuilder("Entry");

        assertThat(Modifier.isAbstract(entryType.getModifiers())).as("Entry is abstract").isTrue();
        assertThat(Modifier.isPrivate(entryType.getModifiers())).as("Entry is private").isTrue();
        assertThat(Modifier.isStatic(entryType.getModifiers())).as("Entry is static").isTrue();

        final List<Class<?>> kinds = new ArrayList<>();
        for (final Class<?> nested : PxlZipExporter.Builder.class.getDeclaredClasses()) {
            if (!nested.isSynthetic() && !nested.equals(entryType) && entryType.isAssignableFrom(nested)) {
                kinds.add(nested);
            }
        }

        // Pinned as a roster rather than "at least one": with five kinds, a weaker assertion would pass just
        // as happily if four of them were deleted. Adding a sixth is meant to touch this line - the modifier
        // checks below then apply to it, and the design notes ask for the same list to grow elsewhere.
        assertThat(kinds).extracting(Class::getSimpleName)
                .containsExactlyInAnyOrder("WorkbookEntry", "PoiWorkbookEntry", "SampleWorkbookEntry",
                        "CsvSheetEntry", "SampleCsvSheetEntry");

        for (final Class<?> kind : kinds) {
            final int modifiers = kind.getModifiers();

            assertThat(Modifier.isPrivate(modifiers)).as("%s is private", kind.getSimpleName()).isTrue();
            assertThat(Modifier.isStatic(modifiers)).as("%s is static", kind.getSimpleName()).isTrue();

            // a kind that can actually be added to an archive is a leaf: left open to subclassing it could
            // hand its own naming down to a kind that must not share it
            if (!Modifier.isAbstract(modifiers)) {
                assertThat(Modifier.isFinal(modifiers)).as("%s is final", kind.getSimpleName()).isTrue();
            }
        }
    }

    /**
     * Looks up one of the builder's nested types by simple name.
     *
     * @param simpleName the nested type's simple name
     * @return the nested type
     */
    private static Class<?> nestedTypeOfBuilder(final String simpleName) {
        for (final Class<?> nested : PxlZipExporter.Builder.class.getDeclaredClasses()) {
            if (nested.getSimpleName().equals(simpleName)) {
                return nested;
            }
        }

        throw new AssertionError("PxlZipExporter.Builder declares no nested type named " + simpleName);
    }

    private static PxlExportWorkbookOption hssfOption() {
        return PxlExportWorkbookOption.builder()
                .exportExcelEngine(PxlExcelEngine.HSSF)
                .build();
    }

    private static PxlExportWorkbookOption xssfOption() {
        return PxlExportWorkbookOption.builder()
                .exportExcelEngine(PxlExcelEngine.XSSF)
                .build();
    }

    /**
     * A CSV option whose effect shows up in the bytes rather than the entry name - the CSV kinds have no
     * format to switch, so this is what an option reaching them looks like.
     */
    private static PxlExportWorkbookOption semicolonOption() {
        return PxlExportWorkbookOption.builder()
                .exportCsvDelimiter(';')
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
