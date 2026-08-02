package io.github.hclimkr.pxl.spring.component;

import io.github.hclimkr.pxl.exception.PxlArgumentException;
import io.github.hclimkr.pxl.exception.PxlException;
import io.github.hclimkr.pxl.exception.PxlIOException;
import io.github.hclimkr.pxl.exception.PxlNullPointerException;
import io.github.hclimkr.pxl.option.PxlImportWorkbookOption;
import io.github.hclimkr.pxl.spring.PxlSpring;
import io.github.hclimkr.pxl.spring.tcdata.TestMultiSheetWorkbook;
import io.github.hclimkr.pxl.spring.tcdata.TestPaths;
import io.github.hclimkr.pxl.spring.tcdata.TestUser;
import io.github.hclimkr.pxl.spring.tcdata.TestWorkbook;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.multipart.MultipartFile;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import static io.github.hclimkr.pxl.spring.component.PxlExcelExporterTests.users;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Behavioural tests for {@link PxlCsvImporter}, all driven through the {@link PxlCsvImporter.Builder}
 * fluent API: CSV round trips across every parse target (workbook, row-class sheet, collection-class sheet)
 * and both terminals ({@code fromMultipartFile} / {@code fromMultipartFiles}), several uploads grouped into
 * one workbook object, workbook-name and import-option handling on both the builder and the source step,
 * and file-extension validation.
 *
 * <p>The builder comes from {@link PxlSpring}, the entry point the documentation guides users to. The
 * facade hands back this component's own builder, so what is exercised here is still the component.</p>
 */
class PxlCsvImporterTests {

    private static final byte[] USERS_CSV = "Name,Age\nAlice,30\nBob,25\n".getBytes(StandardCharsets.UTF_8);

    private static final byte[] SEMICOLON_USERS_CSV = "Name;Age\nAlice;30\nBob;25\n".getBytes(StandardCharsets.UTF_8);

    private final PxlSpring pxlSpring = new PxlSpring();

    private byte[] sheetXlsx(final String sheetName) throws PxlException {
        final ByteArrayOutputStream baos = new ByteArrayOutputStream();
        pxlSpring.exportExcel().sheet(TestUser.class, users(), sheetName).toStream(baos);
        return baos.toByteArray();
    }

    private static MockMultipartFile file(final String filename, final byte[] content) {
        return new MockMultipartFile("file", filename, null, content);
    }

    /**
     * A CSV upload whose {@link MultipartFile#getInputStream()} fails — used to drive the {@code IOException}
     * → {@link PxlIOException} translation path. Its extension still validates, so the failure lands on the
     * stream read rather than the extension check.
     */
    private static MultipartFile throwingFile(final String filename) {
        return new MockMultipartFile("file", filename, null, "x".getBytes(StandardCharsets.UTF_8)) {
            @Override
            public InputStream getInputStream() throws IOException {
                throw new IOException("simulated read failure");
            }
        };
    }

    /**
     * A resource reporting the given file name. {@link ByteArrayResource} reports none of its own, and a
     * nameless resource is refused, so the name has to be supplied by an override — which matters twice over
     * here, because a CSV's file name is also its sheet name.
     */
    private static Resource resource(final String filename, final byte[] content) {
        return new ByteArrayResource(content) {
            @Override
            public String getFilename() {
                return filename;
            }
        };
    }

    /**
     * A CSV resource whose {@link Resource#getInputStream()} fails — the {@code IOException} →
     * {@link PxlIOException} translation path for the resource source form.
     */
    private static Resource throwingResource(final String filename) {
        return new ByteArrayResource("x".getBytes(StandardCharsets.UTF_8)) {
            @Override
            public String getFilename() {
                return filename;
            }

            @Override
            public InputStream getInputStream() throws IOException {
                throw new IOException("simulated read failure");
            }
        };
    }

