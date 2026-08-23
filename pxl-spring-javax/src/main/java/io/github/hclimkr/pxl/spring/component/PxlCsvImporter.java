package io.github.hclimkr.pxl.spring.component;

import io.github.hclimkr.pxl.Pxl;
import io.github.hclimkr.pxl.builder.PxlCsvImportBuilder;
import io.github.hclimkr.pxl.exception.PxlArgumentException;
import io.github.hclimkr.pxl.exception.PxlException;
import io.github.hclimkr.pxl.exception.PxlIOException;
import io.github.hclimkr.pxl.exception.PxlNullPointerException;
import io.github.hclimkr.pxl.option.PxlImportWorkbookOption;
import io.github.hclimkr.pxl.spring.internal.support.PxlArgumentSupport;
import io.github.hclimkr.pxl.spring.internal.support.PxlCoreSupport;
import io.github.hclimkr.pxl.spring.internal.support.PxlImportSupport;
import io.github.hclimkr.pxl.spring.logging.PxlPerformanceLogging;
import org.apache.commons.io.FilenameUtils;
import org.apache.commons.io.IOUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.core.io.InputStreamSource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.multipart.MultipartFile;

import javax.annotation.Nullable;
import javax.validation.constraints.NotEmpty;
import javax.validation.constraints.NotNull;
import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.text.Normalizer;
import java.util.*;

/**
 * Spring component that reads CSV sources - multipart uploads and Spring {@link Resource}s - into Java
 * objects.
 *
 * <p>Everything is configured through the fluent builder returned by {@link #importCsv()}, which mirrors the
 * core {@code Pxl.importCsv()} shape - optionally set {@code workbookName(...)}/{@code override(...)}, pick a
 * parse target ({@code workbook(...)} / {@code sheet(...)}), then call {@code fromMultipartFile(...)} /
 * {@code fromMultipartFiles(...)} or {@code fromResource(...)} / {@code fromResources(...)}. The result type
 * is carried through the generics, so no cast is needed:</p>
 *
 * <pre>{@code
 * ReportDto report = pxlSpring.importCsv().workbook(ReportDto.class).fromMultipartFiles(uploads);
 * List<User> users = pxlSpring.importCsv().sheet(User.class).fromMultipartFile(upload);
 * List<User> seed = pxlSpring.importCsv().sheet(User.class)
 *         .fromResource(new ClassPathResource("seed/Users.csv"));
 * }</pre>
 *
 * <p>The {@code fromResource(...)} pair is the non-HTTP half of the same operation, for the paths a Spring
 * application reads a CSV on that are not an upload - a batch job reading files off disk, an initializer
 * reading a classpath seed, an integration test reading a fixture. They exist so those callers need not
 * build their own core {@code Pxl} instance to get at a stream terminal.</p>
 *
 * <p>The file extension is validated ({@code .csv}); a violation raises
 * {@link HttpMediaTypeNotSupportedException}, and so does a resource that reports no file name at all,
 * since an extension that cannot be read cannot be checked. Each CSV becomes one sheet whose name is
 * derived from the file name and NFC-normalized.</p>
 *
 * <p>That builder is the nested {@link Builder}, and its parse-target step is {@link Builder.Source}. A
 * fluent chain never has to name either; on the rare occasion you hold one in a variable, spell them
 * {@code PxlCsvImporter.Builder} and {@code PxlCsvImporter.Builder.Source<R>}.</p>
 *
 * <p>The component is stateless and safe to share across threads; the builder it hands back is not - start one
 * per import.</p>
 *
 * <p>Reached through {@link io.github.hclimkr.pxl.spring.PxlSpring PxlSpring}: inject that one bean and call
 * {@code pxlSpring.importCsv()}, which hands back the builder documented here.</p>
 *
 * <p>The {@code importCsvFrom*} methods below are the builder's execution back-ends, one per source form.
 * They are {@code public} only because Spring AOP (and {@code @Validated} method validation) can advise public
 * methods only - a terminal has to re-enter this component through its proxy for
 * {@link PxlPerformanceLogging} to fire. Treat them as internal and always go through
 * {@link #importCsv()}.</p>
 */
@Validated
@Component
public class PxlCsvImporter {

    private static final String TAG = "PxlCsvImporter";

    /**
     * The core entry point, shared with the other components - see {@link PxlCoreSupport} for why it is not
     * one instance per component.
     */
    private final Pxl pxl = PxlCoreSupport.core();

