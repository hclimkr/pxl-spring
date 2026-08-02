package io.github.hclimkr.pxl.spring.component;

import io.github.hclimkr.pxl.PxlFileFormat;
import io.github.hclimkr.pxl.exception.PxlArgumentException;
import io.github.hclimkr.pxl.exception.PxlException;
import io.github.hclimkr.pxl.exception.PxlIOException;
import io.github.hclimkr.pxl.exception.PxlNullPointerException;
import io.github.hclimkr.pxl.option.PxlExportWorkbookOption;
import io.github.hclimkr.pxl.option.PxlImportWorkbookOption;
import io.github.hclimkr.pxl.spring.PxlSpring;
import io.github.hclimkr.pxl.spring.tcdata.TestMultiSheetWorkbook;
import io.github.hclimkr.pxl.spring.tcdata.TestPaths;
import io.github.hclimkr.pxl.spring.tcdata.TestUser;
import io.github.hclimkr.pxl.spring.tcdata.TestWorkbook;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.multipart.MultipartFile;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static io.github.hclimkr.pxl.spring.component.PxlExcelExporterTests.admins;
import static io.github.hclimkr.pxl.spring.component.PxlExcelExporterTests.users;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Behavioural tests for {@link PxlExcelImporter}, all driven through the {@link PxlExcelImporter.Builder}
 * fluent API: Excel round trips for the workbook form and all four {@code sheet(...)} overloads, List/Set
 * collection types, candidate-name resolution, workbook-name derivation, import options on both the builder
 * and the source step, and file-extension validation.
 *
 * <p>The builder comes from {@link PxlSpring}, the entry point the documentation guides users to. The
 * facade hands back this component's own builder, so what is exercised here is still the component.</p>
 */
class PxlExcelImporterTests {

    private final PxlSpring pxlSpring = new PxlSpring();

    private byte[] sheetXlsx(final String sheetName) throws PxlException {
        final ByteArrayOutputStream baos = new ByteArrayOutputStream();
        pxlSpring.exportExcel().sheet(TestUser.class, users(), sheetName).toStream(baos);
        return baos.toByteArray();
    }

    private byte[] workbookXlsx() throws PxlException {
        final TestWorkbook workbook = new TestWorkbook();
        workbook.setWorkbookName("WB");
        workbook.setUsers(users());

        final ByteArrayOutputStream baos = new ByteArrayOutputStream();
        pxlSpring.exportExcel().workbook(workbook).toStream(baos);
        return baos.toByteArray();
    }

    private static MockMultipartFile file(final String filename, final byte[] content) {
        return new MockMultipartFile("file", filename, null, content);
    }

