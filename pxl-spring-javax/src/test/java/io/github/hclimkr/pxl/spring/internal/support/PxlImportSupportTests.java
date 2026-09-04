package io.github.hclimkr.pxl.spring.internal.support;

import io.github.hclimkr.pxl.exception.PxlNullPointerException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.multipart.MultipartFile;

import java.lang.reflect.Constructor;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.*;

/**
 * Tests for the {@link PxlImportSupport} utility class itself. Its extension checks are covered indirectly
 * by the importer component tests; this pins down the class's own contract: it is a non-instantiable
 * static-helper holder whose private constructor rejects reflective instantiation, and its extension
 * validators accept every supported extension case-insensitively while rejecting everything else.
 *
 * <p>Both source forms are swept, because they read the file name off different methods -
 * {@code MultipartFile.getOriginalFilename()} and {@code Resource.getFilename()} - and only the second can
 * legitimately be {@code null} on a perfectly usable source. That case is rejected on purpose; see
 * {@code namelessResource_isRejected}.</p>
 */
class PxlImportSupportTests {

    private static MultipartFile file(final String filename) {
        return new MockMultipartFile("file", filename, null, "x".getBytes(StandardCharsets.UTF_8));
    }

    /**
     * A resource that reports the given file name. {@link ByteArrayResource} itself reports none, so the
     * name has to be supplied by an override - which is also what a caller holding bare bytes has to do.
     */
    private static Resource resource(final String filename) {
        return new ByteArrayResource("x".getBytes(StandardCharsets.UTF_8)) {
            @Override
            public String getFilename() {
                return filename;
            }
        };
    }

    /**
     * An upload whose original file name really is {@code null}.
     *
     * <p>{@link MockMultipartFile} normalizes a {@code null} constructor argument to {@code ""}, so passing
     * {@code null} to {@link #file(String)} yields a blank extension rather than a {@code null} one. Only an
     * override reaches the null-extension branch, which is what a servlet container can hand over for a part
     * with no {@code filename} in its {@code Content-Disposition}.</p>
     */
    private static MultipartFile fileWithNullOriginalFilename() {
        return new MockMultipartFile("file", "placeholder", null, "x".getBytes(StandardCharsets.UTF_8)) {
            @Override
            public String getOriginalFilename() {
                return null;
            }
        };
    }

    @Test
    void privateConstructor_rejectsInstantiation() throws NoSuchMethodException {
        final Constructor<PxlImportSupport> constructor = PxlImportSupport.class.getDeclaredConstructor();
        assertThat(constructor.isAccessible()).isFalse();
        constructor.setAccessible(true);

        // reflective newInstance wraps the constructor's AssertionError in InvocationTargetException
        assertThatThrownBy(constructor::newInstance)
                .cause()
                .isInstanceOf(AssertionError.class);
    }

    @Test
    void validateExcelExtension_acceptsBothSupportedExtensions_caseInsensitively() {
        assertThatCode(() -> {
            PxlImportSupport.validateExcelExtension(file("book.xls"));
            PxlImportSupport.validateExcelExtension(file("book.xlsx"));
            PxlImportSupport.validateExcelExtension(file("book.XLSX"));
        }).doesNotThrowAnyException();
    }

    @Test
    void validateExcelExtension_rejectsOtherExtensions() {
        assertThatThrownBy(() -> PxlImportSupport.validateExcelExtension(file("book.csv")))
                .isInstanceOf(HttpMediaTypeNotSupportedException.class);
    }

    @Test
    void validateCsvExtension_acceptsCsvCaseInsensitively() {
        assertThatCode(() -> {
            PxlImportSupport.validateCsvExtension(file("rows.csv"));
            PxlImportSupport.validateCsvExtension(file("rows.CSV"));
        }).doesNotThrowAnyException();
    }

    @Test
    void validateCsvExtension_rejectsOtherExtensions() {
        assertThatThrownBy(() -> PxlImportSupport.validateCsvExtension(file("rows.xlsx")))
                .isInstanceOf(HttpMediaTypeNotSupportedException.class);
    }

