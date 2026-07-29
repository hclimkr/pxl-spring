package io.github.hclimkr.pxl.spring.logging;

import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.AnnotationUtils;
import org.springframework.core.convert.ConversionFailedException;
import org.springframework.core.type.AnnotatedTypeMetadata;

import java.lang.reflect.Method;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * Spring AOP aspect that logs method entry/exit and elapsed time for methods annotated with
 * {@link PxlPerformanceLogging}.
 *
 * <p>Opt-in and disabled by default: it is only registered when {@code pxl.performance.logging.enabled} is
 * {@code true} (see {@link EnabledCondition}). The slow-call threshold is configurable via
 * {@code pxl.performance.logging.low-performance-in-ms} (default 5000 ms); calls at or above the threshold are
 * flagged with a trailing {@code LowPerformance} marker.</p>
 *
 * <p>Both lines go out at {@code INFO} under this class's own logger, tagged with the annotation's text and
 * marked {@code +} on entry and {@code -} on exit:</p>
 *
 * <pre>
 * PxlExcelExporter: + PxlExcelExporter.exportExcelToResponse(..)
 * PxlExcelExporter: - PxlExcelExporter.exportExcelToResponse(..) (37ms)
 * </pre>
 *
 * <p>Needs an AOP proxy in place, so a component constructed plainly outside a Spring context simply logs
 * nothing.</p>
 */
@Aspect
@Conditional(PxlPerformanceLoggingAspect.EnabledCondition.class)
@Configuration
public class PxlPerformanceLoggingAspect {

    private static final Logger log = LoggerFactory.getLogger(PxlPerformanceLoggingAspect.class);

    /**
     * Elapsed-time threshold in milliseconds at or above which a call is flagged {@code LowPerformance}.
     * Configured by {@code pxl.performance.logging.low-performance-in-ms} (default 5000).
     */
    @Value("${pxl.performance.logging.low-performance-in-ms:5000}")
    private long lowPerformanceInMs;

    /**
     * Logs one line on method entry and another on exit with the elapsed time in milliseconds, using the
     * annotation's tag as a prefix. Calls whose duration reaches {@link #lowPerformanceInMs} are marked
     * {@code LowPerformance}. The intercepted method's return value and exceptions are passed through
     * unchanged.
     *
     * @param joinPoint the intercepted method invocation
     * @return the value returned by the intercepted method
     * @throws Throwable whatever the intercepted method throws
     */
    @Around("@annotation(io.github.hclimkr.pxl.spring.logging.PxlPerformanceLogging)")
    public Object doLogging(ProceedingJoinPoint joinPoint) throws Throwable {

        final MethodSignature methodSignature = (MethodSignature) joinPoint.getSignature();
        final String methodSignatureString = methodSignature.toShortString();
        //final String className = methodSignature.getDeclaringTypeName();
        //final String methodName = methodSignature.getName();
        final Method method = methodSignature.getMethod();
        final PxlPerformanceLogging performanceLogging = AnnotationUtils.getAnnotation(method, PxlPerformanceLogging.class);
        final String tag = Optional.ofNullable(performanceLogging)
                .map(a -> !a.tag().isEmpty() ? a.tag() + ": " : a.tag())
                .orElse("");
        //final Object[] methodArguments = joinPoint.getArgs();

        final long start = System.nanoTime();
        try {
            log.info("{}+ {}",
                    tag,
                    methodSignatureString);

            return joinPoint.proceed();
        } catch (Throwable t) {
            throw t;
        } finally {
            final long end = System.nanoTime();
            final long elapsed = TimeUnit.NANOSECONDS.toMillis(end - start);

            log.info("{}- {} ({}ms){}",
                    tag,
                    methodSignatureString,
                    elapsed,
                    elapsed >= lowPerformanceInMs ? " LowPerformance" : "");
        }
    }

    /**
     * Registers the enclosing aspect only when {@code pxl.performance.logging.enabled} is {@code true}.
     *
     * <p><strong>Deliberately built on {@code spring-context} alone.</strong> The obvious spelling is Spring
     * Boot's {@code @ConditionalOnExpression("${pxl.performance.logging.enabled:false}")}, and that is what this was
     * — but it lives in {@code spring-boot-autoconfigure}, which this library declares {@code provided} and a
     * plain (non-Boot) Spring application therefore does not have. Spring reads bean metadata with ASM and
     * silently <em>drops</em> any annotation whose type will not load, so on such a classpath the condition
     * disappeared along with it: the aspect registered unconditionally, logging two INFO lines per operation
     * with no way to switch it off, because the property that was supposed to gate it was never consulted.
     * {@link Conditional} is part of {@code spring-context}, which is always present, so the gate now holds
     * on either classpath. Do not reintroduce a Boot-only annotation here.</p>
     *
     * <p>The property is read straight off the {@link org.springframework.core.env.Environment}, so the usual
     * sources and Boot's relaxed binding ({@code PXL_PERFORMANCE_LOGGING_ENABLED}) still apply. The only thing given
     * up against the old form is arbitrary SpEL in the property value, which was never a documented use.</p>
     *
     * <p>Because the conversion is Spring's own, the accepted vocabulary is wider than the {@code true} the
     * documentation quotes — {@code on}, {@code yes} and {@code 1} enable it too, and {@code off}, {@code no}
     * and {@code 0} disable it, case-insensitively and trimmed; a blank value counts as absent. A
     * <em>malformed</em> value is not quietly taken as disabled: it fails context startup, naming the
     * offending value. That is deliberate — {@code pxl.performance.logging.enabled=ture} silently producing no
     * logging would leave nothing to diagnose.</p>
     */
    static final class EnabledCondition implements Condition {

        private static final String ENABLED_PROPERTY = "pxl.performance.logging.enabled";

        /**
         * Reads the gate property off the environment and converts it to a boolean, treating an absent one as
         * {@code false}.
         *
         * @param context  the condition evaluation context
         * @param metadata metadata of the annotated element (unused; the property alone decides)
         * @return {@code true} when {@code pxl.performance.logging.enabled} holds a true-like value; {@code false}
         * when it holds a false-like or blank one, or is absent altogether
         * @throws ConversionFailedException if the property is set to a value that is neither
         */
        @Override
        public boolean matches(final ConditionContext context,
                               final AnnotatedTypeMetadata metadata) {

            return Boolean.TRUE.equals(
                    context.getEnvironment().getProperty(ENABLED_PROPERTY, Boolean.class, Boolean.FALSE));
        }

    }

}
