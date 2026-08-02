package io.github.hclimkr.pxl.spring.logging;

import io.github.hclimkr.pxl.spring.PxlSpring;
import io.github.hclimkr.pxl.spring.component.*;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.Logger;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Property;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.aop.aspectj.annotation.AspectJProxyFactory;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Conditional;
import org.springframework.core.annotation.AnnotationUtils;
import org.springframework.core.convert.ConversionFailedException;
import org.springframework.core.env.MapPropertySource;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.*;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests for the performance-logging pair. The aspect is opt-in and disabled by default
 * (via {@code @ConditionalOnExpression}), so no other test weaves it; here it is applied directly with
 * Spring's {@link AspectJProxyFactory} to exercise the {@code @Around} advice around a target bean.
 *
 * <p>Three parts:</p>
 * <ul>
 *   <li><strong>The opt-in gate</strong> — that {@code pxl.performance.logging.enabled} really decides whether the
 *       aspect is registered, and that the condition doing so comes from {@code spring-context} rather than
 *       Spring Boot. See {@code theGateDoesNotDependOnSpringBoot} for what went wrong when it did not.</li>
 *   <li><strong>Advice behaviour</strong> — return-value and exception pass-through, and the two lines the
 *       advice actually emits, captured off the logger: the tag prefix (tagged vs. empty-tag), the elapsed
 *       time, and both sides of the {@code LowPerformance} threshold. Asserting the prefix is also what pins
 *       {@link PxlPerformanceLogging}'s {@code value}/{@code tag} {@code @AliasFor} pair, because the aspect
 *       reads {@code tag()} while every real annotation site writes {@code value}.</li>
 *   <li><strong>Annotation placement</strong> — the invariant the whole design rests on: the nineteen builder
 *       back-ends carry the annotation with their own class-name tag, and the fluent entry points (and the
 *       {@link PxlSpring} facade) deliberately do not, because they only construct a builder. That is why a
 *       terminal re-enters its component through the Spring proxy at all, so nothing else guards it. Like
 *       {@code PxlCoreSupportTests}, this reaches into the {@code component} package from outside; the
 *       production dependency still runs one way only.</li>
 * </ul>
 */
class PxlPerformanceLoggingAspectTests {

    /**
     * Concrete (interface-free) target so {@link AspectJProxyFactory} produces a CGLIB proxy and the
     * join-point method resolves to this class's annotated method (carrying the {@code @PxlPerformanceLogging}
     * tag), rather than an interface method that would not.
     */
    static class Service {

        @PxlPerformanceLogging("Svc")
        public String tagged(final String input) {
            return "ok:" + input;
        }

        @PxlPerformanceLogging  // empty tag -> the "prefix is blank" branch
        public String untagged() {
            return "plain";
        }

        @PxlPerformanceLogging("Boom")
        public void boom() {
            throw new IllegalStateException("boom");
        }
    }

    /**
     * Builds a CGLIB proxy of {@link Service} advised by the aspect, with {@code lowPerformanceInMs} set
     * directly (the {@code @Value} field is not populated outside a Spring context).
     */
    private static Service proxy(final long lowPerformanceInMs) throws ReflectiveOperationException {
        final PxlPerformanceLoggingAspect aspect = new PxlPerformanceLoggingAspect();
        final Field field = PxlPerformanceLoggingAspect.class.getDeclaredField("lowPerformanceInMs");
        field.setAccessible(true);
        field.setLong(aspect, lowPerformanceInMs);

        final AspectJProxyFactory factory = new AspectJProxyFactory(new Service());
        factory.addAspect(aspect);
        return factory.getProxy();
    }

    // ----- advice behaviour -----

    @Test
    void taggedMethod_returnValuePassesThroughUnchanged() throws ReflectiveOperationException {
        final Service service = proxy(5_000L);
        assertThat(service.tagged("x")).isEqualTo("ok:x");
    }

    @Test
    void emptyTagMethod_returnValuePassesThroughUnchanged() throws ReflectiveOperationException {
        // exercises the empty-tag branch of the prefix (a.tag().isEmpty() == true)
        final Service service = proxy(5_000L);
        assertThat(service.untagged()).isEqualTo("plain");
    }