    /**
     * An Excel upload whose {@link MultipartFile#getInputStream()} fails — used to drive the {@code IOException}
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
     * nameless resource is refused, so the name has to be supplied by an override.
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
     * An Excel resource whose {@link Resource#getInputStream()} fails — the {@code IOException} →
     * {@link PxlIOException} translation path for the resource source form. Its extension still validates, so
     * the failure lands on the stream read rather than the extension check.
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
    void importExcelSingleSheetAsList_roundTrips() throws PxlException, HttpMediaTypeNotSupportedException {
        // the row-class sheet(...) form is typed List<TestUser> end-to-end — no cast at the call site
        final List<TestUser> result = pxlSpring.importExcel()
                .sheet(TestUser.class, "Users")
                .fromMultipartFile(file("users.xlsx", sheetXlsx("Users")));

        assertThat(result).extracting(TestUser::getName).containsExactly("Alice", "Bob");
        assertThat(result).extracting(TestUser::getAge).containsExactly(30, 25);
    }

    @Test
    void importExcelSingleSheetAsList_withCandidateNameList_roundTrips() throws PxlException, HttpMediaTypeNotSupportedException {
        // the List<String> candidate-name overload keeps the old call shape working
        final List<TestUser> result = pxlSpring.importExcel()
                .sheet(TestUser.class, Collections.singletonList("Users"))
                .fromMultipartFile(file("users.xlsx", sheetXlsx("Users")));

        assertThat(result).extracting(TestUser::getName).containsExactly("Alice", "Bob");
    }

    @Test
    void importExcelSingleSheetAsSet_returnsSet() throws PxlException, HttpMediaTypeNotSupportedException {
        @SuppressWarnings("unchecked") final Set<TestUser> result =
                (Set<TestUser>) pxlSpring.importExcel()
                        .sheet(TestUser.class, Set.class, "Users")
                        .fromMultipartFile(file("users.xlsx", sheetXlsx("Users")));

        assertThat(result).hasSize(2);
        assertThat(result).extracting(TestUser::getName).containsExactlyInAnyOrder("Alice", "Bob");
    }

    @Test
    void importExcelSingleSheetAsList_withCollectionClassAndNameList_roundTrips() throws PxlException, HttpMediaTypeNotSupportedException {
        @SuppressWarnings("unchecked") final List<TestUser> result =
                (List<TestUser>) pxlSpring.importExcel()
                        .sheet(TestUser.class, List.class, Collections.singletonList("Users"))
                        .fromMultipartFile(file("users.xlsx", sheetXlsx("Users")));

        assertThat(result).extracting(TestUser::getName).containsExactly("Alice", "Bob");
    }

    @Test
    void importExcelSheet_resolvesLaterCandidateName() throws PxlException, HttpMediaTypeNotSupportedException {
        final List<TestUser> result = pxlSpring.importExcel()
                .sheet(TestUser.class, "Missing", "Real")
                .fromMultipartFile(file("users.xlsx", sheetXlsx("Real")));

        assertThat(result).extracting(TestUser::getName).containsExactly("Alice", "Bob");
    }

    @Test
    void importExcelWorkbook_roundTrips() throws PxlException, HttpMediaTypeNotSupportedException {
        // the workbook(...) form is typed TestWorkbook end-to-end — no cast at the call site
        final TestWorkbook back = pxlSpring.importExcel()
                .workbook(TestWorkbook.class)
                .fromMultipartFile(file("wb.xlsx", workbookXlsx()));

        assertThat(back.getUsers()).extracting(TestUser::getName).containsExactly("Alice", "Bob");
    }

    @Test
    void importExcelWorkbook_blankName_derivesNameFromFilename() throws PxlException, HttpMediaTypeNotSupportedException {
        final TestWorkbook back = pxlSpring.importExcel()
                .workbook(TestWorkbook.class)
                .fromMultipartFile(file("wb.xlsx", workbookXlsx()));

        assertThat(back.getWorkbookName()).isEqualTo("wb");
    }

    @Test
    void importExcelWorkbook_withExplicitNameOnBuilder_skipsFilenameDerivation() throws PxlException, HttpMediaTypeNotSupportedException {
        // a non-blank workbookName set before the parse target skips the filename-derivation branch
        final TestWorkbook back = pxlSpring.importExcel()
                .workbookName("Explicit")
                .workbook(TestWorkbook.class)
                .fromMultipartFile(file("wb.xlsx", workbookXlsx()));

        assertThat(back.getWorkbookName()).isEqualTo("Explicit");
        assertThat(back.getUsers()).extracting(TestUser::getName).containsExactly("Alice", "Bob");
    }

    @Test
    void importExcelWorkbook_withExplicitNameOnSource_skipsFilenameDerivation() throws PxlException, HttpMediaTypeNotSupportedException {
        // the same setter is available after the parse target; the value set last wins
        final TestWorkbook back = pxlSpring.importExcel()
                .workbookName("Ignored")
                .workbook(TestWorkbook.class)
                .workbookName("Explicit")
                .fromMultipartFile(file("wb.xlsx", workbookXlsx()));

        assertThat(back.getWorkbookName()).isEqualTo("Explicit");
    }

    @Test
    void importExcelUnsupportedExtension_throwsHttpMediaTypeNotSupported() {
        assertThatThrownBy(() -> pxlSpring.importExcel()
                .workbook(TestWorkbook.class)
                .fromMultipartFile(file("users.txt", sheetXlsx("Users"))))
                .isInstanceOf(HttpMediaTypeNotSupportedException.class);
    }

    @Test
    void importExcelXlsExtension_roundTrips() throws PxlException, HttpMediaTypeNotSupportedException {
        // an .xls (HSSF) upload exercises the valid-.xls half of the extension check (the other tests use .xlsx)
        final PxlExportWorkbookOption hssfOption = PxlExportWorkbookOption.builder()
                .exportFileFormat(PxlFileFormat.HSSF)
                .build();
        final ByteArrayOutputStream baos = new ByteArrayOutputStream();
        pxlSpring.exportExcel().sheet(TestUser.class, users(), "Users").override(hssfOption).toStream(baos);

        final List<TestUser> result = pxlSpring.importExcel()
                .sheet(TestUser.class, "Users")
                .fromMultipartFile(file("users.xls", baos.toByteArray()));

        assertThat(result).extracting(TestUser::getName).containsExactly("Alice", "Bob");
    }

    @Test
    void importExcelNullFilename_throwsHttpMediaTypeNotSupported() {
        // MockMultipartFile turns a null file name into an empty one, so this exercises the blank-extension
        // branch (the genuinely-null path is covered in PxlImportSupportTests)
        assertThatThrownBy(() -> pxlSpring.importExcel()
                .workbook(TestWorkbook.class)
                .fromMultipartFile(file(null, new byte[]{1})))
                .isInstanceOf(HttpMediaTypeNotSupportedException.class);
    }

    @Test
    void importExcelWorkbook_whenFileReadFails_throwsPxlIOException() {
        assertThatThrownBy(() -> pxlSpring.importExcel()
                .workbook(TestWorkbook.class)
                .fromMultipartFile(throwingFile("wb.xlsx")))
                .isInstanceOf(PxlIOException.class);
    }

    @Test
    void importExcelSheet_whenFileReadFails_throwsPxlIOException() {
        assertThatThrownBy(() -> pxlSpring.importExcel()
                .sheet(TestUser.class, "Users")
                .fromMultipartFile(throwingFile("users.xlsx")))
                .isInstanceOf(PxlIOException.class);
    }

    // ----- builder parse-target guards (delegated to the core builder) -----

    @Test
    void nullWorkbookClass_throwsPxlNullPointer() {
        assertThatThrownBy(() -> pxlSpring.importExcel().workbook(null))
                .isInstanceOf(PxlNullPointerException.class);
    }

    @Test
    void emptyCandidateSheetNames_throwsPxlArgument() {
        assertThatThrownBy(() -> pxlSpring.importExcel().sheet(TestUser.class, Collections.emptyList()))
                .isInstanceOf(PxlArgumentException.class);
    }

    @Test
    void nullUploadOnPlainComponent_throwsPxlNullPointerNotRawNpe() {
        // this component is a plain instance, so @NotNull never fires (no Spring proxy). Without the guard in
        // PxlImportSupport the upload would be dereferenced and raise a raw NullPointerException, outside the
        // library's exception contract. Through a proxy the same call raises ConstraintViolationException -
        // that half is pinned by PxlValidationTests.
        assertThatThrownBy(() -> pxlSpring.importExcel()
                .workbook(TestWorkbook.class)
                .fromMultipartFile(null))
                .isInstanceOf(PxlNullPointerException.class);
    }

    // ----- candidate-name resolution across all four sheet(...) overloads -----
    // The builder mirrors the core's four sheet(...) shapes (row class or collection class, varargs or
    // List). Each resolves candidate names through its own overload chain, so each is swept here.

    /**
     * Picks a parse target on the given builder. Not {@link java.util.function.Function} because the
     * {@code sheet(...)} overloads declare checked pxl exceptions.
     */
    @FunctionalInterface
    interface SheetTarget {
        PxlExcelImporter.Builder.Source<?> apply(PxlExcelImporter.Builder builder) throws PxlException;
    }

