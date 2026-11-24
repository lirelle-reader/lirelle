package app.tfdn.lirelle.feature.comics

import app.tfdn.lirelle.core.comics.ComicKey
import app.tfdn.lirelle.core.comics.DocumentFailure
import app.tfdn.lirelle.core.comics.OpeningProgress
import app.tfdn.lirelle.core.comics.PageCountResult
import app.tfdn.lirelle.core.library.model.PagedKind
import app.tfdn.lirelle.core.testing.MainDispatcherRule
import app.tfdn.lirelle.core.testing.samplePagedDetail
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test

/** A book read by pages that is not a comic: how a PDF and a DjVu open, and what is said when one cannot. */
class ComicReaderPagedKindsTest {

    @get:Rule
    val main = MainDispatcherRule()

    private val pdf = samplePagedDetail("PDF")
    private val pdfFile = checkNotNull(pdf.pagedFile)

    @Test
    fun `a comic says it is one`() = runTest {
        val state = ComicHarness(this).reader().state.value

        assertThat(state.kind).isEqualTo(PagedKind.Comic)
    }

    @Test
    fun `a PDF opens as one, its pages asked with the book it is of`() = runTest {
        val harness = ComicHarness(this, detail = pdf)

        val state = harness.reader().state.value

        assertThat(state.phase).isEqualTo(ComicPhase.Ready)
        assertThat(state.kind).isEqualTo(PagedKind.Pdf)
        assertThat(harness.opener.opened)
            .containsExactly(ComicKey(pdfFile.id, pdfFile.contentVersion, PagedKind.Pdf, AlbumBook))
    }

    @Test
    fun `a DjVu opens as one`() = runTest {
        val djvu = samplePagedDetail("djvu")
        val file = checkNotNull(djvu.pagedFile)
        val harness = ComicHarness(this, detail = djvu)

        val state = harness.reader().state.value

        assertThat(state.phase).isEqualTo(ComicPhase.Ready)
        assertThat(state.kind).isEqualTo(PagedKind.Djvu)
        assertThat(harness.opener.opened)
            .containsExactly(ComicKey(file.id, file.contentVersion, PagedKind.Djvu, AlbumBook))
    }

    @Test
    fun `while a PDF is fetched the state says how far it is, and no longer once it is open`() = runTest {
        val harness = ComicHarness(this, detail = pdf)
        val fetched = CompletableDeferred<Unit>()
        harness.pages.countGate = fetched

        val reader = harness.reader()
        assertThat(reader.state.value.phase).isEqualTo(ComicPhase.Loading)
        assertThat(reader.state.value.kind).isEqualTo(PagedKind.Pdf)
        assertThat(reader.state.value.opening).isNull()

        harness.pages.opening.value = OpeningProgress(3_200_000, 9_400_000)
        assertThat(reader.state.value.opening).isEqualTo(OpeningProgress(3_200_000, 9_400_000))

        harness.pages.opening.value = OpeningProgress(6_000_000, null)
        assertThat(reader.state.value.opening).isEqualTo(OpeningProgress(6_000_000, null))

        fetched.complete(Unit)
        assertThat(reader.state.value.phase).isEqualTo(ComicPhase.Ready)
        assertThat(reader.state.value.opening).isNull()

        // What the pages say after the opening is not the reader's to show.
        harness.pages.opening.value = OpeningProgress(1, 2)
        assertThat(reader.state.value.opening).isNull()
    }

    @Test
    fun `a fetch that ends in a failure leaves no progress behind`() = runTest {
        val harness = ComicHarness(this, detail = pdf)
        val fetched = CompletableDeferred<Unit>()
        harness.pages.countGate = fetched
        harness.pages.countResult = PageCountResult.Unreadable(DocumentFailure.Damaged)
        val reader = harness.reader()
        harness.pages.opening.value = OpeningProgress(3_200_000, 9_400_000)

        fetched.complete(Unit)

        assertThat(reader.state.value.phase).isEqualTo(ComicPhase.Failed(ComicFailure.Damaged))
        assertThat(reader.state.value.opening).isNull()
    }

    @Test
    fun `a PDF protected by a password says so, and is not tried again`() = runTest {
        val harness = ComicHarness(this, detail = pdf)
        harness.pages.countResult = PageCountResult.Unreadable(DocumentFailure.Protected)

        val state = harness.reader().state.value

        assertThat(state.phase).isEqualTo(ComicPhase.Failed(ComicFailure.Protected))
        assertThat(state.kind).isEqualTo(PagedKind.Pdf)
    }

    @Test
    fun `a damaged copy kept on the device is said as one a new try cannot help`() = runTest {
        val harness = ComicHarness(this, detail = pdf)
        harness.pages.countResult = PageCountResult.Unreadable(DocumentFailure.KeptDamaged)

        val state = harness.reader().state.value

        assertThat(state.phase).isEqualTo(ComicPhase.Failed(ComicFailure.KeptDamaged))
    }

    @Test
    fun `a damaged document says so, and is fetched again with the retry`() = runTest {
        val harness = ComicHarness(this, detail = pdf)
        harness.pages.countResult = PageCountResult.Unreadable(DocumentFailure.Damaged)
        val reader = harness.reader()
        assertThat(reader.state.value.phase).isEqualTo(ComicPhase.Failed(ComicFailure.Damaged))

        harness.pages.countResult = PageCountResult.Known(Pages)
        reader.retryOpening()

        assertThat(reader.state.value.phase).isEqualTo(ComicPhase.Ready)
        assertThat(harness.pages.countAsked).isEqualTo(2)
        assertThat(harness.opener.opened).hasSize(1)
    }

    @Test
    fun `a page to draw sharper is asked of the pages, with the size the zoom needs`() = runTest {
        val harness = ComicHarness(this, detail = pdf)
        val reader = harness.reader()

        reader.sharpenPage(3, 2400)

        assertThat(harness.pages.calls).contains("sharpen 3 2400")
    }
}
