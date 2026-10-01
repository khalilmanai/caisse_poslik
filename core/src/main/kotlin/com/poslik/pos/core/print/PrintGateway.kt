package com.poslik.pos.core.print

import com.poslik.pos.core.domain.Sale
import com.poslik.pos.core.util.TimeFormat
import java.io.File
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

interface PrintGateway {
    suspend fun print(sale: Sale, attempt: Int)
}

class PrintFailure(message: String, cause: Throwable? = null) : IOException(message, cause)

class PrintSpoolGateway(
    private val spoolDirectory: Path,
    private val storeLabel: String = "Boutique",
) : PrintGateway {

    override suspend fun print(sale: Sale, attempt: Int) {
        val content = TicketRenderer(storeLabel).render(sale)
        val stamp = TimeFormat.fileStamp(sale.createdAtEpochMs)
        val name = "${stamp}-${sale.ticketNumber.value}.txt"
        val target: File = spoolDirectory.resolve(name).toFile()
        try {
            spoolDirectory.toFile().mkdirs()
            val temporary = File(target.parentFile, "$name.tmp")
            Files.writeString(temporary.toPath(), content, StandardCharsets.UTF_8)
            Files.move(
                temporary.toPath(),
                target.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE,
            )
        } catch (error: Exception) {
            throw PrintFailure("impossible d'\u00E9crire le ticket ${sale.ticketNumber.value} : ${error.message}", error)
        }
    }
}

class SimulatedPrintGateway(
    private val failFirstAttempts: Int = 0,
    private val failEveryAttempt: Int = 0,
) : PrintGateway {

    private val lock = Any()
    private val printedTickets = mutableListOf<String>()

    val delivered: List<String> get() = synchronized(lock) { printedTickets.toList() }

    override suspend fun print(sale: Sale, attempt: Int) {
        synchronized(lock) {
            val shouldFail = attempt <= failFirstAttempts ||
                (failEveryAttempt > 0 && attempt % failEveryAttempt == 0)
            if (shouldFail) {
                throw PrintFailure("panne d'imprimante simul\u00E9e (tentative $attempt) pour ${sale.ticketNumber.value}")
            }
            printedTickets.add(sale.ticketNumber.value)
        }
    }
}
