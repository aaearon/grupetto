package com.spop.poverlay

import android.content.Context
import android.content.SharedPreferences
import androidx.lifecycle.LifecycleOwner
import io.mockk.every
import io.mockk.mockk
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ConfigurationRepositoryShowOverlayTest {

    private lateinit var preferences: FakeSharedPreferences
    private lateinit var context: Context
    private lateinit var lifecycleOwner: LifecycleOwner

    @Before
    fun setup() {
        preferences = FakeSharedPreferences()
        context = mockk(relaxed = true)
        every { context.getSharedPreferences(any(), any()) } returns preferences
        lifecycleOwner = mockk(relaxed = true)
    }

    @After
    fun tearDown() {
        ConfigurationRepository.SharedPreferenceListeners.clear()
    }

    private fun newRepository() = ConfigurationRepository(context, lifecycleOwner)

    @Test
    fun `show overlay defaults to true`() {
        assertTrue(newRepository().showOverlay.value)
    }

    @Test
    fun `show overlay persists false`() {
        newRepository().setShowOverlay(false)

        assertFalse(
            preferences.getBoolean(ConfigurationRepository.Preferences.ShowOverlay.key, true)
        )
    }

    @Test
    fun `show overlay round trips through a new repository`() {
        newRepository().setShowOverlay(false)
        assertFalse(newRepository().showOverlay.value)

        newRepository().setShowOverlay(true)
        assertTrue(newRepository().showOverlay.value)
    }

    @Test
    fun `show overlay uses its own preference key`() {
        assertEquals("showOverlay", ConfigurationRepository.Preferences.ShowOverlay.key)
    }
}

/**
 * Minimal in-memory [SharedPreferences]; the JVM test classpath has no Android runtime and the
 * project deliberately avoids Robolectric.
 */
class FakeSharedPreferences : SharedPreferences {
    private val values = mutableMapOf<String, Any?>()

    override fun getAll(): MutableMap<String, *> = values.toMutableMap()

    override fun getString(key: String?, defValue: String?): String? =
        values[key] as? String ?: defValue

    override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? =
        @Suppress("UNCHECKED_CAST")
        (values[key] as? MutableSet<String>) ?: defValues

    override fun getInt(key: String?, defValue: Int): Int = values[key] as? Int ?: defValue

    override fun getLong(key: String?, defValue: Long): Long = values[key] as? Long ?: defValue

    override fun getFloat(key: String?, defValue: Float): Float = values[key] as? Float ?: defValue

    override fun getBoolean(key: String?, defValue: Boolean): Boolean =
        values[key] as? Boolean ?: defValue

    override fun contains(key: String?): Boolean = values.containsKey(key)

    override fun edit(): SharedPreferences.Editor = FakeEditor()

    override fun registerOnSharedPreferenceChangeListener(
        listener: SharedPreferences.OnSharedPreferenceChangeListener?
    ) = Unit

    override fun unregisterOnSharedPreferenceChangeListener(
        listener: SharedPreferences.OnSharedPreferenceChangeListener?
    ) = Unit

    private inner class FakeEditor : SharedPreferences.Editor {
        private val pending = mutableMapOf<String, Any?>()
        private val removals = mutableSetOf<String>()
        private var clearRequested = false

        override fun putString(key: String, value: String?) = apply { pending[key] = value }

        override fun putStringSet(key: String, value: MutableSet<String>?) =
            apply { pending[key] = value }

        override fun putInt(key: String, value: Int) = apply { pending[key] = value }

        override fun putLong(key: String, value: Long) = apply { pending[key] = value }

        override fun putFloat(key: String, value: Float) = apply { pending[key] = value }

        override fun putBoolean(key: String, value: Boolean) = apply { pending[key] = value }

        override fun remove(key: String) = apply { removals.add(key) }

        override fun clear() = apply { clearRequested = true }

        override fun commit(): Boolean {
            apply()
            return true
        }

        override fun apply() {
            if (clearRequested) {
                values.clear()
            }
            removals.forEach { values.remove(it) }
            values.putAll(pending)
        }
    }
}