    @Test
    void importCsvSingleSheet_roundTrips() throws PxlException, HttpMediaTypeNotSupportedException {
        // the row-class sheet(...) form is typed List<TestUser> end-to-end — no cast at the call site
        final List<TestUser> result = pxlSpring.importCsv()
                .sheet(TestUser.class)
                .fromMultipartFile(file("users.csv", USERS_CSV));

        assertThat(result).extracting(TestUser::getName).containsExactly("Alice", "Bob");
        assertThat(result).extracting(TestUser::getAge).containsExactly(30, 25);
    }

    @Test
    void importCsvSingleSheetAsSet_returnsSet() throws PxlException, HttpMediaTypeNotSupportedException {
        @SuppressWarnings("unchecked") final Set<TestUser> result =
                (Set<TestUser>) pxlSpring.importCsv()
                        .sheet(TestUser.class, Set.class)
                        .fromMultipartFile(file("users.csv", USERS_CSV));

        assertThat(result).hasSize(2);
        assertThat(result).extracting(TestUser::getName).containsExactlyInAnyOrder("Alice", "Bob");
    }

    @Test
    void importCsvWorkbook_mapsFileToNamedSheet() throws PxlException, HttpMediaTypeNotSupportedException {
        // the CSV base name ("Users") is matched to the @PxlSheet(name = "Users") field
        final TestWorkbook back = pxlSpring.importCsv()
                .workbook(TestWorkbook.class)
                .fromMultipartFiles(Collections.singletonList(file("Users.csv", USERS_CSV)));

        assertThat(back.getUsers()).extracting(TestUser::getName).containsExactly("Alice", "Bob");
    }

    @Test
    void importCsvWorkbook_fromSingleMultipartFile_mapsFileToNamedSheet() throws PxlException, HttpMediaTypeNotSupportedException {
        // fromMultipartFile(...) is just the single-upload spelling of fromMultipartFiles(...), so the
        // workbook form accepts it too — the old single-file overload was sheet-only
        final TestWorkbook back = pxlSpring.importCsv()
                .workbook(TestWorkbook.class)
                .fromMultipartFile(file("Users.csv", USERS_CSV));

        assertThat(back.getUsers()).extracting(TestUser::getName).containsExactly("Alice", "Bob");
    }

    @Test
    void importCsvWorkbook_withExplicitName_setsWorkbookName() throws PxlException, HttpMediaTypeNotSupportedException {
        final TestWorkbook back = pxlSpring.importCsv()
                .workbookName("Explicit")
                .workbook(TestWorkbook.class)
                .fromMultipartFile(file("Users.csv", USERS_CSV));

        assertThat(back.getWorkbookName()).isEqualTo("Explicit");
    }

    @Test
    void importCsvSheet_withSeveralUploads_throwsPxlArgument() {
        // the sheet form parses exactly one CSV; the workbook form is the multi-file one
        assertThatThrownBy(() -> pxlSpring.importCsv()
                .sheet(TestUser.class)
                .fromMultipartFiles(Arrays.asList(file("a.csv", USERS_CSV), file("b.csv", USERS_CSV))))
                .isInstanceOf(PxlArgumentException.class);
    }

    @Test
    void importCsvUnsupportedExtension_throwsHttpMediaTypeNotSupported() {
        assertThatThrownBy(() -> pxlSpring.importCsv()
                .sheet(TestUser.class)
                .fromMultipartFile(file("users.xlsx", sheetXlsx("Users"))))
                .isInstanceOf(HttpMediaTypeNotSupportedException.class);
    }

    @Test
    void importCsvNullFilename_throwsHttpMediaTypeNotSupported() {
        // MockMultipartFile turns a null file name into an empty one, so this exercises the blank-extension
        // branch (the genuinely-null path is covered in PxlImportSupportTests)
        assertThatThrownBy(() -> pxlSpring.importCsv()
                .sheet(TestUser.class)
                .fromMultipartFile(file(null, "x".getBytes(StandardCharsets.UTF_8))))
                .isInstanceOf(HttpMediaTypeNotSupportedException.class);
    }

