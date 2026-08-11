package io.github.hclimkr.pxl.spring.internal.support;

import io.github.hclimkr.pxl.exception.PxlIOException;
import io.github.hclimkr.pxl.type.PxlFileFormat;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.util.FastByteArrayOutputStream;
import org.springframework.util.StreamUtils;

import javax.servlet.ServletOutputStream;
import javax.servlet.WriteListener;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Constructor;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests for the {@link PxlExportSupport} utility class itself. Its response/header helpers are covered
 * indirectly by the exporter component tests; this pins down the class's own contract: it is a
 * non-instantiable static-helper holder whose private constructor rejects reflective instantiation,
 * its buffer-to-response writers translate a body-write {@code IOException} into {@link PxlIOException},
 * and the body it puts in a {@link ResponseEntity} is a view of the download buffer rather than a copy of
 * it - which only holds if that view spans every block of the buffer, survives the buffer being closed and
 * can be read more than once.
 */
class PxlExportSupportTests {

    @Test
    void privateConstructor_rejectsInstantiation() throws NoSuchMethodException {
        final Constructor<PxlExportSupport> constructor = PxlExportSupport.class.getDeclaredConstructor();
        assertThat(constructor.isAccessible()).isFalse();
        constructor.setAccessible(true);

        // reflective newInstance wraps the constructor's AssertionError in InvocationTargetException
        assertThatThrownBy(constructor::newInstance)
                .cause()
                .isInstanceOf(AssertionError.class);
    }

    @Test
    void writeBufferToResponseForExport_whenBodyWriteFails_throwsPxlIOException() throws IOException {
        final FastByteArrayOutputStream outputStream = new FastByteArrayOutputStream();
        outputStream.write('x');

        assertThatThrownBy(() -> PxlExportSupport.writeBufferToResponseForExport(
                outputStream, "data", PxlFileFormat.XLSX, failingBodyResponse()))
                .isInstanceOf(PxlIOException.class);
    }

    @Test
    void writeBufferToResponseForExportZip_whenBodyWriteFails_throwsPxlIOException() throws IOException {
        final FastByteArrayOutputStream outputStream = new FastByteArrayOutputStream();
        outputStream.write('x');

        assertThatThrownBy(() -> PxlExportSupport.writeBufferToResponseForExportZip(
                outputStream, "data", failingBodyResponse()))
                .isInstanceOf(PxlIOException.class);
    }

    // ----- Content-Length -----
    // The body is fully buffered before the response is touched, so its length is known and must be sent:
    // without it the response goes out chunked (no download progress) and disagrees with the ResponseEntity
    // destinations, which set the length from the same buffer.

    @Test
    void writeBufferToResponseForExport_setsContentLengthFromTheBuffer() throws PxlIOException, IOException {
        final FastByteArrayOutputStream outputStream = new FastByteArrayOutputStream();
        outputStream.write('x');
        outputStream.write('y');

        final MockHttpServletResponse response = new MockHttpServletResponse();
        PxlExportSupport.writeBufferToResponseForExport(outputStream, "data", PxlFileFormat.XLSX, response);

        assertThat(response.getContentLength()).isEqualTo(2);
        assertThat(response.getContentAsByteArray()).containsExactly('x', 'y');
    }

    @Test
    void writeBufferToResponseForExportZip_setsContentLengthFromTheBuffer() throws PxlIOException, IOException {
        final FastByteArrayOutputStream outputStream = new FastByteArrayOutputStream();
        outputStream.write('x');
        outputStream.write('y');
        outputStream.write('z');

        final MockHttpServletResponse response = new MockHttpServletResponse();
        PxlExportSupport.writeBufferToResponseForExportZip(outputStream, "data", response);

        assertThat(response.getContentLength()).isEqualTo(3);
        assertThat(response.getContentAsByteArray()).containsExactly('x', 'y', 'z');
    }

    // ----- ResponseEntity body: a view of the buffer, not a copy of it -----
    // The body reads the buffer where it lies instead of being handed toByteArray(), which is what keeps the
    // entity destinations from holding the finished output twice. Three properties have to hold for that to
    // be safe, and none of them is visible from the header assertions below.

