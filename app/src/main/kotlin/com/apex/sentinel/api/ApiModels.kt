package com.apex.sentinel.api

import kotlinx.serialization.Serializable

@Serializable
data class ErrorResponse(
    val error: String,
    val message: String,
    val requestId: String? = null,
)

@Serializable
data class HealthResponse(
    val status: String,
    val storage: String? = null,
)
