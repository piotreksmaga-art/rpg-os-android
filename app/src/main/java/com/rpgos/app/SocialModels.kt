package com.rpgos.app

data class RelationshipItem(
    val entityUid: String,
    val type: String,
    val score: String
)

data class OrganizationItem(
    val uid: String,
    val name: String,
    val type: String,
    val status: String
)

data class PoliticalItem(
    val uid: String,
    val name: String,
    val legitimacy: String,
    val influence: String,
    val stability: String
)
