package app.tfdn.lirelle.core.comics.pdf

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class DrawPlanTest {

    private val first = FirstLongSidePx

    @Test
    fun `nothing is drawn when nothing was asked`() {
        assertThat(DrawPlan().next()).isNull()
    }

    @Test
    fun `the window is drawn in its order`() {
        var plan = DrawPlan().settled(listOf(10, 11, 12, 9))
        val order = List(4) { plan.next()!!.also { plan = plan.started(it) } }

        assertThat(order).containsExactly(Drawing(10, first), Drawing(11, first), Drawing(12, first), Drawing(9, first))
            .inOrder()
        assertThat(plan.next()).isNull()
    }

    @Test
    fun `the page shown goes before the window and before a sharper drawing`() {
        val plan = DrawPlan().settled(listOf(10, 11)).sharpened(Drawing(10, 4096)).shown(30)

        assertThat(plan.next()).isEqualTo(Drawing(30, first))
    }

    @Test
    fun `a sharper drawing goes before the window`() {
        val plan = DrawPlan().settled(listOf(11, 12)).sharpened(Drawing(10, 4096))

        assertThat(plan.next()).isEqualTo(Drawing(10, 4096))
        assertThat(plan.started(Drawing(10, 4096)).next()).isEqualTo(Drawing(11, first))
    }

    @Test
    fun `only the last page shown is drawn first`() {
        val plan = DrawPlan().shown(3).shown(4)

        assertThat(plan.next()).isEqualTo(Drawing(4, first))
        assertThat(plan.started(Drawing(4, first)).next()).isNull()
    }

    @Test
    fun `a page drawn for being shown does not wait in the window any more`() {
        val plan = DrawPlan().settled(listOf(10, 11)).shown(10).started(Drawing(10, first))

        assertThat(plan.next()).isEqualTo(Drawing(11, first))
    }

    @Test
    fun `a page asked sharper twice is drawn once, at the last size asked`() {
        val plan = DrawPlan().sharpened(Drawing(2, 3000)).sharpened(Drawing(2, 4096))

        assertThat(plan.next()).isEqualTo(Drawing(2, 4096))
        assertThat(plan.started(Drawing(2, 4096)).next()).isNull()
    }

    @Test
    fun `a new window replaces the one that waited`() {
        val plan = DrawPlan().settled(listOf(1, 2, 3)).settled(listOf(7, 8))

        assertThat(plan.waiting).containsExactly(7, 8).inOrder()
    }
}
