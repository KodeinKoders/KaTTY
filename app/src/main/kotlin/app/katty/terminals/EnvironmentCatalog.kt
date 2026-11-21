package app.katty.terminals

import app.katty.core.EnvironmentListing
import app.katty.core.Environments
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicLong

/**
 * The environments that exist in the folders of the settings ([directories]), which the menus offer and the badges tell missing ones by. The
 * folders are not watched: the list is read when it is made, and again on [refresh], which the windows call when they get the focus, a menu or the
 * settings' page opens, and the folders change. Reading it takes a listing of each folder and a reading of each file.
 *
 * @param reader Where it is read again. Tests give one that does it at once.
 */
class EnvironmentCatalog(
    private val scope: CoroutineScope,
    private val directories: () -> List<String>,
    private val reader: CoroutineDispatcher = Dispatchers.IO,
) {
    // Read once at once, so that the first frame does not show every environment as missing.
    private val state = MutableStateFlow(Environments.list(directories()))

    // Which reading is the latest asked for: an older one that ends after it does not replace it.
    private val readings = AtomicLong()

    /** The environments, as they were last read. */
    val listing: StateFlow<EnvironmentListing> get() = state

    /** Reads the folders again. */
    fun refresh() {
        val reading = readings.incrementAndGet()
        scope.launch(reader) {
            val listing = Environments.list(directories())
            if (readings.get() == reading) state.value = listing
        }
    }
}