    @Test
    void blankExtension_isRejected() {
        // a name with no dot yields an empty extension, and MockMultipartFile turns a null file name into
        // an empty one - both take the blank-extension branch
        assertThatThrownBy(() -> PxlImportSupport.validateExcelExtension(file(null)))
                .isInstanceOf(HttpMediaTypeNotSupportedException.class);

        assertThatThrownBy(() -> PxlImportSupport.validateCsvExtension(file("noextension")))
                .isInstanceOf(HttpMediaTypeNotSupportedException.class);
    }

    @Test
    void nullOriginalFilename_isRejectedWithoutDereferencingNull() {
        // the genuinely-null extension path: the message renders it as empty rather than the literal "null"
        assertThatThrownBy(() -> PxlImportSupport.validateExcelExtension(fileWithNullOriginalFilename()))
                .isInstanceOf(HttpMediaTypeNotSupportedException.class);

        assertThatThrownBy(() -> PxlImportSupport.validateCsvExtension(fileWithNullOriginalFilename()))
                .isInstanceOf(HttpMediaTypeNotSupportedException.class);
    }

    @Test
    void nullUpload_isRejectedAsPxlNullPointerNotRawNpe() {
        // @NotNull on the components only fires through the Spring proxy, so the guard here is what keeps a
        // plainly built component inside the "every failure is a PxlException" contract
        assertThatThrownBy(() -> PxlImportSupport.validateExcelExtension((MultipartFile) null))
                .isInstanceOf(PxlNullPointerException.class)
                .hasMessageContaining("excelFile");

        assertThatThrownBy(() -> PxlImportSupport.validateCsvExtension((MultipartFile) null))
                .isInstanceOf(PxlNullPointerException.class)
                .hasMessageContaining("csvFile");
    }

    // ----- the same contract for the Resource source form -----

    @Test
    void validateExcelExtension_onResource_acceptsBothSupportedExtensions_caseInsensitively() {
        assertThatCode(() -> {
            PxlImportSupport.validateExcelExtension(resource("book.xls"));
            PxlImportSupport.validateExcelExtension(resource("book.xlsx"));
            PxlImportSupport.validateExcelExtension(resource("book.XLSX"));
        }).doesNotThrowAnyException();
    }

    @Test
    void validateExcelExtension_onResource_rejectsOtherExtensions() {
        assertThatThrownBy(() -> PxlImportSupport.validateExcelExtension(resource("book.csv")))
                .isInstanceOf(HttpMediaTypeNotSupportedException.class);
    }

    @Test
    void validateCsvExtension_onResource_acceptsCsvCaseInsensitively() {
        assertThatCode(() -> {
            PxlImportSupport.validateCsvExtension(resource("rows.csv"));
            PxlImportSupport.validateCsvExtension(resource("rows.CSV"));
        }).doesNotThrowAnyException();
    }

    @Test
    void validateCsvExtension_onResource_rejectsOtherExtensions() {
        assertThatThrownBy(() -> PxlImportSupport.validateCsvExtension(resource("rows.xlsx")))
                .isInstanceOf(HttpMediaTypeNotSupportedException.class);
    }

    @Test
    void namelessResource_isRejected() {
        // A plain ByteArrayResource reports no file name at all - unlike a validated upload, which always has
        // one. The extension cannot be read, so it cannot be checked, and the source is refused rather than
        // let through unchecked. This is also what makes the importers' name derivation safe: past this
        // check, getFilename() is known to be non-blank.
        final Resource nameless = new ByteArrayResource("x".getBytes(StandardCharsets.UTF_8));

        assertThat(nameless.getFilename()).isNull();

        assertThatThrownBy(() -> PxlImportSupport.validateExcelExtension(nameless))
                .isInstanceOf(HttpMediaTypeNotSupportedException.class);
        assertThatThrownBy(() -> PxlImportSupport.validateCsvExtension(nameless))
                .isInstanceOf(HttpMediaTypeNotSupportedException.class);
    }

