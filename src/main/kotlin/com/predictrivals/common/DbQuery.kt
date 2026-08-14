package com.predictrivals.common

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.exposed.sql.Transaction
import org.jetbrains.exposed.sql.transactions.transaction

suspend fun <T> dbQuery(block: Transaction.() -> T): T =
    withContext(Dispatchers.IO) { transaction { block() } }
