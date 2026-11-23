package io.github.jackwener.dsh.core.designsystem

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import com.mikepenz.markdown.compose.elements.MarkdownTableBasicText
import org.intellij.markdown.ast.ASTNode
import org.intellij.markdown.flavours.gfm.GFMElementTypes
import org.intellij.markdown.flavours.gfm.GFMTokenTypes

/**
 * A GFM table sized to its content (#63, #65): every cell shows its whole text, wrapped; columns get
 * widths from what they hold ([TableColumns]), and only a table that cannot fit the width scrolls
 * sideways. (The renderer's own table gives every column 160 dp and cuts each cell to one line.)
 */
@Composable
internal fun DshTable(content: String, node: ASTNode, style: TextStyle) {
    val c = Dsh.colors
    val rows = remember(node) {
        node.children.filter { it.type == GFMElementTypes.HEADER || it.type == GFMElementTypes.ROW }
            .map { r -> r.children.filter { it.type == GFMTokenTypes.CELL } }
    }
    val cols = rows.firstOrNull()?.size ?: 0
    if (cols == 0) return
    val header = style.copy(fontWeight = FontWeight(600))
    BoxWithConstraints(Modifier.fillMaxWidth().clip(Radius.md).background(c.bgModule)) {
        val avail = if (constraints.hasBoundedWidth) constraints.maxWidth else 0
        Box(Modifier.horizontalScroll(rememberScrollState())) {
            Layout(content = {
                rows.forEachIndexed { r, cells ->
                    repeat(cols) { col ->
                        Box(Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
                            cells.getOrNull(col)?.let { MarkdownTableBasicText(content, it, if (r == 0) header else style, maxLines = Int.MAX_VALUE, overflow = TextOverflow.Clip) }
                        }
                    }
                }
                repeat(rows.size - 1) { Box(Modifier.background(c.borderL2)) }
            }) { measurables, _ ->
                val cells = measurables.subList(0, rows.size * cols)
                val dividers = measurables.subList(rows.size * cols, measurables.size)
                val min = IntArray(cols)
                val max = IntArray(cols)
                cells.forEachIndexed { i, m ->
                    val col = i % cols
                    min[col] = maxOf(min[col], m.minIntrinsicWidth(Constraints.Infinity))
                    max[col] = maxOf(max[col], m.maxIntrinsicWidth(Constraints.Infinity))
                }
                val widths = TableColumns.widths(min, max, avail, floor = 96.dp.roundToPx(), cap = 320.dp.roundToPx(), readable = 200.dp.roundToPx())
                val width = widths.sum()
                val placed = cells.mapIndexed { i, m -> m.measure(Constraints.fixedWidth(widths[i % cols])) }
                val rowHeights = IntArray(rows.size) { r -> (0 until cols).maxOf { placed[r * cols + it].height } }
                val line = maxOf(1, Hairline.roundToPx())
                val lines = dividers.map { it.measure(Constraints.fixed(width, line)) }
                layout(width, rowHeights.sum() + line * lines.size) {
                    var y = 0
                    rowHeights.forEachIndexed { r, h ->
                        var x = 0
                        repeat(cols) { col -> placed[r * cols + col].place(x, y); x += widths[col] }
                        y += h
                        lines.getOrNull(r)?.let { it.place(0, y); y += line }
                    }
                }
            }
        }
    }
}

/** Column widths of a table, browser auto-layout style (pure: px in, px out). */
object TableColumns {
    /**
     * [min]: each column's widest unbreakable word, [max]: its widest unwrapped cell (padding included);
     * [avail]: the width to fill (0 = unknown). A column wants its unwrapped width (at most [cap]); one
     * that has to wrap keeps at least [floor] (or its whole width, if smaller), so short labels never
     * break. Fits as is → stretched to [avail] in proportion; fits wrapped → the room left goes to the
     * columns that wrap, in proportion to what they lack; fits not even then → the table scrolls and
     * every column gets a readable width (up to [readable]).
     */
    fun widths(min: IntArray, max: IntArray, avail: Int, floor: Int, cap: Int, readable: Int): IntArray {
        val n = min.size
        val want = IntArray(n) { maxOf(min[it].coerceAtMost(cap), max[it].coerceAtMost(cap)) }
        val low = IntArray(n) { maxOf(min[it], minOf(max[it], floor)).coerceAtMost(want[it]) }
        if (avail <= 0) return want
        val sumWant = want.sum()
        if (sumWant <= avail) return spread(want, avail - sumWant, want)
        val sumLow = low.sum()
        if (sumLow <= avail) return spread(low, avail - sumLow, IntArray(n) { want[it] - low[it] })
        return IntArray(n) { maxOf(low[it], minOf(want[it], readable)) }
    }

    /** [base] plus [extra] shared by [weights]; rounding leftovers go to the last weighted column. */
    private fun spread(base: IntArray, extra: Int, weights: IntArray): IntArray {
        val total = weights.sumOf { it.toLong() }
        if (extra <= 0 || total <= 0L) return base
        val out = IntArray(base.size) { base[it] + (extra.toLong() * weights[it] / total).toInt() }
        val last = weights.indexOfLast { it > 0 }
        out[last] += base.sum() + extra - out.sum()
        return out
    }
}
