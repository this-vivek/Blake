package com.blake.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** A day's warm-up moves and sets must collapse into ONE Training row. */
class TimelineGroupingTest {
    private var nextId = 1L

    private fun warmup(name: String, seconds: Double, ts: Double, voided: Boolean = false) = TimelineEntry(
        id = nextId++, module = "workouts", ts = ts, title = "Warm-up", detail = "", value = "",
        voided = voided, ids = listOf(nextId - 1), kind = "warmup", seconds = seconds, burned = 1.0, moveName = name,
    )

    private fun set(exercise: String, weight: Double, reps: Int, ts: Double, voided: Boolean = false) = TimelineEntry(
        id = nextId++, module = "workouts", ts = ts, title = exercise, detail = "", value = "",
        voided = voided, ids = listOf(nextId - 1), kind = "set", exercise = exercise,
        weightKg = weight, reps = reps, burned = 4.0,
    )

    private fun meal(ts: Double) = TimelineEntry(
        id = nextId++, module = "meals", ts = ts, title = "Lunch", detail = "", value = "600 kcal",
        voided = false, ids = listOf(nextId - 1), kind = "meal",
    )

    @Test
    fun sixWarmupsAndManySetsBecomeOneTrainingRow() {
        val day = listOf(
            warmup("Jumping jacks", 45.0, 100.0), warmup("Arm circles", 30.0, 101.0),
            warmup("Hip circles", 30.0, 102.0), warmup("Bodyweight squats", 36.0, 103.0),
            warmup("Wall push-ups", 36.0, 104.0), warmup("Plank", 30.0, 105.0),
            set("Bench Press", 60.0, 14, 200.0), set("Bench Press", 60.0, 14, 210.0), set("Bench Press", 60.0, 14, 220.0),
            set("Squat", 80.0, 10, 300.0), set("Squat", 80.0, 8, 310.0),
            meal(150.0),
        )

        val rows = collapseTraining(day)

        assertEquals("one meal row and one training row", 2, rows.size)
        val training = rows.single { it.kind == "training" }
        assertEquals(5, training.setCount)
        assertEquals("2 exercises · 5 sets", training.detail)
        // one Warm-up line plus one line per move, then one line per exercise
        assertEquals(1 + 6 + 2, training.lines.size)
        assertTrue(training.lines.first().startsWith("Warm-up · 6 moves"))
        assertTrue(training.lines.contains("Bench Press · 3 × 14 · 60 kg"))
        assertTrue(training.lines.any { it.startsWith("Squat · 2 sets · 10/8 reps") })
        assertEquals(11, training.ids.size)
    }

    @Test
    fun undoneSetsAreLeftOut() {
        val day = listOf(set("Bench Press", 60.0, 14, 1.0), set("Bench Press", 60.0, 14, 2.0, voided = true))
        val training = collapseTraining(day).single()
        assertEquals(1, training.setCount)
    }

    @Test
    fun aDayWithOnlyWarmupsIsStillOneRow() {
        val rows = collapseTraining(listOf(warmup("Plank", 30.0, 1.0), warmup("Arm circles", 30.0, 2.0)))
        assertEquals(1, rows.size)
        assertEquals("Warm-up only", rows.single().detail)
    }

    @Test
    fun aDayWithoutTrainingIsUntouched() {
        val m = meal(1.0)
        assertEquals(listOf(m), collapseTraining(listOf(m)))
        assertFalse(collapseTraining(listOf(m)).any { it.kind == "training" })
    }

    @Test
    fun bodyweightSetsSayBodyweight() {
        val line = collapseTraining(listOf(set("Push-ups", 0.0, 20, 1.0), set("Push-ups", 0.0, 20, 2.0)))
            .single().lines.single()
        assertEquals("Push-ups · 2 × 20 · bodyweight", line)
    }
}
