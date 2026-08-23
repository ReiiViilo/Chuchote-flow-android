package dev.soupslurpr.transcribro.overlay

/** Métadonnées stables observables d'un champ de texte d'accessibilité. */
internal data class FocusedTargetDescriptor(
    val packageName: String,
    val windowId: Int,
    val viewId: String?,
    val uniqueId: String? = null,
)

/** Politique fail-closed appliquée entre le début et la fin d'une dictée. */
internal object FocusedTargetMatcher {
    /**
     * Retourne toujours le descripteur réellement observé après validation.
     * Cette distinction est importante lorsque Compose ou une WebView remplace
     * la source du nœud tout en conservant son `uniqueId` logique.
     */
    fun reanchorIfMatching(
        expected: FocusedTargetDescriptor,
        actual: FocusedTargetDescriptor,
        sameNode: Boolean,
    ): FocusedTargetDescriptor? = actual.takeIf {
        matches(expected = expected, actual = actual, sameNode = sameNode)
    }

    fun matches(
        expected: FocusedTargetDescriptor,
        actual: FocusedTargetDescriptor,
        sameNode: Boolean,
    ): Boolean {
        if (
            expected.windowId < 0 ||
            actual.windowId < 0 ||
            actual.packageName != expected.packageName ||
            actual.windowId != expected.windowId
        ) {
            return false
        }

        val expectedUniqueId = expected.uniqueId?.takeIf(String::isNotBlank)
        val actualUniqueId = actual.uniqueId?.takeIf(String::isNotBlank)
        if (expectedUniqueId != null || actualUniqueId != null) {
            return expectedUniqueId != null && expectedUniqueId == actualUniqueId
        }

        if (!sameNode) return false

        val expectedViewId = expected.viewId?.takeIf(String::isNotBlank)
        val actualViewId = actual.viewId?.takeIf(String::isNotBlank)
        if (expectedViewId != null || actualViewId != null) {
            return expectedViewId != null && expectedViewId == actualViewId
        }

        return true
    }
}
