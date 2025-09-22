package org.themessagesearch.infra.db

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.flywaydb.core.Flyway
import org.jetbrains.exposed.sql.Database
import javax.sql.DataSource

object DatabaseFactory {
    @Volatile private var dataSource: DataSource? = null
    @Volatile private var database: Database? = null

    data class DbConfig(val url: String, val user: String, val password: String)

    fun init(config: DbConfig, runMigrations: Boolean = true): Database {
        if (database != null) return database!!
        synchronized(this) {
            if (database != null) return database!!
            val ds = hikari(config)
            if (runMigrations) migrate(ds)
            database = Database.connect(ds)
            dataSource = ds
            return database!!
        }
    }

    fun get(): Database = database ?: error("Database not initialized")
    fun getDataSource(): DataSource = dataSource ?: error("DataSource not initialized")

    private fun hikari(cfg: DbConfig): HikariDataSource {
        val hc = HikariConfig().apply {
            jdbcUrl = cfg.url
            username = cfg.user
            password = cfg.password
            maximumPoolSize = 10
            driverClassName = "org.postgresql.Driver"
            validate()
        }
        return HikariDataSource(hc)
    }

    private fun migrate(ds: DataSource) {
        Flyway.configure()
            .dataSource(ds)
            .locations("classpath:db/migration")
            .load()
            .migrate()
    }
}

