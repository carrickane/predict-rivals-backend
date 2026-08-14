package com.predictrivals.common

import org.jetbrains.exposed.sql.insert
import java.time.OffsetDateTime

class AdminAuditLogRepository {
    suspend fun record(adminUserId: Long, action: String, targetType: String, targetId: String, before: String?, after: String?) =
        dbQuery {
            AdminAuditLogTable.insert {
                it[AdminAuditLogTable.adminUserId] = adminUserId
                it[AdminAuditLogTable.action] = action
                it[AdminAuditLogTable.targetType] = targetType
                it[AdminAuditLogTable.targetId] = targetId
                it[AdminAuditLogTable.beforeValue] = before
                it[AdminAuditLogTable.afterValue] = after
                it[AdminAuditLogTable.createdAt] = OffsetDateTime.now()
            }
            Unit
        }
}
