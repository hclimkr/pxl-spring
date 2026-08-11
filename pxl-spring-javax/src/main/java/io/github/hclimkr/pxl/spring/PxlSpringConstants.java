package io.github.hclimkr.pxl.spring;

import org.springframework.util.FastByteArrayOutputStream;

/**
 * Tuning constants shared by more than one component.
 *
 * <p>Only values that several components must agree on belong here. Anything a single class owns stays a
 * {@code private static final} field of that class, and anything a caller is meant to choose belongs on the
 * fluent builders instead - this is not a configuration surface.</p>
 *
 * <p>Public because the components that read it sit in {@code io.github.hclimkr.pxl.spring.component}, one
 * package down. The values are implementation detail: they may change with any release, so do not build
 * behaviour on top of them.</p>
 *
 * <p>A holder, not a type: reference the constants through the interface name and never implement it - an
 * implementing class would inherit them into its own API surface for no reason.</p>
 */
public interface PxlSpringConstants {

    /**
     * Size of the first block of a download body buffer.
     *
     * <p>The buffer is a {@link FastByteArrayOutputStream}, which holds its output as a deque of blocks and
     * adds a block rather than growing one, so this figure is the first block's size and not a ceiling.
     * Its own default is 256 bytes, so even the smallest workbook - a minimal XLSX container runs to a few
     * kilobytes - would be spread over a run of little blocks. This starts above that floor so a typical
     * small download lands in one.</p>
     *
     * <p>Deliberately modest rather than generous: a large first block would be wasted on every small
     * export, and with many concurrent downloads that waste is what shows up as peak heap. A large export
     * needs no hint from here - it simply gets more blocks, at no copying cost - so this removes the churn
     * of many small allocations rather than solving that case.</p>
     */
    int DOWNLOAD_BUFFER_INITIAL_BYTES = 32 * 1024;

}
