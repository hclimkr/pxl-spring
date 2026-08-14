package io.github.hclimkr.pxl.spring.internal.support;

import io.github.hclimkr.pxl.Pxl;

/**
 * Holder for the single core {@link Pxl} entry point shared by every pxl-spring component.
 *
 * <p>{@code new Pxl()} is not cheap: its constructor bootstraps a bean-validation
 * {@code ValidatorFactory} (provider discovery, EL factory, metadata providers, {@code validation.xml}
 * parsing) and keeps only the {@code Validator} it produced, so the factory is never closed and holds its
 * metadata cache for the lifetime of the process. One instance per component would pay that seven times over
 * - and the two sample exporters would pay it for nothing at all, because {@code Pxl.exportSampleExcel()} and
 * {@code Pxl.exportSampleCsv()} build sample builders that take no validator.</p>
 *
 * <p>Sharing is safe because {@link Pxl} is immutable after construction - its only instance field is that
 * {@code Validator}, which is thread-safe and meant to be shared - and every {@code Pxl} factory method
 * hands back a fresh builder. What little process-wide state the core has ({@code Pxl.setMessageLocale})
 * was already {@code static}, so a shared instance changes nothing semantically.</p>
 *
 * <p>Deliberately not a Spring bean: components must keep working when constructed plainly outside a Spring
 * context ({@code new PxlExcelExporter()}), and making the core an injected dependency would force another
 * {@code @Autowired(required = false)} fallback like the self-reference. A plain {@code static final} field
 * is enough - {@link #core()} is this class's only member, so class initialization (and with it the one
 * {@code new Pxl()}) cannot be triggered by anything else, and the JVM's class-initialization lock makes it
 * thread-safe once, with no locking afterwards. An initialization-on-demand holder would add a nested class
 * without deferring anything: there is no cheaper member here for a caller to reach first. Should this class
 * ever gain one, revisit that.</p>
 *
 * <p>Intended to be internal, but its callers sit in a different package
 * ({@code io.github.hclimkr.pxl.spring.component}) and there is no JPMS {@code module-info} to hide it, so
 * the class and its {@code static} helper must be - and are - declared {@code public}. Treat them as
 * internal despite the {@code public} modifier; the {@code internal.support} package name is the marker.</p>
 */
public final class PxlCoreSupport {

    private static final Pxl CORE = new Pxl();

    private PxlCoreSupport() {
        throw new AssertionError("no instances of this class");
    }

    /**
     * Returns the shared core entry point.
     *
     * @return the process-wide (per-classloader) {@link Pxl} instance; never {@code null}
     */
    public static Pxl core() {

        return CORE;
    }

}
