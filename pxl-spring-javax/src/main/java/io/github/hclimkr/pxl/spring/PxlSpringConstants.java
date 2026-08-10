package io.github.hclimkr.pxl.spring;

import java.io.ByteArrayOutputStream;

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
     * Initial capacity of a download body buffer.
     *
     * <p>{@link ByteArrayOutputStream}'s own default is 32 bytes, so even the smallest workbook - a minimal
     * XLSX container runs to a few kilobytes - costs a run of doubling reallocations, each copying the whole
     * buffer. This starts above that floor so a typical small download is written into one allocation.</p>
     *
     * <p>Deliberately modest rather than generous: a large hint would be wasted on every small export, and
     * with many concurrent downloads that waste is what shows up as peak heap. It does not make a large
     * export cheap - the doublings that dominate there are the last few, which no fixed hint can avoid - so
     * it removes the pointless churn rather than solving that case.</p>
     */
    int DOWNLOAD_BUFFER_INITIAL_BYTES = 32 * 1024;

}
