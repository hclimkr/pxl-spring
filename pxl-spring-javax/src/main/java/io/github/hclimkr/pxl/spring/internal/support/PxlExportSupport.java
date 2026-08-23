package io.github.hclimkr.pxl.spring.internal.support;

import io.github.hclimkr.pxl.exception.PxlIOException;
import io.github.hclimkr.pxl.type.PxlFileFormat;
import org.apache.commons.io.FilenameUtils;
import org.springframework.core.io.AbstractResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.util.FastByteArrayOutputStream;

import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.InputStream;
import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/**
 * Shared download response/header helpers used by the export-family components.
 *
 * <p>Purely HTTP-facing: it knows about file names, content types and response bodies, and nothing about the
 * builders or about how a workbook is produced - each component generates its own bytes and hands the
 * finished buffer here.</p>
 *
 * <p>That buffer is a {@link FastByteArrayOutputStream} rather than a {@code ByteArrayOutputStream} because
 * neither of the two things done with it here has to copy the output: the servlet destinations write its
 * segments straight out with {@code writeTo(...)}, and the {@link ResponseEntity} destinations read the same
 * segments through a {@link BufferResource}. A {@code ByteArrayOutputStream} would force
 * {@code toByteArray()} on the entity path - a second full copy of the output, both arrays alive at once -
 * and would also grow by doubling, copying everything written so far each time it does.</p>
 *
 * <p>File names arrive exactly as the caller gave them - nothing here normalizes them, so a caller that
 * needs NFC has to apply it upstream - and every {@code Content-Disposition} is built by one helper,
 * {@link #contentDisposition(String, String)}, which emits both the RFC 5987 {@code filename*=UTF-8''} form
 * and a plain ASCII {@code filename=} fallback.</p>
 *
 * <p>Intended to be internal, but its callers sit in a different package
 * ({@code io.github.hclimkr.pxl.spring.component}) and there is no JPMS {@code module-info} to hide it, so
 * the class and its {@code static} helpers must be - and are - declared {@code public}. Treat them as
 * internal despite the {@code public} modifier; the {@code internal.support} package name is the marker.</p>
 */
public final class PxlExportSupport {

    private PxlExportSupport() {
        throw new AssertionError("no instances of this class");
    }

    /**
     * Writes the {@code Content-Disposition}/{@code Content-Type} headers and a {@code 200 OK} status
     * for a spreadsheet download onto the servlet response, before the body is streamed.
     *
     * <p>Format-neutral: the extension and the content type come from the given {@link PxlFileFormat}, so
     * the Excel and CSV exporters share this one helper. Only ZIP has a family of its own, that being an
     * archive rather than a file format PXL writes.</p>
     *
     * <p>Sets no {@code Content-Length}: it does not see the body. The caller does, and
     * {@link #writeBufferToResponseForExport} sets it from the finished buffer.</p>
     *
     * @param filename   the file name without extension
     * @param fileFormat the export file format (provides extension and content type)
     * @param response   the servlet response to configure
     */
    public static void setDownloadHeadersForExport(final String filename,
                                                   final PxlFileFormat fileFormat,
                                                   final HttpServletResponse response) {

        response.setHeader(HttpHeaders.CONTENT_DISPOSITION,
                contentDisposition(filename, fileFormat.getFilenameExtension()));
        response.setContentType(fileFormat.getContentType());

        response.setStatus(HttpServletResponse.SC_OK);
    }

    /**
     * Sets the download headers and writes the fully-built export bytes to the servlet response.
     *
     * <p>Called only after generation has succeeded, so the response - including any CORS headers added
     * upstream by a filter - is never touched on a generation failure, and no partial/download-flagged
     * body can leak on error.</p>
     *
     * <p>The body is complete at this point, so {@code Content-Length} is set from the buffer. Without it
     * the response would go out chunked and clients could show no download progress - and the
     * {@link ResponseEntity} destinations, which set the length from the same buffer, would disagree.</p>
     *
     * @param outputStream the completed export bytes
     * @param filename     the download file name
     * @param fileFormat   the export file format (provides extension and content type)
     * @param response     the servlet response to write to
     * @throws PxlIOException if setting headers or writing the body fails
     */
    public static void writeBufferToResponseForExport(final FastByteArrayOutputStream outputStream,
                                                      final String filename,
                                                      final PxlFileFormat fileFormat,
                                                      final HttpServletResponse response)
            throws PxlIOException {

        try {
            PxlExportSupport.setDownloadHeadersForExport(filename, fileFormat, response);
            response.setContentLength(outputStream.size());
            outputStream.writeTo(response.getOutputStream());
        } catch (IOException e) {
            throw new PxlIOException(e);
        }
    }