    @Test
    void responseEntityBody_readsEveryBlockOfTheBuffer() throws IOException {
        // deliberately more content than the first block holds: the buffer keeps a deque of blocks rather
        // than one growing array, so a body that read only the first would come back short
        final FastByteArrayOutputStream outputStream = new FastByteArrayOutputStream(8);
        final byte[] content = "0123456789abcdefghij".getBytes(StandardCharsets.UTF_8);
        outputStream.write(content);

        final ResponseEntity<Resource> entity = PxlExportSupport.makeResponseEntityForExport(
                "data", PxlFileFormat.XLSX, outputStream);

        assertThat(bodyBytes(entity)).isEqualTo(content);
        assertThat(entity.getBody().contentLength()).isEqualTo(content.length);
        assertThat(entity.getHeaders().getContentLength()).isEqualTo(content.length);
    }

    @Test
    void responseEntityBody_survivesTheBufferBeingClosed() throws IOException {
        final FastByteArrayOutputStream outputStream = new FastByteArrayOutputStream(8);
        final byte[] content = "0123456789abcdefghij".getBytes(StandardCharsets.UTF_8);
        outputStream.write(content);

        final ResponseEntity<Resource> entity = PxlExportSupport.makeResponseEntityForExport(
                "data", PxlFileFormat.XLSX, outputStream);

        // what the export components do in their finally, which runs before the entity reaches its caller -
        // and long before the framework reads the body
        outputStream.close();

        assertThat(bodyBytes(entity)).isEqualTo(content);
    }

    @Test
    void responseEntityBody_canBeReadMoreThanOnce() throws IOException {
        final FastByteArrayOutputStream outputStream = new FastByteArrayOutputStream(8);
        final byte[] content = "0123456789abcdefghij".getBytes(StandardCharsets.UTF_8);
        outputStream.write(content);

        final ResponseEntity<Resource> entity = PxlExportSupport.makeResponseEntityForExportZip(
                "data", outputStream);

        // a single-use body - what wrapping the buffer in an InputStreamResource would give - reads empty the
        // second time, and Spring opens the resource again to answer a Range request
        assertThat(bodyBytes(entity)).isEqualTo(content);
        assertThat(bodyBytes(entity)).isEqualTo(content);
        assertThat(entity.getBody().isOpen()).isFalse();
    }

    // ----- RFC 5987 file-name encoding -----
    // URLEncoder emits application/x-www-form-urlencoded, which spells a space as "+"; RFC 5987 requires
    // "%20", so the shared encoder rewrites it. These pin that difference on both header families.

