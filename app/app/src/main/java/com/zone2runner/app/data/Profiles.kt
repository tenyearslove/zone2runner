package com.zone2runner.app.data

import android.content.Context

/**
 * 다중 프로필 레지스트리 (spec-020 FR1). 이름 붙은 프로필 여러 개 + 활성 프로필 하나.
 *
 * 무손실 하위 호환의 핵심: 기본 프로필 id = "default" → 프로필별 스토어(ProfileStore/LearnedZone)가
 * 접미사 없이 기존 pref 키를 그대로 쓴다. 신규 프로필만 "_<id>" 접미사.
 * 따라서 기존 단일 프로필 사용자의 신체정보/학습값이 마이그레이션 없이 "기본"으로 이어진다(AC2).
 */
object Profiles {
    const val DEFAULT_ID = "default"
    private const val PREF = "zone2_profiles"
    private const val K_IDS = "ids"          // "id:name|id:name|..."
    private const val K_ACTIVE = "active"

    data class Entry(val id: String, val name: String)

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)

    /** 최초 접근 시 기본 프로필을 보장. */
    private fun ensureInit(ctx: Context) {
        val p = prefs(ctx)
        if (!p.contains(K_IDS)) {
            p.edit().putString(K_IDS, "$DEFAULT_ID:기본").putString(K_ACTIVE, DEFAULT_ID).apply()
        }
    }

    fun list(ctx: Context): List<Entry> {
        ensureInit(ctx)
        return prefs(ctx).getString(K_IDS, "$DEFAULT_ID:기본")!!.split("|").filter { it.isNotBlank() }
            .map { val i = it.indexOf(':'); Entry(it.substring(0, i), it.substring(i + 1)) }
    }

    fun activeId(ctx: Context): String {
        ensureInit(ctx)
        val id = prefs(ctx).getString(K_ACTIVE, DEFAULT_ID)!!
        // 활성 id가 목록에 없으면(삭제 등) 첫 프로필로 복구
        return if (list(ctx).any { it.id == id }) id else list(ctx).first().id.also { setActive(ctx, it) }
    }

    fun activeName(ctx: Context): String = list(ctx).firstOrNull { it.id == activeId(ctx) }?.name ?: "기본"

    fun setActive(ctx: Context, id: String) {
        if (list(ctx).any { it.id == id }) prefs(ctx).edit().putString(K_ACTIVE, id).apply()
    }

    /** 새 프로필 생성(활성 전환하지 않음 — 호출부가 결정). 반환 = 새 id. */
    fun create(ctx: Context, name: String): String {
        val id = "p" + System.currentTimeMillis()
        val cleanName = name.trim().ifBlank { "프로필 ${list(ctx).size + 1}" }.replace("|", " ").replace(":", " ")
        val ids = list(ctx) + Entry(id, cleanName)
        writeList(ctx, ids)
        return id
    }

    fun rename(ctx: Context, id: String, name: String) {
        val clean = name.trim().ifBlank { return }.replace("|", " ").replace(":", " ")
        writeList(ctx, list(ctx).map { if (it.id == id) it.copy(name = clean) else it })
    }

    /** 프로필 삭제 + 그 데이터 제거. 마지막 1개면 삭제하지 않고 false(초기화만 허용). */
    fun delete(ctx: Context, id: String): Boolean {
        val cur = list(ctx)
        if (cur.size <= 1) return false
        writeList(ctx, cur.filter { it.id != id })
        // 스토어 데이터 제거(기본 프로필은 접미사 없어 별도 처리)
        ProfileStore.clear(ctx, id); LearnedZone.clear(ctx, id)
        if (activeId(ctx) == id) setActive(ctx, list(ctx).first().id)
        return true
    }

    private fun writeList(ctx: Context, entries: List<Entry>) {
        prefs(ctx).edit().putString(K_IDS, entries.joinToString("|") { "${it.id}:${it.name}" }).apply()
    }

    /** 스토어 pref 이름 헬퍼: 기본 프로필은 접미사 없음(무손실), 나머지는 "_<id>". */
    fun prefName(ctx: Context, base: String): String {
        val id = activeId(ctx)
        return if (id == DEFAULT_ID) base else "${base}_$id"
    }

    fun prefNameFor(base: String, id: String): String = if (id == DEFAULT_ID) base else "${base}_$id"
}
