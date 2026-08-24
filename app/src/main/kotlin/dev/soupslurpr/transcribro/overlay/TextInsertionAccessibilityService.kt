package dev.soupslurpr.transcribro.overlay

import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Parcel
import android.os.SystemClock
import android.provider.Settings
import android.text.ParcelableSpan
import android.text.SpannableString
import android.text.Spanned
import android.util.TypedValue
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import dev.soupslurpr.transcribro.BuildConfig
import dev.soupslurpr.transcribro.MainActivity
import dev.soupslurpr.transcribro.memory.ChuchoteStore
import dev.soupslurpr.transcribro.memory.CorrectionDiff
import dev.soupslurpr.transcribro.preferences.PrivacyConsent
import java.lang.ref.WeakReference
import java.util.ArrayDeque
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

/** Résultat précis d'une demande d'insertion directe, avant le repli explicite. */
enum class TextInsertionResult {
    /** Le texte et la position finale du curseur ont été confirmés. */
    INSERTED,

    /** Le texte a été accepté, mais la position finale du curseur reste invérifiable. */
    INSERTED_CURSOR_UNCONFIRMED,

    /** Android a accepté l'action, mais le texte modifié n'a pas pu être relu. */
    ACTION_ACCEPTED_UNCONFIRMED,

    /** La politique actuelle n'a pas encore été acceptée. */
    CONSENT_REQUIRED,

    /** Le champ capturé au début de la dictée n'est plus le champ actif. */
    TARGET_CHANGED,

    /** Aucune instance vivante n'est liée (réglage désactivé ou service interrompu). */
    SERVICE_DISCONNECTED,

    /** Aucun nœud éditable externe ne détient actuellement le focus de saisie. */
    NO_FOCUSED_FIELD,

    /** Le texte source ou la sélection ne permet pas une réécriture globale sûre. */
    UNSAFE_FIELD_STATE,

    /** Le nœud est devenu obsolète ou a refusé `ACTION_SET_TEXT`. */
    ACTION_REJECTED,
}

internal class FocusedTextTarget internal constructor(
    internal val identity: TargetIdentity,
)

internal data class TargetIdentity(
    val descriptor: FocusedTargetDescriptor,
    val node: AccessibilityNodeInfo,
) {
    fun reanchor(other: AccessibilityNodeInfo): TargetIdentity? {
        val actual = descriptorFor(other) ?: return null
        val sameNode = runCatching { node == other }.getOrDefault(false)
        val matchedDescriptor = FocusedTargetMatcher.reanchorIfMatching(
            expected = descriptor,
            actual = actual,
            sameNode = sameNode,
        )
        val matches = matchedDescriptor != null
        if (BuildConfig.DEBUG) {
            Log.d(
                TARGET_LOG_TAG,
                "targetMatch=$matches sameSource=$sameNode " +
                    "expectedUnique=${!descriptor.uniqueId.isNullOrBlank()} " +
                    "actualUnique=${!actual.uniqueId.isNullOrBlank()}",
            )
        }
        return matchedDescriptor?.let { TargetIdentity(descriptor = it, node = other) }
    }

    fun matches(other: AccessibilityNodeInfo): Boolean = reanchor(other) != null

    companion object {
        fun capture(node: AccessibilityNodeInfo): TargetIdentity? {
            return TargetIdentity(
                descriptor = descriptorFor(node) ?: return null,
                node = node,
            )
        }

        private fun descriptorFor(node: AccessibilityNodeInfo): FocusedTargetDescriptor? {
            val packageName = runCatching { node.packageName?.toString() }
                .getOrNull()
                ?.takeIf { it.isNotBlank() }
                ?: return null
            val windowId = runCatching { node.windowId }
                .getOrNull()
                ?.takeIf { it >= 0 }
                ?: return null
            return FocusedTargetDescriptor(
                packageName = packageName,
                windowId = windowId,
                viewId = runCatching { node.viewIdResourceName }.getOrNull(),
                uniqueId = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    runCatching { node.uniqueId }.getOrNull()
                } else {
                    null
                },
            )
        }
    }
}

private const val TARGET_LOG_TAG = "ChuchoteTarget"

