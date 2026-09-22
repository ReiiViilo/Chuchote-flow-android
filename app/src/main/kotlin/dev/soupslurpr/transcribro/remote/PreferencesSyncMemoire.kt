package dev.soupslurpr.transcribro.remote

import android.content.SharedPreferences

/**
 * [SyncMemoire] sur `SharedPreferences`. `commit()` applique la valeur en
 * mémoire avant d'écrire le disque et ne rend que le résultat du disque :
 * après un refus, une lecture rendrait la valeur refusée pour le reste du
 * processus, alors que le coordinateur tient une écriture refusée pour non
 * faite (constat du reviewer, huitième ronde, 20 septembre 2026). La valeur
 * précédente est donc remise — ce second `commit()` échoue sur le même
 * disque, la mémoire suit — et le contrat de [SyncMemoire] tient. `commit()`
 * et son résultat, pas l'aide KTX qui le jette : une écriture refusée par le
 * disque doit se savoir.
 */
internal class PreferencesSyncMemoire(private val prefs: SharedPreferences) : SyncMemoire {
    override fun lire(cle: String): String? = prefs.getString(cle, null)

    override fun ecrire(cle: String, valeur: String): Boolean {
        val avant = prefs.getString(cle, null)
        if (prefs.edit().putString(cle, valeur).commit()) return true
        remettre(cle, avant)
        return false
    }

    override fun effacer(cle: String): Boolean {
        val avant = prefs.getString(cle, null)
        if (prefs.edit().remove(cle).commit()) return true
        remettre(cle, avant)
        return false
    }

    private fun remettre(cle: String, avant: String?) {
        val editeur = prefs.edit()
        if (avant == null) editeur.remove(cle) else editeur.putString(cle, avant)
        editeur.commit()
    }
}
