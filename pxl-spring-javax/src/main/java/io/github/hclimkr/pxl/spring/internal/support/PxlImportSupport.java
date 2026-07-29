package io.github.hclimkr.pxl.spring.internal.support;

import io.github.hclimkr.pxl.PxlConstants;
import io.github.hclimkr.pxl.exception.PxlNullPointerException;
import org.apache.commons.io.FilenameUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.multipart.MultipartFile;

import java.util.Objects;

/**
 * Shared multipart-upload helpers used by the import-family components.
 *
 * <p>Uploads are accepted or rejected on their file-name extension alone; content-type checks are
 * deliberately not applied, because browsers and OS registries report wildly inconsistent MIME types for
 * spreadsheet and CSV files.</p>
 *
 * <p>A {@code null} upload is rejected here with {@link PxlNullPointerException} rather than being left to
 * blow up as a raw {@code NullPointerException} further in. The components' {@code @NotNull} bean validation
 * only fires when a call goes through the Spring proxy, so a component built plainly
 * ({@code new PxlExcelImporter()}, which is what {@code new PxlSpring()} does) would otherwise break the
 * library's "every failure is a {@code PxlException}" contract.</p>
 *
 * <p>Like {@link PxlExportSupport} this is intended to be internal, but its callers sit in a different
 * package ({@code io.github.hclimkr.pxl.spring.component}) and there is no JPMS {@code module-info} to hide
 * it, so the class and its helpers must be — and are — declared {@code public}. Treat it as internal despite
 * the {@code public} modifier.</p>
 */
public final class PxlImportSupport {

    private PxlImportSupport() {
        throw new AssertionError("no instances of this class");
    }

    /**
     * Validates that the uploaded file is present and has a supported Excel extension
     * ({@code .xls}/{@code .xlsx}).
     *
     * @param excelFile the file to check
     * @throws PxlNullPointerException            if {@code excelFile} is {@code null}
     * @throws HttpMediaTypeNotSupportedException if the extension is missing or unsupported
     */
    public static void validateExcelExtension(final MultipartFile excelFile)
            throws PxlNullPointerException, HttpMediaTypeNotSupportedException {

        validateFileExtension(excelFile, "excelFile",
                PxlConstants.FILENAME_EXTENSION_XLS, PxlConstants.FILENAME_EXTENSION_XLSX);
    }

    /**
     * Validates that the uploaded file is present and has a {@code .csv} extension.
     *
     * @param csvFile the file to check
     * @throws PxlNullPointerException            if {@code csvFile} is {@code null}
     * @throws HttpMediaTypeNotSupportedException if the extension is missing or unsupported
     */
    public static void validateCsvExtension(final MultipartFile csvFile)
            throws PxlNullPointerException, HttpMediaTypeNotSupportedException {

        validateFileExtension(csvFile, "csvFile", PxlConstants.FILENAME_EXTENSION_CSV);
    }

    /**
     * Validates that the upload is present, then checks its extension against the supported set,
     * case-insensitively.
     *
     * <p>A {@code null} original file name yields a {@code null} extension, which is reported as an empty
     * extension rather than the literal {@code "null"} — and, because that is rejected here, the callers'
     * later {@code getOriginalFilename()} use is safe.</p>
     *
     * @param multipartFile       the file to check
     * @param parameterName       the caller's parameter name, used in the {@code null} message
     * @param supportedExtensions the accepted extensions, without the leading dot
     * @throws PxlNullPointerException            if {@code multipartFile} is {@code null}
     * @throws HttpMediaTypeNotSupportedException if the extension is missing or not in the supported set
     */
    private static void validateFileExtension(final MultipartFile multipartFile,
                                              final String parameterName,
                                              final String... supportedExtensions)
            throws PxlNullPointerException, HttpMediaTypeNotSupportedException {

        PxlArgumentSupport.requireNonNull(multipartFile, parameterName);

        final String fileExtension = FilenameUtils.getExtension(multipartFile.getOriginalFilename());

        if (StringUtils.isNotBlank(fileExtension)) {
            for (final String supportedExtension : supportedExtensions) {
                if (supportedExtension.equalsIgnoreCase(fileExtension)) {
                    return;
                }
            }
        }

        throw new HttpMediaTypeNotSupportedException(
                "File extension '" + (Objects.nonNull(fileExtension) ? fileExtension : "") + "' not supported");
    }

}
