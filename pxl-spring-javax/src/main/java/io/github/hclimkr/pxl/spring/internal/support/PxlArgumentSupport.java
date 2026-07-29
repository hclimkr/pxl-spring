package io.github.hclimkr.pxl.spring.internal.support;

import io.github.hclimkr.pxl.exception.PxlNullPointerException;

import java.util.Objects;

/**
 * Argument guards that back up the components' Bean Validation annotations.
 *
 * <p>{@code @NotNull} and friends only fire when a call arrives through the Spring proxy. A component built
 * plainly — {@code new PxlExcelExporter()}, which is also what {@code new PxlSpring()} does, and a documented
 * way to use this library — never sees them, so without a guard here a {@code null} destination would surface
 * as a raw {@code NullPointerException} (or, worse, only after the whole workbook had been generated),
 * breaking the "every failure is a {@code PxlException}" contract.</p>
 *
 * <p>Call these as the <em>first</em> statement of a back-end method, so the plain path fails exactly where
 * the proxied path would: before any work is done. Through the proxy the guard is simply redundant — bean
 * validation has already rejected the call — so the two paths differ only in exception type
 * ({@code ConstraintViolationException} vs. {@link PxlNullPointerException}).</p>
 *
 * <p>This is deliberately not a general-purpose assertion utility: the core library's own
 * {@code PxlAssertSupport} is off limits (it lives under {@code pxl.internal}, which the build's enforcer
 * rule bans), and everything else the components need is already validated by the core builders.</p>
 *
 * <p>Intended to be internal, but its callers sit in a different package
 * ({@code io.github.hclimkr.pxl.spring.component}) and there is no JPMS {@code module-info} to hide it, so
 * the class and its {@code static} helper must be — and are — declared {@code public}. Treat them as
 * internal despite the {@code public} modifier; the {@code internal.support} package name is the marker.</p>
 */
public final class PxlArgumentSupport {

    private PxlArgumentSupport() {
        throw new AssertionError("no instances of this class");
    }

    /**
     * Rejects a {@code null} argument with the library's own exception type.
     *
     * @param argument      the argument to check
     * @param parameterName the caller's parameter name, used in the message
     * @throws PxlNullPointerException if {@code argument} is {@code null}
     */
    public static void requireNonNull(final Object argument,
                                      final String parameterName)
            throws PxlNullPointerException {

        if (Objects.isNull(argument)) {
            throw new PxlNullPointerException(parameterName + " must not be null");
        }
    }

}
