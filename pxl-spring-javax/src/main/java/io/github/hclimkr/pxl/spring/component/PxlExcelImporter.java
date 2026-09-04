package io.github.hclimkr.pxl.spring.component;

import io.github.hclimkr.pxl.Pxl;
import io.github.hclimkr.pxl.builder.PxlExcelImportBuilder;
import io.github.hclimkr.pxl.exception.PxlArgumentException;
import io.github.hclimkr.pxl.exception.PxlException;
import io.github.hclimkr.pxl.exception.PxlIOException;
import io.github.hclimkr.pxl.exception.PxlNullPointerException;
import io.github.hclimkr.pxl.option.PxlImportWorkbookOption;
import io.github.hclimkr.pxl.spring.internal.support.PxlCoreSupport;
import io.github.hclimkr.pxl.spring.internal.support.PxlImportSupport;
import io.github.hclimkr.pxl.spring.logging.PxlPerformanceLogging;
import org.apache.commons.io.FilenameUtils;
import org.apache.commons.io.IOUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.core.io.InputStreamSource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.multipart.MultipartFile;

import javax.annotation.Nullable;
import javax.validation.constraints.NotNull;
import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.text.Normalizer;
import java.util.Collection;
import java.util.List;
import java.util.Objects;

/**
 * Spring component that reads Excel sources - multipart uploads and Spring {@link Resource}s - into Java
 * objects.
 *
 * <p>Everything is configured through the fluent builder returned by {@link #importExcel()}, which mirrors
 * the core {@code Pxl.importExcel()} shape - optionally set {@code workbookName(...)}/{@code override(...)},
 * pick a parse target ({@code workbook(...)} / {@code sheet(...)}), then call
 * {@code fromMultipartFile(...)} or {@code fromResource(...)}. The result type is carried through the
 * generics, so no cast is needed:</p>
 *
 * <pre>{@code
 * ReportDto report = pxlSpring.importExcel().workbook(ReportDto.class).fromMultipartFile(upload);
 * List<User> users = pxlSpring.importExcel().sheet(User.class, "Users").fromMultipartFile(upload);
 * List<User> seed = pxlSpring.importExcel().sheet(User.class, "Users")
 *         .fromResource(new ClassPathResource("seed/users.xlsx"));
 * }</pre>
 *
 * <p>{@code fromResource(...)} is the non-HTTP half of the same operation, for the paths a Spring
 * application reads a spreadsheet on that are not an upload - a batch job reading a file off disk, an
 * initializer reading a classpath seed, an integration test reading a fixture. It exists so those callers
 * need not build their own core {@code Pxl} instance to get at a stream terminal.</p>
 *
 * <p>The file extension is validated ({@code .xls}/{@code .xlsx}); a violation raises
 * {@link HttpMediaTypeNotSupportedException}, and so does a resource that reports no file name at all,
 * since an extension that cannot be read cannot be checked - as does a name that cannot be read as a file
 * name in the first place. When a workbook name is omitted it is derived from the file name and
 * NFC-normalized.</p>
 *
 * <p>That builder is the nested {@link Builder}, and its parse-target step is {@link Builder.Source}. A
 * fluent chain never has to name either; on the rare occasion you hold one in a variable, spell them
 * {@code PxlExcelImporter.Builder} and {@code PxlExcelImporter.Builder.Source<R>}.</p>
 *
 * <p>The component is stateless and safe to share across threads; the builder it hands back is not - start one
 * per import.</p>
 *
 * <p>Reached through {@link io.github.hclimkr.pxl.spring.PxlSpring PxlSpring}: inject that one bean and call
 * {@code pxlSpring.importExcel()}, which hands back the builder documented here.</p>
 *
 * <p>The {@code importExcelFrom*} methods below are the builder's execution back-ends, one per source form.
 * They are {@code public} only because Spring AOP (and {@code @Validated} method validation) can advise public
 * methods only - a terminal has to re-enter this component through its proxy for
 * {@link PxlPerformanceLogging} to fire. Treat them as internal and always go through
 * {@link #importExcel()}.</p>
 */
