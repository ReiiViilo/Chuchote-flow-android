package dev.soupslurpr.transcribro.overlay

import android.accessibilityservice.AccessibilityService
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Insère le texte dicté dans le champ de saisie actif, quelle que soit
 * l'application affichée.
 *
 * Le widget flottant ne peut pas s'en charger lui-même : sa fenêtre est
 * volontairement non focalisable pour que le champ de l'app hôte garde le
 * focus, et le clavier actif n'est pas forcément le nôtre. Passer par le
 * service d'accessibilité est la seule voie qui fonctionne partout.
 */
class TextInsertionAccessibilityService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        instance = null
        return super.onUnbind(intent)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Rien à faire : le service n'observe pas, il agit seulement sur demande
        // du widget via insertText().
    }

    override fun onInterrupt() {
        // Sans objet : aucune action de longue durée à interrompre.
    }

    /**
     * Cherche le champ modifiable qui a le focus.
     *
     * `rootInActiveWindow` ne suffit pas : quand le widget est affiché, la
     * fenêtre « active » peut être notre propre superposition, et la recherche
     * échoue alors alors que le champ existe bel et bien dans l'application du
     * dessous. On balaie donc toutes les fenêtres, en ignorant les nôtres.
     */
    private fun findFocusedEditable(): AccessibilityNodeInfo? {
        rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)?.let {
            if (it.isEditable) return it
        }

        for (window in windows) {
            val root = window.root ?: continue
            if (root.packageName == packageName) continue
            val focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: continue
            if (focused.isEditable) return focused
        }

        return null
    }

    private fun insertIntoFocusedField(text: String): Boolean {
        val focused = findFocusedEditable() ?: return false

        // Le collage respecte la position du curseur et la sélection en cours,
        // contrairement à ACTION_SET_TEXT qui réécrit tout le champ.
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        if (clipboard != null) {
            clipboard.setPrimaryClip(ClipData.newPlainText(CLIP_LABEL, text))
            if (focused.performAction(AccessibilityNodeInfo.ACTION_PASTE)) return true
        }

        // Repli : certains champs refusent le collage. On réécrit alors le champ
        // en conservant ce qu'il contenait déjà.
        val existing = focused.text?.toString() ?: ""
        val arguments = Bundle().apply {
            putCharSequence(
                AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                existing + text
            )
        }
        return focused.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)
    }

    companion object {
        private const val CLIP_LABEL = "Chuchote Flow"

        @Volatile
        private var instance: TextInsertionAccessibilityService? = null

        /** true si l'utilisateur a activé le service dans les réglages d'accessibilité. */
        fun isConnected(): Boolean = instance != null

        /**
         * Tente d'insérer [text] dans le champ actif.
         * Retourne false si le service est inactif ou si aucun champ modifiable
         * n'a le focus — l'appelant se rabat alors sur le presse-papiers.
         */
        fun insertText(text: String): Boolean = instance?.insertIntoFocusedField(text) ?: false
    }
}