/**
 * Insère le texte dicté seulement si le champ capturé au début de la dictée
 * est toujours le champ actif, quelle que soit l'application affichée — puis
 * observe ce champ quelques instants : si
 * l'utilisateur corrige un mot à la main, un pop-up propose d'ajouter la
 * correction au dictionnaire, pour que la dictée apprenne.
 *
 * Le widget flottant ne peut pas s'en charger lui-même : sa fenêtre est
 * volontairement non focalisable pour que le champ de l'app hôte garde le
 * focus, et le clavier actif n'est pas forcément le nôtre. Passer par le
 * service d'accessibilité fournit cette insertion directe quand l'utilisateur
 * l'a explicitement activé; les champs qui ne donnent pas un état fiable sont
 * laissés intacts.
 */
class TextInsertionAccessibilityService : AccessibilityService() {

    private val handler = Handler(Looper.getMainLooper())
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var consentJob: Job? = null

    @Volatile
    private var currentPolicyAccepted = false

    // L'observation ne conserve qu'une fenêtre bornée autour de l'insertion,
    // jamais le contenu complet d'un long champ.
    private var watchTarget: TargetIdentity? = null
    private var watchWindow: CorrectionWatchWindow? = null
    private var lastSeen: String? = null
    private var pollsLeft = 0