    /**
     * The four {@code sheet(...)} overloads, applied to an upload whose only sheet is named {@code Real}
     * and reached through a candidate list whose first entry does not match.
     */
    static Stream<Arguments> sheetOverloads() {
        return Stream.of(
                Arguments.of("rowClass, String...",
                        (SheetTarget) b -> b.sheet(TestUser.class, "Missing", "Real")),
                Arguments.of("rowClass, List<String>",
                        (SheetTarget) b -> b.sheet(TestUser.class, Arrays.asList("Missing", "Real"))),
                Arguments.of("collectionClass, rowClass, String...",
                        (SheetTarget) b -> b.sheet(TestUser.class, List.class, "Missing", "Real")),
                Arguments.of("collectionClass, rowClass, List<String>",
                        (SheetTarget) b -> b.sheet(TestUser.class, List.class, Arrays.asList("Missing", "Real"))));
    }

    @ParameterizedTest(name = "sheet({0})")
    @MethodSource("sheetOverloads")
    void everySheetOverload_resolvesLaterCandidateName(final String label, final SheetTarget target)
            throws PxlException, HttpMediaTypeNotSupportedException {

        final Object result = target.apply(pxlSpring.importExcel())
                .fromMultipartFile(file("users.xlsx", sheetXlsx("Real")));

        assertThat((Collection<?>) result).extracting("name").containsExactly("Alice", "Bob");
    }

