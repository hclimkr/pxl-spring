package io.github.hclimkr.pxl.spring.internal.support;

import io.github.hclimkr.pxl.exception.PxlNullPointerException;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;

import static org.assertj.core.api.Assertions.*;

/**
 * Tests for the {@link PxlArgumentSupport} guard. Its real effect is covered by the component tests, which
 * pin that a plainly constructed component rejects a {@code null} destination with
 * {@link PxlNullPointerException} rather than a raw {@code NullPointerException}; this pins the class's own
 * contract — a non-instantiable static-helper holder whose guard names the offending parameter and stays out
 * of the way otherwise.
 */
class PxlArgumentSupportTests {

    @Test
    void privateConstructor_rejectsInstantiation() throws NoSuchMethodException {
        final Constructor<PxlArgumentSupport> constructor = PxlArgumentSupport.class.getDeclaredConstructor();
        assertThat(constructor.isAccessible()).isFalse();
        constructor.setAccessible(true);

        // reflective newInstance wraps the constructor's AssertionError in InvocationTargetException
        assertThatThrownBy(constructor::newInstance)
                .cause()
                .isInstanceOf(AssertionError.class);
    }

    @Test
    void requireNonNull_namesTheOffendingParameter() {
        assertThatThrownBy(() -> PxlArgumentSupport.requireNonNull(null, "outputStream"))
                .isInstanceOf(PxlNullPointerException.class)
                .hasMessageContaining("outputStream");
    }

    @Test
    void requireNonNull_passesAnythingNonNullThrough() {
        assertThatCode(() -> PxlArgumentSupport.requireNonNull("x", "value"))
                .doesNotThrowAnyException();
    }

}