@Validated
@Component
public class PxlExcelImporter {

    private static final String TAG = "PxlExcelImporter";

    /**
     * The core entry point, shared with the other components - see {@link PxlCoreSupport} for why it is not
     * one instance per component.
     */
    private final Pxl pxl = PxlCoreSupport.core();

    /**
     * This component's own AOP proxy, injected by Spring where available.
     *
     * <p>The builder's terminal must call back through the proxy, not through {@code this}: a plain
     * {@code this} reference bypasses the proxy, and with it {@link PxlPerformanceLogging} and {@code @Validated}.
     * {@code @Lazy} breaks the self-reference cycle, and {@code required = false} keeps plain
     * {@code new PxlExcelImporter()} usage (outside a Spring context) working - it then falls back to
     * {@code this} and simply produces no performance log.</p>
     */
    @Autowired(required = false)
    @Lazy
    private PxlExcelImporter self;

    /**
     * Starts a fluent Excel import, from a multipart upload or a {@link Resource}.
     *
     * @return a new builder bound to this component
     */
    public Builder importExcel() {

        return new Builder(pxl, Objects.nonNull(self) ? self : this);
    }

    // ----- builder execution back-ends (internal; reached through the nested Builder) -----

    /**
     * Reads the uploaded Excel file into whatever parse target the source step carries.
     *
     * <p>Internal: called by {@link Builder.Source#fromMultipartFile(MultipartFile)}.</p>
     *
     * <p>The {@code @NotNull} constraint only fires when the call arrives through the Spring proxy, so
     * {@link PxlImportSupport#validateExcelExtension(MultipartFile)} rejects a {@code null} upload again for
     * components built plainly (outside a Spring context). Through the proxy a violation is a
     * {@code ConstraintViolationException}; plainly it is a {@code PxlNullPointerException}.</p>
     *
     * @param source    the configured source step
     * @param excelFile the uploaded Excel file ({@code .xls}/{@code .xlsx})
     * @param <R>       the parsed result type
     * @return the parsed workbook object or row collection
     * @throws PxlException                       if {@code excelFile} is {@code null}, the source step's
     *                                            parse target is invalid, the upload cannot be read, or
     *                                            parsing fails
     * @throws HttpMediaTypeNotSupportedException if the file name cannot be read as one, or its
     *                                            extension is not a supported Excel type
     */
    @PxlPerformanceLogging(TAG)
    public <R> R importExcelFromMultipartFile(@NotNull final Builder.Source<R> source,
                                              @NotNull final MultipartFile excelFile)
            throws PxlException, HttpMediaTypeNotSupportedException {

        PxlImportSupport.validateExcelExtension(excelFile);

        // resolved only once the upload is known: a blank name falls back to the file's base name
        final String workbookName = source.resolveWorkbookName(excelFile);

        return readInto(source, workbookName, excelFile);
    }

    /**
     * Reads the given Excel resource into whatever parse target the source step carries.
     *
     * <p>Internal: called by {@link Builder.Source#fromResource(Resource)}.</p>
     *
     * <p>Guarded exactly like the multipart back-end above, and for the same reason: the {@code @NotNull}
     * constraint is only there on the proxied path, so
     * {@link PxlImportSupport#validateExcelExtension(Resource)} re-checks it. That validator also rejects a
     * resource reporting no file name, which is what makes the name derivation below safe.</p>
     *
     * @param source        the configured source step
     * @param excelResource the Excel resource ({@code .xls}/{@code .xlsx})
     * @param <R>           the parsed result type
     * @return the parsed workbook object or row collection
     * @throws PxlException                       if {@code excelResource} is {@code null}, the source step's
     *                                            parse target is invalid, the resource cannot be read, or
     *                                            parsing fails
     * @throws HttpMediaTypeNotSupportedException if the resource reports no file name or one that cannot
     *                                            be read as one, or its extension is not a supported
     *                                            Excel type
     */
    @PxlPerformanceLogging(TAG)
    public <R> R importExcelFromResource(@NotNull final Builder.Source<R> source,
                                         @NotNull final Resource excelResource)
            throws PxlException, HttpMediaTypeNotSupportedException {

        PxlImportSupport.validateExcelExtension(excelResource);

        // resolved only once the resource is known: a blank name falls back to the file's base name
        final String workbookName = source.resolveWorkbookName(excelResource);

        return readInto(source, workbookName, excelResource);
    }

