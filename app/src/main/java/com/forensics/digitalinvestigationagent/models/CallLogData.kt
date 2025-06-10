package com.forensics.digitalinvestigationagent.models

data class CallLogData(
    val number: String,
    val name: String?,
    val date: Long,
    val duration: Long,  // en secondes
    val type: Int,       // 1=entrant, 2=sortant, 3=manqué
    val simId: Int? = null
)