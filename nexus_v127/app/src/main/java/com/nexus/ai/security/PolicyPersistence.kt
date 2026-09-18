package com.nexus.ai.security

import android.content.ContentValues
import android.content.Context
import org.json.JSONObject

/** V1.18: serializable matcher types. Lambdas are never persisted. */
enum class PolicyMatchType {
    PERMISSION,
    RISK_AT_LEAST,
    MUTATING,
    BACKGROUND_MUTATING,
    READ_ONLY_LOW,
    TOOL,
    ANY
}

data class PolicyRuleRecord(
    val id: String,
    val description: String,
    val priority: Int,
    val matcherType: PolicyMatchType,
    val matcherValue: String?,
    val action: PolicyAction,
    val reason: String,
    val origin: PolicyOrigin,
    val enabled: Boolean = true,
    val updatedAt: Long = System.currentTimeMillis()
)

enum class PolicyOrigin { BUILTIN, USER, SYSTEM }

/** Persistent repository. Builtins are seeded and cannot be removed or disabled. */
class PolicyStore(context: Context) {
    private val db = PolicyDb(context)

    fun upsert(record: PolicyRuleRecord): Boolean {
        if (record.origin == PolicyOrigin.BUILTIN) return false
        db.writableDatabase.insertWithOnConflict("policy_rules", null, record.toValues(), android.database.sqlite.SQLiteDatabase.CONFLICT_REPLACE)
        recordVersion(record, "UPSERT")
        return true
    }

    fun recordVersion(record: PolicyRuleRecord, operation: String) {
        val values = ContentValues().apply {
            put("version_id", java.util.UUID.randomUUID().toString())
            put("rule_id", record.id)
            put("snapshot", record.toJson().toString())
            put("created_at", System.currentTimeMillis())
            put("operation", operation)
        }
        db.writableDatabase.insert("policy_versions", null, values)
    }

    fun versions(ruleId: String): List<PolicyRuleVersion> = db.readableDatabase.rawQuery(
        "SELECT version_id,rule_id,snapshot,created_at,operation FROM policy_versions WHERE rule_id=? ORDER BY created_at DESC", arrayOf(ruleId)
    ).use { c ->
        val out = mutableListOf<PolicyRuleVersion>()
        while (c.moveToNext()) out += PolicyRuleVersion(c.getString(0), c.getString(1), c.getString(2), c.getLong(3), c.getString(4))
        out
    }

    fun restoreVersion(versionId: String): PolicyRuleRecord? = db.readableDatabase.rawQuery(
        "SELECT snapshot FROM policy_versions WHERE version_id=?", arrayOf(versionId)
    ).use { c -> if (c.moveToFirst()) policyRuleFromJson(c.getString(0)) else null }

    fun setEnabled(id: String, enabled: Boolean): Boolean {
        val current = find(id) ?: return false
        if (current.origin == PolicyOrigin.BUILTIN) return false
        val values = ContentValues().apply { put("enabled", if (enabled) 1 else 0); put("updated_at", System.currentTimeMillis()) }
        val ok = db.writableDatabase.update("policy_rules", values, "id=?", arrayOf(id)) > 0
        if (ok) recordVersion(current.copy(enabled = enabled, updatedAt = System.currentTimeMillis()), if (enabled) "ENABLE" else "DISABLE")
        return ok
    }

    fun delete(id: String): Boolean {
        val current = find(id) ?: return false
        if (current.origin == PolicyOrigin.BUILTIN) return false
        recordVersion(current, "DELETE")
        return db.writableDatabase.delete("policy_rules", "id=?", arrayOf(id)) > 0
    }

    internal fun insertBuiltin(record: PolicyRuleRecord) {
        db.writableDatabase.insertWithOnConflict("policy_rules", null, record.toValues(), android.database.sqlite.SQLiteDatabase.CONFLICT_IGNORE)
    }

    fun find(id: String): PolicyRuleRecord? = db.readableDatabase.rawQuery(
        "SELECT id,description,priority,matcher_type,matcher_value,action,reason,origin,enabled,updated_at FROM policy_rules WHERE id=?",
        arrayOf(id)
    ).use { c -> if (c.moveToFirst()) c.toRecord() else null }

    fun all(): List<PolicyRuleRecord> = query("SELECT id,description,priority,matcher_type,matcher_value,action,reason,origin,enabled,updated_at FROM policy_rules ORDER BY priority DESC,id")

    fun active(): List<PolicyRuleRecord> = query("SELECT id,description,priority,matcher_type,matcher_value,action,reason,origin,enabled,updated_at FROM policy_rules WHERE enabled=1 ORDER BY priority DESC,id")

