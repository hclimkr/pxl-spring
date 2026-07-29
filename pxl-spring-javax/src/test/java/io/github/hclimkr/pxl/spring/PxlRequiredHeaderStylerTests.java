package io.github.hclimkr.pxl.spring;

import io.github.hclimkr.pxl.exception.PxlException;
import io.github.hclimkr.pxl.spring.tcdata.TestRequiredUser;
import org.apache.poi.ss.usermodel.*;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies the pxl {@code mandatory}&rarr;{@code required} rename is reflected here. {@link TestRequiredUser}
 * wires the renamed {@code @PxlColumn(exportColumnRequiredHeaderCellStyler = ...)} property (formerly
 * {@code exportColumnMandatoryTitleCellStyler}) on its required {@code Name} column, so this test would fail
 * to compile against a pxl version still using the old name. At runtime it confirms the property takes effect:
 * the required header renders with the required styler (black font) and the optional header with the optional
 * styler (grey-50% font).
 */
class PxlRequiredHeaderStylerTests {

    private final PxlSpring pxlSpring = new PxlSpring();

    private static short headerFontColor(final Workbook workbook, final String header) {
        final Sheet sheet = workbook.getSheetAt(0);
        final Row headerRow = sheet.getRow(0);
        for (final Cell cell : headerRow) {
            if (cell.getCellType() == CellType.STRING && header.equals(cell.getStringCellValue())) {
                final Font font = workbook.getFontAt(cell.getCellStyle().getFontIndexAsInt());
                return font.getColor();
            }
        }
        throw new AssertionError("header cell not found: " + header);
    }

    @Test
    void requiredColumnHeader_usesRequiredStyler_optionalColumnHeader_usesOptionalStyler()
            throws PxlException, IOException {
        final List<TestRequiredUser> rows = Collections.singletonList(new TestRequiredUser("Alice", 30));

        final ByteArrayOutputStream baos = new ByteArrayOutputStream();
        pxlSpring.exportExcel().sheet(TestRequiredUser.class, rows, "Users").toStream(baos);

        try (Workbook workbook = WorkbookFactory.create(new ByteArrayInputStream(baos.toByteArray()))) {
            final short requiredHeaderFontColor = headerFontColor(workbook, "Name");
            final short optionalHeaderFontColor = headerFontColor(workbook, "Age");

            // PxlHeaderRequiredStyler recolors the required header font black; PxlHeaderOptionalStyler grey-50%.
            assertThat(requiredHeaderFontColor).isEqualTo(IndexedColors.BLACK.getIndex());
            assertThat(optionalHeaderFontColor).isEqualTo(IndexedColors.GREY_50_PERCENT.getIndex());
            assertThat(requiredHeaderFontColor).isNotEqualTo(optionalHeaderFontColor);
        }
    }
}
