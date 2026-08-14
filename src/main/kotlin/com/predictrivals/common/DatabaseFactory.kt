package com.predictrivals.common

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.flywaydb.core.Flyway
import org.jetbrains.exposed.sql.Database
import javax.sql.DataSource

object DatabaseFactory {
    fun init(config: AppConfig): DataSource {
        val dataSource = createDataSource(config)
        runMigrations(dataSource)
        Database.connect(dataSource)
        return dataSource
    }

    private fun createDataSource(config: AppConfig): DataSource {
        val hikariConfig = HikariConfig().apply {
            jdbcUrl = config.databaseUrl
            username = config.databaseUser
            password = config.databasePassword
            driverClassName = "org.postgresql.Driver"
            maximumPoolSize = 10
            isAutoCommit = false
            transactionIsolation = "TRANSACTION_REPEATABLE_READ"
            validate()
        }
        return HikariDataSource(hikariConfig)
    }

    private fun runMigrations(dataSource: DataSource) {
        Flyway.configure()
            .dataSource(dataSource)
            .schemas("admin_ref", "game")
            .defaultSchema("game")
            .load()
            .migrate()
    }
}
