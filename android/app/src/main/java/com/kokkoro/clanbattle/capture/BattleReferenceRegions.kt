package com.kokkoro.clanbattle.capture

data class ReferenceRegion(val x: Int, val y: Int, val width: Int, val height: Int)

object BattleReferenceRegions {
    val START_BUTTON = ReferenceRegion(1565, 850, 275, 115)
    val LOADING = ReferenceRegion(1545, 955, 190, 60)
}