    @Test
    void thrownException_propagatesUnchanged() throws ReflectiveOperationException {
        final Service service = proxy(5_000L);
        assertThatThrownBy(service::boom)
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("boom");
    }

    @Test
    void lowThreshold_flagsLowPerformance_andStillReturns() throws ReflectiveOperationException {
        // threshold 0 => elapsed >= 0 is always true, taking the LowPerformance branch on exit
        final Service service = proxy(0L);
        assertThat(service.tagged("y")).isEqualTo("ok:y");
    }

    @Test
    void taggedMethod_logsEntryAndExitWithTheTagPrefixAndElapsedTime() throws ReflectiveOperationException {
        final List<String> messages = logsFrom(5_000L, service -> service.tagged("x"));

        // one line on entry, one on exit - the tag prefixes both. It reads "Svc" only because the aspect's
        // tag() resolves through @AliasFor to the value the annotation site wrote.
        assertThat(messages).hasSize(2);
        assertThat(messages.get(0)).startsWith("Svc: + ").contains("tagged");
        assertThat(messages.get(1)).startsWith("Svc: - ").contains("tagged").matches(".*\\(\\d+ms\\)$");
    }

    @Test
    void emptyTagMethod_logsWithNoPrefixAtAll() throws ReflectiveOperationException {
        // an empty tag must not produce a stray ": " separator
        final List<String> messages = logsFrom(5_000L, Service::untagged);

        assertThat(messages).hasSize(2);
        assertThat(messages.get(0)).startsWith("+ ").contains("untagged");
        assertThat(messages.get(1)).startsWith("- ").contains("untagged");
    }

    @Test
    void exitLine_isMarkedLowPerformanceOnlyPastTheThreshold() throws ReflectiveOperationException {
        // threshold 0: elapsed >= 0 always holds, so the marker is appended
        assertThat(logsFrom(0L, service -> service.tagged("y")).get(1)).endsWith(" LowPerformance");

        // a threshold no test call can reach: the same line ends at the elapsed time
        assertThat(logsFrom(Long.MAX_VALUE, service -> service.tagged("y")).get(1))
                .doesNotContain("LowPerformance")
                .matches(".*\\(\\d+ms\\)$");
    }

    @Test
    void aThrowingMethod_stillLogsItsExitLine() throws ReflectiveOperationException {
        // the exit line is emitted from a finally block, so a failed call is timed like any other
        final List<String> messages = logsFrom(5_000L, service ->
                assertThatThrownBy(service::boom).isInstanceOf(IllegalStateException.class));

        assertThat(messages).hasSize(2);
        assertThat(messages.get(0)).startsWith("Boom: + ");
        assertThat(messages.get(1)).startsWith("Boom: - ").matches(".*\\(\\d+ms\\)$");
    }

    // ----- annotation placement on the components -----