    @Test
    void importCsvSingleSheet_whenFileReadFails_throwsPxlIOException() {
        assertThatThrownBy(() -> pxlSpring.importCsv()
                .sheet(TestUser.class)
                .fromMultipartFile(throwingFile("users.csv")))
                .isInstanceOf(PxlIOException.class);
    }

    @Test
    void importCsvWorkbook_whenFileReadFails_throwsPxlIOException() {
        assertThatThrownBy(() -> pxlSpring.importCsv()
                .workbook(TestWorkbook.class)
                .fromMultipartFiles(Collections.singletonList(throwingFile("Users.csv"))))
                .isInstanceOf(PxlIOException.class);
    }

    // ----- builder parse-target guards (delegated to the core builder) -----

    @Test
    void nullWorkbookClass_throwsPxlNullPointer() {
        assertThatThrownBy(() -> pxlSpring.importCsv().workbook(null))
                .isInstanceOf(PxlNullPointerException.class);
    }

    @Test
    void nullRowClass_throwsPxlNullPointer() {
        assertThatThrownBy(() -> pxlSpring.importCsv().sheet(null))
                .isInstanceOf(PxlNullPointerException.class);
    }

    // ----- upload guards on a plain (unproxied) component -----
    // @NotNull/@NotEmpty only fire through the Spring proxy, so without these guards a plainly built
    // component would raise a raw NullPointerException, outside the library's exception contract. Through a
    // proxy the same calls raise ConstraintViolationException - that half is pinned by PxlValidationTests.

    @Test
    void nullUploadOnPlainComponent_throwsPxlNullPointerNotRawNpe() {
        assertThatThrownBy(() -> pxlSpring.importCsv()
                .sheet(TestUser.class)
                .fromMultipartFile(null))
                .isInstanceOf(PxlNullPointerException.class);
    }

    @Test
    void nullUploadListOnPlainComponent_throwsPxlNullPointerNotRawNpe() {
        assertThatThrownBy(() -> pxlSpring.importCsv()
                .workbook(TestWorkbook.class)
                .fromMultipartFiles(null))
                .isInstanceOf(PxlNullPointerException.class);
    }

    @Test
    void emptyUploadListOnPlainComponent_throwsPxlArgument() {
        assertThatThrownBy(() -> pxlSpring.importCsv()
                .workbook(TestWorkbook.class)
                .fromMultipartFiles(Collections.emptyList()))
                .isInstanceOf(PxlArgumentException.class);
    }

    // ----- parse target x terminal matrix -----
    // fromMultipartFile(...) is the single-upload spelling of fromMultipartFiles(...), so every parse target
    // must behave identically through both. Only some pairings were spot-checked before.

    @Test
    void sheetRowClass_viaMultipartFiles_roundTrips() throws PxlException, HttpMediaTypeNotSupportedException {
        final List<TestUser> result = pxlSpring.importCsv()
                .sheet(TestUser.class)
                .fromMultipartFiles(Collections.singletonList(file("users.csv", USERS_CSV)));

        assertThat(result).extracting(TestUser::getName).containsExactly("Alice", "Bob");
    }

    @Test
    void sheetCollectionClass_viaMultipartFiles_returnsSet() throws PxlException, HttpMediaTypeNotSupportedException {
        @SuppressWarnings("unchecked") final Set<TestUser> result =
                (Set<TestUser>) pxlSpring.importCsv()
                        .sheet(TestUser.class, Set.class)
                        .fromMultipartFiles(Collections.singletonList(file("users.csv", USERS_CSV)));

        assertThat(result).extracting(TestUser::getName).containsExactlyInAnyOrder("Alice", "Bob");
    }

    @Test
    void sheetCollectionClass_viaMultipartFiles_withSeveralUploads_throwsPxlArgument() {
        // the single-CSV guard applies to the collection-class sheet form too, not just the row-class one
        assertThatThrownBy(() -> pxlSpring.importCsv()
                .sheet(TestUser.class, Set.class)
                .fromMultipartFiles(Arrays.asList(file("a.csv", USERS_CSV), file("b.csv", USERS_CSV))))
                .isInstanceOf(PxlArgumentException.class);
    }