    /**
     * This component's own AOP proxy, injected by Spring where available.
     *
     * <p>The builder's terminals must call back through the proxy, not through {@code this}: a plain
     * {@code this} reference bypasses the proxy, and with it {@link PxlPerformanceLogging} and {@code @Validated}.
     * {@code @Lazy} breaks the self-reference cycle, and {@code required = false} keeps plain
     * {@code new PxlCsvImporter()} usage (outside a Spring context) working - it then falls back to
     * {@code this} and simply produces no performance log.</p>
     */
    @Autowired(required = false)
    @Lazy
    private PxlCsvImporter self;

    /**
     * Starts a fluent CSV import, from multipart uploads or {@link Resource}s.
     *
     * @return a new builder bound to this component
     */
    public Builder importCsv() {

        return new Builder(pxl, Objects.nonNull(self) ? self : this);
    }

    // ----- builder execution back-ends (internal; reached through the nested Builder) -----

    /**
     * Reads the uploaded CSV files - one sheet per file - into whatever parse target the source step carries.
     *
     * <p>Internal: called by {@link Builder.Source#fromMultipartFiles(List)} (and, with a single-element
     * list, by {@code fromMultipartFile(...)}).</p>
     *
     * <p>The {@code @NotEmpty}/{@code @NotNull} constraints only fire when the call arrives through the
     * Spring proxy, so the same cases are checked again below for components built plainly (outside a Spring
     * context). Through the proxy a violation is a {@code ConstraintViolationException}; plainly it is the
     * {@link PxlException} subclass named here.</p>
     *
     * @param source   the configured source step
     * @param csvFiles the uploaded CSV files (one file becomes one sheet)
     * @param <R>      the parsed result type
     * @return the parsed workbook object or row collection
     * @throws PxlException                       if {@code csvFiles} is {@code null} or empty or holds a
     *                                            {@code null} element, a {@code sheet(...)} parse target is
     *                                            given more than one file, a file cannot be read, or parsing
     *                                            fails
     * @throws HttpMediaTypeNotSupportedException if any file extension is not {@code .csv}
     */
    @PxlPerformanceLogging(TAG)
    public <R> R importCsvFromMultipartFiles(@NotNull final Builder.Source<R> source,
                                             @NotEmpty final List<@NotNull MultipartFile> csvFiles)
            throws PxlException, HttpMediaTypeNotSupportedException {

        validateCsvSources(csvFiles, "csvFiles");

        // rejects a null element too, so the getOriginalFilename() calls below are safe
        for (final MultipartFile csvFile : csvFiles) {
            PxlImportSupport.validateCsvExtension(csvFile);
        }

        final List<String> csvNames = new ArrayList<>();
        for (final MultipartFile csvFile : csvFiles) {
            csvNames.add(resolveSheetName(csvFile.getOriginalFilename()));
        }

        return readInto(source, csvNames, csvFiles);
    }

    /**
     * Reads the given CSV resources - one sheet per resource - into whatever parse target the source step
     * carries.
     *
     * <p>Internal: called by {@link Builder.Source#fromResources(List)} (and, with a single-element list, by
     * {@code fromResource(...)}).</p>
     *
     * <p>Guarded exactly like the multipart back-end above, and for the same reason. The extension check
     * also rejects a resource reporting no file name, which is what makes the sheet-name derivation below
     * safe.</p>
     *
     * @param source       the configured source step
     * @param csvResources the CSV resources (one resource becomes one sheet)
     * @param <R>          the parsed result type
     * @return the parsed workbook object or row collection
     * @throws PxlException                       if {@code csvResources} is {@code null} or empty or holds a
     *                                            {@code null} element, a {@code sheet(...)} parse target is
     *                                            given more than one resource, a resource cannot be read, or
     *                                            parsing fails
     * @throws HttpMediaTypeNotSupportedException if any resource reports no file name, or its extension is
     *                                            not {@code .csv}
     */
    @PxlPerformanceLogging(TAG)
    public <R> R importCsvFromResources(@NotNull final Builder.Source<R> source,
                                        @NotEmpty final List<@NotNull Resource> csvResources)
            throws PxlException, HttpMediaTypeNotSupportedException {

        validateCsvSources(csvResources, "csvResources");

        // rejects a null element too, so the getFilename() calls below are safe
        for (final Resource csvResource : csvResources) {
            PxlImportSupport.validateCsvExtension(csvResource);
        }

        final List<String> csvNames = new ArrayList<>();
        for (final Resource csvResource : csvResources) {
            csvNames.add(resolveSheetName(csvResource.getFilename()));
        }

        return readInto(source, csvNames, csvResources);
    }

