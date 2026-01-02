package org.themessagesearch.app

import org.themessagesearch.infra.db.DatabaseFactory
import org.themessagesearch.core.model.HybridWeights
import java.io.InputStreamReader
import org.yaml.snakeyaml.Yaml

data class JwtConfig(val secret: String, val issuer: String, val audience: String)

data class AiConfig(val provider: String, val apiKey: String?)

data class SearchConfig(val k: Int, val weights: HybridWeights)

data class CorsConfig(val allowedOrigins: List<String>, val allowCredentials: Boolean)

data class AppConfig(
    val db: DatabaseFactory.DbConfig,
    val jwt: JwtConfig,
    val ai: AiConfig,
    val search: SearchConfig,
    val webhooks: WebhookConfig,
    val cors: CorsConfig
)

object ConfigLoader {
    @Suppress("UNCHECKED_CAST")
    fun load(): AppConfig {
        val yamlStream = this::class.java.classLoader.getResourceAsStream("application.yaml")
            ?: error("application.yaml not found")
        val yaml = Yaml().load<Map<String, Any?>>(InputStreamReader(yamlStream))
        val dbMap = yaml["db"] as Map<String, Any?>
        val aiMap = yaml["ai"] as Map<String, Any?>
        val secMap = (yaml["security"] as Map<String, Any?>)["jwt"] as Map<String, Any?>
        val searchMap = yaml["search"] as Map<String, Any?>
        val weightsMap = searchMap["weights"] as Map<String, Any?>
        val webhookMap = yaml["webhooks"] as? Map<String, Any?> ?: emptyMap()
        val corsMap = yaml["cors"] as? Map<String, Any?> ?: emptyMap()

        fun env(name: String, default: String? = null): String? = System.getenv(name) ?: default

        val dbCfg = DatabaseFactory.DbConfig(
            url = dbMap["url"].toString(),
            user = dbMap["user"].toString(),
            password = env("DB_PASSWORD", dbMap["password"].toString())!!
        )
        val jwtCfg = JwtConfig(
            secret = env("JWT_SECRET", secMap["secret"].toString())!!, // TODO store secret in secret manager
            issuer = secMap["issuer"].toString(),
            audience = secMap["audience"].toString()
        )
        val aiCfg = AiConfig(
            provider = aiMap["provider"].toString(),
            apiKey = env("AI_API_KEY", aiMap["apiKey"]?.toString()) // TODO secure external key retrieval
        )
        val searchCfg = SearchConfig(
            k = searchMap["k"].toString().toInt(),
            weights = HybridWeights(
                text = weightsMap["text"].toString().toDouble(),
                vector = weightsMap["vector"].toString().toDouble()
            )
        )
        val webhooksCfg = WebhookConfig(
            reviewSubmittedUrl = webhookMap["reviewSubmittedUrl"]?.toString(),
            documentPublishedUrl = webhookMap["documentPublishedUrl"]?.toString()
        )
        val allowedOrigins = env("CORS_ALLOWED_ORIGINS")?.split(",")
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            ?: (corsMap["allowedOrigins"] as? List<*>)?.mapNotNull { it?.toString() }
            ?: emptyList()
        val corsCfg = CorsConfig(
            allowedOrigins = allowedOrigins,
            allowCredentials = corsMap["allowCredentials"]?.toString()?.toBooleanStrictOrNull() ?: false
        )
        return AppConfig(
            db = dbCfg,
            jwt = jwtCfg,
            ai = aiCfg,
            search = searchCfg,
            webhooks = webhooksCfg,
            cors = corsCfg
        )
    }
}