    @Test
    void collectionClassOverload_withSetAndCandidateNameList_returnsSet() throws PxlException, HttpMediaTypeNotSupportedException {
        @SuppressWarnings("unchecked") final Set<TestUser> result =
                (Set<TestUser>) pxlSpring.importExcel()
                        .sheet(TestUser.class, Set.class, Collections.singletonList("Users"))
                        .fromMultipartFile(file("users.xlsx", sheetXlsx("Users")));

        assertThat(result).extracting(TestUser::getName).containsExactlyInAnyOrder("Alice", "Bob");
    }

    @Test
    void noCandidateNameMatches_yieldsNoRows() throws PxlException {
        // Depending on whether the sheet is treated as required, pxl either rejects the upload outright or
        // returns nothing; both are acceptable, binding rows from an unmatched sheet is not.
        final byte[] xlsx = sheetXlsx("Real");

        final List<TestUser> parsed;
        try {
            parsed = pxlSpring.importExcel()
                    .sheet(TestUser.class, "Missing", "AlsoMissing")
                    .fromMultipartFile(file("users.xlsx", xlsx));
        } catch (Exception expected) {
            return;
        }

        assertThat(parsed).isEmpty();
    }

    // ----- override(...) on the builder and on the source step -----
    // The import option was previously never exercised through the Spring component at all. A password-
    // protected upload only parses when the option reaches the core, so it proves the wiring on both steps.

    private byte[] encryptedSheetXlsx() throws PxlException {
        final PxlExportWorkbookOption exportOption = PxlExportWorkbookOption.builder()
                .exportPassword("secret")
                .build();

        final ByteArrayOutputStream baos = new ByteArrayOutputStream();
        pxlSpring.exportExcel()
                .sheet(TestUser.class, users(), "Users")
                .override(exportOption)
                .toStream(baos);
        return baos.toByteArray();
    }

    @Test
    void overrideOnBuilder_appliesImportOption() throws PxlException, HttpMediaTypeNotSupportedException {
        final PxlImportWorkbookOption option = PxlImportWorkbookOption.builder()
                .importPassword("secret")
                .build();

        final List<TestUser> result = pxlSpring.importExcel()
                .override(option)
                .sheet(TestUser.class, "Users")
                .fromMultipartFile(file("users.xlsx", encryptedSheetXlsx()));

        assertThat(result).extracting(TestUser::getName).containsExactly("Alice", "Bob");
    }

    @Test
    void overrideOnSource_appliesImportOption() throws PxlException, HttpMediaTypeNotSupportedException {
        // the same setter is available after the parse target; the value set last wins
        final PxlImportWorkbookOption option = PxlImportWorkbookOption.builder()
                .importPassword("secret")
                .build();

        final List<TestUser> result = pxlSpring.importExcel()
                .sheet(TestUser.class, "Users")
                .override(option)
                .fromMultipartFile(file("users.xlsx", encryptedSheetXlsx()));

        assertThat(result).extracting(TestUser::getName).containsExactly("Alice", "Bob");
    }

