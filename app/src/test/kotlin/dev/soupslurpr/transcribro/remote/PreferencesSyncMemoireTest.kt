package dev.soupslurpr.transcribro.remote

import android.content.SharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Double fidèle à `SharedPreferencesImpl` : `commit()` applique en mémoire,
 * puis rend le résultat du disque — faux tant que [disqueRefuse].
 */
private class FauxPrefs : SharedPreferences {
    val memoire = mutableMapOf<String, Any?>()
    var disqueRefuse = false

    override fun getAll(): MutableMap<String, *> = memoire
    override fun getString(key: String?, defValue: String?): String? = memoire[key] as String? ?: defValue
    override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? = throw UnsupportedOperationException()
    override fun getInt(key: String?, defValue: Int): Int = throw UnsupportedOperationException()
    override fun getLong(key: String?, defValue: Long): Long = throw UnsupportedOperationException()
    override fun getFloat(key: String?, defValue: Float): Float = throw UnsupportedOperationException()
    override fun getBoolean(key: String?, defValue: Boolean): Boolean = throw UnsupportedOperationException()
    override fun contains(key: String?): Boolean = memoire.containsKey(key)
    override fun edit(): SharedPreferences.Editor = Editeur()
    override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit
    override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit

    private inner class Editeur : SharedPreferences.Editor {
        private val modifications = mutableMapOf<String, String?>() // null = retrait

        override fun putString(key: String?, value: String?): SharedPreferences.Editor = also { modifications[key!!] = value }
        override fun putStringSet(key: String?, values: MutableSet<String>?): SharedPreferences.Editor = throw UnsupportedOperationException()
        override fun putInt(key: String?, value: Int): SharedPreferences.Editor = throw UnsupportedOperationException()
        override fun putLong(key: String?, value: Long): SharedPreferences.Editor = throw UnsupportedOperationException()
        override fun putFloat(key: String?, value: Float): SharedPreferences.Editor = throw UnsupportedOperationException()
        override fun putBoolean(key: String?, value: Boolean): SharedPreferences.Editor = throw UnsupportedOperationException()
        override fun remove(key: String?): SharedPreferences.Editor = also { modifications[key!!] = null }
        override fun clear(): SharedPreferences.Editor = throw UnsupportedOperationException()

        override fun commit(): Boolean {
            modifications.forEach { (cle, valeur) -> if (valeur == null) memoire.remove(cle) else memoire[cle] = valeur }
            return !disqueRefuse
        }

        override fun apply() {
            commit()
        }
    }
}

class PreferencesSyncMemoireTest {
    private val prefs = FauxPrefs()
    private val memoire = PreferencesSyncMemoire(prefs)

    @Test
    fun `un commit refuse laisse quand meme la valeur en memoire`() {
        // Le comportement de la plateforme que le double reproduit, et que
        // `PreferencesSyncMemoire` doit défaire.
        prefs.disqueRefuse = true
        assertFalse(prefs.edit().putString("file", "b").commit())
        assertEquals("b", prefs.getString("file", null))
    }

    @Test
    fun `une ecriture acceptee se lit`() {
        assertTrue(memoire.ecrire("file", "a"))
        assertEquals("a", memoire.lire("file"))
    }

    @Test
    fun `une ecriture refusee laisse la valeur precedente`() {
        assertTrue(memoire.ecrire("file", "a"))
        prefs.disqueRefuse = true
        assertFalse(memoire.ecrire("file", "b"))
        assertEquals("a", memoire.lire("file"))
    }

    @Test
    fun `une ecriture refusee d une cle nouvelle la laisse absente`() {
        prefs.disqueRefuse = true
        assertFalse(memoire.ecrire("file", "a"))
        assertNull(memoire.lire("file"))
        assertFalse(prefs.contains("file"))
    }

    @Test
    fun `un effacement refuse laisse la valeur`() {
        assertTrue(memoire.ecrire("file", "a"))
        prefs.disqueRefuse = true
        assertFalse(memoire.effacer("file"))
        assertEquals("a", memoire.lire("file"))
    }
}
