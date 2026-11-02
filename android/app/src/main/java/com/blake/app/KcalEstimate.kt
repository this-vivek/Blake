package com.blake.app

/** Client-side mirror of modules/workouts/__init__.py's kcal-burned formula, used only for
 * an instant *optimistic* preview while offline/syncing. The server's computed value
 * (returned once the set actually syncs, and summed in /today's kcal_burned_today) is always
 * the authoritative number — this never needs to be exact, just close enough to feel right
 * immediately. */
private val MET_BY_GROUP = mapOf(
    "chest" to 5.0, "back" to 5.0, "legs" to 6.0, "shoulders" to 4.0,
    "biceps" to 3.5, "triceps" to 3.5, "core" to 4.0, "cardio" to 8.0,
)
private const val DEFAULT_MET = 5.0
private const val SET_DURATION_MIN = 0.75
const val DEFAULT_BODYWEIGHT_KG = 70.0

fun estimateKcalForSet(muscleGroup: String?, bodyweightKg: Double = DEFAULT_BODYWEIGHT_KG): Double {
    val met = MET_BY_GROUP[muscleGroup] ?: DEFAULT_MET
    return Math.round(met * bodyweightKg * 3.5 / 200 * SET_DURATION_MIN * 10) / 10.0
}