    /**
     * Rejects a source list that bean validation would have rejected, for components built plainly.
     *
     * @param csvSources    the source list handed to a back-end
     * @param parameterName the calling back-end's own parameter name, so the message names the source form
     *                      the call actually came from
     * @throws PxlNullPointerException if {@code csvSources} is {@code null}
     * @throws PxlArgumentException    if {@code csvSources} is empty
     */
    private static void validateCsvSources(final List<?> csvSources,
                                           final String parameterName)
            throws PxlNullPointerException, PxlArgumentException {

        PxlArgumentSupport.requireNonNull(csvSources, parameterName);
        if (csvSources.isEmpty()) {
            throw new PxlArgumentException("at least one CSV file must be specified");
        }
    }

    /**
     * Derives a sheet name from a CSV source's file name: the base name, NFC-normalized and trimmed.
     *
     * @param sourceFilename the source's file name (non-blank: the extension check has already run)
     * @return the sheet name
     */
    private static String resolveSheetName(final String sourceFilename) {

        return Normalizer.normalize(FilenameUtils.getBaseName(sourceFilename), Normalizer.Form.NFC).trim();
    }

    /**
     * Opens every source and parses them into the source step's target, one sheet per source.
     *
     * <p>Shared by both back-ends so the two cannot drift apart: uploads and resources differ only in where
     * the streams and the file names come from, and both are {@link InputStreamSource}s, so everything past
     * that point is one path. Every stream opened is closed here, whether the parse succeeded or not.</p>
     *
     * <p>The two lists line up by construction - the caller builds {@code csvNames} by walking
     * {@code csvSources} in order, and the loop below walks the same list again - which matters because the
     * core's {@code fromStreams} pairs them positionally without checking their lengths.</p>
     *
     * @param source     the configured source step (already validated)
     * @param csvNames   the resolved sheet names, in source order
     * @param csvSources the uploads or resources to read
     * @param <R>        the parsed result type
     * @return the parsed workbook object or row collection
     * @throws PxlException if a source cannot be read or parsing fails
     */
    private static <R> R readInto(final Builder.Source<R> source,
                                  final List<String> csvNames,
                                  final List<? extends InputStreamSource> csvSources)
            throws PxlException {

        final List<InputStream> csvStreams = new ArrayList<>();
        try {
            for (final InputStreamSource csvSource : csvSources) {
                csvStreams.add(new BufferedInputStream(csvSource.getInputStream()));
            }

            return source.coreSource.fromStreams(csvNames, csvStreams);
        } catch (IOException e) {
            throw new PxlIOException(e);
        } finally {
            csvStreams.forEach(IOUtils::closeQuietly);
        }
    }

    /**
     * Fluent builder for CSV imports, created via {@link PxlCsvImporter#importCsv()}.
     *
     * <p>It mirrors the core {@code io.github.hclimkr.pxl.builder.PxlCsvImportBuilder} shape - optional
     * {@link #workbookName(String)}/{@link #override(PxlImportWorkbookOption)}, then a parse target
     * ({@link #workbook(Class)} or one of the {@code sheet(...)} forms) yielding a typed {@link Source}, then
     * a terminal - and swaps the core's file/stream terminals for the Spring-facing
     * {@link Source#fromMultipartFile(MultipartFile)} / {@link Source#fromMultipartFiles(List)} and
     * {@link Source#fromResource(Resource)} / {@link Source#fromResources(List)}.</p>
     *
     * <p>Each CSV source becomes one sheet, named after its file (base name, NFC-normalized). The workbook
     * form accepts several sources; the sheet form accepts exactly one. The result type is carried through the
     * generics, so no cast is needed at the call site:</p>
     *
     * <pre>{@code
     * ReportDto report = pxlSpring.importCsv().workbook(ReportDto.class).fromMultipartFiles(uploads);
     * List<User> users = pxlSpring.importCsv().sheet(User.class).fromMultipartFile(upload);
     * Set<User> unique = pxlSpring.importCsv().sheet(User.class, Set.class).fromMultipartFile(upload);
     * List<User> seed = pxlSpring.importCsv().sheet(User.class)
     *         .fromResource(new ClassPathResource("seed/Users.csv"));
     * }</pre>
     *
     * <p>The builder holds the collected arguments only; the terminals delegate straight back to the enclosing
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
         * The owning component; the terminals call back into it so the import runs through its AOP proxy.
         */
        private final PxlCsvImporter importer;