    /**
     * Each component with its fluent entry point and the back-end methods that must carry the annotation.
     * Nineteen back-ends in total: two per importer (one per source form — a multipart upload and a Spring
     * {@code Resource}), five destinations per exporter — the two response shapes (buffered and streaming)
     * are separate terminals, so each has its own back-end.
     */
    static Stream<Arguments> componentBackEnds() {
        return Stream.of(
                Arguments.of(PxlExcelImporter.class, "importExcel",
                        Arrays.asList("importExcelFromMultipartFile", "importExcelFromResource")),
                Arguments.of(PxlCsvImporter.class, "importCsv",
                        Arrays.asList("importCsvFromMultipartFiles", "importCsvFromResources")),
                Arguments.of(PxlExcelExporter.class, "exportExcel",
                        Arrays.asList("exportExcelToStream", "exportExcelToFile",
                                "exportExcelToResponse", "exportExcelToResponseStreaming",
                                "exportExcelToResponseEntity")),
                Arguments.of(PxlSampleExcelExporter.class, "exportSampleExcel",
                        Arrays.asList("exportSampleExcelToStream", "exportSampleExcelToFile",
                                "exportSampleExcelToResponse", "exportSampleExcelToResponseStreaming",
                                "exportSampleExcelToResponseEntity")),
                Arguments.of(PxlExcelZipExporter.class, "exportExcelZip",
                        Arrays.asList("exportExcelZipToStream", "exportExcelZipToFile",
                                "exportExcelZipToResponse", "exportExcelZipToResponseStreaming",
                                "exportExcelZipToResponseEntity")));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("componentBackEnds")
    void everyBackEnd_isAnnotatedWithItsOwnTag(final Class<?> component,
                                               final String entryPointName,
                                               final List<String> backEndNames) {

        assertThat(annotatedMethodNames(component))
                .as("methods carrying @PxlPerformanceLogging on %s", component.getSimpleName())
                .containsExactlyInAnyOrderElementsOf(backEndNames);

        for (final Method method : component.getDeclaredMethods()) {
            final PxlPerformanceLogging annotation = AnnotationUtils.getAnnotation(method, PxlPerformanceLogging.class);
            if (annotation == null) {
                continue;
            }

            // the tag tells one component's log lines from another's, so it has to be that component's name
            assertThat(annotation.tag()).isEqualTo(component.getSimpleName());
            // Spring AOP can only advise public methods; a package-private back-end would drop the advice
            // silently, because a CGLIB proxy may well end up in another classloader
            assertThat(Modifier.isPublic(method.getModifiers()))
                    .as("%s.%s must stay public for the proxy to advise it",
                            component.getSimpleName(), method.getName())
                    .isTrue();
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("componentBackEnds")
    void theFluentEntryPoint_isNotAnnotated(final Class<?> component,
                                            final String entryPointName,
                                            final List<String> backEndNames) throws NoSuchMethodException {

        // the entry point only constructs a builder, so timing it would measure nothing; the work is timed
        // where it happens, in the back-end the terminal re-enters through the proxy
        assertThat(AnnotationUtils.getAnnotation(component.getMethod(entryPointName), PxlPerformanceLogging.class))
                .as("%s.%s() must not be timed", component.getSimpleName(), entryPointName)
                .isNull();
    }

    @Test
    void theFacade_isNotAnnotatedAnywhere() {
        // PxlSpring hands out the owning component's builder and nothing else, so it is entry points all the
        // way down - the work still runs inside the proxied component
        assertThat(annotatedMethodNames(PxlSpring.class)).isEmpty();
    }

    // ----- the opt-in gate -----

    @Test
    void theGateDoesNotDependOnSpringBoot() {
        // Regression guard for a bug this used to have. The gate was Boot's
        // @ConditionalOnExpression("${pxl.performance.logging.enabled:false}"), which lives in
        // spring-boot-autoconfigure - a `provided` dependency a plain (non-Boot) Spring application does not
        // have. Spring reads bean metadata with ASM and silently drops an annotation whose type will not
        // load, so on such a classpath the condition vanished with it: the aspect registered
        // unconditionally, logging two INFO lines per operation that no property could switch off.
        //
        // The classpath here does have Boot, so this cannot reproduce the failure directly; what it can pin
        // is the property that made it possible. The gate must come from spring-context alone.
        final List<String> annotationTypes = Arrays.stream(PxlPerformanceLoggingAspect.class.getAnnotations())
                .map(annotation -> annotation.annotationType().getName())
                .collect(Collectors.toList());

        assertThat(annotationTypes).noneMatch(name -> name.startsWith("org.springframework.boot."));

        assertThat(PxlPerformanceLoggingAspect.class.isAnnotationPresent(Conditional.class))
                .as("the aspect must stay gated by a spring-context @Conditional")
                .isTrue();
    }

    @Test
    void withoutTheProperty_theAspectIsNotRegistered() {
        // disabled by default: no property, no aspect
        assertThat(aspectRegisteredWith(null)).isFalse();
    }

    @ParameterizedTest(name = "\"{0}\"")
    @ValueSource(strings = {"true", "TRUE", "True", "  true  ", "on", "yes", "1"})
    void anEnablingValue_registersTheAspect(final String value) {
        // the value goes through Spring's own String-to-Boolean conversion, so the whole relaxed vocabulary
        // works, case-insensitively and trimmed - not just the literal "true" the docs quote
        assertThat(aspectRegisteredWith(value)).isTrue();
    }

    @ParameterizedTest(name = "\"{0}\"")
    @ValueSource(strings = {"false", "off", "no", "0", "", "   "})
    void aDisablingValue_leavesTheAspectUnregistered(final String value) {
        // a blank value converts to null, which is where the getProperty default (false) takes over
        assertThat(aspectRegisteredWith(value)).isFalse();
    }

    @Test
    void aMalformedValue_failsFastInsteadOfSilentlyDisabling() {
        // The default passed to getProperty covers an *absent* property only; a present but unconvertible
        // one raises instead. That is the better half of the trade: a typo like "ture" quietly producing no
        // logging would be undiagnosable, whereas this fails at context startup and names the bad value.
        assertThatThrownBy(() -> aspectRegisteredWith("ture"))
                .isInstanceOf(ConversionFailedException.class)
                .hasMessageContaining("ture");
    }

    /**
     * Refreshes a context that registers the aspect with {@code pxl.performance.logging.enabled} set to the given
     * value ({@code null} leaves the property unset) and reports whether the bean survived the condition.
     */
    private static boolean aspectRegisteredWith(final String enabled) {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            if (Objects.nonNull(enabled)) {
                context.getEnvironment().getPropertySources().addFirst(new MapPropertySource(
                        "pxl-test", Collections.<String, Object>singletonMap("pxl.performance.logging.enabled", enabled)));
            }

            context.register(PxlPerformanceLoggingAspect.class);
            context.refresh();

            return context.getBeanNamesForType(PxlPerformanceLoggingAspect.class).length > 0;
        }
    }

    @Test
    void theComponentsCarryNineteenBackEndsBetweenThem() {
        // the count the docs quote; a new destination or source form has to be added deliberately, not by
        // accident
        final long total = componentBackEnds()
                .mapToLong(arguments -> annotatedMethodNames((Class<?>) arguments.get()[0]).size())
                .sum();

        assertThat(total).isEqualTo(19);
    }

    private static List<String> annotatedMethodNames(final Class<?> component) {
        return Arrays.stream(component.getDeclaredMethods())
                .filter(method -> method.isAnnotationPresent(PxlPerformanceLogging.class))
                .map(Method::getName)
                .collect(Collectors.toList());
    }

    // ----- log capture -----

    /**
     * Runs the given call against a freshly advised {@link Service} and returns the messages the aspect
     * logged, in order.
     *
     * <p>The aspect logs through SLF4J at {@code INFO}; log4j-core (the test binding's implementation) is
     * driven by its default configuration here, whose root level is {@code ERROR}, so the level is raised on
     * this one logger for the duration of the call and restored afterwards.</p>
     */
    private static List<String> logsFrom(final long lowPerformanceInMs,
                                         final Consumer<Service> call)
            throws ReflectiveOperationException {

        final CapturingAppender appender = new CapturingAppender();
        appender.start();

        final Logger logger = (Logger) LogManager.getLogger(PxlPerformanceLoggingAspect.class);
        final Level previousLevel = logger.getLevel();
        logger.addAppender(appender);
        logger.setLevel(Level.INFO);
        try {
            call.accept(proxy(lowPerformanceInMs));
        } finally {
            logger.setLevel(previousLevel);
            logger.removeAppender(appender);
            appender.stop();
        }

        return appender.messages;
    }

    /**
     * Minimal log4j appender that keeps the formatted message of every event it receives.
     */
    private static final class CapturingAppender extends AbstractAppender {

        private final List<String> messages = new ArrayList<>();

        private CapturingAppender() {
            super("pxl-capture", null, null, true, Property.EMPTY_ARRAY);
        }

        @Override
        public void append(final LogEvent event) {
            messages.add(event.getMessage().getFormattedMessage());
        }
    }
}