    private var proposalView: View? = null
    private var proposalTarget: TargetIdentity? = null
    private var launcherView: View? = null
    private var launcherAttached = false
    private var lastAutoOrbEventAt = 0L
    private var lastAutoOrbTarget: String? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = WeakReference(this)
        consentJob?.cancel()
        consentJob = serviceScope.launch {
            PrivacyConsent.acceptanceFlow(this@TextInsertionAccessibilityService)
                .collect { accepted ->
                    currentPolicyAccepted = accepted
                    if (!accepted) releaseTransientState()
                }
        }
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        if (instance?.get() === this) instance = null
        consentJob?.cancel()
        consentJob = null
        currentPolicyAccepted = false
        releaseTransientState()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        if (instance?.get() === this) instance = null
        consentJob?.cancel()
        serviceScope.cancel()
        currentPolicyAccepted = false
        releaseTransientState()
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        if (!currentPolicyAccepted) {
            releaseTransientState()
            return
        }
        if (event.eventType != AccessibilityEvent.TYPE_VIEW_FOCUSED) {
            event.source?.let(::purgeIfTargetBecameSensitive)
            return
        }
        runCatching {
            val source = event.source
            if (source == null) {
                clearForUnknownFocus()
                return@runCatching
            }
            stopSensitiveWorkAfterFocusChange(source)

            val sourcePackage = source.packageName?.toString()
            val isExternalTarget = sourcePackage != null && sourcePackage != packageName
            if (source.isPassword) {
                cancelWatching()
                removeProposal()
                removeLauncher()
                return@runCatching
            }
            if (!isExternalTarget) {
                removeLauncher()
                return@runCatching
            }
            // Le lanceur est un substitut persistant au widget: un passage
            // bref sur un contrôle non éditable ne le fait pas clignoter.
            if (!isWritable(source)) return@runCatching
            if (!isAutoShowOrbEnabled(this)) {
                removeLauncher()
                return@runCatching
            }

            val targetKey = "$sourcePackage:${source.windowId}:${source.viewIdResourceName.orEmpty()}"
            val now = SystemClock.elapsedRealtime()
            if (targetKey == lastAutoOrbTarget && now - lastAutoOrbEventAt < AUTO_ORB_DEBOUNCE_MS) {
                return@runCatching
            }
            lastAutoOrbTarget = targetKey
            lastAutoOrbEventAt = now

            // Une instance vivante est seulement rappelée. Si elle n'existe
            // plus, l'AccessibilityService affiche un lanceur sans microphone;
            // seul le tap explicite de l'utilisateur démarre le widget.
            if (FloatingWidgetService.showForFocusedField()) {
                removeLauncher()
            } else {
                showLauncher()
            }
        }
    }

    override fun onInterrupt() {
        releaseTransientState()
    }

    private fun releaseTransientState() {
        handler.removeCallbacksAndMessages(null)
        clearWatchState()
        removeProposal()
        removeLauncher()
    }

    private fun clearForUnknownFocus() {
        cancelWatching()
        removeProposal()
        removeLauncher()
    }

    private fun stopSensitiveWorkAfterFocusChange(source: AccessibilityNodeInfo) {
        watchTarget?.takeUnless { it.matches(source) }?.let { cancelWatching() }
        proposalTarget?.takeUnless { it.matches(source) }?.let { removeProposal() }
    }

    private fun purgeIfTargetBecameSensitive(source: AccessibilityNodeInfo) {
        val becamePassword = runCatching { source.isPassword }.getOrDefault(true)
        if (!becamePassword) return
        if (watchTarget?.matches(source) == true) cancelWatching()
        if (proposalTarget?.matches(source) == true) removeProposal()
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
        val roots = mutableListOf<AccessibilityNodeInfo>()
        rootInActiveWindow
            ?.takeIf(::isExternalNode)
            ?.let(roots::add)
        windows
            .sortedWith(compareByDescending { it.isFocused })
            .mapNotNull { it.root }
            .filterTo(roots) { isExternalNode(it) && it !in roots }

        roots.forEach { root ->
            findFocusedIn(root)?.let { return it }
        }
        return null
    }

    private fun isExternalNode(node: AccessibilityNodeInfo): Boolean {
        val nodePackage = runCatching { node.packageName?.toString() }.getOrNull()
        return !nodePackage.isNullOrBlank() && nodePackage != packageName
    }

    private fun findFocusedIn(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        runCatching { root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) }
            .getOrNull()
            ?.takeIf(::isWritable)
            ?.let { return it }
        runCatching { root.findFocus(AccessibilityNodeInfo.FOCUS_ACCESSIBILITY) }
            .getOrNull()
            ?.takeIf { node ->
                runCatching { node.isFocused && isWritable(node) }.getOrDefault(false)
            }
            ?.let { return it }

        // Certains champs Compose/WebView exposent bien ACTION_SET_TEXT sans
        // répondre à findFocus(FOCUS_INPUT). Un balayage borné couvre ce cas.
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        var inspected = 0
        while (queue.isNotEmpty() && inspected++ < MAX_INSPECTED_NODES) {
            val node = queue.removeFirst()
            if (runCatching { node.isFocused && isWritable(node) }.getOrDefault(false)) return node
            val childCount = runCatching { node.childCount }.getOrDefault(0)
            for (index in 0 until childCount) {
                runCatching { node.getChild(index) }.getOrNull()?.let(queue::addLast)
            }
        }
        return null
    }

    private fun isWritable(node: AccessibilityNodeInfo): Boolean {
        return runCatching {
            if (!node.isEnabled) return@runCatching false
            val actions = node.actionList.map { it.id }
            // PASTE signale parfois le caractère éditable du nœud, mais cette
            // action n'est jamais exécutée ici: le clipboard reste au widget.
            node.isEditable ||
                AccessibilityNodeInfo.ACTION_SET_TEXT in actions ||
                AccessibilityNodeInfo.ACTION_PASTE in actions
        }.getOrDefault(false)
    }

    private fun captureFocusedTarget(): FocusedTextTarget? {
        if (!currentPolicyAccepted || !PrivacyConsent.isAcceptedBlocking(this)) return null
        val focused = findFocusedEditable() ?: return null
        if (!runCatching { focused.refresh() }.getOrDefault(false)) return null
        val safeAndFocused = runCatching {
            focused.isFocused &&
                isExternalNode(focused) &&
                isWritable(focused) &&
                !focused.isPassword
        }.getOrDefault(false)
        if (!safeAndFocused) return null
        return TargetIdentity.capture(focused)?.let(::FocusedTextTarget)
    }

    private fun isTargetStillFocused(target: FocusedTextTarget): Boolean {
        return findMatchingFocusedTarget(target.identity) != null
    }

    /**
     * Réacquiert la source actuellement focalisée sans jamais rejouer l'action
     * de texte. Une identité stable peut ainsi suivre une recomposition, tandis
     * qu'un changement de champ reste refusé.
     */
    private fun findMatchingFocusedTarget(expected: TargetIdentity): TargetIdentity? {
        if (!currentPolicyAccepted || !PrivacyConsent.isAcceptedBlocking(this)) return null
        val focused = findFocusedEditable() ?: return null
        if (!runCatching { focused.refresh() }.getOrDefault(false)) return null
        val current = expected.reanchor(focused) ?: return null
        val safeAndFocused = runCatching {
            focused.isFocused &&
                isExternalNode(focused) &&
                isWritable(focused) &&
                !focused.isPassword
        }.getOrDefault(false)
        return current.takeIf { safeAndFocused }
    }

    private fun insertIntoFocusedField(
        text: String,
        expectedTarget: FocusedTextTarget,
    ): TextInsertionResult {
        if (!currentPolicyAccepted || !PrivacyConsent.isAcceptedBlocking(this)) {
            return TextInsertionResult.CONSENT_REQUIRED
        }
        cancelWatching()
        removeProposal()
        val focused = findFocusedEditable() ?: return TextInsertionResult.TARGET_CHANGED
        // Un nœud devenu obsolète n'est jamais recherché puis retenté ailleurs :
        // une action acceptée mais mal signalée pourrait sinon insérer deux fois.
        if (!runCatching { focused.refresh() }.getOrDefault(false)) {
            return TextInsertionResult.ACTION_REJECTED
        }
        val mutationTarget = expectedTarget.identity.reanchor(focused)
            ?: return TextInsertionResult.TARGET_CHANGED
        val remainsFocused = runCatching {
            focused.isFocused && isExternalNode(focused) && isWritable(focused)
        }.getOrDefault(false)
        if (!remainsFocused) {
            return TextInsertionResult.ACTION_REJECTED
        }
        if (runCatching { focused.isPassword }.getOrDefault(true)) {
            return TextInsertionResult.UNSAFE_FIELD_STATE
        }

        // Les champs web et React Native (Gmail dans Chrome, ChatGPT, Claude)
        // exposent souvent un placeholder à la place d'un texte vide, ou une
        // sélection absente (-1). Interpréter ces états ici évite de refuser
        // une insertion parfaitement sûre dans un champ vide.
        val fieldState = runCatching {
            EditableFieldState.read(
                rawText = focused.text,
                showingHint = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
                    focused.isShowingHintText,
                selectionStart = focused.textSelectionStart,
                selectionEnd = focused.textSelectionEnd,
            )
        }.getOrNull()
        if (fieldState == null) {
            logInsertionRefusal(focused, "field_state_unreadable")
            return TextInsertionResult.UNSAFE_FIELD_STATE
        }
        val existing = fieldState.existing
        val composition = runCatching {
            TextInsertionComposer.compose(
                existing = existing,
                selectionStart = fieldState.selectionStart,
                selectionEnd = fieldState.selectionEnd,
                inserted = text,
            )
        }.getOrNull()
        if (composition == null) {
            logInsertionRefusal(focused, "compose_refused")
            return TextInsertionResult.UNSAFE_FIELD_STATE
        }
        val actionText = composeActionText(existing, composition)
        if (actionText == null) {
            logInsertionRefusal(focused, "span_remap_refused")
            return TextInsertionResult.UNSAFE_FIELD_STATE
        }
        val arguments = Bundle().apply {
            putCharSequence(
                AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                actionText,
            )
        }
        // ACTION_SET_TEXT traverse Binder. Une CharSequence riche dont un span
        // ne peut pas être parcelé est refusée avant toute mutation du champ.
        if (!arguments.canBeMarshalled()) return TextInsertionResult.UNSAFE_FIELD_STATE
        val actionAccepted = runCatching {
            focused.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)
        }.getOrDefault(false)
        if (!actionAccepted) {
            logInsertionRefusal(focused, "set_text_rejected")
            return TextInsertionResult.ACTION_REJECTED
        }

        // Le champ est déjà modifié à ce stade. Compose et les WebView peuvent
        // remplacer leur source en réaction à SET_TEXT : réacquérir le focus,
        // valider son identité, puis agir sur ce nœud courant. SET_TEXT n'est
        // jamais rejoué, même si cette vérification échoue.
        val postTextTarget = findMatchingFocusedTarget(mutationTarget)
            ?: return TextInsertionResult.ACTION_ACCEPTED_UNCONFIRMED
        val postTextNode = postTextTarget.node
        val selectionArguments = Bundle().apply {
            putInt(
                AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT,
                composition.cursor,
            )
            putInt(
                AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT,
                composition.cursor,
            )
        }
        runCatching {
            postTextNode.performAction(
                AccessibilityNodeInfo.ACTION_SET_SELECTION,
                selectionArguments,
            )
        }

        // La sélection peut elle-même déclencher une recomposition. La preuve
        // finale et l'observateur de corrections adoptent donc la dernière
        // source focalisée qui représente toujours le champ autorisé.
        val verificationTarget = findMatchingFocusedTarget(postTextTarget)
            ?: return TextInsertionResult.ACTION_ACCEPTED_UNCONFIRMED
        val verificationNode = verificationTarget.node
        val verification = runCatching {
            TextInsertionVerifier.verify(
                refreshed = true,
                expectedText = composition.text,
                expectedCursor = composition.cursor,
                actualText = verificationNode.text,
                actualSelectionStart = verificationNode.textSelectionStart,
                actualSelectionEnd = verificationNode.textSelectionEnd,
            )
        }.getOrDefault(TextInsertionVerification.ACTION_UNCONFIRMED)
        when (verification) {
            TextInsertionVerification.ACTION_UNCONFIRMED -> {
                // ACTION_SET_TEXT a déjà été accepté : ne jamais retenter ni
                // demander un collage, ce qui pourrait insérer deux fois.
                return TextInsertionResult.ACTION_ACCEPTED_UNCONFIRMED
            }
            TextInsertionVerification.CURSOR_UNCONFIRMED ->
                return TextInsertionResult.INSERTED_CURSOR_UNCONFIRMED
            TextInsertionVerification.CONFIRMED -> Unit
        }

        verificationTarget
            .takeUnless { runCatching { verificationNode.isPassword }.getOrDefault(true) }
            ?.let {
                startWatching(
                    target = it,
                    fullBaseline = composition.text,
                    insertionStart = composition.contentStart,
                    insertionEnd = composition.contentEnd,
                )
            }
        return TextInsertionResult.INSERTED
    }

    // --- Apprentissage des corrections -------------------------------------

    /**
     * Observe uniquement la même cible et une fenêtre bornée autour du texte
     * inséré. Un champ mot de passe, un changement de focus ou un nœud obsolète
     * annule l'observation sans proposition.
     */
    private fun startWatching(
        target: TargetIdentity,
        fullBaseline: String,
        insertionStart: Int,
        insertionEnd: Int,
    ) {
        if (!currentPolicyAccepted) return
        cancelWatching()
        removeProposal()
        val window = CorrectionWatchWindow.create(
            fullText = fullBaseline,
            insertionStart = insertionStart,
            insertionEnd = insertionEnd,
        ) ?: return

        handler.removeCallbacks(pollRunnable)
        watchTarget = target
        watchWindow = window
        lastSeen = window.baseline
        pollsLeft = MAX_POLLS
        handler.postDelayed(pollRunnable, FIRST_POLL_MS)
    }

    private val pollRunnable = object : Runnable {
        override fun run() {
            val target = watchTarget ?: return
            val window = watchWindow ?: return cancelWatching()
            val current = currentTextFor(target) ?: return cancelWatching()

            if (current.isEmpty()) {
                // Champ vidé ou disparu : le message est parti. Le dernier
                // relevé est l'état final du texte.
                finishWatching()
                return
            }

            val bounded = window.capture(current) ?: return cancelWatching()
            lastSeen = bounded
            pollsLeft--
            if (pollsLeft <= 0) {
                finishWatching()
            } else {
                handler.postDelayed(this, POLL_INTERVAL_MS)
            }
        }
    }

    private fun finishWatching() {
        val target = watchTarget ?: return clearWatchState()
        val avant = watchWindow?.baseline ?: return clearWatchState()
        val apres = lastSeen ?: return clearWatchState()
        clearWatchState()
        if (avant == apres || !isSameFocusedTarget(target)) return

        val store = ChuchoteStore.get(this)
        val propositions = CorrectionDiff.proposer(avant, apres)
            .filterNot { proposition ->
                store.dictionnaire.value.any {
                    it.entendu.equals(proposition.entendu, ignoreCase = true)
                }
            }

        if (propositions.isNotEmpty()) showProposal(propositions, target)
    }

    private fun currentTextFor(target: TargetIdentity): String? {
        val currentTarget = findMatchingFocusedTarget(target) ?: return null
        val node = currentTarget.node
        watchTarget = currentTarget
        if (runCatching { node.isPassword }.getOrDefault(true)) return null
        return runCatching { node.text?.toString() }.getOrNull()
    }

    private fun isSameFocusedTarget(target: TargetIdentity): Boolean {
        return findMatchingFocusedTarget(target) != null
    }

    private fun cancelWatching() {
        handler.removeCallbacks(pollRunnable)
        clearWatchState()
    }

    private fun clearWatchState() {
        watchTarget = null
        watchWindow = null
        lastSeen = null
        pollsLeft = 0
    }

    // --- Pop-up de proposition ----------------------------------------------

    /**
     * Petite carte posée en bas de l'écran : chaque correction détectée y est
     * proposée avec un bouton « Ajouter ». Elle s'efface d'elle-même — un
     * refus ne doit rien coûter, pas même un geste.
     */
    private fun showProposal(
        propositions: List<CorrectionDiff.Proposition>,
        target: TargetIdentity,
    ) {
        removeProposal()
        val currentTarget = findMatchingFocusedTarget(target) ?: return

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
                        if (proposalTarget?.let(::isSameFocusedTarget) != true) {
                            removeProposal()
                            return@setOnClickListener
                        }
                        // Le pop-up peut rester visible pendant que le
                        // consentement est révoqué. Relire la source durable
                        // immédiatement avant l'écriture SQLite ferme cette
                        // fenêtre, même si le collecteur en mémoire est en
                        // retard d'un événement.
                        if (
                            !currentPolicyAccepted ||
                            !PrivacyConsent.isAcceptedBlocking(
                                this@TextInsertionAccessibilityService,
                            )
                        ) {
                            currentPolicyAccepted = false
                            removeProposal()
                            return@setOnClickListener
                        }
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
                proposalTarget = currentTarget
                handler.postDelayed(
                    { if (proposalView === card) removeProposal() },
                    PROPOSAL_TIMEOUT_MS,
                )
            }
    }

    private fun removeProposal() {
        val view = proposalView
        proposalView = null
        proposalTarget = null
        if (view == null) return
        val windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        runCatching { windowManager.removeView(view) }
    }

    // --- Lanceur de secours de l'orbe --------------------------------------

    private fun showLauncher() {
        val launcher = launcherView ?: buildLauncher().also { launcherView = it }
        val (savedX, savedY) = FloatingWidgetService.loadSavedOrbPosition(this)
        val params = WindowManager.LayoutParams(
            dp(LAUNCHER_SIZE_DP),
            dp(LAUNCHER_SIZE_DP),
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = savedX
            y = savedY
        }
        val windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        if (launcherAttached) {
            runCatching { windowManager.updateViewLayout(launcher, params) }
            return
        }
        runCatching { windowManager.addView(launcher, params) }
            .onSuccess { launcherAttached = true }
    }

    private fun buildLauncher(): View = TextView(this).apply {
        text = "✦"
        gravity = Gravity.CENTER
        contentDescription = "Afficher l’orbe Chuchote Flow"
        setTextColor(Color.WHITE)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
        background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(Color.parseColor("#F2141334"))
            setStroke(dp(2), Color.parseColor("#8FEFFB"))
        }
        elevation = dp(6).toFloat()
        setOnClickListener {
            // Le Flow peut être révoqué entre l'affichage du lanceur et ce tap.
            // Revalider ici empêche même le démarrage d'un widget sans consentement.
            if (!PrivacyConsent.isAcceptedBlocking(this@TextInsertionAccessibilityService)) {
                removeLauncher()
                startActivity(
                    Intent(this@TextInsertionAccessibilityService, MainActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
                return@setOnClickListener
            }
            // Android 14+ exige qu'un service microphone soit créé depuis une
            // activité visible. Cette activité-pont ne capture rien : la
            // dictée exige encore un second tap explicite sur l'orbe principale.
            val started = runCatching {
                startActivity(
                    Intent(
                        this@TextInsertionAccessibilityService,
                        WidgetLaunchActivity::class.java,
                    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }.isSuccess
            if (!started) {
                Toast.makeText(
                    this@TextInsertionAccessibilityService,
                    "Impossible de démarrer le widget",
                    Toast.LENGTH_SHORT,
                ).show()
            }
            // Le lanceur reste visible jusqu'au signal réel envoyé après
            // l'attachement de la bulle. Un démarrage de service accepté ne
            // garantit ni la permission overlay ni addView().
            it.isEnabled = false
            handler.postDelayed({ it.isEnabled = true }, LAUNCHER_RETRY_DELAY_MS)
        }
    }

    private fun removeLauncher() {
        val launcher = launcherView
        if (launcher != null && launcherAttached) {
            val windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
            runCatching { windowManager.removeView(launcher) }
        }
        launcherAttached = false
        launcherView = null
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    /**
     * Construit la CharSequence de l'action sans aplatir un champ riche.
     *
     * Seuls les spans Android explicitement parcelables peuvent franchir
     * Binder. Les plages qui survivent à la sélection sont remaniées par le
     * contrat pur [TextInsertionSpanMapper]; un objet, un flag ou une plage
     * incohérente fait échouer toute l'insertion avant ACTION_SET_TEXT.
     */
    /**
     * Journalise la raison d'un refus d'insertion, sans jamais consigner le
     * contenu du champ : longueurs et indicateurs seulement.
     */
    private fun logInsertionRefusal(node: AccessibilityNodeInfo, reason: String) {
        if (!BuildConfig.DEBUG) return
        val textLength = runCatching { node.text?.length ?: -1 }.getOrDefault(-1)
        val hint = runCatching {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && node.isShowingHintText
        }.getOrDefault(false)
        val selStart = runCatching { node.textSelectionStart }.getOrDefault(Int.MIN_VALUE)
        val selEnd = runCatching { node.textSelectionEnd }.getOrDefault(Int.MIN_VALUE)
        Log.d(
            TARGET_LOG_TAG,
            "refus=$reason len=$textLength hint=$hint sel=$selStart..$selEnd",
        )
    }

    private fun composeActionText(
        existing: CharSequence?,
        composition: TextInsertionComposition,
    ): CharSequence? {
        if (existing !is Spanned) return composition.text

        val spans = runCatching {
            existing.getSpans(0, existing.length, Any::class.java)
        }.getOrNull() ?: return null
        if (spans.isEmpty()) return composition.text

        val result = SpannableString(composition.text)
        val replacementLength = composition.cursor - composition.replacedStart
        for (span in spans) {
            val sourceStart = runCatching { existing.getSpanStart(span) }.getOrNull()
                ?: return null
            val sourceEnd = runCatching { existing.getSpanEnd(span) }.getOrNull()
                ?: return null
            val flags = runCatching { existing.getSpanFlags(span) }.getOrNull()
                ?: return null
            when (
                val remap = TextInsertionSpanMapper.remap(
                    sourceLength = existing.length,
                    spanStart = sourceStart,
                    spanEnd = sourceEnd,
                    replacedStart = composition.replacedStart,
                    replacedEnd = composition.replacedEnd,
                    replacementLength = replacementLength,
                )
            ) {
                TextSpanRemap.DROP -> Unit
                TextSpanRemap.UNSAFE -> return null
                is TextSpanRemap.Preserve -> {
                    if (span !is ParcelableSpan) return null
                    if (
                        remap.start !in 0..result.length ||
                        remap.end !in remap.start..result.length
                    ) {
                        return null
                    }
                    val applied = runCatching {
                        result.setSpan(span, remap.start, remap.end, flags)
                    }.isSuccess
                    if (!applied) return null
                }
            }
        }
        return result.takeIf { it.toString() == composition.text }
    }

    private fun Bundle.canBeMarshalled(): Boolean {
        val parcel = runCatching { Parcel.obtain() }.getOrNull() ?: return false
        return try {
            if (hasFileDescriptors()) return false
            writeToParcel(parcel, 0)
            true
        } catch (_: Exception) {
            false
        } finally {
            parcel.recycle()
        }
    }

    companion object {
        private const val FIRST_POLL_MS = 1_200L
        private const val POLL_INTERVAL_MS = 3_000L
        private const val MAX_POLLS = 10
        private const val PROPOSAL_TIMEOUT_MS = 25_000L
        private const val MAX_INSPECTED_NODES = 700

        private const val LAUNCHER_SIZE_DP = 48
        private const val AUTO_ORB_DEBOUNCE_MS = 350L
        private const val LAUNCHER_RETRY_DELAY_MS = 1_500L
        private const val AUTO_ORB_PREFERENCES = "accessibility_orb_preferences"
        private const val AUTO_ORB_ENABLED = "auto_show_orb"

        @Volatile
        private var instance: WeakReference<TextInsertionAccessibilityService>? = null

        /** `true` uniquement quand Android a lié une instance vivante au processus courant. */
        fun isConnected(): Boolean = instance?.get() != null

        /**
         * État déclaré dans les réglages système. Il peut rester `true` alors
         * que [isConnected] vaut `false` après une interruption du service.
         */
        fun isEnabledInSettings(context: Context): Boolean {
            return runCatching {
                val component = ComponentName(
                    context,
                    TextInsertionAccessibilityService::class.java,
                ).flattenToString()
                val enabled = Settings.Secure.getString(
                    context.contentResolver,
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                ).orEmpty()
                enabled.split(':').any { it.equals(component, ignoreCase = true) }
            }.getOrDefault(false)
        }

        /** Préférence utilisateur de rappel de l'orbe, activée au premier lancement. */
        fun isAutoShowOrbEnabled(context: Context): Boolean =
            context.getSharedPreferences(AUTO_ORB_PREFERENCES, Context.MODE_PRIVATE)
                .getBoolean(AUTO_ORB_ENABLED, true)

        fun setAutoShowOrbEnabled(context: Context, enabled: Boolean) {
            context.getSharedPreferences(AUTO_ORB_PREFERENCES, Context.MODE_PRIVATE)
                .edit()
                .putBoolean(AUTO_ORB_ENABLED, enabled)
                .apply()
            if (!enabled) {
                instance?.get()?.handler?.post { instance?.get()?.removeLauncher() }
            }
        }

        /** Retire le lanceur de secours dès que le vrai widget est vivant. */
        internal fun onWidgetServiceAvailable() {
            instance?.get()?.let { service ->
                service.handler.post { service.removeLauncher() }
            }
        }

        /** Capture l'identité du champ qui recevra cette dictée, ou échoue fermé. */
        internal fun captureFocusedTarget(): FocusedTextTarget? =
            runCatching { instance?.get()?.captureFocusedTarget() }.getOrNull()

        /** Confirme que le champ capturé est encore le champ actif et sûr. */
        internal fun isTargetStillFocused(target: FocusedTextTarget?): Boolean =
            target != null && runCatching {
                instance?.get()?.isTargetStillFocused(target) == true
            }.getOrDefault(false)

        /**
         * Tente exactement une insertion directe dans le champ capturé au
         * début de la dictée, sans toucher au presse-papiers.
         */
        internal fun insertText(
            text: String,
            expectedTarget: FocusedTextTarget?,
        ): TextInsertionResult =
            runCatching {
                val target = expectedTarget ?: return@runCatching TextInsertionResult.TARGET_CHANGED
                instance?.get()?.insertIntoFocusedField(text, target)
                    ?: TextInsertionResult.SERVICE_DISCONNECTED
            }.getOrDefault(TextInsertionResult.ACTION_REJECTED)
    }
}
