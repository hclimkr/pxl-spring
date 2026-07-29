package io.github.hclimkr.pxl.spring.internal.support;

import io.github.hclimkr.pxl.exception.PxlNullPointerException;
import org.junit.jupiter.api.Test;
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
 */
class PxlImportSupportTests {

    private static MultipartFile file(final String filename) {
        return new MockMultipartFile("file", filename, null, "x".getBytes(StandardCharsets.UTF_8));
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
        // an empty one — both take the blank-extension branch
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
        assertThatThrownBy(() -> PxlImportSupport.validateExcelExtension(null))
                .isInstanceOf(PxlNullPointerException.class)
                .hasMessageContaining("excelFile");

        assertThatThrownBy(() -> PxlImportSupport.validateCsvExtension(null))
                .isInstanceOf(PxlNullPointerException.class)
                .hasMessageContaining("csvFile");
    }
}
