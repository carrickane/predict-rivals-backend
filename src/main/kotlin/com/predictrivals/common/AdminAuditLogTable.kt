package com.predictrivals.common

import com.predictrivals.auth.UsersTable
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.javatime.timestampWithTimeZone

object AdminAuditLogTable : Table("game.admin_audit_log") {
    val id = long("id").autoIncrement()
    val adminUserId = long("admin_user_id").references(UsersTable.id)
    val action = varchar("action", 64)
    val targetType = varchar("target_type", 64)
    val targetId = varchar("target_id", 64)
    val beforeValue = text("before_value").nullable()
    val afterValue = text("after_value").nullable()
    val createdAt = timestampWithTimeZone("created_at")

    override val primaryKey = PrimaryKey(id)
}
