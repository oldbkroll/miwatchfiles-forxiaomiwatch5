package com.example.watchfiles.browser

import java.nio.file.Path

data class BrowserSelection(
    val selectedPaths: Set<Path> = emptySet(),
) {
    val isActive: Boolean get() = selectedPaths.isNotEmpty()

    fun begin(path: Path): BrowserSelection = copy(selectedPaths = linkedSetOf(path))

    fun toggle(path: Path): BrowserSelection {
        val updated = LinkedHashSet(selectedPaths)
        if (!updated.add(path)) updated.remove(path)
        return copy(selectedPaths = updated)
    }

    fun selectAll(paths: List<Path>): BrowserSelection =
        copy(selectedPaths = LinkedHashSet(paths))

    fun clear(): BrowserSelection = BrowserSelection()
}