    /**
     * Opens the given source and parses it into the source step's target.
     *
     * <p>Shared by both back-ends so the two cannot drift apart: an upload and a resource differ only in
     * where the stream and the file name come from, and both are {@link InputStreamSource}s, so everything
     * past that point is one path. The stream is always closed here, whether the parse succeeded or not.</p>
     *
     * @param source            the configured source step (already validated)
     * @param workbookName      the resolved workbook name
     * @param inputStreamSource the upload or resource to read
     * @param <R>               the parsed result type
     * @return the parsed workbook object or row collection
     * @throws PxlException if the source cannot be read or parsing fails
     */
    private static <R> R readInto(final Builder.Source<R> source,
                                  final String workbookName,
                                  final InputStreamSource inputStreamSource)
            throws PxlException {

        InputStream inputStream = null;
        try {
            inputStream = new BufferedInputStream(inputStreamSource.getInputStream());

            return source.coreSource
                    .workbookName(workbookName)
                    .fromStream(inputStream);
        } catch (IOException e) {
            throw new PxlIOException(e);
        } finally {
            IOUtils.closeQuietly(inputStream);
        }
    }

    /**
     * Fluent builder for Excel imports, created via {@link PxlExcelImporter#importExcel()}.
     *
     * <p>It mirrors the core {@code io.github.hclimkr.pxl.builder.PxlExcelImportBuilder} shape - optional
     * {@link #workbookName(String)}/{@link #override(PxlImportWorkbookOption)}, then a parse target
     * ({@link #workbook(Class)} or one of the {@code sheet(...)} forms) yielding a typed {@link Source}, then
     * a terminal - and swaps the core's file/stream terminals for the Spring-facing
     * {@link Source#fromMultipartFile(MultipartFile)} and {@link Source#fromResource(Resource)}.</p>
     *
     * <p>The result type is carried through the generics, so no cast is needed at the call site:</p>
     *
     * <pre>{@code
     * ReportDto report = pxlSpring.importExcel().workbook(ReportDto.class).fromMultipartFile(upload);
     * List<User> users = pxlSpring.importExcel().sheet(User.class, "Users").fromMultipartFile(upload);
     * Set<User> unique = pxlSpring.importExcel().sheet(User.class, Set.class, "Users").fromMultipartFile(upload);
     * List<User> seed = pxlSpring.importExcel().sheet(User.class, "Users")
     *         .fromResource(new ClassPathResource("seed/users.xlsx"));
     * }</pre>
     *
     * <p>The builder holds the collected arguments only; the terminal delegates straight back to the enclosing
     * component so the work still runs inside a Spring-proxied, {@code @PxlPerformanceLogging}-annotated
     * method.</p>
     *
     * <p>Nested in the component on purpose: the constructor and everything the component reads off the source
     * step are {@code private} and stay reachable only because the two are nestmates. The public surface is
     * exactly the option, parse-target and terminal methods.</p>
     *
     * <p>Not thread-safe, and single-use per terminal call.</p>
     */
    public static final class Builder {

        /**
         * The owning component; the terminal calls back into it so the import runs through its AOP proxy.
         */
        private final PxlExcelImporter importer;

        /**
         * The core import builder, used as the store for the workbook name and the import option until a parse
         * target is chosen.
         */
        private final PxlExcelImportBuilder coreBuilder;

        /**
         * Mirrors the workbook name handed to {@code coreBuilder} - the core builder exposes no getter, and
         * the terminal needs to know whether an explicit name was set before falling back to the source's file
         * name.
         */
        private String workbookName;

