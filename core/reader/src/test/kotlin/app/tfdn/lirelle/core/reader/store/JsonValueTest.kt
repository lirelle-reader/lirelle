package app.tfdn.lirelle.core.reader.store

import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import app.tfdn.lirelle.core.reader.marks.MemoryDataStore
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.encoding.Decoder
import org.junit.Test

/** A value shares its store with others: only a change of its own key is read again. */
@OptIn(ExperimentalCoroutinesApi::class)
class JsonValueTest {

    /** Strings, counting how many times one was decoded. */
    private class Counting(
        private val inner: KSerializer<String> = String.serializer(),
    ) : KSerializer<String> by inner {
        var decodes = 0

        override fun deserialize(decoder: Decoder): String {
            decodes++
            return inner.deserialize(decoder)
        }
    }

    private val dataStore = MemoryDataStore()
    private val serializer = Counting()
    private val value = JsonValue(dataStore, "mine", serializer, "")

    @Test
    fun `a write to another key neither emits nor decodes the value again`() = runTest {
        value.change { "kept" }
        val seen = mutableListOf<String>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { value.flow.collect { seen += it } }
        runCurrent()
        val before = serializer.decodes

        repeat(3) { page -> dataStore.edit { it[stringPreferencesKey("other")] = "page $page" } }
        runCurrent()

        assertThat(seen).containsExactly("kept")
        assertThat(serializer.decodes).isEqualTo(before)
    }

    @Test
    fun `a change of its own key is emitted`() = runTest {
        val seen = mutableListOf<String>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { value.flow.collect { seen += it } }
        runCurrent()

        value.change { "first" }
        dataStore.edit { it[stringPreferencesKey("other")] = "page" }
        value.change { "second" }
        runCurrent()

        assertThat(seen).containsExactly("", "first", "second").inOrder()
    }
}
