package io.github.hclimkr.pxl.spring.logging;

import org.springframework.core.annotation.AliasFor;

import java.lang.annotation.*;

/**
 * Marks a method to be wrapped by {@link PxlPerformanceLoggingAspect}, which logs the method's entry,
 * exit, and elapsed time.
 *
 * <p>The aspect is opt-in and disabled by default; enable it with {@code pxl.performance.logging.enabled=true}.
 * {@link #value()} and {@link #tag()} are aliases of each other: the given text is used as a log-line
 * prefix so entries from different components can be told apart. Each component tags its own class name.</p>
 *
 * <p>Only fires where Spring AOP can reach: a {@code public} method invoked through the bean's proxy. A call
 * that a component makes to itself through {@code this} is not advised, which is why the fluent terminals
 * re-enter their component through its injected self-reference.</p>
 *
 * <p>Within this library it is carried by the components' execution back-ends and never by their fluent start
 * methods ({@code exportExcel()} and its siblings) or by {@code PxlSpring}: those only construct a builder, so
 * timing them would measure nothing. Keep it on the work.</p>
 *
 * <p>Example: {@code @PxlPerformanceLogging("PxlExcelExporter")}.</p>
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
@Documented
public @interface PxlPerformanceLogging {

    /**
     * Alias for {@link #tag()} - the log-line prefix identifying the annotated method's origin.
     *
     * @return the tag text (empty for none)
     */
    @AliasFor("tag")
    String value() default "";

    /**
     * Alias for {@link #value()} - the log-line prefix identifying the annotated method's origin.
     *
     * @return the tag text (empty for none)
     */
    @AliasFor("value")
    String tag() default "";

}
