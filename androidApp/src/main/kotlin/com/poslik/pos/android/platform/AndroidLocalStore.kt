package com.poslik.pos.android.platform

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.poslik.pos.core.data.LocalStore
import com.poslik.pos.core.data.SalesSchema
import com.poslik.pos.core.data.SaleJson
import com.poslik.pos.core.data.TerminalState
import com.poslik.pos.core.domain.HistoryEntry
import com.poslik.pos.core.domain.Money
import com.poslik.pos.core.domain.PaymentMethod
import com.poslik.pos.core.domain.PrintState
import com.poslik.pos.core.domain.PrintStatus
import com.poslik.pos.core.domain.Sale
import com.poslik.pos.core.domain.SaleRecord
import com.poslik.pos.core.domain.SyncState
import com.poslik.pos.core.domain.SyncStatus
import com.poslik.pos.core.domain.TerminalIdGenerator
import com.poslik.pos.core.domain.TerminalNamespace
import com.poslik.pos.core.domain.TicketNumber
import java.util.concurrent.locks.ReentrantLock

/**
 * Adaptateur Android de [LocalStore] sur le SQLite embarque.
 *
 * S'appuie sur [SalesSchema], partage avec l'adaptateur JDBC, afin que les deux
 * implementations ne puissent pas diverger. Le mapping reste mecanique :
 * une colonne, un parametre.
 *
 * Note sur [transaction] : une seule connexion SQLiteOpenHelper et un seul
 * ecrivain a la fois (le verrou), donc les ecritures sont deja serialisees.
 */
