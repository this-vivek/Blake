package com.blake.app

/** Warm-up moves prepended to every training session. Static content (no numbers come from a
 * model): a short general block, then one or two moves for the muscle groups being trained.
 * Moves are ExerciseItems with group "warm-up"; reps holds either "N sec" or a rep count. */
object WarmUp {
    const val GROUP = "warm-up"

    private fun move(name: String, reps: String, note: String) =
        ExerciseItem(group = GROUP, name = name, sets = 1, reps = reps, note = note)

    private val general = listOf(
        move("Jumping jacks", "45 sec", "Light and steady. Breathe through your nose if you can."),
        move("Arm circles", "30 sec", "Big circles, 15 seconds forward then 15 back."),
        move("Hip circles", "30 sec", "Hands on hips, slow wide circles, both directions."),
        move("Bodyweight squats", "12", "Sit back, chest tall, knees track over toes."),
    )

    private val byGroup: Map<String, List<ExerciseItem>> = mapOf(
        "chest" to listOf(move("Wall push-ups", "12", "Hands at chest height, slow lower and press.")),
        "shoulders" to listOf(move("Band-free shoulder dislocates", "10", "Use a towel, arms wide, lift overhead and back.")),
        "triceps" to listOf(move("Wall push-ups", "12", "Elbows close to the body.")),
        "back" to listOf(move("Cat-cow", "30 sec", "Round and arch the spine slowly.")),
        "biceps" to listOf(move("Wrist and elbow circles", "30 sec", "Loosen the forearms before curling.")),
        "legs" to listOf(
            move("Leg swings", "10", "Hold a support, swing front to back, each leg."),
            move("Glute bridges", "12", "Squeeze at the top for a second."),
        ),
        "core" to listOf(move("Plank", "30 sec", "Straight line from head to heels.")),
        "cardio" to listOf(move("Easy jog in place", "60 sec", "Keep it conversational.")),
    )

    /** General block first, then group-specific moves (each group once, in the given order). */
    fun forGroups(groups: List<String>): List<ExerciseItem> {
        val specific = groups.distinct().flatMap { byGroup[it.lowercase()] ?: emptyList() }
            .distinctBy { it.name }
        return general + specific
    }

    /** Seconds for a timed move ("45 sec"), else 0 for a rep-counted one. */
    fun seconds(reps: String): Int =
        if (reps.contains("sec")) reps.filter { it.isDigit() }.toIntOrNull() ?: 0 else 0
}
