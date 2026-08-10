package io.github.hclimkr.pxl.spring.internal.support;

import io.github.hclimkr.pxl.Pxl;
import io.github.hclimkr.pxl.spring.component.*;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests for the {@link PxlCoreSupport} holder. It pins down the class's own contract - a non-instantiable
 * static-helper holder handing out one stable {@link Pxl} - and, more importantly, guards the reason it
 * exists: every component must take its core from here rather than calling {@code new Pxl()} itself, because
 * each {@code new Pxl()} bootstraps (and never closes) another bean-validation {@code ValidatorFactory}.
 *
 * <p>The sharing has no observable behaviour of its own - {@link Pxl} is immutable and every factory method
 * on it returns a fresh builder either way - so the guard has to read each component's private {@code pxl}
 * field reflectively. That is also why this test class, alone among the {@code internal.support} tests,
 * reaches up into the {@code component} package; the production dependency still runs one way only
 * ({@code component} &rarr; {@code internal.support}).</p>
 */
class PxlCoreSupportTests {

    @Test
    void privateConstructor_rejectsInstantiation() throws NoSuchMethodException {
        final Constructor<PxlCoreSupport> constructor = PxlCoreSupport.class.getDeclaredConstructor();
        assertThat(constructor.isAccessible()).isFalse();
        constructor.setAccessible(true);

        // reflective newInstance wraps the constructor's AssertionError in InvocationTargetException
        assertThatThrownBy(constructor::newInstance)
                .cause()
                .isInstanceOf(AssertionError.class);
    }

    @Test
    void core_returnsTheSameInstanceEveryTime() {
        assertThat(PxlCoreSupport.core())
                .isNotNull()
                .isSameAs(PxlCoreSupport.core());
    }

    @Test
    void everyComponent_takesItsCoreFromTheSharedHolder() throws NoSuchFieldException, IllegalAccessException {
        // freshly constructed instances, i.e. the plain (non-Spring) path a consumer can also take
        assertThat(coreOf(new PxlExcelImporter())).isSameAs(PxlCoreSupport.core());
        assertThat(coreOf(new PxlCsvImporter())).isSameAs(PxlCoreSupport.core());
        assertThat(coreOf(new PxlExcelExporter())).isSameAs(PxlCoreSupport.core());
        assertThat(coreOf(new PxlSampleExcelExporter())).isSameAs(PxlCoreSupport.core());
        assertThat(coreOf(new PxlExcelZipExporter())).isSameAs(PxlCoreSupport.core());
        assertThat(coreOf(new PxlCsvExporter())).isSameAs(PxlCoreSupport.core());
        assertThat(coreOf(new PxlSampleCsvExporter())).isSameAs(PxlCoreSupport.core());
    }

    /**
     * Reads a component's private {@code pxl} field.
     *
     * @param component the component instance to inspect
     * @return the core entry point that component holds
     * @throws NoSuchFieldException   if the field was renamed
     * @throws IllegalAccessException if the field cannot be made accessible
     */
    private static Pxl coreOf(final Object component)
            throws NoSuchFieldException, IllegalAccessException {

        final Field field = component.getClass().getDeclaredField("pxl");
        field.setAccessible(true);

        return (Pxl) field.get(component);
    }

}