    @Test
    void excelFilenameWithSpaces_isPercentEncodedNotPlusEncoded() {
        final ResponseEntity<Resource> entity = PxlExportSupport.makeResponseEntityForExport(
                "my report", PxlFileFormat.XLSX, new FastByteArrayOutputStream());

        assertThat(entity.getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION))
                .isEqualTo("attachment; filename=\"my report.xlsx\"; filename*=UTF-8''my%20report.xlsx");
    }

    @Test
    void zipFilenameWithSpaces_isPercentEncodedNotPlusEncoded() {
        final ResponseEntity<Resource> entity = PxlExportSupport.makeResponseEntityForExportZip(
                "my archive", new FastByteArrayOutputStream());

        assertThat(entity.getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION))
                .isEqualTo("attachment; filename=\"my archive.zip\"; filename*=UTF-8''my%20archive.zip");
    }

    @Test
    void responseHeaderSetters_useTheSameEncoding() {
        final MockHttpServletResponse excelResponse = new MockHttpServletResponse();
        PxlExportSupport.setResponseForExport("my report", PxlFileFormat.XLS, excelResponse);

        assertThat(excelResponse.getHeader(HttpHeaders.CONTENT_DISPOSITION))
                .isEqualTo("attachment; filename=\"my report.xls\"; filename*=UTF-8''my%20report.xls");
        assertThat(excelResponse.getStatus()).isEqualTo(200);

        final MockHttpServletResponse zipResponse = new MockHttpServletResponse();
        PxlExportSupport.setResponseForExportZip("my archive", zipResponse);

        assertThat(zipResponse.getHeader(HttpHeaders.CONTENT_DISPOSITION))
                .isEqualTo("attachment; filename=\"my archive.zip\"; filename*=UTF-8''my%20archive.zip");
        assertThat(zipResponse.getContentType()).isEqualTo("application/zip");
    }

    // ----- ASCII filename= fallback (RFC 6266 wants both forms) -----
    // filename* is the only standard way to carry a non-ASCII name, but a client that does not implement it
    // (IE 11, curl -OJ, the many scripts that just grep for filename="...") is left with nothing. Both are
    // emitted, ASCII first so a parser that reads only the first parameter lands on the safe one.

    @Test
    void asciiFilename_isCarriedThroughUnchanged() {
        final ResponseEntity<Resource> entity = PxlExportSupport.makeResponseEntityForExport(
                "report", PxlFileFormat.XLSX, new FastByteArrayOutputStream());

        assertThat(entity.getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION))
                .isEqualTo("attachment; filename=\"report.xlsx\"; filename*=UTF-8''report.xlsx");
    }

    @Test
    void nonAsciiFilename_isSubstitutedCharacterForCharacterInTheFallback() {
        final ResponseEntity<Resource> entity = PxlExportSupport.makeResponseEntityForExport(
                "보고서", PxlFileFormat.XLSX, new FastByteArrayOutputStream());

        // substituted rather than dropped, so the extension and the length survive instead of collapsing
        // to a bare ".xlsx"
        assertThat(entity.getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION))
                .isEqualTo("attachment; filename=\"___.xlsx\"; filename*=UTF-8''%EB%B3%B4%EA%B3%A0%EC%84%9C.xlsx");
    }

    @Test
    void quoteInFilename_cannotEndTheQuotedStringEarly() {
        final ResponseEntity<Resource> entity = PxlExportSupport.makeResponseEntityForExport(
                "a\"b\\c", PxlFileFormat.XLSX, new FastByteArrayOutputStream());

        // an unescaped quote would close filename="..." and let the rest be read as more parameters
        assertThat(entity.getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION))
                .isEqualTo("attachment; filename=\"a_b_c.xlsx\"; filename*=UTF-8''a%22b%5Cc.xlsx");
    }

    @Test
    void controlCharactersInFilename_cannotSplitTheHeader() {
        final MockHttpServletResponse response = new MockHttpServletResponse();
        PxlExportSupport.setResponseForExport("a\r\nX-Evil: 1", PxlFileFormat.XLSX, response);

        // header-injection guard: filename* is safe through percent-encoding, but the ASCII fallback is
        // written literally, so it is the one place a CR/LF could have broken the header apart
        final String contentDisposition = response.getHeader(HttpHeaders.CONTENT_DISPOSITION);
        assertThat(contentDisposition).doesNotContain("\r").doesNotContain("\n");
        assertThat(contentDisposition).startsWith("attachment; filename=\"a__X-Evil: 1.xlsx\"");
    }

    /**
     * Reads a response entity's body out as bytes, through {@link Resource#getInputStream()} rather than by
     * casting to whatever implementation the helpers return - which is the point of the assertions that use
     * it: the body is a view over the download buffer, and only the {@code Resource} contract says so.
     */
    private static byte[] bodyBytes(final ResponseEntity<Resource> entity) throws IOException {
        try (InputStream inputStream = entity.getBody().getInputStream()) {
            return StreamUtils.copyToByteArray(inputStream);
        }
    }

    /**
     * A servlet response whose body stream fails on every write, so writing the buffered bytes raises an
     * {@code IOException} - the path both {@code writeBufferToResponse...} helpers translate to
     * {@link PxlIOException}. The header setters remain the mock's own (they must succeed first).
     */
    private static HttpServletResponse failingBodyResponse() {
        return new MockHttpServletResponse() {
            @Override
            public ServletOutputStream getOutputStream() {
                return new ServletOutputStream() {
                    @Override
                    public boolean isReady() {
                        return false;
                    }

                    @Override
                    public void setWriteListener(final WriteListener writeListener) {
                    }

                    @Override
                    public void write(final int b) throws IOException {
                        throw new IOException("simulated body-write failure");
                    }
                };
            }
        };
    }
}