    @Test
    void overrideOnBuilderAndSource_lastValueWins() throws PxlException, HttpMediaTypeNotSupportedException {
        // both steps write the same slot, so the wrong password set on the builder is replaced by the one the
        // source step sets afterwards
        final PxlImportWorkbookOption wrongPassword = PxlImportWorkbookOption.builder()
                .importPassword("nope")
                .build();
        final PxlImportWorkbookOption rightPassword = PxlImportWorkbookOption.builder()
                .importPassword("secret")
                .build();

        final List<TestUser> result = pxlSpring.importExcel()
                .override(wrongPassword)
                .sheet(TestUser.class, "Users")
                .override(rightPassword)
                .fromMultipartFile(file("users.xlsx", encryptedSheetXlsx()));

        assertThat(result).extracting(TestUser::getName).containsExactly("Alice", "Bob");
    }

    @Test
    void withoutImportOption_encryptedUploadFails() throws PxlException {
        // guards the tests above: without the password the very same upload must not parse
        final byte[] encrypted = encryptedSheetXlsx();

        assertThatThrownBy(() -> pxlSpring.importExcel()
                .sheet(TestUser.class, "Users")
                .fromMultipartFile(file("users.xlsx", encrypted)))
                .isInstanceOf(PxlException.class);
    }

    @Test
    void overrideOnWorkbookForm_appliesImportOption() throws PxlException, HttpMediaTypeNotSupportedException {
        final PxlExportWorkbookOption exportOption = PxlExportWorkbookOption.builder()
                .exportPassword("secret")
                .build();
        final TestWorkbook workbook = new TestWorkbook();
        workbook.setWorkbookName("WB");
        workbook.setUsers(users());

        final ByteArrayOutputStream baos = new ByteArrayOutputStream();
        pxlSpring.exportExcel().workbook(workbook).override(exportOption).toStream(baos);

        final PxlImportWorkbookOption importOption = PxlImportWorkbookOption.builder()
                .importPassword("secret")
                .build();
        final TestWorkbook back = pxlSpring.importExcel()
                .override(importOption)
                .workbook(TestWorkbook.class)
                .fromMultipartFile(file("wb.xlsx", baos.toByteArray()));

        assertThat(back.getUsers()).extracting(TestUser::getName).containsExactly("Alice", "Bob");
    }

    // ----- multi-sheet round trips -----

    @Test
    void importExcelWorkbook_withSeveralSheets_populatesEveryField() throws PxlException, HttpMediaTypeNotSupportedException {
        final TestMultiSheetWorkbook workbook = new TestMultiSheetWorkbook();
        workbook.setWorkbookName("WB");
        workbook.setUsers(users());
        workbook.setAdmins(admins());

        final ByteArrayOutputStream baos = new ByteArrayOutputStream();
        pxlSpring.exportExcel().workbook(workbook).toStream(baos);

        final TestMultiSheetWorkbook back = pxlSpring.importExcel()
                .workbook(TestMultiSheetWorkbook.class)
                .fromMultipartFile(file("wb.xlsx", baos.toByteArray()));

        assertThat(back.getUsers()).extracting(TestUser::getName).containsExactly("Alice", "Bob");
        assertThat(back.getAdmins()).extracting(TestUser::getName).containsExactly("Carol");
    }

    @Test
    void importExcelSheet_calledOncePerSheet_readsEverySheetOfOneUpload() throws PxlException, HttpMediaTypeNotSupportedException {
        // unlike the exporter's sheet(...), this one picks a parse target and hands back a Source, so it is not
        // repeatable within a single chain: several sheets means one chain - and one parse - per sheet, each
        // reading the same upload again
        final ByteArrayOutputStream baos = new ByteArrayOutputStream();
        pxlSpring.exportExcel()
                .sheet(TestUser.class, users(), "Users")
                .sheet(TestUser.class, admins(), "Admins")
                .toStream(baos);
        final MockMultipartFile upload = file("wb.xlsx", baos.toByteArray());

        final List<TestUser> parsedUsers = pxlSpring.importExcel()
                .sheet(TestUser.class, "Users")
                .fromMultipartFile(upload);
        final List<TestUser> parsedAdmins = pxlSpring.importExcel()
                .sheet(TestUser.class, "Admins")
                .fromMultipartFile(upload);

        assertThat(parsedUsers).extracting(TestUser::getName).containsExactly("Alice", "Bob");
        assertThat(parsedAdmins).extracting(TestUser::getName).containsExactly("Carol");
    }

