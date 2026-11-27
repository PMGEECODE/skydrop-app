package com.famage.skydrop.ui.theme

import android.content.Context
import android.content.SharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for [ThemePreferenceStore].
 *
 * We use a hand-rolled in-memory [SharedPreferences] fake so we don't need
 * Robolectric or Mockito and the test runs on the standard JVM.
 */
class ThemePreferenceStoreTest {

    // ── In-memory SharedPreferences fake ────────────────────────────────────

    private class InMemorySharedPreferences : SharedPreferences {
        private val data = mutableMapOf<String, Any?>()

        override fun getString(key: String, defValue: String?): String? =
            data.getOrDefault(key, defValue) as? String ?: defValue

        @Suppress("UNCHECKED_CAST")
        override fun getStringSet(key: String, defValues: MutableSet<String>?): MutableSet<String>? =
            (data.getOrDefault(key, defValues) as? Set<*>)
                ?.filterIsInstance<String>()?.toMutableSet() ?: defValues

        override fun getAll(): Map<String, *> = data.toMap()
        override fun getInt(key: String, defValue: Int) = data.getOrDefault(key, defValue) as Int
        override fun getLong(key: String, defValue: Long) = data.getOrDefault(key, defValue) as Long
        override fun getFloat(key: String, defValue: Float) = data.getOrDefault(key, defValue) as Float
        override fun getBoolean(key: String, defValue: Boolean) = data.getOrDefault(key, defValue) as Boolean
        override fun contains(key: String) = data.containsKey(key)

        override fun edit(): SharedPreferences.Editor = EditorImpl(data)

        override fun registerOnSharedPreferenceChangeListener(
            listener: SharedPreferences.OnSharedPreferenceChangeListener
        ) = Unit

        override fun unregisterOnSharedPreferenceChangeListener(
            listener: SharedPreferences.OnSharedPreferenceChangeListener
        ) = Unit

        private class EditorImpl(private val backing: MutableMap<String, Any?>) :
            SharedPreferences.Editor {
            private val pending = mutableMapOf<String, Any?>()
            private val removals = mutableSetOf<String>()
            private var clearAll = false

            override fun putString(key: String, value: String?) = apply { pending[key] = value }
            override fun putInt(key: String, value: Int) = apply { pending[key] = value }
            override fun putLong(key: String, value: Long) = apply { pending[key] = value }
            override fun putFloat(key: String, value: Float) = apply { pending[key] = value }
            override fun putBoolean(key: String, value: Boolean) = apply { pending[key] = value }
            override fun putStringSet(key: String, values: Set<String>?) = apply { pending[key] = values }
            override fun remove(key: String) = apply { removals.add(key) }
            override fun clear() = apply { clearAll = true }

            override fun commit(): Boolean { flush(); return true }
            override fun apply() = flush()

            private fun flush() {
                if (clearAll) backing.clear()
                removals.forEach { backing.remove(it) }
                backing.putAll(pending)
            }
        }
    }

    // ── Fake context ─────────────────────────────────────────────────────────

    private lateinit var fakePrefs: InMemorySharedPreferences

    /** Returns a minimal [Context] stub that hands back [fakePrefs]. */
    private fun fakeContext(): Context {
        return object : android.content.ContextWrapper(null) {
            override fun getApplicationContext(): Context = this
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences = fakePrefs
            override fun getPackageName(): String = "com.famage.skydrop.test"
        }
    }

    @Before
    fun setUp() {
        fakePrefs = InMemorySharedPreferences()
        // Reset singleton between tests
        val field = ThemePreferenceStore::class.java.getDeclaredField("INSTANCE")
        field.isAccessible = true
        field.set(null, null)
    }

    // ── Tests ────────────────────────────────────────────────────────────────

    @Test
    fun `default preference is SYSTEM when no stored value`() {
        val store = ThemePreferenceStore(fakeContext())
        assertEquals(ThemePreference.SYSTEM, store.themePreference.value)
    }

    @Test
    fun `reads DARK preference stored in prefs on init`() {
        fakePrefs.edit().putString("theme_preference", "DARK").commit()
        val store = ThemePreferenceStore(fakeContext())
        assertEquals(ThemePreference.DARK, store.themePreference.value)
    }

    @Test
    fun `reads LIGHT preference stored in prefs on init`() {
        fakePrefs.edit().putString("theme_preference", "LIGHT").commit()
        val store = ThemePreferenceStore(fakeContext())
        assertEquals(ThemePreference.LIGHT, store.themePreference.value)
    }

    @Test
    fun `reads SYSTEM preference stored in prefs on init`() {
        fakePrefs.edit().putString("theme_preference", "SYSTEM").commit()
        val store = ThemePreferenceStore(fakeContext())
        assertEquals(ThemePreference.SYSTEM, store.themePreference.value)
    }

    @Test
    fun `invalid stored value falls back to SYSTEM`() {
        fakePrefs.edit().putString("theme_preference", "INVALID_VALUE").commit()
        val store = ThemePreferenceStore(fakeContext())
        assertEquals(ThemePreference.SYSTEM, store.themePreference.value)
    }

    @Test
    fun `setPreference DARK updates StateFlow immediately`() {
        val store = ThemePreferenceStore(fakeContext())
        store.setPreference(ThemePreference.DARK)
        assertEquals(ThemePreference.DARK, store.themePreference.value)
    }

    @Test
    fun `setPreference LIGHT updates StateFlow immediately`() {
        val store = ThemePreferenceStore(fakeContext())
        store.setPreference(ThemePreference.LIGHT)
        assertEquals(ThemePreference.LIGHT, store.themePreference.value)
    }

    @Test
    fun `setPreference SYSTEM updates StateFlow from DARK`() {
        fakePrefs.edit().putString("theme_preference", "DARK").commit()
        val store = ThemePreferenceStore(fakeContext())
        store.setPreference(ThemePreference.SYSTEM)
        assertEquals(ThemePreference.SYSTEM, store.themePreference.value)
    }

    @Test
    fun `setPreference persists to SharedPreferences`() {
        val store = ThemePreferenceStore(fakeContext())
        store.setPreference(ThemePreference.DARK)
        assertEquals("DARK", fakePrefs.getString("theme_preference", null))
    }

    @Test
    fun `setPreference LIGHT persists LIGHT to SharedPreferences`() {
        val store = ThemePreferenceStore(fakeContext())
        store.setPreference(ThemePreference.LIGHT)
        assertEquals("LIGHT", fakePrefs.getString("theme_preference", null))
    }

    @Test
    fun `multiple setPreference calls update StateFlow each time`() {
        val store = ThemePreferenceStore(fakeContext())
        store.setPreference(ThemePreference.DARK)
        assertEquals(ThemePreference.DARK, store.themePreference.value)
        store.setPreference(ThemePreference.LIGHT)
        assertEquals(ThemePreference.LIGHT, store.themePreference.value)
        store.setPreference(ThemePreference.SYSTEM)
        assertEquals(ThemePreference.SYSTEM, store.themePreference.value)
    }

    @Test
    fun `getInstance returns same singleton instance`() {
        val ctx = fakeContext()
        val a = ThemePreferenceStore.getInstance(ctx)
        val b = ThemePreferenceStore.getInstance(ctx)
        assertEquals(a, b)
    }
}