    // ----- workbook form with several CSVs, one per sheet -----
    // This is the whole point of the workbook form, yet it was only ever driven with a single upload.

    @Test
    void importCsvWorkbook_withSeveralUploads_mapsEachFileToItsSheet() throws PxlException, HttpMediaTypeNotSupportedException {
        final byte[] adminsCsv = "Name,Age\nCarol,40\n".getBytes(StandardCharsets.UTF_8);

        final TestMultiSheetWorkbook back = pxlSpring.importCsv()
                .workbook(TestMultiSheetWorkbook.class)
                .fromMultipartFiles(Arrays.asList(
                        file("Users.csv", USERS_CSV),
                        file("Admins.csv", adminsCsv)));

        assertThat(back.getUsers()).extracting(TestUser::getName).containsExactly("Alice", "Bob");
        assertThat(back.getAdmins()).extracting(TestUser::getName).containsExactly("Carol");
    }

    @Test
    void importCsvWorkbook_uploadOrderDoesNotMatter() throws PxlException, HttpMediaTypeNotSupportedException {
        // sheets are matched by file base name, not by position
        final byte[] adminsCsv = "Name,Age\nCarol,40\n".getBytes(StandardCharsets.UTF_8);

        final TestMultiSheetWorkbook back = pxlSpring.importCsv()
                .workbook(TestMultiSheetWorkbook.class)
                .fromMultipartFiles(Arrays.asList(
                        file("Admins.csv", adminsCsv),
                        file("Users.csv", USERS_CSV)));

        assertThat(back.getUsers()).extracting(TestUser::getName).containsExactly("Alice", "Bob");
        assertThat(back.getAdmins()).extracting(TestUser::getName).containsExactly("Carol");
    }

    @Test
    void importCsvWorkbook_whenOneOfSeveralUploadsHasBadExtension_throwsHttpMediaTypeNotSupported() {
        // every upload is validated up front, so a bad one anywhere in the list rejects the whole call
        assertThatThrownBy(() -> pxlSpring.importCsv()
                .workbook(TestMultiSheetWorkbook.class)
                .fromMultipartFiles(Arrays.asList(
                        file("Users.csv", USERS_CSV),
                        file("Admins.txt", USERS_CSV))))
                .isInstanceOf(HttpMediaTypeNotSupportedException.class);
    }

    // ----- workbookName / override on the builder and on the source step -----

    @Test
    void workbookNameOnSource_setsWorkbookName() throws PxlException, HttpMediaTypeNotSupportedException {
        // the same setter is available after the parse target; the value set last wins
        final TestWorkbook back = pxlSpring.importCsv()
                .workbookName("Ignored")
                .workbook(TestWorkbook.class)
                .workbookName("Explicit")
                .fromMultipartFile(file("Users.csv", USERS_CSV));

        assertThat(back.getWorkbookName()).isEqualTo("Explicit");
    }

    @Test
    void blankWorkbookName_isNotDerivedFromFilename() throws PxlException, HttpMediaTypeNotSupportedException {
        // unlike the Excel importer, a CSV file name names its sheet — there is no workbook-name fallback
        final TestWorkbook back = pxlSpring.importCsv()
                .workbook(TestWorkbook.class)
                .fromMultipartFile(file("Users.csv", USERS_CSV));

        assertThat(back.getWorkbookName()).isNull();
    }

    @Test
    void overrideOnBuilder_appliesImportOption() throws PxlException, HttpMediaTypeNotSupportedException {
        // a semicolon-delimited CSV only parses when the delimiter option reaches the core
        final List<TestUser> result = pxlSpring.importCsv()
                .override(semicolonDelimited())
                .sheet(TestUser.class)
                .fromMultipartFile(file("users.csv", SEMICOLON_USERS_CSV));

        assertThat(result).extracting(TestUser::getName).containsExactly("Alice", "Bob");
        assertThat(result).extracting(TestUser::getAge).containsExactly(30, 25);
    }

