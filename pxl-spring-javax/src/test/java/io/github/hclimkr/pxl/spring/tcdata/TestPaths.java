package io.github.hclimkr.pxl.spring.tcdata;

import org.junit.jupiter.api.TestInfo;

import java.io.File;
import java.lang.reflect.Method;

/**
 * Shared location for test export artifacts.
 *
 * <p>File-based export tests write real files under {@code target/test-outputs/} and leave them in
 * place, so they can be opened and inspected after the run (they are intentionally not deleted, and are
 * removed by {@code mvn clean} along with the rest of {@code target/}). Round-trip tests name the
 * artifact after the current test method - pass the {@link TestInfo} injected in a
 * {@code @BeforeEach} method.</p>
 */
public final class TestPaths {

    public static final String EXPORT_DIR = "target/test-outputs";

    private TestPaths() {
    }

    /**
     * Returns a file handle under the export directory, creating the directory if it is absent.
     *
     * @param name the file name
     * @return the file handle
     */
    public static File exportFile(final String name) {
        final File dir = new File(EXPORT_DIR);
        dir.mkdirs();
        return new File(dir, name);
    }

    /**
     * Returns a {@code <methodName>.xlsx} file handle for the current test method.
     *
     * @param testInfo the JUnit test info for the running method
     * @return the file handle
     */
    public static File exportFile(final TestInfo testInfo) {
        return exportFile(testInfo, ".xlsx");
    }

    /**
     * Returns a {@code <methodName><extension>} file handle for the current test method
     * (e.g. {@code ".zip"}).
     *
     * @param testInfo  the JUnit test info for the running method
     * @param extension the file extension including the leading dot
     * @return the file handle
     */
    public static File exportFile(final TestInfo testInfo, final String extension) {
        return exportFile(methodName(testInfo) + extension);
    }

    private static String methodName(final TestInfo testInfo) {
        return testInfo.getTestMethod()
                .map(Method::getName)
                .orElseThrow(() -> new IllegalStateException("cannot resolve test method name"));
    }
}