    /**
     * Builds a {@link ResponseEntity} carrying the produced export bytes together with the download
     * headers ({@code Content-Disposition}, {@code Content-Type}, content length).
     *
     * <p>The body reads the buffer where it lies - see {@link BufferResource} - so the finished output is
     * not copied again on its way into the entity.</p>
     *
     * @param filename     the file name without extension
     * @param fileFormat   the export file format (provides extension and content type)
     * @param outputStream the buffer holding the produced export bytes, finished and no longer written to
     * @return a {@code 200 OK} response entity with the export body and download headers
     */
    public static ResponseEntity<Resource> makeResponseEntityForExport(final String filename,
                                                                       final PxlFileFormat fileFormat,
                                                                       final FastByteArrayOutputStream outputStream) {

        final HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.CONTENT_DISPOSITION,
                contentDisposition(filename, fileFormat.getFilenameExtension()));
        headers.set(HttpHeaders.CONTENT_TYPE, fileFormat.getContentType());

        return ResponseEntity.ok()
                .headers(headers)
                .contentLength(outputStream.size())
                .body(new BufferResource(outputStream));
    }

    /**
     * Writes the ZIP download headers and a {@code 200 OK} status onto the servlet response, before
     * the archive body is streamed.
     *
     * <p>Sets no {@code Content-Length}: it does not see the body. The caller does, and
     * {@link #writeBufferToResponseForExportZip} sets it from the finished buffer.</p>
     *
     * @param zipFilename the archive file name without extension
     * @param response    the servlet response to configure
     */
    public static void setDownloadHeadersForExportZip(final String zipFilename,
                                                      final HttpServletResponse response) {

        response.setHeader(HttpHeaders.CONTENT_DISPOSITION, contentDisposition(zipFilename, "zip"));
        response.setContentType("application/zip");

        response.setStatus(HttpServletResponse.SC_OK);
    }

    /**
     * Sets the ZIP download headers and writes the fully-built archive bytes to the servlet response.
     *
     * <p>Called only after the archive has been finalized, so the response - including any CORS headers
     * added upstream by a filter - is never touched on a generation failure, and no partial/download-flagged
     * body can leak on error.</p>
     *
     * <p>The archive is complete at this point, so {@code Content-Length} is set from the buffer, for the
     * same reason as on the Excel side.</p>
     *
     * @param outputStream the completed archive bytes
     * @param zipFilename  the archive file name without extension
     * @param response     the servlet response to write to
     * @throws PxlIOException if setting headers or writing the body fails
     */
    public static void writeBufferToResponseForExportZip(final FastByteArrayOutputStream outputStream,
                                                         final String zipFilename,
                                                         final HttpServletResponse response)
            throws PxlIOException {

        try {
            PxlExportSupport.setDownloadHeadersForExportZip(zipFilename, response);
            response.setContentLength(outputStream.size());
            outputStream.writeTo(response.getOutputStream());
        } catch (IOException e) {
            throw new PxlIOException(e);
        }
    }

    /**
     * Builds a {@link ResponseEntity} carrying the produced ZIP bytes together with the download
     * headers ({@code Content-Disposition}, {@code Content-Type}, content length).
     *
     * <p>The body reads the buffer where it lies, as on the export side.</p>
     *
     * @param zipFilename  the archive file name without extension
     * @param outputStream the buffer holding the produced archive bytes, finished and no longer written to
     * @return a {@code 200 OK} response entity with the archive body and download headers
     */
    public static ResponseEntity<Resource> makeResponseEntityForExportZip(final String zipFilename,
                                                                          final FastByteArrayOutputStream outputStream) {

        final HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.CONTENT_DISPOSITION, contentDisposition(zipFilename, "zip"));
        headers.set(HttpHeaders.CONTENT_TYPE, "application/zip");

        return ResponseEntity.ok()
                .headers(headers)
                .contentLength(outputStream.size())
                .body(new BufferResource(outputStream));
    }

    /**
     * Percent-encodes a download file name for the RFC 5987 {@code filename*=UTF-8''} form.
     *
     * <p>{@link URLEncoder} produces {@code application/x-www-form-urlencoded} output, which differs from
     * RFC 5987 in one place - a space becomes {@code +} rather than {@code %20} - so spaces are rewritten
     * afterwards.</p>
     *
     * @param filenameWithExtension the file name, extension included
     * @return the percent-encoded file name
     */
    private static String urlEncodeFilename(final String filenameWithExtension) {

        try {
            return URLEncoder.encode(filenameWithExtension, StandardCharsets.UTF_8.name()).replaceAll("\\+", "%20");
        } catch (UnsupportedEncodingException e) {
            // Unreachable: every JVM is required to support UTF-8, so the charset name always resolves.
            // Centralized here so the callers need no dead catch block of their own.
            throw new IllegalStateException(e);
        }
    }

    /**
     * Builds the whole {@code Content-Disposition} value for a download - the single place any of them is
     * assembled.
     *
     * <p>Both parameter forms are emitted, as RFC 6266 intends: {@code filename*} carries the real,
     * percent-encoded UTF-8 name, and {@code filename} carries an ASCII-only rendering for clients that do
     * not implement RFC 5987 (IE 11, {@code curl -OJ}, and the many scripts that simply look for
     * {@code filename="..."}). A client that understands {@code filename*} prefers it whatever the order, so
     * the ASCII form is written first, leaving the safe value to any parser that only reads the first
     * parameter it finds.</p>
     *
     * @param filename  the file name without extension
     * @param extension the file name extension, without the dot
     * @return the {@code Content-Disposition} header value
     */
    private static String contentDisposition(final String filename,
                                             final String extension) {

        final String filenameWithExtension = filename + FilenameUtils.EXTENSION_SEPARATOR_STR + extension;

        return "attachment; filename=\"" + asciiFallbackFilename(filenameWithExtension) + "\""
                + "; filename*=UTF-8''" + urlEncodeFilename(filenameWithExtension);
    }

    /**
     * Renders a file name for the ASCII {@code filename=} parameter.
     *
     * <p>Every character that cannot stand there becomes {@code '_'}: anything outside printable US-ASCII
     * (a Korean character, say), and the two quoted-string metacharacters {@code "} and {@code \}. That last
     * part is not cosmetic - an unescaped quote would end the string early and let the rest of the name be
     * read as further header parameters, and a control character would let it break the header apart
     * altogether. The {@code filename*} form is safe from that on its own because percent-encoding leaves
     * nothing dangerous behind, so this is the only place the risk exists.</p>
     *
     * <p>Substituting rather than dropping keeps the extension and the length: a three-character Korean name
     * ending in {@code .xlsx} renders as {@code ___.xlsx} instead of collapsing to a bare {@code .xlsx}, so
     * the result is never empty.</p>
     *
     * @param filenameWithExtension the file name, extension included
     * @return an ASCII-only rendering of the same length
     */
    private static String asciiFallbackFilename(final String filenameWithExtension) {

        final StringBuilder fallback = new StringBuilder(filenameWithExtension.length());

        for (int index = 0; index < filenameWithExtension.length(); index++) {
            final char character = filenameWithExtension.charAt(index);

            fallback.append(character >= 0x20 && character < 0x7F && character != '"' && character != '\\'
                    ? character
                    : '_');
        }

        return fallback.toString();
    }

    /**
     * Read-only {@link Resource} view of a finished download buffer, used as the body of the
     * {@link ResponseEntity} destinations.
     *
     * <p>What it is for is the copy it avoids. A {@code ByteArrayResource} has to be handed an array, so the
     * buffer would have to be flattened with {@code toByteArray()} - the whole output copied a second time,
     * with both arrays alive at once - while {@link FastByteArrayOutputStream#getInputStream()} reads the
     * segments already written. Nothing else about the response changes: {@link #contentLength()} answers
     * the same figure the entity's {@code Content-Length} carries, which a {@code Range} request also
     * reads.</p>
     *
     * <p>Deliberately not {@code InputStreamResource}, which would be the other way to wrap a stream: that
     * one is single-use ({@code isOpen()} is {@code true}) and cannot report a length, whereas this opens a
     * fresh view per call and stays re-readable.</p>
     *
     * <p>What the view rests on is that the buffer is finished before a body wraps it and is never written
     * to again. The four export components close theirs as well, in a {@code finally} that outlives the
     * entity they return, so a later write there fails outright rather than altering a body already handed
     * to the framework - closing does not stop the body being read, only being changed.</p>
     */
    private static final class BufferResource extends AbstractResource {

        /**
         * The finished export bytes. Read through, never copied.
         */
        private final FastByteArrayOutputStream buffer;

        private BufferResource(final FastByteArrayOutputStream buffer) {

            this.buffer = buffer;
        }

        /**
         * Opens a view over the buffer's segments. A fresh one each call, so the resource is re-readable.
         *
         * @return a stream over the finished export bytes
         */
        @Override
        public InputStream getInputStream() {

            return buffer.getInputStream();
        }

        /**
         * @return the number of bytes written to the buffer
         */
        @Override
        public long contentLength() {

            return buffer.size();
        }

        /**
         * Always exists: the bytes are held in memory, so there is nothing to look up. Overridden because
         * {@link AbstractResource#exists()} would otherwise open and close a stream to find out.
         *
         * @return {@code true}
         */
        @Override
        public boolean exists() {

            return true;
        }

        /**
         * @return a description naming this as an in-memory download buffer, for error messages
         */
        @Override
        public String getDescription() {

            return "PXL download buffer (" + buffer.size() + " bytes)";
        }

    }

}
