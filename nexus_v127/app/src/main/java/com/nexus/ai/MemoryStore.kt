package com.nexus.ai

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.content.ContentValues

class MemoryStore(context: Context) : SQLiteOpenHelper(context, "nexus_memory.db", null, 2) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE memory (id INTEGER PRIMARY KEY AUTOINCREMENT, kind TEXT NOT NULL, content TEXT NOT NULL, created_at INTEGER NOT NULL)")
        db.execSQL("CREATE INDEX idx_memory_content ON memory(content)")
    }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) db.execSQL("CREATE INDEX IF NOT EXISTS idx_memory_content ON memory(content)")
    }
    fun add(kind: String, content: String) {
        writableDatabase.insert("memory", null, ContentValues().apply {
            put("kind", kind); put("content", content); put("created_at", System.currentTimeMillis())
        })
    }
    fun recent(limit: Int = 8): List<String> {
        val out = mutableListOf<String>()
        readableDatabase.rawQuery("SELECT kind,content FROM memory ORDER BY id DESC LIMIT ?", arrayOf(limit.toString())).use { c ->
            while (c.moveToNext()) out += "[${c.getString(0)}] ${c.getString(1)}"
        }
        return out
    }
    fun search(term: String, limit: Int = 8): List<String> {
        val out = mutableListOf<String>()
        readableDatabase.rawQuery(
            "SELECT kind,content FROM memory WHERE content LIKE ? ORDER BY id DESC LIMIT ?",
            arrayOf("%$term%", limit.toString())
        ).use { c ->
            while (c.moveToNext()) out += "[${c.getString(0)}] ${c.getString(1)}"
        }
        return out
    }
}
