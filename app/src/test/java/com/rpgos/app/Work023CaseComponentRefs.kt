package com.rpgos.app

internal fun owner(uid: String) = OwnershipOwnerRef("CHARACTER", uid)
internal fun asset() = OwnedAssetRef("ASSET", "A1")