        /**
         * The core import builder, used as the store for the workbook name and the import option until a parse
         * target is chosen.
         */
        private final PxlCsvImportBuilder coreBuilder;

        /**
         * Creates a builder bound to the given core entry point and owning component.
         *
         * @param pxl      the core entry point used to create the underlying import builder
         * @param importer the component the terminal methods delegate back to (its AOP proxy where available)
         */
        private Builder(final Pxl pxl, final PxlCsvImporter importer) {

            this.coreBuilder = pxl.importCsv();
            this.importer = importer;
        }

        // ----- options -----

        /**
         * Sets the workbook name written into the {@code @PxlWorkbookName} field of the workbook form.
         * (Optional)
         *
         * <p>Unlike {@link PxlExcelImporter.Builder#workbookName(String)} there is no file-name fallback: a
         * CSV source's file name names its <em>sheet</em>, not the workbook. Ignored by the
         * {@code sheet(...)} forms, which produce no workbook object.</p>
         *
         * @param workbookName the workbook name, or {@code null}
         * @return this builder
         */
        public Builder workbookName(@Nullable final String workbookName) {

            this.coreBuilder.workbookName(workbookName);
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
         * Parses the sources into an object of the given {@code @PxlWorkbook}-annotated class, one sheet per
         * CSV.
         *
         * @param workbookClass the {@code @PxlWorkbook}-annotated target class
         * @param <W>           the workbook type
         * @return the source step returning the parsed workbook object
         * @throws PxlNullPointerException if {@code workbookClass} is {@code null}
         */
        public <W> Source<W> workbook(final Class<W> workbookClass)
                throws PxlNullPointerException {

            return new Source<>(importer, coreBuilder.workbook(workbookClass));
        }

        /**
         * Parses a single CSV source into a {@link List} of rows.
         *
         * @param rowClass the row class each record is bound to
         * @param <T>      the row type
         * @return the source step returning the parsed rows
         * @throws PxlNullPointerException if {@code rowClass} is {@code null}
         */
        public <T> Source<List<T>> sheet(final Class<T> rowClass)
                throws PxlNullPointerException {

            return new Source<>(importer, coreBuilder.sheet(rowClass));
        }

        /**
         * Parses a single CSV source into the requested collection type.
         *
         * <p>{@code C} binds from the {@code collectionClass} literal, and a literal such as {@code Set.class}
         * is a {@code Class<Set>} - the raw type - so the parsed result arrives raw too. Assigning it to a
         * parameterized variable is therefore an unchecked conversion, and the call site needs
         * {@code @SuppressWarnings("unchecked")}. The single-argument form above has no such gap: {@code T}
         * binds to the row class there and the result is a {@code List<T>}.</p>
         *
         * @param rowClass        the row class each record is bound to
         * @param collectionClass the collection type to return (e.g. {@code List.class}, {@code Set.class})
         * @param <C>             the collection type to return
         * @return the source step returning the parsed rows
         * @throws PxlNullPointerException if {@code rowClass} or {@code collectionClass} is {@code null}
         */
        public <C extends Collection<?>> Source<C> sheet(final Class<?> rowClass,
                                                         final Class<C> collectionClass)
                throws PxlNullPointerException {

            return new Source<>(importer, coreBuilder.sheet(rowClass, collectionClass));
        }

        /**
         * Terminal source step for a CSV import: holds the parse target chosen on the enclosing
         * {@link Builder} and reads it from the uploaded file(s) or {@link Resource}(s).
         *
         * <p>Obtained only from {@code workbook(...)}/{@code sheet(...)}: picking a parse target is what
         * produces this step, and its type parameter is what carries the result type through to the
         * terminal.</p>
         *
         * @param <R> the parsed result type (a workbook object or a collection of rows)
         */
        public static final class Source<R> {

            /**
             * The owning component; the terminals call back into it so the import runs through its AOP proxy.
             */
            private final PxlCsvImporter importer;

            /**
             * The underlying core source step carrying the parse target. Read directly by
             * {@link PxlCsvImporter}, a nestmate of this class.
             */
            private final PxlCsvImportBuilder.Source<R> coreSource;

            /**
             * Wraps a core source step.
             *
             * @param importer   the component the terminal methods delegate back to
             * @param coreSource the core source step carrying the parse target
             */
            private Source(final PxlCsvImporter importer,
                           final PxlCsvImportBuilder.Source<R> coreSource) {

                this.importer = importer;
                this.coreSource = coreSource;
            }

            /**
             * Sets the workbook name, overriding any value set on the enclosing builder. (Optional)
             *
             * @param workbookName the workbook name, or {@code null}
             * @return this source step
             */
            public Source<R> workbookName(@Nullable final String workbookName) {

                this.coreSource.workbookName(workbookName);
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
             * Reads a single uploaded CSV file and returns the parsed result.
             *
             * <p>The one-file case of {@link #fromMultipartFiles(List)}. A {@code sheet(...)} parse target
             * accepts no other shape; the workbook form accepts it too and yields a single-sheet workbook.</p>
             *
             * @param csvFile the uploaded CSV file
             * @return the parsed workbook object or row collection
             * @throws PxlException                       if {@code csvFile} is {@code null}, the upload cannot
             *                                            be read, or parsing fails
             * @throws HttpMediaTypeNotSupportedException if the file extension is not {@code .csv}
             */
            public R fromMultipartFile(final MultipartFile csvFile)
                    throws PxlException, HttpMediaTypeNotSupportedException {

                return fromMultipartFiles(Collections.singletonList(csvFile));
            }

            /**
             * Reads the uploaded CSV files - one sheet per file - and returns the parsed result.
             *
             * <p>The {@code sheet(...)} forms accept exactly one file; passing more raises
             * {@link PxlArgumentException}.</p>
             *
             * @param csvFiles the uploaded CSV files
             * @return the parsed workbook object or row collection
             * @throws PxlException                       if {@code csvFiles} is {@code null} or empty, a
             *                                            {@code sheet(...)} form is given more than one
             *                                            upload, an upload cannot be read, or parsing fails
             * @throws HttpMediaTypeNotSupportedException if any file extension is not {@code .csv}
             */
            public R fromMultipartFiles(final List<MultipartFile> csvFiles)
                    throws PxlException, HttpMediaTypeNotSupportedException {

                return importer.importCsvFromMultipartFiles(this, csvFiles);
            }

            /**
             * Reads a single CSV resource and returns the parsed result.
             *
             * <p>The one-resource case of {@link #fromResources(List)}, and the non-HTTP counterpart of
             * {@link #fromMultipartFile(MultipartFile)} - for a file on disk ({@code FileSystemResource}), a
             * packaged one ({@code ClassPathResource}), or anything else behind Spring's {@link Resource}
             * abstraction.</p>
             *
             * <p>The resource must report a file name: it names the sheet, and it is what the extension is
             * read from. One that does not (a bare {@code ByteArrayResource}, say) is rejected rather than
             * let through unchecked.</p>
             *
             * @param csvResource the CSV resource
             * @return the parsed workbook object or row collection
             * @throws PxlException                       if {@code csvResource} is {@code null}, the resource
             *                                            cannot be read, or parsing fails
             * @throws HttpMediaTypeNotSupportedException if the resource reports no file name, or its
             *                                            extension is not {@code .csv}
             */
            public R fromResource(final Resource csvResource)
                    throws PxlException, HttpMediaTypeNotSupportedException {

                return fromResources(Collections.singletonList(csvResource));
            }

            /**
             * Reads the given CSV resources - one sheet per resource - and returns the parsed result.
             *
             * <p>The non-HTTP counterpart of {@link #fromMultipartFiles(List)}, and identical to it in every
             * other respect: the {@code sheet(...)} forms accept exactly one resource, and each resource's
             * base file name becomes its sheet name.</p>
             *
             * @param csvResources the CSV resources
             * @return the parsed workbook object or row collection
             * @throws PxlException                       if {@code csvResources} is {@code null} or empty, a
             *                                            {@code sheet(...)} form is given more than one
             *                                            resource, a resource cannot be read, or parsing
             *                                            fails
             * @throws HttpMediaTypeNotSupportedException if any resource reports no file name, or its
             *                                            extension is not {@code .csv}
             */
            public R fromResources(final List<Resource> csvResources)
                    throws PxlException, HttpMediaTypeNotSupportedException {

                return importer.importCsvFromResources(this, csvResources);
            }

        }

    }

}