        /**
         * Creates a builder bound to the given core entry point and owning component.
         *
         * @param pxl      the core entry point used to create the underlying import builder
         * @param importer the component the terminal method delegates back to (its AOP proxy where available)
         */
        private Builder(final Pxl pxl, final PxlExcelImporter importer) {

            this.coreBuilder = pxl.importExcel();
            this.importer = importer;
        }

        // ----- options -----

        /**
         * Sets the workbook name written into the {@code @PxlWorkbookName} field of the workbook form.
         * (Optional)
         *
         * <p>When blank, the name is derived from the source's file name (base name, NFC-normalized and
         * trimmed) at terminal time - the upload's or the resource's, whichever the terminal was given.
         * Ignored by the {@code sheet(...)} forms, which produce no workbook object.</p>
         *
         * @param workbookName the workbook name, or {@code null}/blank for the file-name fallback
         * @return this builder
         */
        public Builder workbookName(@Nullable final String workbookName) {

            this.coreBuilder.workbookName(workbookName);
            this.workbookName = workbookName;
            return this;
        }

        /**
         * Overrides annotation-declared values with the given import option. (Optional)
         *
         * @param option the import option, or {@code null}
         * @return this builder
         */
        public Builder override(@Nullable final PxlImportWorkbookOption option) {

            this.coreBuilder.override(option);
            return this;
        }

        // ----- parse target -----

        /**
         * Parses the source into an object of the given {@code @PxlWorkbook}-annotated class.
         *
         * @param workbookClass the {@code @PxlWorkbook}-annotated target class
         * @param <W>           the workbook type
         * @return the source step returning the parsed workbook object
         * @throws PxlNullPointerException if {@code workbookClass} is {@code null}
         */
        public <W> Source<W> workbook(final Class<W> workbookClass)
                throws PxlNullPointerException {

            return new Source<>(importer, coreBuilder.workbook(workbookClass), workbookName);
        }

        /**
         * Parses one sheet, resolved from the first matching candidate sheet name, into a {@link List} of rows.
         *
         * @param rowClass            the row class each record is bound to
         * @param candidateSheetNames the candidate sheet names (the first match is used)
         * @param <T>                 the row type
         * @return the source step returning the parsed rows
         * @throws PxlNullPointerException if {@code rowClass} or {@code candidateSheetNames} is {@code null}
         * @throws PxlArgumentException    if {@code candidateSheetNames} is empty
         */
        public <T> Source<List<T>> sheet(final Class<T> rowClass,
                                         final String... candidateSheetNames)
                throws PxlNullPointerException, PxlArgumentException {

            return new Source<>(importer, coreBuilder.sheet(rowClass, candidateSheetNames), workbookName);
        }

        /**
         * Parses one sheet, resolved from the first matching candidate sheet name, into a {@link List} of rows.
         *
         * @param rowClass            the row class each record is bound to
         * @param candidateSheetNames the candidate sheet names (the first match is used)
         * @param <T>                 the row type
         * @return the source step returning the parsed rows
         * @throws PxlNullPointerException if {@code rowClass} or {@code candidateSheetNames} is {@code null}
         * @throws PxlArgumentException    if {@code candidateSheetNames} is empty
         */
        public <T> Source<List<T>> sheet(final Class<T> rowClass,
                                         final List<String> candidateSheetNames)
                throws PxlNullPointerException, PxlArgumentException {

            return new Source<>(importer, coreBuilder.sheet(rowClass, candidateSheetNames), workbookName);
        }