class AndroidLocalStore(
    context: Context,
    databaseName: String = "caisse.db",
    private val clock: () -> Long = System::currentTimeMillis,
) : LocalStore {

    private val helper = object : SQLiteOpenHelper(context.applicationContext, databaseName, null, 1) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL(SalesSchema.CREATE_META)
            db.execSQL(SalesSchema.CREATE_SALES)
            SalesSchema.CREATE_INDEXES.forEach(db::execSQL)
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
    }

    private val writeLock = ReentrantLock(true)

    override fun <T> transaction(block: (LocalStore) -> T): T = read { block(this) }

    private fun <T> read(block: () -> T): T {
        writeLock.lock()
        try {
            return block()
        } finally {
            writeLock.unlock()
        }
    }

    override fun terminal(): TerminalState = read {
        TerminalState(
            id = meta(SalesSchema.META_TERMINAL_ID) ?: error("terminal_id missing"),
            lastSequence = (meta(SalesSchema.META_SEQUENCE) ?: "0").toLong(),
            namespaceLength = (meta(SalesSchema.META_NAMESPACE_LENGTH) ?: "6").toInt(),
            createdAtEpochMs = (meta(SalesSchema.META_TERMINAL_CREATED_AT) ?: "0").toLong(),
            claimedAtEpochMs = meta(SalesSchema.META_TERMINAL_CLAIMED_AT)?.toLongOrNull(),
        )
    }

    override fun peekSequence(): Long = read { (meta(SalesSchema.META_SEQUENCE) ?: "0").toLong() }

    override fun nextSequence(): Long = read {
        val next = (meta(SalesSchema.META_SEQUENCE) ?: "0").toLong() + 1
        putMeta(SalesSchema.META_SEQUENCE, next.toString())
        next
    }

    override fun raiseSequenceTo(value: Long) = read {
        val current = (meta(SalesSchema.META_SEQUENCE) ?: "0").toLong()
        if (value > current) putMeta(SalesSchema.META_SEQUENCE, value.toString())
    }

    override fun markTerminalClaimed(now: Long) = read { putMeta(SalesSchema.META_TERMINAL_CLAIMED_AT, now.toString()) }

    override fun setNamespaceLength(length: Int) = read { putMeta(SalesSchema.META_NAMESPACE_LENGTH, length.toString()) }

    override fun insert(record: SaleRecord) = read {
        val sale = record.sale
        val values = ContentValues(SalesSchema.COLUMNS.size).apply {
            put("id", sale.id)
            put("ticket_number", sale.ticketNumber.value)
            put("terminal_id", sale.terminalId)
            put("store_id", sale.storeId)
            put("currency", sale.currency)
            put("total_minor", sale.total.minor)
            put("created_at", sale.createdAtEpochMs)
            put("payment_method", sale.paymentMethod.name)
            put("cash_given_minor", sale.cashGiven?.minor)
            put("change_minor", sale.changeDue.minor)
            put("lines_json", SaleJson.encodeLines(sale.lines))
            put("print_state", record.print.state.name)
            put("print_attempts", record.print.attempts)
            put("next_print_attempt_at", record.print.nextAttemptAtEpochMs)
            put("last_print_error", record.print.lastError)
            put("printed_at", record.print.printedAtEpochMs)
            put("print_updated_at", record.print.updatedAtEpochMs)
            put("sync_state", record.sync.state.name)
            put("sync_attempts", record.sync.attempts)
            put("next_sync_attempt_at", record.sync.nextAttemptAtEpochMs)
            put("last_sync_error", record.sync.lastError)
            put("synced_at", record.sync.syncedAtEpochMs)
            put("server_updated_at", record.sync.serverUpdatedAtEpochMs)
            put("conflict_note", record.sync.conflictNote)
        }
        helper.writableDatabase.insertOrThrow("sales", null, values)
        Unit
    }

    override fun updatePrint(id: String, status: PrintStatus) = read {
        val values = ContentValues().apply {
            put("print_state", status.state.name)
            put("print_attempts", status.attempts)
            put("next_print_attempt_at", status.nextAttemptAtEpochMs)
            put("last_print_error", status.lastError)
            put("printed_at", status.printedAtEpochMs)
            put("print_updated_at", status.updatedAtEpochMs)
        }
        helper.writableDatabase.update("sales", values, "id = ?", arrayOf(id))
        Unit
    }

    override fun updateSync(id: String, status: SyncStatus) = read {
        val values = ContentValues().apply {
            put("sync_state", status.state.name)
            put("sync_attempts", status.attempts)
            put("next_sync_attempt_at", status.nextAttemptAtEpochMs)
            put("last_sync_error", status.lastError)
            put("synced_at", status.syncedAtEpochMs)
            put("server_updated_at", status.serverUpdatedAtEpochMs)
            put("conflict_note", status.conflictNote)
        }
        helper.writableDatabase.update("sales", values, "id = ?", arrayOf(id))
        Unit
    }

    override fun setServerUpdatedAt(id: String, serverUpdatedAtEpochMs: Long) = read {
        val values = ContentValues().apply { put("server_updated_at", serverUpdatedAtEpochMs) }
        helper.writableDatabase.update("sales", values, "id = ?", arrayOf(id))
        Unit
    }

    override fun byId(id: String): SaleRecord? = read { queryMany("id = ?", arrayOf(id)).firstOrNull() }

    override fun byTicketNumber(ticketNumber: String): SaleRecord? =
        read { queryMany("ticket_number = ?", arrayOf(ticketNumber)).firstOrNull() }

    override fun history(limit: Int): List<SaleRecord> =
        read { queryMany("1", emptyArray(), "ORDER BY created_at DESC, id DESC", limit) }

    override fun historyEntries(limit: Int): List<HistoryEntry> = history(limit).map(HistoryEntry::from)

    override fun printQueue(nowEpochMs: Long, limit: Int): List<SaleRecord> = read {
        queryMany(
            "print_state IN ('PENDING','FAILED') AND next_print_attempt_at <= ?",
            arrayOf(nowEpochMs.toString()),
            "ORDER BY created_at ASC, id ASC",
            limit,
        )
    }

    override fun syncQueue(nowEpochMs: Long, limit: Int): List<SaleRecord> = read {
        queryMany(
            "sync_state <> 'SYNCED' AND next_sync_attempt_at <= ?",
            arrayOf(nowEpochMs.toString()),
            "ORDER BY created_at ASC, id ASC",
            limit,
        )
    }

    override fun recoverPrintQueueAtStartup(): Int = read {
        val db = helper.writableDatabase
        var affected = 0
        affected += db.compileStatement(
            "UPDATE sales SET print_state='PENDING', next_print_attempt_at=0 WHERE print_state='PRINTING'",
        ).executeUpdateDelete()
        affected += db.compileStatement(
            "UPDATE sales SET next_print_attempt_at=0 WHERE print_state='FAILED'",
        ).executeUpdateDelete()
        affected
    }

    override fun syncCursor(): Long = read { (meta(SalesSchema.META_SYNC_CURSOR) ?: "0").toLong() }

    override fun setSyncCursor(cursor: Long) = read { putMeta(SalesSchema.META_SYNC_CURSOR, cursor.toString()) }

    override fun unsyncedCount(): Int = read { count("WHERE sync_state <> 'SYNCED'") }

    override fun printStateCounts(): Map<String, Int> = read {
        helper.readableDatabase.rawQuery("SELECT print_state, COUNT(*) FROM sales GROUP BY print_state", null).use { cursor ->
            buildMap {
                while (cursor.moveToNext()) put(cursor.getString(0), cursor.getInt(1))
            }
        }
    }

    override fun columnNames(table: String): List<String> = read {
        helper.readableDatabase.rawQuery("PRAGMA table_info($table)", null).use { cursor ->
            val nameIndex = cursor.getColumnIndexOrThrow("name")
            buildList {
                while (cursor.moveToNext()) add(cursor.getString(nameIndex))
            }
        }
    }

    override fun close() {
        writeLock.lock()
        try {
            helper.close()
        } finally {
            writeLock.unlock()
        }
    }

    fun bootstrapTerminal() = read {
        if (meta(SalesSchema.META_TERMINAL_ID) == null) {
            putMeta(SalesSchema.META_TERMINAL_ID, TerminalIdGenerator.newTerminalId())
            putMeta(SalesSchema.META_SEQUENCE, "0")
            putMeta(SalesSchema.META_NAMESPACE_LENGTH, TerminalNamespace.MIN_LENGTH.toString())
            putMeta(SalesSchema.META_TERMINAL_CREATED_AT, clock().toString())
            putMeta(SalesSchema.META_SYNC_CURSOR, "0")
        }
    }

    private fun count(where: String): Int =
        helper.readableDatabase.rawQuery("SELECT COUNT(*) FROM sales $where", null).use { cursor ->
            if (cursor.moveToFirst()) cursor.getInt(0) else 0
        }

    private fun queryMany(
        where: String,
        arguments: Array<String>,
        order: String = "",
        limit: Int? = null,
    ): List<SaleRecord> {
        val sql = buildString {
            append("SELECT ").append(SalesSchema.SELECT_COLUMNS).append(" FROM sales")
            append(" WHERE ").append(where)
            if (order.isNotEmpty()) append(' ').append(order)
            if (limit != null) append(" LIMIT ").append(limit)
        }
        return helper.readableDatabase.rawQuery(sql, arguments).use { cursor ->
            buildList {
                while (cursor.moveToNext()) add(cursor.toRecord())
            }
        }
    }

    private fun Cursor.toRecord(): SaleRecord {
        val currency = text("currency")
        val terminalId = text("terminal_id")
        val sale = Sale(
            id = text("id"),
            ticketNumber = TicketNumber.parse(text("ticket_number"), terminalId),
            storeId = text("store_id"),
            terminalId = terminalId,
            currency = currency,
            lines = SaleJson.decodeLines(text("lines_json"), currency),
            total = Money(number("total_minor"), currency),
            createdAtEpochMs = number("created_at"),
            paymentMethod = runCatching { PaymentMethod.valueOf(text("payment_method")) }
                .getOrDefault(PaymentMethod.CASH),
            cashGiven = optionalNumber("cash_given_minor")?.let { Money(it, currency) },
            changeDue = Money(number("change_minor"), currency),
        )
        val printUpdatedAt = number("print_updated_at")
        val print = PrintStatus(
            state = runCatching { PrintState.valueOf(text("print_state")) }
                .getOrDefault(PrintState.PENDING),
            attempts = number("print_attempts").toInt(),
            nextAttemptAtEpochMs = number("next_print_attempt_at"),
            lastError = text("last_print_error"),
            printedAtEpochMs = optionalNumber("printed_at"),
            updatedAtEpochMs = printUpdatedAt,
        )
        val sync = SyncStatus(
            state = runCatching { SyncState.valueOf(text("sync_state")) }
                .getOrDefault(SyncState.LOCAL_ONLY),
            attempts = number("sync_attempts").toInt(),
            nextAttemptAtEpochMs = number("next_sync_attempt_at"),
            lastError = text("last_sync_error"),
            syncedAtEpochMs = optionalNumber("synced_at"),
            serverUpdatedAtEpochMs = optionalNumber("server_updated_at"),
            conflictNote = text("conflict_note"),
        )
        return SaleRecord(sale, print, sync)
    }

    private fun Cursor.text(column: String): String {
        val index = getColumnIndexOrThrow(column)
        return if (isNull(index)) "" else getString(index)
    }

    private fun Cursor.number(column: String): Long {
        val index = getColumnIndexOrThrow(column)
        return if (isNull(index)) 0L else getLong(index)
    }

    private fun Cursor.optionalNumber(column: String): Long? {
        val index = getColumnIndexOrThrow(column)
        return if (isNull(index)) null else getLong(index)
    }

    private fun meta(key: String): String? =
        helper.readableDatabase.rawQuery("SELECT value FROM meta WHERE key = ?", arrayOf(key)).use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }

    private fun putMeta(key: String, value: String) {
        val values = ContentValues().apply {
            put("key", key)
            put("value", value)
        }
        helper.writableDatabase.insertWithOnConflict(
            "meta",
            null,
            values,
            SQLiteDatabase.CONFLICT_REPLACE,
        )
    }
}