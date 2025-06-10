package com.forensics.digitalinvestigationagent.models

data class WhatsAppData(
    val messages: List<WhatsAppMessage> = emptyList(),
    val contacts: List<WhatsAppContact> = emptyList()
)

data class WhatsAppMessage(
    val keyId: String,
    val message: String,
    val timestamp: Long,
    val senderJid: String,
    val isMedia: Boolean = false,
    val mediaPath: String? = null
)

data class WhatsAppContact(
    val jid: String,
    val displayName: String?,
    val phoneNumber: String
)