package io.github.hclimkr.pxl.spring.internal.support;

import io.github.hclimkr.pxl.PxlConstants;
import io.github.hclimkr.pxl.exception.PxlNullPointerException;
import org.apache.commons.io.FilenameUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.core.io.Resource;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.multipart.MultipartFile;

import java.util.Objects;

/**
 * Shared source-validation helpers used by the import-family components.
 *
 * <p>Sources are accepted or rejected on their file-name extension alone; content-type checks are
 * deliberately not applied, because browsers and OS registries report wildly inconsistent MIME types for
 * spreadsheet and CSV files.</p>
 *
 * <p>Both import source forms are covered - a multipart upload ({@link MultipartFile}) and a Spring
 * {@link Resource} - and they differ in one way that matters here. A validated upload always has a file
 * name, because the extension check itself is what proves it. A {@code Resource} need not: several
 * implementations ({@code ByteArrayResource} and {@code InputStreamResource} among them) return
 * {@code null} from {@link Resource#getFilename()}. Such a source is <strong>rejected</strong>, on the
 * same {@link HttpMediaTypeNotSupportedException} as an unsupported extension: an extension that cannot be
 * read cannot be checked, and letting it through would quietly drop the guarantee every other path
 * enforces. Callers that hold nameless bytes should wrap them in a resource that reports a name.</p>
 *
 * <p>A {@code null} source is rejected here with {@link PxlNullPointerException} rather than being left to
 * blow up as a raw {@code NullPointerException} further in. The components' {@code @NotNull} bean validation
 * only fires when a call goes through the Spring proxy, so a component built plainly
 * ({@code new PxlExcelImporter()}, which is what {@code new PxlSpring()} does) would otherwise break the
 * library's "every failure is a {@code PxlException}" contract.</p>
 *
 * <p>Like {@link PxlExportSupport} this is intended to be internal, but its callers sit in a different
 * package ({@code io.github.hclimkr.pxl.spring.component}) and there is no JPMS {@code module-info} to hide
 * it, so the class and its helpers must be - and are - declared {@code public}. Treat it as internal despite
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

        PxlArgumentSupport.requireNonNull(excelFile, "excelFile");

        validateFileExtension(excelFile.getOriginalFilename(),
                PxlConstants.FILENAME_EXTENSION_XLS, PxlConstants.FILENAME_EXTENSION_XLSX);
    }

    /**
     * Validates that the resource is present and has a supported Excel extension
     * ({@code .xls}/{@code .xlsx}).
     *
     * @param excelFile the resource to check
     * @throws PxlNullPointerException            if {@code excelFile} is {@code null}
     * @throws HttpMediaTypeNotSupportedException if the resource reports no file name, or its extension is
     *                                            unsupported
     */
    public static void validateExcelExtension(final Resource excelFile)
            throws PxlNullPointerException, HttpMediaTypeNotSupportedException {

        PxlArgumentSupport.requireNonNull(excelFile, "excelFile");

        validateFileExtension(excelFile.getFilename(),
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

        PxlArgumentSupport.requireNonNull(csvFile, "csvFile");

        validateFileExtension(csvFile.getOriginalFilename(), PxlConstants.FILENAME_EXTENSION_CSV);
    }

    /**
     * Validates that the resource is present and has a {@code .csv} extension.
     *
     * @param csvFile the resource to check
     * @throws PxlNullPointerException            if {@code csvFile} is {@code null}
     * @throws HttpMediaTypeNotSupportedException if the resource reports no file name, or its extension is
     *                                            not {@code .csv}
     */
    public static void validateCsvExtension(final Resource csvFile)
            throws PxlNullPointerException, HttpMediaTypeNotSupportedException {

        PxlArgumentSupport.requireNonNull(csvFile, "csvFile");

        validateFileExtension(csvFile.getFilename(), PxlConstants.FILENAME_EXTENSION_CSV);
    }

    /**
     * Checks a source's file name extension against the supported set, case-insensitively.
     *
     * <p>A {@code null} file name yields a {@code null} extension, which is reported as an empty extension
     * rather than the literal {@code "null"} - and, because that is rejected here, the callers' later use of
     * the same name is safe. That is what lets both importers derive a workbook or sheet name from the file
     * name without a further {@code null} check.</p>
     *
     * @param filename            the source's file name, or {@code null} if it reports none
     * @param supportedExtensions the accepted extensions, without the leading dot
     * @throws HttpMediaTypeNotSupportedException if the extension is missing or not in the supported set
     */
    private static void validateFileExtension(final String filename,
                                              final String... supportedExtensions)
            throws HttpMediaTypeNotSupportedException {

        final String fileExtension = FilenameUtils.getExtension(filename);

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