    private fun query(sql: String): List<PolicyRuleRecord> {
        val out = mutableListOf<PolicyRuleRecord>()
        db.readableDatabase.rawQuery(sql, null).use { c -> while (c.moveToNext()) out += c.toRecord() }
        return out
    }

    private class PolicyDb(context: Context) : android.database.sqlite.SQLiteOpenHelper(context, "nexus_policy.db", null, 2) {
        override fun onCreate(db: android.database.sqlite.SQLiteDatabase) {
            db.execSQL("CREATE TABLE IF NOT EXISTS policy_rules (id TEXT PRIMARY KEY, description TEXT NOT NULL, priority INTEGER NOT NULL, matcher_type TEXT NOT NULL, matcher_value TEXT, action TEXT NOT NULL, reason TEXT NOT NULL, origin TEXT NOT NULL, enabled INTEGER NOT NULL, updated_at INTEGER NOT NULL)")
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_policy_priority ON policy_rules(priority DESC)")
            db.execSQL("CREATE TABLE IF NOT EXISTS policy_versions (version_id TEXT PRIMARY KEY, rule_id TEXT NOT NULL, snapshot TEXT NOT NULL, created_at INTEGER NOT NULL, operation TEXT NOT NULL)")
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_policy_versions_rule ON policy_versions(rule_id,created_at DESC)")
        }
        override fun onUpgrade(db: android.database.sqlite.SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            if (oldVersion < 2) {
                db.execSQL("CREATE TABLE IF NOT EXISTS policy_versions (version_id TEXT PRIMARY KEY, rule_id TEXT NOT NULL, snapshot TEXT NOT NULL, created_at INTEGER NOT NULL, operation TEXT NOT NULL)")
                db.execSQL("CREATE INDEX IF NOT EXISTS idx_policy_versions_rule ON policy_versions(rule_id,created_at DESC)")
            }
        }
    }

    private fun PolicyRuleRecord.toValues() = ContentValues().apply {
        put("id", id); put("description", description); put("priority", priority)
        put("matcher_type", matcherType.name); put("matcher_value", matcherValue)
        put("action", action.name); put("reason", reason); put("origin", origin.name)
        put("enabled", if (enabled) 1 else 0); put("updated_at", updatedAt)
    }

    private fun android.database.Cursor.toRecord() = PolicyRuleRecord(
        getString(0), getString(1), getInt(2), PolicyMatchType.valueOf(getString(3)), getString(4),
        PolicyAction.valueOf(getString(5)), getString(6), PolicyOrigin.valueOf(getString(7)), getInt(8) == 1, getLong(9)
    )
}

data class PolicyRuleVersion(
    val versionId: String, val ruleId: String, val snapshot: String, val createdAt: Long, val operation: String
)

private fun PolicyRuleRecord.toJson(): JSONObject = JSONObject().apply {
    put("id", id); put("description", description); put("priority", priority)
    put("matcherType", matcherType.name); put("matcherValue", matcherValue)
    put("action", action.name); put("reason", reason); put("origin", origin.name)
    put("enabled", enabled); put("updatedAt", updatedAt)
}

private fun policyRuleFromJson(raw: String): PolicyRuleRecord {
    val json = JSONObject(raw)
    return PolicyRuleRecord(
        json.getString("id"), json.getString("description"), json.getInt("priority"),
        PolicyMatchType.valueOf(json.getString("matcherType")), if (json.isNull("matcherValue")) null else json.getString("matcherValue"),
        PolicyAction.valueOf(json.getString("action")), json.getString("reason"), PolicyOrigin.valueOf(json.getString("origin")),
        json.getBoolean("enabled"), json.getLong("updatedAt")
    )
}

class PolicyRepository(private val store: PolicyStore) {
    init { seedBuiltins() }

    fun records(): List<PolicyRuleRecord> = store.all()
    fun activeRecords(): List<PolicyRuleRecord> = store.active()

    fun rules(): List<PolicyRule> = activeRecords().map { record ->
        PolicyRule(record.id, record.description, record.priority, record.matcher(), record.action, record.reason)
    }

    private fun seedBuiltins() {
        val existing = store.all().map { it.id }.toSet()
        builtinRecords().filterNot { it.id in existing }.forEach { store.insertBuiltin(it) }
    }