    @Test
    void overrideOnSource_appliesImportOption() throws PxlException, HttpMediaTypeNotSupportedException {
        // the same setter is available after the parse target; the value set last wins
        final List<TestUser> result = pxlSpring.importCsv()
                .sheet(TestUser.class)
                .override(semicolonDelimited())
                .fromMultipartFile(file("users.csv", SEMICOLON_USERS_CSV));

        assertThat(result).extracting(TestUser::getName).containsExactly("Alice", "Bob");
    }

    @Test
    void overrideOnWorkbookForm_appliesImportOption() throws PxlException, HttpMediaTypeNotSupportedException {
        final TestWorkbook back = pxlSpring.importCsv()
                .override(semicolonDelimited())
                .workbook(TestWorkbook.class)
                .fromMultipartFile(file("Users.csv", SEMICOLON_USERS_CSV));

        assertThat(back.getUsers()).extracting(TestUser::getName).containsExactly("Alice", "Bob");
    }

    @Test
    void overrideOnBuilderAndSource_lastValueWins() throws PxlException, HttpMediaTypeNotSupportedException {
        // both steps write the same slot, so the comma delimiter set on the builder is replaced by the
        // semicolon one the source step sets afterwards
        final PxlImportWorkbookOption commaDelimited = PxlImportWorkbookOption.builder()
                .importCsvDelimiter(',')
                .build();

        final List<TestUser> result = pxlSpring.importCsv()
                .override(commaDelimited)
                .sheet(TestUser.class)
                .override(semicolonDelimited())
                .fromMultipartFile(file("users.csv", SEMICOLON_USERS_CSV));

        assertThat(result).extracting(TestUser::getName).containsExactly("Alice", "Bob");
        assertThat(result).extracting(TestUser::getAge).containsExactly(30, 25);
    }

    @Test
    void withoutDelimiterOption_semicolonCsvDoesNotYieldTheRows() {
        // Guards the tests above — without the option the very same upload must not produce the rows. With
        // the default comma delimiter the whole line is one "Name;Age" cell, which either fails the parse
        // outright or matches no column; both outcomes are acceptable, binding "Alice" is not.
        final List<TestUser> parsed;
        try {
            parsed = pxlSpring.importCsv()
                    .sheet(TestUser.class)
                    .fromMultipartFile(file("users.csv", SEMICOLON_USERS_CSV));
        } catch (Exception expected) {
            return;
        }

        assertThat(parsed).extracting(TestUser::getName).doesNotContain("Alice");
    }

    private static PxlImportWorkbookOption semicolonDelimited() {
        return PxlImportWorkbookOption.builder()
                .importCsvDelimiter(';')
                .build();
    }

    // ----- the Resource source form -----
    // fromResource(...)/fromResources(...) are the non-HTTP half of the same operation. Everything after the
    // sources are opened is shared with the multipart pair, so what is swept here is the part that is not:
    // extension validation, sheet-name derivation, the single-CSV guard, and stream handling.

    @Test
    void importCsvSheet_fromResource_roundTrips() throws PxlException, HttpMediaTypeNotSupportedException {
        final List<TestUser> result = pxlSpring.importCsv()
                .sheet(TestUser.class)
                .fromResource(resource("users.csv", USERS_CSV));

        assertThat(result).extracting(TestUser::getName).containsExactly("Alice", "Bob");
        assertThat(result).extracting(TestUser::getAge).containsExactly(30, 25);
    }

    @Test
    void importCsvWorkbook_fromResource_mapsResourceNameToNamedSheet() throws PxlException, HttpMediaTypeNotSupportedException {
        // the resource's base name ("Users") is matched to the @PxlSheet(name = "Users") field, exactly as an
        // upload's is
        final TestWorkbook back = pxlSpring.importCsv()
                .workbook(TestWorkbook.class)
                .fromResource(resource("Users.csv", USERS_CSV));

        assertThat(back.getUsers()).extracting(TestUser::getName).containsExactly("Alice", "Bob");
    }

