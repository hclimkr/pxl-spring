package io.github.hclimkr.pxl.spring.component;

import io.github.hclimkr.pxl.exception.PxlArgumentException;
import io.github.hclimkr.pxl.exception.PxlException;
import io.github.hclimkr.pxl.exception.PxlIOException;
import io.github.hclimkr.pxl.exception.PxlNullPointerException;
import io.github.hclimkr.pxl.option.PxlImportWorkbookOption;
import io.github.hclimkr.pxl.spring.PxlSpring;
import io.github.hclimkr.pxl.spring.tcdata.TestMultiSheetWorkbook;
import io.github.hclimkr.pxl.spring.tcdata.TestUser;
import io.github.hclimkr.pxl.spring.tcdata.TestWorkbook;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Set;

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
}