    // ----- the Resource source form -----
    // fromResource(...) is the non-HTTP half of the same operation: the batch job, the classpath seed, the
    // test fixture. Everything after the source is opened is shared with fromMultipartFile(...), so what is
    // swept here is the part that is not: extension validation, name derivation, and stream handling.

    @Test
    void importExcelSheet_fromResource_roundTrips() throws PxlException, HttpMediaTypeNotSupportedException {
        final List<TestUser> result = pxlSpring.importExcel()
                .sheet(TestUser.class, "Users")
                .fromResource(resource("users.xlsx", sheetXlsx("Users")));

        assertThat(result).extracting(TestUser::getName).containsExactly("Alice", "Bob");
        assertThat(result).extracting(TestUser::getAge).containsExactly(30, 25);
    }

    @Test
    void importExcelWorkbook_fromResource_roundTrips() throws PxlException, HttpMediaTypeNotSupportedException {
        final TestWorkbook back = pxlSpring.importExcel()
                .workbook(TestWorkbook.class)
                .fromResource(resource("wb.xlsx", workbookXlsx()));

        assertThat(back.getUsers()).extracting(TestUser::getName).containsExactly("Alice", "Bob");
    }

    @Test
    void importExcelWorkbook_fromResource_derivesNameFromResourceFilename() throws PxlException, HttpMediaTypeNotSupportedException {
        // the same fallback as the upload form, read off Resource.getFilename() instead
        final TestWorkbook back = pxlSpring.importExcel()
                .workbook(TestWorkbook.class)
                .fromResource(resource("wb.xlsx", workbookXlsx()));

        assertThat(back.getWorkbookName()).isEqualTo("wb");
    }

    @Test
    void importExcelWorkbook_fromResource_withExplicitName_skipsFilenameDerivation() throws PxlException, HttpMediaTypeNotSupportedException {
        final TestWorkbook back = pxlSpring.importExcel()
                .workbookName("Explicit")
                .workbook(TestWorkbook.class)
                .fromResource(resource("wb.xlsx", workbookXlsx()));

        assertThat(back.getWorkbookName()).isEqualTo("Explicit");
    }

    @Test
    void importExcelFromFileSystemResource_roundTrips() throws PxlException, HttpMediaTypeNotSupportedException {
        // the case the terminal exists for: a real file on disk, read back without going near a Pxl instance
        // of the caller's own. Written through the export side's own file terminal, so this is a full
        // file-to-object round trip.
        final File file = TestPaths.exportFile("importExcelFromFileSystemResource.xlsx");
        pxlSpring.exportExcel().sheet(TestUser.class, users(), "Users").toFile(file);

        final List<TestUser> result = pxlSpring.importExcel()
                .sheet(TestUser.class, "Users")
                .fromResource(new FileSystemResource(file));

        assertThat(result).extracting(TestUser::getName).containsExactly("Alice", "Bob");
    }

    @Test
    void importExcelFromResource_unsupportedExtension_throwsHttpMediaTypeNotSupported() {
        assertThatThrownBy(() -> pxlSpring.importExcel()
                .workbook(TestWorkbook.class)
                .fromResource(resource("users.txt", sheetXlsx("Users"))))
                .isInstanceOf(HttpMediaTypeNotSupportedException.class);
    }

    @Test
    void importExcelFromNamelessResource_throwsHttpMediaTypeNotSupported() throws PxlException {
        // A bare ByteArrayResource reports no file name, so its extension cannot be read - and an extension
        // that cannot be read cannot be checked. Refused rather than let through unchecked; a caller holding
        // bare bytes has to wrap them in a resource that reports a name.
        final byte[] xlsx = sheetXlsx("Users");

        assertThatThrownBy(() -> pxlSpring.importExcel()
                .sheet(TestUser.class, "Users")
                .fromResource(new ByteArrayResource(xlsx)))
                .isInstanceOf(HttpMediaTypeNotSupportedException.class);
    }

