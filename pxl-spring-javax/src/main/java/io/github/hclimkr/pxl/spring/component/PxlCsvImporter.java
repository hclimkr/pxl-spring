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
 * Spring component that reads multipart CSV uploads into Java objects.
 *
 * <p>Everything is configured through the fluent builder returned by {@link #importCsv()}, which mirrors the
 * core {@code Pxl.importCsv()} shape — optionally set {@code workbookName(...)}/{@code override(...)}, pick a
 * parse target ({@code workbook(...)} / {@code sheet(...)}), then call {@code fromMultipartFile(...)} or
 * {@code fromMultipartFiles(...)}. The result type is carried through the generics, so no cast is needed:</p>
 *
 * <pre>{@code
 * ReportDto report = pxlSpring.importCsv().workbook(ReportDto.class).fromMultipartFiles(uploads);
 * List<User> users = pxlSpring.importCsv().sheet(User.class).fromMultipartFile(upload);
 * }</pre>
 *
 * <p>The file extension is validated ({@code .csv}); a violation raises
 * {@link HttpMediaTypeNotSupportedException}. Each CSV file becomes one sheet whose name is derived
 * from the file name and NFC-normalized.</p>
 *
 * <p>That builder is the nested {@link Builder}, and its parse-target step is {@link Builder.Source}. A
 * fluent chain never has to name either; on the rare occasion you hold one in a variable, spell them
 * {@code PxlCsvImporter.Builder} and {@code PxlCsvImporter.Builder.Source<R>}.</p>
 *
 * <p>The component is stateless and safe to share across threads; the builder it hands back is not — start one
 * per import.</p>
 *
 * <p>Reached through {@link io.github.hclimkr.pxl.spring.PxlSpring PxlSpring}: inject that one bean and call
 * {@code pxlSpring.importCsv()}, which hands back the builder documented here.</p>
 *
 * <p>The {@code importCsvFrom*} method below is the builder's execution back-end. It is {@code public} only
 * because Spring AOP (and {@code @Validated} method validation) can advise public methods only — a terminal
 * has to re-enter this component through its proxy for {@link PxlPerformanceLogging} to fire. Treat it as
 * internal and always go through {@link #importCsv()}.</p>
 */
@Validated
@Component
public class PxlCsvImporter {

    private static final String TAG = "PxlCsvImporter";

    /**
     * The core entry point, shared with the other components — see {@link PxlCoreSupport} for why it is not
     * one instance per component.
     */
    private final Pxl pxl = PxlCoreSupport.core();

    /**
     * This component's own AOP proxy, injected by Spring where available.
     *
     * <p>The builder's terminals must call back through the proxy, not through {@code this}: a plain
     * {@code this} reference bypasses the proxy, and with it {@link PxlPerformanceLogging} and {@code @Validated}.
     * {@code @Lazy} breaks the self-reference cycle, and {@code required = false} keeps plain
     * {@code new PxlCsvImporter()} usage (outside a Spring context) working — it then falls back to
     * {@code this} and simply produces no performance log.</p>
     */
    @Autowired(required = false)
    @Lazy
    private PxlCsvImporter self;

    /**
     * Starts a fluent multipart CSV import.
     *
     * @return a new builder bound to this component
     */
    public Builder importCsv() {

        return new Builder(pxl, Objects.nonNull(self) ? self : this);
    }

    // ----- builder execution back-end (internal; reached through the nested Builder) -----

    /**
     * Reads the uploaded CSV files — one sheet per file — into whatever parse target the source step carries.
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

        PxlArgumentSupport.requireNonNull(csvFiles, "csvFiles");
        if (csvFiles.isEmpty()) {
            throw new PxlArgumentException("at least one CSV file must be specified");
        }

        // rejects a null element too, so the getOriginalFilename() calls below are safe
        for (final MultipartFile csvFile : csvFiles) {
            PxlImportSupport.validateCsvExtension(csvFile);
        }

        final List<String> csvNames = new ArrayList<>();
        final List<InputStream> csvStreams = new ArrayList<>();
        try {
            for (final MultipartFile csvFile : csvFiles) {
                csvNames.add(Normalizer.normalize(FilenameUtils.getBaseName(csvFile.getOriginalFilename()), Normalizer.Form.NFC).trim());
                csvStreams.add(new BufferedInputStream(csvFile.getInputStream()));
            }

            return source.coreSource.fromStreams(csvNames, csvStreams);
        } catch (IOException e) {
            throw new PxlIOException(e);
        } finally {
            csvStreams.forEach(IOUtils::closeQuietly);
        }
    }

    /**
     * Fluent builder for multipart CSV imports, created via {@link PxlCsvImporter#importCsv()}.
     *
     * <p>It mirrors the core {@code io.github.hclimkr.pxl.builder.PxlCsvImportBuilder} shape — optional
     * {@link #workbookName(String)}/{@link #override(PxlImportWorkbookOption)}, then a parse target
     * ({@link #workbook(Class)} or one of the {@code sheet(...)} forms) yielding a typed {@link Source}, then
     * a terminal — and swaps the core's file/stream terminals for the Spring-facing
     * {@link Source#fromMultipartFile(MultipartFile)} / {@link Source#fromMultipartFiles(List)}.</p>
     *
     * <p>Each CSV upload becomes one sheet, named after its file (base name, NFC-normalized). The workbook
     * form accepts several uploads; the sheet form accepts exactly one. The result type is carried through the
     * generics, so no cast is needed at the call site:</p>
     *
     * <pre>{@code
     * ReportDto report = pxlSpring.importCsv().workbook(ReportDto.class).fromMultipartFiles(uploads);
     * List<User> users = pxlSpring.importCsv().sheet(User.class).fromMultipartFile(upload);
     * Set<User> unique = pxlSpring.importCsv().sheet(User.class, Set.class).fromMultipartFile(upload);
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
         * CSV upload's file name names its <em>sheet</em>, not the workbook. Ignored by the
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
         * Parses the uploads into an object of the given {@code @PxlWorkbook}-annotated class, one sheet per
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
         * Parses a single CSV upload into a {@link List} of rows.
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
         * Parses a single CSV upload into the requested collection type.
         *
         * <p>{@code C} binds from the {@code collectionClass} literal, and a literal such as {@code Set.class}
         * is a {@code Class<Set>} — the raw type — so the parsed result arrives raw too. Assigning it to a
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
         * Terminal source step for a multipart CSV import: holds the parse target chosen on the enclosing
         * {@link Builder} and reads it from the uploaded file(s).
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
             * Reads the uploaded CSV files — one sheet per file — and returns the parsed result.
             *
             * <p>The {@code sheet(...)} forms accept exactly one file; passing more raises
             * {@link PxlArgumentException}.</p>
             *
             * @param csvFiles the uploaded CSV files
             * @return the parsed workbook object or row collection
             * @throws PxlException                       if {@code csvFiles} is {@code null}, a
             *                                            {@code sheet(...)} form is given more than one
             *                                            upload, an upload cannot be read, or parsing fails
             * @throws HttpMediaTypeNotSupportedException if any file extension is not {@code .csv}
             */
            public R fromMultipartFiles(final List<MultipartFile> csvFiles)
                    throws PxlException, HttpMediaTypeNotSupportedException {

                return importer.importCsvFromMultipartFiles(this, csvFiles);
            }

        }

    }

}
