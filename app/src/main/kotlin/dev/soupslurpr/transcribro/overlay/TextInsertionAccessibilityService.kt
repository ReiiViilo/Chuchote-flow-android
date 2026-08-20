package dev.soupslurpr.transcribro.overlay

import android.accessibilityservice.AccessibilityService
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import dev.soupslurpr.transcribro.memory.ChuchoteStore
import dev.soupslurpr.transcribro.memory.CorrectionDiff

/**
 * Insère le texte dicté dans le champ de saisie actif, quelle que soit
 * l'application affichée — puis observe ce champ quelques instants : si
 * l'utilisateur corrige un mot à la main, un pop-up propose d'ajouter la
 * correction au dictionnaire, pour que la dictée apprenne.
 *
 * Le widget flottant ne peut pas s'en charger lui-même : sa fenêtre est
 * volontairement non focalisable pour que le champ de l'app hôte garde le
 * focus, et le clavier actif n'est pas forcément le nôtre. Passer par le
 * service d'accessibilité est la seule voie qui fonctionne partout.
 */
class TextInsertionAccessibilityService : AccessibilityService() {

    private val handler = Handler(Looper.getMainLooper())

    // L'observation en cours : le texte du champ juste après l'insertion, le
    // dernier état vu, et le nombre de relevés restants.
    private var baseline: String? = null
    private var lastSeen: String? = null
    private var pollsLeft = 0

    private var proposalView: View? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        instance = null
        handler.removeCallbacksAndMessages(null)
        removeProposal()
        return super.onUnbind(intent)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Rien à faire : le service n'écoute pas d'événements, il agit sur
        // demande du widget et observe le champ par relevés espacés.
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
            if (focused.performAction(AccessibilityNodeInfo.ACTION_PASTE)) {
                startWatching()
                return true
            }
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
        val inserted = focused.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)
        if (inserted) startWatching()
        return inserted
    }

    // --- Apprentissage des corrections -------------------------------------

    /**
     * Observe le champ après une insertion : un premier relevé fige le texte
     * de référence, puis des relevés espacés suivent les retouches de
     * l'utilisateur. L'observation se termine quand le champ se vide (message
     * envoyé), disparaît, ou après une trentaine de secondes.
     */
    private fun startWatching() {
        handler.removeCallbacks(pollRunnable)
        baseline = null
        lastSeen = null
        pollsLeft = MAX_POLLS
        // Premier relevé différé : le collage vient d'être demandé mais le
        // champ ne reflète pas encore forcément le nouveau texte.
        handler.postDelayed(pollRunnable, FIRST_POLL_MS)
    }

    private val pollRunnable = object : Runnable {
        override fun run() {
            val current = findFocusedEditable()?.text?.toString()

            if (baseline == null) {
                // Pas de texte de référence : sans lui, impossible de savoir ce
                // qui a changé. Abandonner plutôt que deviner.
                if (current.isNullOrBlank()) return
                baseline = current
                lastSeen = current
                handler.postDelayed(this, POLL_INTERVAL_MS)
                return
            }

            if (current.isNullOrEmpty()) {
                // Champ vidé ou disparu : le message est parti. Le dernier
                // relevé est l'état final du texte.
                finishWatching()
                return
            }

            lastSeen = current
            pollsLeft--
            if (pollsLeft <= 0) {
                finishWatching()
            } else {
                handler.postDelayed(this, POLL_INTERVAL_MS)
            }
        }
    }

    private fun finishWatching() {
        val avant = baseline ?: return
        val apres = lastSeen ?: return
        baseline = null
        lastSeen = null

        val store = ChuchoteStore.get(this)
        val propositions = CorrectionDiff.proposer(avant, apres)
            .filterNot { proposition ->
                store.dictionnaire.value.any {
                    it.entendu.equals(proposition.entendu, ignoreCase = true)
                }
            }

        if (propositions.isNotEmpty()) showProposal(propositions)
    }

    // --- Pop-up de proposition ----------------------------------------------

    /**
     * Petite carte posée en bas de l'écran : chaque correction détectée y est
     * proposée avec un bouton « Ajouter ». Elle s'efface d'elle-même — un
     * refus ne doit rien coûter, pas même un geste.
     */
    private fun showProposal(propositions: List<CorrectionDiff.Proposition>) {
        removeProposal()

        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(12))
            background = GradientDrawable().apply {
                cornerRadius = dp(16).toFloat()
                setColor(Color.parseColor("#F2141334"))
                setStroke(dp(1), Color.parseColor("#5596F5FF"))
            }
        }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        header.addView(
            TextView(this).apply {
                text = "Ajouter au dictionnaire ?"
                setTextColor(Color.parseColor("#E3E1F1"))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                setTypeface(typeface, Typeface.BOLD)
            },
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        )
        header.addView(
            TextView(this).apply {
                text = "✕"
                setTextColor(Color.parseColor("#8F8FB0"))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
                setPadding(dp(8), 0, 0, 0)
                setOnClickListener { removeProposal() }
            }
        )
        card.addView(header)

        for (proposition in propositions) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(8), 0, 0)
            }
            row.addView(
                TextView(this).apply {
                    text = "« ${proposition.entendu} » → « ${proposition.remplacerPar} »"
                    setTextColor(Color.WHITE)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                },
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            )
            row.addView(
                TextView(this).apply {
                    text = "Ajouter"
                    setTextColor(Color.parseColor("#8FEFFB"))
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                    setTypeface(typeface, Typeface.BOLD)
                    setPadding(dp(12), dp(4), dp(4), dp(4))
                    setOnClickListener {
                        ChuchoteStore.get(this@TextInsertionAccessibilityService)
                            .ajouterEntree(proposition.entendu, proposition.remplacerPar)
                        Toast.makeText(
                            this@TextInsertionAccessibilityService,
                            "« ${proposition.remplacerPar} » ajouté au dictionnaire",
                            Toast.LENGTH_SHORT
                        ).show()
                        (row.parent as? LinearLayout)?.removeView(row)
                        // Plus rien à proposer : la carte n'a plus de raison
                        // de rester (il ne reste que l'en-tête).
                        if (card.childCount <= 1) removeProposal()
                    }
                }
            )
            card.addView(row)
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            // La superposition d'accessibilité ne requiert aucune permission
            // supplémentaire, contrairement à TYPE_APPLICATION_OVERLAY.
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            y = dp(96)
            horizontalMargin = 0.04f
        }

        val windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        runCatching { windowManager.addView(card, params) }
            .onSuccess {
                proposalView = card
                handler.postDelayed({ removeProposal() }, PROPOSAL_TIMEOUT_MS)
            }
    }

    private fun removeProposal() {
        val view = proposalView ?: return
        proposalView = null
        val windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        runCatching { windowManager.removeView(view) }
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    companion object {
        private const val CLIP_LABEL = "Chuchote Flow"

        private const val FIRST_POLL_MS = 1_200L
        private const val POLL_INTERVAL_MS = 3_000L
        private const val MAX_POLLS = 10
        private const val PROPOSAL_TIMEOUT_MS = 25_000L

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