    @Test
    void importExcelFromResource_whenReadFails_throwsPxlIOException() {
        assertThatThrownBy(() -> pxlSpring.importExcel()
                .workbook(TestWorkbook.class)
                .fromResource(throwingResource("wb.xlsx")))
                .isInstanceOf(PxlIOException.class);
    }

    @Test
    void nullResourceOnPlainComponent_throwsPxlNullPointerNotRawNpe() {
        // as with the upload form: @NotNull never fires on a plain instance, so PxlImportSupport's guard is
        // what keeps this inside the library's exception contract
        assertThatThrownBy(() -> pxlSpring.importExcel()
                .workbook(TestWorkbook.class)
                .fromResource(null))
                .isInstanceOf(PxlNullPointerException.class);
    }

    @Test
    void importExcelFromResource_closesTheStreamItOpened() throws PxlException, HttpMediaTypeNotSupportedException {
        // The finally block in readInto owns every stream it opens. This matters more for resources than for
        // uploads: a FileSystemResource hands out a real file handle, and a batch job importing in a loop
        // would exhaust the descriptor table if they were left open.
        final AtomicBoolean closed = new AtomicBoolean(false);
        final byte[] xlsx = sheetXlsx("Users");

        final Resource tracked = new ByteArrayResource(xlsx) {
            @Override
            public String getFilename() {
                return "users.xlsx";
            }

            @Override
            public InputStream getInputStream() {
                return new ByteArrayInputStream(xlsx) {
                    @Override
                    public void close() throws IOException {
                        closed.set(true);
                        super.close();
                    }
                };
            }
        };

        pxlSpring.importExcel().sheet(TestUser.class, "Users").fromResource(tracked);

        assertThat(closed).as("the stream opened for the resource must be closed").isTrue();
    }

    @Test
    void importExcelFromResource_andFromUpload_produceTheSameResult() throws PxlException, HttpMediaTypeNotSupportedException {
        // Pins the claim the two back-ends rest on: past the point the source is opened they are one path
        // (readInto), so the same bytes under the same file name must parse identically either way.
        final byte[] xlsx = workbookXlsx();

        final TestWorkbook fromUpload = pxlSpring.importExcel()
                .workbook(TestWorkbook.class)
                .fromMultipartFile(file("wb.xlsx", xlsx));
        final TestWorkbook fromResource = pxlSpring.importExcel()
                .workbook(TestWorkbook.class)
                .fromResource(resource("wb.xlsx", xlsx));

        assertThat(fromResource.getWorkbookName()).isEqualTo(fromUpload.getWorkbookName());
        assertThat(fromResource.getUsers())
                .extracting(TestUser::getName)
                .containsExactlyElementsOf(
                        fromUpload.getUsers().stream().map(TestUser::getName).collect(Collectors.toList()));
    }

    @Test
    void importExcelFromResource_nfcNormalizesTheDerivedWorkbookName() throws PxlException, HttpMediaTypeNotSupportedException {
        // The name derivation was pulled into a helper shared by both source forms; this pins that the NFC
        // normalization survived the move. macOS hands out decomposed file names, so a local file and an
        // upload can disagree on the bytes while naming the same thing.
        //
        // Spelled with escapes on purpose: written literally, this source file would already hold the
        // composed form on both sides, and the test would pass whether or not anything normalized.
        final String decomposed = "\u1100\u1161";   // HANGUL CHOSEONG KIYEOK + JUNGSEONG A
        final String composed = "\uAC00";           // the same syllable, precomposed

        final TestWorkbook back = pxlSpring.importExcel()
                .workbook(TestWorkbook.class)
                .fromResource(resource(decomposed + ".xlsx", workbookXlsx()));

        assertThat(back.getWorkbookName()).isEqualTo(composed);
    }
}
