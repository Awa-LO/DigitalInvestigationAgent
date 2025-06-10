package com.forensics.digitalinvestigationagent.models

data class SmsData(
    val id: Long,
    val address: String,
    val body: String,
    val date: Long,  // Timestamp en millisecondes
    val type: Int,   // 1=reçu, 2=envoyé
    val threadId: Long? = null
)