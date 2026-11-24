package app.tfdn.lirelle.core.ui

import androidx.compose.ui.unit.dp
import app.tfdn.lirelle.core.designsystem.WindowWidthClass
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class GridMathTest {

    @Test
    fun `as many columns as fit the minimum item width`() {
        assertThat(columnsFor(available = 379.dp, minItemWidth = 104.dp, spacing = 12.dp)).isEqualTo(3)
        assertThat(columnsFor(available = 116.dp, minItemWidth = 104.dp, spacing = 12.dp)).isEqualTo(2)
        assertThat(columnsFor(available = 104.dp * 4 + 12.dp * 3, minItemWidth = 104.dp, spacing = 12.dp))
            .isEqualTo(4)
    }

    @Test
    fun `never fewer than the minimum number of columns`() {
        assertThat(columnsFor(available = 50.dp, minItemWidth = 104.dp, spacing = 12.dp)).isEqualTo(2)
        assertThat(columnsFor(available = 50.dp, minItemWidth = 104.dp, spacing = 12.dp, minColumns = 1))
            .isEqualTo(1)
    }

    @Test
    fun `book grid columns on the reference devices`() {
        // Phone: 411 dp wide, 16 dp padding on each side.
        assertThat(bookGridColumns(379.dp, WindowWidthClass.Compact)).isEqualTo(3)
        // Medium window with the rail: 700 - 88 - 32.
        assertThat(bookGridColumns(580.dp, WindowWidthClass.Medium)).isEqualTo(5)
        // Tablet portrait with the rail: 1001 - 88 - 32.
        assertThat(bookGridColumns(881.dp, WindowWidthClass.Expanded)).isEqualTo(5)
        // Tablet landscape with the rail: 1600 - 88 - 32.
        assertThat(bookGridColumns(1480.dp, WindowWidthClass.Expanded)).isEqualTo(8)
    }

    @Test
    fun `book list columns`() {
        assertThat(bookListColumns(WindowWidthClass.Compact)).isEqualTo(1)
        assertThat(bookListColumns(WindowWidthClass.Medium)).isEqualTo(1)
        assertThat(bookListColumns(WindowWidthClass.Expanded)).isEqualTo(2)
    }

    @Test
    fun `rows needed for a number of items`() {
        assertThat(rowCount(items = 0, columns = 3)).isEqualTo(0)
        assertThat(rowCount(items = 3, columns = 3)).isEqualTo(1)
        assertThat(rowCount(items = 4, columns = 3)).isEqualTo(2)
    }
}