    @Test
    void resourceWithNoExtension_isRejected() {
        assertThatThrownBy(() -> PxlImportSupport.validateExcelExtension(resource("noextension")))
                .isInstanceOf(HttpMediaTypeNotSupportedException.class);
        assertThatThrownBy(() -> PxlImportSupport.validateCsvExtension(resource("noextension")))
                .isInstanceOf(HttpMediaTypeNotSupportedException.class);
    }

    @Test
    void nullResource_isRejectedAsPxlNullPointerNotRawNpe() {
        assertThatThrownBy(() -> PxlImportSupport.validateExcelExtension((Resource) null))
                .isInstanceOf(PxlNullPointerException.class)
                .hasMessageContaining("excelResource");

        assertThatThrownBy(() -> PxlImportSupport.validateCsvExtension((Resource) null))
                .isInstanceOf(PxlNullPointerException.class)
                .hasMessageContaining("csvResource");
    }

    @Test
    void fileNameCarryingANulCharacter_isRejectedAsUnsupportedMediaTypeNotRawIllegalArgument() {
        // FilenameUtils refuses a name holding a NUL outright, on every platform, and the refusal is an
        // unchecked IllegalArgumentException. It used to escape: getExtension has no such check, so the name
        // passed validation here and blew up afterwards in the importers' own getBaseName - a 500 where this
        // class's answer is a 415, and outside the "every failure is a PxlException" contract either way.
        final String nul = "report\u0000.xlsx";

        assertThatThrownBy(() -> PxlImportSupport.validateExcelExtension(file(nul)))
                .isInstanceOf(HttpMediaTypeNotSupportedException.class);
        assertThatThrownBy(() -> PxlImportSupport.validateExcelExtension(resource(nul)))
                .isInstanceOf(HttpMediaTypeNotSupportedException.class);

        assertThatThrownBy(() -> PxlImportSupport.validateCsvExtension(file("rows\u0000.csv")))
                .isInstanceOf(HttpMediaTypeNotSupportedException.class);
        assertThatThrownBy(() -> PxlImportSupport.validateCsvExtension(resource("rows\u0000.csv")))
                .isInstanceOf(HttpMediaTypeNotSupportedException.class);
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    void fileNameCarryingAnAdsSeparator_isRejectedAsUnsupportedMediaTypeOnWindows() {
        // Windows only, mirroring commons-io's own isSystemWindows() gate: there a ':' after the last
        // separator reads as an NTFS alternate-data-stream identifier and FilenameUtils refuses the name.
        // Elsewhere the same upload is an ordinary file name and is accepted, which is why this is gated
        // rather than asserted both ways - the platform's rule, not this library's.
        assertThatThrownBy(() -> PxlImportSupport.validateExcelExtension(file("report:1.xlsx")))
                .isInstanceOf(HttpMediaTypeNotSupportedException.class);
        assertThatThrownBy(() -> PxlImportSupport.validateExcelExtension(resource("report:1.xlsx")))
                .isInstanceOf(HttpMediaTypeNotSupportedException.class);

        assertThatThrownBy(() -> PxlImportSupport.validateCsvExtension(file("rows:1.csv")))
                .isInstanceOf(HttpMediaTypeNotSupportedException.class);
        assertThatThrownBy(() -> PxlImportSupport.validateCsvExtension(resource("rows:1.csv")))
                .isInstanceOf(HttpMediaTypeNotSupportedException.class);
    }

    @Test
    void fullWindowsPathAsFileName_isStillAccepted() {
        // The colon that matters is the one after the last separator. A browser that sends the whole local
        // path instead of the bare name - which some do - puts one before it, and that must keep working:
        // the drive letter is not an ADS identifier, and the guard has to leave it alone.
        assertThatCode(() -> PxlImportSupport.validateExcelExtension(file("C:\\Users\\someone\\report.xlsx")))
                .doesNotThrowAnyException();
        assertThatCode(() -> PxlImportSupport.validateCsvExtension(resource("C:\\Users\\someone\\rows.csv")))
                .doesNotThrowAnyException();
    }
}
