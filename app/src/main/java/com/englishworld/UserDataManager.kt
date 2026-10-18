package com.englishworld

import android.content.Context
import android.content.SharedPreferences

/**
 * 用户数据管理器
 * 使用 SharedPreferences 持久化真实的用户学习数据：
 * 收藏单词、查询历史、已学单词、学习天数、协议同意状态。
 */
class UserDataManager(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("english_world_user", Context.MODE_PRIVATE)

    companion object {
        private const val KEY_FIRST_USE_TIME = "first_use_time"
        private const val KEY_PRIVACY_ACCEPTED = "privacy_accepted"
        private const val KEY_FAVORITES = "favorites"
        private const val KEY_LEARNED = "learned"
        private const val KEY_HISTORY = "history"
        private const val KEY_UPDATE_ENTRY_UNLOCKED = "update_entry_unlocked"
        private const val MAX_HISTORY = 50
    }

    // ---------- 最新版本安装入口（连点版本号 9 次解锁，与学习数据互不影响） ----------
    var updateEntryUnlocked: Boolean
        get() = prefs.getBoolean(KEY_UPDATE_ENTRY_UNLOCKED, false)
        set(value) {
            prefs.edit().putBoolean(KEY_UPDATE_ENTRY_UNLOCKED, value).apply()
        }

    // ---------- 协议 ----------
    val isPrivacyAccepted: Boolean
        get() = prefs.getBoolean(KEY_PRIVACY_ACCEPTED, false)

    fun acceptPrivacy() {
        prefs.edit().putBoolean(KEY_PRIVACY_ACCEPTED, true).apply()
    }

    // ---------- 学习天数（按首次使用计算，真实数据） ----------
    val studyDays: Long
        get() {
            val firstUse = prefs.getLong(KEY_FIRST_USE_TIME, System.currentTimeMillis())
            prefs.edit().putLong(KEY_FIRST_USE_TIME, firstUse).apply()
            val diff = System.currentTimeMillis() - firstUse
            return (diff / (24 * 60 * 60 * 1000L)) + 1
        }

    // ---------- 收藏 ----------
    fun getFavorites(): Set<String> =
        prefs.getStringSet(KEY_FAVORITES, emptySet()) ?: emptySet()

    fun isFavorite(word: String): Boolean = word in getFavorites()

    /** 切换收藏状态，返回是否已收藏 */
    fun toggleFavorite(word: String): Boolean {
        val set = getFavorites().toMutableSet()
        val added = if (word in set) {
            set.remove(word)
            false
        } else {
            set.add(word)
            true
        }
        prefs.edit().putStringSet(KEY_FAVORITES, set).apply()
        return added
    }

    fun clearFavorites() {
        prefs.edit().remove(KEY_FAVORITES).apply()
    }

    // ---------- 已学 ----------
    fun getLearned(): Set<String> =
        prefs.getStringSet(KEY_LEARNED, emptySet()) ?: emptySet()

    fun isLearned(word: String): Boolean = word in getLearned()

    fun markLearned(word: String) {
        val set = getLearned().toMutableSet()
        set.add(word)
        prefs.edit().putStringSet(KEY_LEARNED, set).apply()
    }

    fun unmarkLearned(word: String) {
        val set = getLearned().toMutableSet()
        set.remove(word)
        prefs.edit().putStringSet(KEY_LEARNED, set).apply()
    }

    fun clearLearned() {
        prefs.edit().remove(KEY_LEARNED).apply()
    }

    // ---------- 查询历史 ----------
    fun getHistory(): List<String> =
        prefs.getString(KEY_HISTORY, "")
            ?.split("\n")
            ?.filter { it.isNotBlank() }
            ?: emptyList()

    fun addHistory(word: String) {
        if (word.isBlank()) return
        val list = getHistory().toMutableList()
        list.remove(word)
        list.add(0, word)
        prefs.edit().putString(KEY_HISTORY, list.take(MAX_HISTORY).joinToString("\n")).apply()
    }

    fun clearHistory() {
        prefs.edit().remove(KEY_HISTORY).apply()
    }

    // ---------- 全部清除 ----------
    fun clearAll() {
        prefs.edit()
            .remove(KEY_FAVORITES)
            .remove(KEY_LEARNED)
            .remove(KEY_HISTORY)
            .apply()
    }
}