    private fun PolicyRuleRecord.matcher(): (PolicyContext) -> Boolean = when (matcherType) {
        PolicyMatchType.PERMISSION -> { ctx -> ctx.permission.name == matcherValue }
        PolicyMatchType.RISK_AT_LEAST -> { ctx -> CapabilityCatalog.spec(ctx.capability).risk >= RiskLevel.valueOf(matcherValue ?: RiskLevel.HIGH.name) }
        PolicyMatchType.MUTATING -> { ctx -> !ctx.readOnly }
        PolicyMatchType.BACKGROUND_MUTATING -> { ctx -> ctx.background && !ctx.readOnly }
        PolicyMatchType.READ_ONLY_LOW -> { ctx -> ctx.readOnly && CapabilityCatalog.spec(ctx.capability).risk == RiskLevel.LOW }
        PolicyMatchType.TOOL -> { ctx -> ctx.toolName == matcherValue }
        PolicyMatchType.ANY -> { _ -> true }
    }

    companion object {
        fun builtinRecords(): List<PolicyRuleRecord> = listOf(
            PolicyRuleRecord("deny-delete", "Operações de exclusão nunca são liberadas silenciosamente.", 100, PolicyMatchType.PERMISSION, PermissionKind.DELETE_FILES.name, PolicyAction.REQUIRE_CONFIRMATION, "DELETE_FILES exige confirmação explícita e verificação pós-execução.", PolicyOrigin.BUILTIN),
            PolicyRuleRecord("deny-background-mutating", "Ações mutáveis não devem ser executadas em background sem política explícita.", 90, PolicyMatchType.BACKGROUND_MUTATING, null, PolicyAction.DENY, "Ferramenta mutável bloqueada em background pela política padrão.", PolicyOrigin.BUILTIN),
            PolicyRuleRecord("require-confirmation-risk", "Capabilities de risco médio ou maior exigem aprovação.", 80, PolicyMatchType.RISK_AT_LEAST, RiskLevel.MEDIUM.name, PolicyAction.REQUIRE_CONFIRMATION, "Capability de risco médio ou maior exige confirmação explícita.", PolicyOrigin.BUILTIN),
            PolicyRuleRecord("verify-mutating", "Ações que podem alterar estado sempre exigem verificação.", 70, PolicyMatchType.MUTATING, null, PolicyAction.REQUIRE_VERIFICATION, "Ferramenta mutável exige verificação pós-execução.", PolicyOrigin.BUILTIN),
            PolicyRuleRecord("allow-readonly", "Leituras de baixo risco podem prosseguir sem confirmação adicional.", 10, PolicyMatchType.READ_ONLY_LOW, null, PolicyAction.ALLOW, "Ferramenta somente leitura de baixo risco autorizada pela política.", PolicyOrigin.BUILTIN)
        )
    }

}

/** Administrative API. The agent must not receive this service. */
class PolicyAdminService(private val store: PolicyStore, private val audit: (String, String, Boolean) -> Unit) {
    fun createOrUpdate(record: PolicyRuleRecord): Result<PolicyRuleRecord> {
        if (record.origin == PolicyOrigin.BUILTIN) return Result.failure(IllegalArgumentException("BUILTIN é protegido."))
        if (record.priority !in 1..50) return Result.failure(IllegalArgumentException("Regras USER devem usar priority entre 1 e 50."))
        if (record.action == PolicyAction.ALLOW && ((record.matcherType == PolicyMatchType.PERMISSION && record.matcherValue == PermissionKind.DELETE_FILES.name) || (record.matcherType == PolicyMatchType.RISK_AT_LEAST && RiskLevel.valueOf(record.matcherValue ?: RiskLevel.HIGH.name) >= RiskLevel.HIGH))) {
            return Result.failure(IllegalArgumentException("Proteção crítica não pode ser reduzida para ALLOW."))
        }
        val normalized = record.copy(origin = PolicyOrigin.USER, updatedAt = System.currentTimeMillis())
        val ok = store.upsert(normalized)
        audit("UPSERT", normalized.id, ok)
        return if (ok) Result.success(normalized) else Result.failure(IllegalStateException("Não foi possível persistir a regra."))
    }

    fun setEnabled(id: String, enabled: Boolean): Boolean {
        val ok = store.setEnabled(id, enabled)
        audit(if (enabled) "ENABLE" else "DISABLE", id, ok)
        return ok
    }

    fun delete(id: String): Boolean {
        val ok = store.delete(id)
        audit("DELETE", id, ok)
        return ok
    }

    fun history(id: String): List<PolicyRuleVersion> = store.versions(id)

    fun rollback(versionId: String): Result<PolicyRuleRecord> {
        val snapshot = store.restoreVersion(versionId) ?: return Result.failure(IllegalArgumentException("Versão não encontrada."))
        if (snapshot.origin != PolicyOrigin.USER) return Result.failure(IllegalArgumentException("Somente regras USER podem sofrer rollback."))
        val result = createOrUpdate(snapshot.copy(updatedAt = System.currentTimeMillis()))
        if (result.isSuccess) audit("ROLLBACK", snapshot.id, true)
        return result
    }
}