    @Test
    void importCsvWorkbook_fromResources_mapsEachResourceToItsSheet() throws PxlException, HttpMediaTypeNotSupportedException {
        final byte[] adminsCsv = "Name,Age\nCarol,40\n".getBytes(StandardCharsets.UTF_8);

        final TestMultiSheetWorkbook back = pxlSpring.importCsv()
                .workbook(TestMultiSheetWorkbook.class)
                .fromResources(Arrays.asList(
                        resource("Users.csv", USERS_CSV),
                        resource("Admins.csv", adminsCsv)));

        assertThat(back.getUsers()).extracting(TestUser::getName).containsExactly("Alice", "Bob");
        assertThat(back.getAdmins()).extracting(TestUser::getName).containsExactly("Carol");
    }

    @Test
    void importCsvSheet_fromResources_withSeveralResources_throwsPxlArgument() {
        // the sheet form parses exactly one CSV whichever source form it is given
        assertThatThrownBy(() -> pxlSpring.importCsv()
                .sheet(TestUser.class)
                .fromResources(Arrays.asList(resource("a.csv", USERS_CSV), resource("b.csv", USERS_CSV))))
                .isInstanceOf(PxlArgumentException.class);
    }

    @Test
    void importCsvFromResource_appliesImportOption() throws PxlException, HttpMediaTypeNotSupportedException {
        // the option reaches the core on this terminal too, not just the multipart one
        final List<TestUser> result = pxlSpring.importCsv()
                .sheet(TestUser.class)
                .override(semicolonDelimited())
                .fromResource(resource("users.csv", SEMICOLON_USERS_CSV));

        assertThat(result).extracting(TestUser::getName).containsExactly("Alice", "Bob");
    }

    @Test
    void importCsvFromResource_unsupportedExtension_throwsHttpMediaTypeNotSupported() {
        assertThatThrownBy(() -> pxlSpring.importCsv()
                .sheet(TestUser.class)
                .fromResource(resource("users.xlsx", USERS_CSV)))
                .isInstanceOf(HttpMediaTypeNotSupportedException.class);
    }

    @Test
    void importCsvFromNamelessResource_throwsHttpMediaTypeNotSupported() {
        // a CSV's file name is also its sheet name, so a nameless resource has nothing to bind by even if the
        // extension check let it past
        assertThatThrownBy(() -> pxlSpring.importCsv()
                .sheet(TestUser.class)
                .fromResource(new ByteArrayResource(USERS_CSV)))
                .isInstanceOf(HttpMediaTypeNotSupportedException.class);
    }

    @Test
    void importCsvFromResources_whenOneResourceHasBadExtension_throwsHttpMediaTypeNotSupported() {
        // every source is validated up front, so a bad one anywhere in the list rejects the whole call
        assertThatThrownBy(() -> pxlSpring.importCsv()
                .workbook(TestMultiSheetWorkbook.class)
                .fromResources(Arrays.asList(
                        resource("Users.csv", USERS_CSV),
                        resource("Admins.txt", USERS_CSV))))
                .isInstanceOf(HttpMediaTypeNotSupportedException.class);
    }

    @Test
    void importCsvFromResource_whenReadFails_throwsPxlIOException() {
        assertThatThrownBy(() -> pxlSpring.importCsv()
                .sheet(TestUser.class)
                .fromResource(throwingResource("users.csv")))
                .isInstanceOf(PxlIOException.class);
    }

    @Test
    void nullResourceOnPlainComponent_throwsPxlNullPointerNotRawNpe() {
        assertThatThrownBy(() -> pxlSpring.importCsv()
                .sheet(TestUser.class)
                .fromResource(null))
                .isInstanceOf(PxlNullPointerException.class);
    }

    @Test
    void nullResourceListOnPlainComponent_throwsPxlNullPointerNotRawNpe() {
        assertThatThrownBy(() -> pxlSpring.importCsv()
                .workbook(TestWorkbook.class)
                .fromResources(null))
                .isInstanceOf(PxlNullPointerException.class);
    }