        /**
         * Parses one sheet, resolved from the first matching candidate sheet name, into the requested
         * collection type.
         *
         * <p>{@code C} binds from the {@code collectionClass} literal, and a literal such as {@code Set.class}
         * is a {@code Class<Set>} - the raw type - so the parsed result arrives raw too. Assigning it to a
         * parameterized variable is therefore an unchecked conversion, and the call site needs
         * {@code @SuppressWarnings("unchecked")}. The two-argument forms above have no such gap: {@code T}
         * binds to the row class there and the result is a {@code List<T>}.</p>
         *
         * @param rowClass            the row class each record is bound to
         * @param collectionClass     the collection type to return (e.g. {@code List.class}, {@code Set.class})
         * @param candidateSheetNames the candidate sheet names (the first match is used)
         * @param <C>                 the collection type to return
         * @return the source step returning the parsed rows
         * @throws PxlNullPointerException if {@code rowClass}, {@code collectionClass}, or {@code candidateSheetNames} is {@code null}
         * @throws PxlArgumentException    if {@code candidateSheetNames} is empty
         */
        public <C extends Collection<?>> Source<C> sheet(final Class<?> rowClass,
                                                         final Class<C> collectionClass,
                                                         final String... candidateSheetNames)
                throws PxlNullPointerException, PxlArgumentException {

            return new Source<>(importer, coreBuilder.sheet(rowClass, collectionClass, candidateSheetNames), workbookName);
        }

        /**
         * Parses one sheet, resolved from the first matching candidate sheet name, into the requested
         * collection type.
         *
         * <p>{@code C} binds from the {@code collectionClass} literal, and a literal such as {@code Set.class}
         * is a {@code Class<Set>} - the raw type - so the parsed result arrives raw too. Assigning it to a
         * parameterized variable is therefore an unchecked conversion, and the call site needs
         * {@code @SuppressWarnings("unchecked")}. The two-argument forms above have no such gap: {@code T}
         * binds to the row class there and the result is a {@code List<T>}.</p>
         *
         * @param rowClass            the row class each record is bound to
         * @param collectionClass     the collection type to return (e.g. {@code List.class}, {@code Set.class})
         * @param candidateSheetNames the candidate sheet names (the first match is used)
         * @param <C>                 the collection type to return
         * @return the source step returning the parsed rows
         * @throws PxlNullPointerException if {@code rowClass}, {@code collectionClass}, or {@code candidateSheetNames} is {@code null}
         * @throws PxlArgumentException    if {@code candidateSheetNames} is empty
         */
        public <C extends Collection<?>> Source<C> sheet(final Class<?> rowClass,
                                                         final Class<C> collectionClass,
                                                         final List<String> candidateSheetNames)
                throws PxlNullPointerException, PxlArgumentException {

            return new Source<>(importer, coreBuilder.sheet(rowClass, collectionClass, candidateSheetNames), workbookName);
        }

        /**
         * Terminal source step for an Excel import: holds the parse target chosen on the enclosing
         * {@link Builder} and reads it from an uploaded file or a {@link Resource}.
         *
         * <p>Obtained only from {@code workbook(...)}/{@code sheet(...)}: picking a parse target is what
         * produces this step, and its type parameter is what carries the result type through to the
         * terminal.</p>
         *
         * @param <R> the parsed result type (a workbook object or a collection of rows)
         */
        public static final class Source<R> {

            /**
             * The owning component; the terminal calls back into it so the import runs through its AOP proxy.
             */
            private final PxlExcelImporter importer;

            /**
             * The underlying core source step carrying the parse target. Read directly by
             * {@link PxlExcelImporter}, a nestmate of this class.
             */
            private final PxlExcelImportBuilder.Source<R> coreSource;

            /**
             * Mirrors the workbook name handed to {@code coreSource} - inherited from the enclosing builder and
             * replaceable here. Kept because the core exposes no getter and {@code resolveWorkbookName} has to
             * know whether an explicit name was set.
             */
            private String workbookName;

            /**
             * Wraps a core source step, inheriting the workbook name set on the enclosing builder.
             *
             * @param importer     the component the terminal method delegates back to
             * @param coreSource   the core source step carrying the parse target
             * @param workbookName the workbook name set on the enclosing builder, or {@code null}
             */
            private Source(final PxlExcelImporter importer,
                           final PxlExcelImportBuilder.Source<R> coreSource,
                           final String workbookName) {

                this.importer = importer;
                this.coreSource = coreSource;
                this.workbookName = workbookName;
            }