    @Test
    void emptyResourceListOnPlainComponent_throwsPxlArgument() {
        assertThatThrownBy(() -> pxlSpring.importCsv()
                .workbook(TestWorkbook.class)
                .fromResources(Collections.emptyList()))
                .isInstanceOf(PxlArgumentException.class);
    }

    @Test
    void importCsvFromResources_closesEveryStreamItOpened() throws PxlException, HttpMediaTypeNotSupportedException {
        // The CSV path opens every source up front and holds them all open until the parse ends, so the
        // finally block has to close all of them, not just the last. With FileSystemResources that is a file
        // handle per sheet.
        final byte[] adminsCsv = "Name,Age\nCarol,40\n".getBytes(StandardCharsets.UTF_8);
        final AtomicInteger closedCount = new AtomicInteger();

        pxlSpring.importCsv()
                .workbook(TestMultiSheetWorkbook.class)
                .fromResources(Arrays.asList(
                        trackedResource("Users.csv", USERS_CSV, closedCount),
                        trackedResource("Admins.csv", adminsCsv, closedCount)));

        assertThat(closedCount).as("every stream opened for a resource must be closed").hasValue(2);
    }

    @Test
    void importCsvFromResources_whenOneSourceFailsToOpen_closesTheOnesAlreadyOpened() {
        // The real leak risk on the CSV path: the sources are opened in a loop, so a failure part-way
        // through leaves earlier streams open with nothing else to close them. The first resource opens
        // fine, the second throws - the first must still be released.
        final AtomicInteger closedCount = new AtomicInteger();

        assertThatThrownBy(() -> pxlSpring.importCsv()
                .workbook(TestMultiSheetWorkbook.class)
                .fromResources(Arrays.asList(
                        trackedResource("Users.csv", USERS_CSV, closedCount),
                        throwingResource("Admins.csv"))))
                .isInstanceOf(PxlIOException.class);

        assertThat(closedCount).as("a source opened before the failure must still be closed").hasValue(1);
    }

    @Test
    void importCsvFromResource_andFromUpload_produceTheSameResult() throws PxlException, HttpMediaTypeNotSupportedException {
        // Pins the claim the two back-ends rest on: past the point the sources are opened they are one path
        // (readInto), so the same bytes under the same file name must parse identically either way.
        final List<TestUser> fromUpload = pxlSpring.importCsv()
                .sheet(TestUser.class)
                .fromMultipartFile(file("users.csv", USERS_CSV));
        final List<TestUser> fromResource = pxlSpring.importCsv()
                .sheet(TestUser.class)
                .fromResource(resource("users.csv", USERS_CSV));

        assertThat(fromResource)
                .extracting(TestUser::getName)
                .containsExactlyElementsOf(
                        fromUpload.stream().map(TestUser::getName).collect(Collectors.toList()));
    }

    @Test
    void importCsvFromFileSystemResource_roundTrips() throws PxlException, HttpMediaTypeNotSupportedException, IOException {
        // the case the terminal exists for: real files on disk, one per sheet, read without a Pxl instance
        // of the caller's own
        final File file = TestPaths.exportFile("importCsvFromFileSystemResource.csv");
        Files.write(file.toPath(), USERS_CSV);

        final List<TestUser> result = pxlSpring.importCsv()
                .sheet(TestUser.class)
                .fromResource(new FileSystemResource(file));

        assertThat(result).extracting(TestUser::getName).containsExactly("Alice", "Bob");
    }

    /**
     * A named CSV resource that counts how many of the streams it handed out were closed.
     */
    private static Resource trackedResource(final String filename,
                                            final byte[] content,
                                            final AtomicInteger closedCount) {
        return new ByteArrayResource(content) {
            @Override
            public String getFilename() {
                return filename;
            }

            @Override
            public InputStream getInputStream() {
                return new ByteArrayInputStream(content) {
                    @Override
                    public void close() throws IOException {
                        closedCount.incrementAndGet();
                        super.close();
                    }
                };
            }
        };
    }
}