            /**
             * Sets the workbook name, overriding any value set on the enclosing builder. (Optional)
             *
             * @param workbookName the workbook name, or {@code null}/blank for the file-name fallback
             * @return this source step
             */
            public Source<R> workbookName(@Nullable final String workbookName) {

                this.coreSource.workbookName(workbookName);
                this.workbookName = workbookName;
                return this;
            }

            /**
             * Overrides annotation-declared values with the given import option, overriding any value set on
             * the enclosing builder. (Optional)
             *
             * @param option the import option, or {@code null}
             * @return this source step
             */
            public Source<R> override(@Nullable final PxlImportWorkbookOption option) {

                this.coreSource.override(option);
                return this;
            }

            /**
             * Reads the uploaded Excel file and returns the parsed result.
             *
             * @param excelFile the uploaded Excel file ({@code .xls}/{@code .xlsx})
             * @return the parsed workbook object or row collection
             * @throws PxlException                       if {@code excelFile} is {@code null}, the upload
             *                                            cannot be read, or parsing fails
             * @throws HttpMediaTypeNotSupportedException if the file name cannot be read as one, or its
             *                                            extension is not a supported Excel type
             */
            public R fromMultipartFile(final MultipartFile excelFile)
                    throws PxlException, HttpMediaTypeNotSupportedException {

                return importer.importExcelFromMultipartFile(this, excelFile);
            }

            /**
             * Reads the given Excel resource and returns the parsed result.
             *
             * <p>The non-HTTP counterpart of {@link #fromMultipartFile(MultipartFile)} - for a file on disk
             * ({@code FileSystemResource}), a packaged one ({@code ClassPathResource}), or anything else
             * behind Spring's {@link Resource} abstraction. Everything else behaves identically, the workbook
             * name included: it falls back to the resource's base file name.</p>
             *
             * <p>The resource must report a file name, because that is what the extension is read from; one
             * that does not (a bare {@code ByteArrayResource}, say) is rejected rather than let through
             * unchecked.</p>
             *
             * @param excelResource the Excel resource ({@code .xls}/{@code .xlsx})
             * @return the parsed workbook object or row collection
             * @throws PxlException                       if {@code excelResource} is {@code null}, the
             *                                            resource cannot be read, or parsing fails
             * @throws HttpMediaTypeNotSupportedException if the resource reports no file name or one that
             *                                            cannot be read as one, or its extension is not a
             *                                            supported Excel type
             */
            public R fromResource(final Resource excelResource)
                    throws PxlException, HttpMediaTypeNotSupportedException {

                return importer.importExcelFromResource(this, excelResource);
            }

            // ----- resolution helpers read by PxlExcelImporter -----
            // private: the component is this class's nestmate, so nothing here needs to be exposed.

            /**
             * Resolves the workbook name: the explicit name, else the upload's base file name, NFC-normalized
             * and trimmed.
             *
             * @param excelFile the uploaded Excel file (already extension-validated, so its name is non-blank)
             * @return the workbook name
             */
            private String resolveWorkbookName(final MultipartFile excelFile) {

                return resolveWorkbookName(excelFile.getOriginalFilename());
            }

            /**
             * Resolves the workbook name: the explicit name, else the resource's base file name,
             * NFC-normalized and trimmed.
             *
             * @param excelResource the Excel resource (already extension-validated, so its name is non-blank)
             * @return the workbook name
             */
            private String resolveWorkbookName(final Resource excelResource) {

                return resolveWorkbookName(excelResource.getFilename());
            }

            /**
             * Resolves the workbook name from the source's file name, shared by both source forms.
             *
             * <p>{@link FilenameUtils#getBaseName} throws on a name it cannot parse; the extension check has
             * already put this one through that same call, which is what makes it safe here.</p>
             *
             * @param sourceFilename the source's file name (non-blank and readable as one: the extension
             *                       check has already run)
             * @return the workbook name
             */
            private String resolveWorkbookName(final String sourceFilename) {

                if (StringUtils.isNotBlank(workbookName)) {
                    return workbookName;
                }

                return Normalizer.normalize(FilenameUtils.getBaseName(sourceFilename), Normalizer.Form.NFC).trim();
            }

        }

    }

}
