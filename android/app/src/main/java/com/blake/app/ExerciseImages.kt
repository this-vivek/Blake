package com.blake.app

/** Real exercise photos, public domain (Unlicense), from the free-exercise-db dataset:
 * https://github.com/yuhonas/free-exercise-db — mapped by hand to our exercise names
 * (verified by movement + equipment, not just text similarity). Exercises with no good
 * match in that dataset (mostly specific home-workout variants) fall back to the
 * muscle-group emoji icon instead of a wrong/misleading photo. */
private const val BASE = "https://raw.githubusercontent.com/yuhonas/free-exercise-db/main/exercises"

val EXERCISE_IMAGES: Map<String, String> = mapOf(
    "Bench Press" to "$BASE/Barbell_Bench_Press_-_Medium_Grip/0.jpg",
    "Incline Dumbbell Press" to "$BASE/Incline_Dumbbell_Press/0.jpg",
    "Cable Fly" to "$BASE/Cable_Crossover/0.jpg",
    "Dips" to "$BASE/Dips_-_Triceps_Version/0.jpg",
    "Push-ups" to "$BASE/Pushups/0.jpg",
    "Diamond Push-ups" to "$BASE/Push-Ups_-_Close_Triceps_Position/0.jpg",
    "Decline Push-ups" to "$BASE/Decline_Push-Up/0.jpg",
    "Wide Push-ups" to "$BASE/Push-Up_Wide/0.jpg",
    "Lat Pulldown" to "$BASE/Wide-Grip_Lat_Pulldown/0.jpg",
    "Barbell Row" to "$BASE/Bent_Over_Barbell_Row/0.jpg",
    "Seated Cable Row" to "$BASE/Seated_Cable_Rows/0.jpg",
    "Face Pull" to "$BASE/Face_Pull/0.jpg",
    "Pull-ups" to "$BASE/Pullups/0.jpg",
    "Superman" to "$BASE/Superman/0.jpg",
    "Inverted Row (table)" to "$BASE/Inverted_Row/0.jpg",
    "Barbell Squat" to "$BASE/Barbell_Squat/0.jpg",
    "Leg Press" to "$BASE/Leg_Press/0.jpg",
    "Romanian Deadlift" to "$BASE/Romanian_Deadlift/0.jpg",
    "Leg Curl" to "$BASE/Lying_Leg_Curls/0.jpg",
    "Bodyweight Squats" to "$BASE/Bodyweight_Squat/0.jpg",
    "Lunges" to "$BASE/Bodyweight_Walking_Lunge/0.jpg",
    "Glute Bridges" to "$BASE/Butt_Lift_Bridge/0.jpg",
    "Overhead Press" to "$BASE/Barbell_Shoulder_Press/0.jpg",
    "Lateral Raise" to "$BASE/Side_Lateral_Raise/0.jpg",
    "Front Raise" to "$BASE/Front_Dumbbell_Raise/0.jpg",
    "Rear Delt Fly" to "$BASE/Reverse_Flyes/0.jpg",
    "Arm Circles" to "$BASE/Arm_Circles/0.jpg",
    "Lateral Raise (water bottles)" to "$BASE/Side_Lateral_Raise/0.jpg",
    "Barbell Curl" to "$BASE/Barbell_Curl/0.jpg",
    "Hammer Curl" to "$BASE/Hammer_Curls/0.jpg",
    "Concentration Curl" to "$BASE/Concentration_Curls/0.jpg",
    "Cable Curl" to "$BASE/Standing_Biceps_Cable_Curl/0.jpg",
    "Chin-ups" to "$BASE/Chin-Up/0.jpg",
    "Close-Grip Bench Press" to "$BASE/Close-Grip_Barbell_Bench_Press/0.jpg",
    "Tricep Pushdown" to "$BASE/Triceps_Pushdown/0.jpg",
    "Overhead Tricep Extension" to "$BASE/Standing_Dumbbell_Triceps_Extension/0.jpg",
    "Skull Crushers" to "$BASE/EZ-Bar_Skullcrusher/0.jpg",
    "Tricep Dips (chair)" to "$BASE/Dips_-_Triceps_Version/0.jpg",
    "Bench Dips" to "$BASE/Bench_Dips/0.jpg",
    "Cable Crunch" to "$BASE/Cable_Crunch/0.jpg",
    "Hanging Leg Raise" to "$BASE/Hanging_Leg_Raise/0.jpg",
    "Russian Twist" to "$BASE/Russian_Twist/0.jpg",
    "Plank" to "$BASE/Plank/0.jpg",
    "Crunches" to "$BASE/Crunches/0.jpg",
    "Leg Raises" to "$BASE/Flat_Bench_Lying_Leg_Raise/0.jpg",
    "Treadmill Run" to "$BASE/Running_Treadmill/0.jpg",
    "Rowing Machine" to "$BASE/Rowing_Stationary/0.jpg",
    "Stair Climber" to "$BASE/Stairmaster/0.jpg",
    "Cycling" to "$BASE/Bicycling_Stationary/0.jpg",
    "Mountain Climbers" to "$BASE/Mountain_Climbers/0.jpg",
    "Close-Grip Push-ups" to "$BASE/Push-Ups_-_Close_Triceps_Position/0.jpg",
    // Warm-up moves (see WarmUp.kt)
    "Arm circles" to "$BASE/Arm_Circles/0.jpg",
    "Hip circles" to "$BASE/Standing_Hip_Circles/0.jpg",
    "Glute bridges" to "$BASE/Butt_Lift_Bridge/0.jpg",
    "Cat-cow" to "$BASE/Cat_Stretch/0.jpg",
    // No good match in the dataset — these show a neutral placeholder instead of a wrong photo:
    // Reverse Snow Angels, Wall Sit, Pike Push-ups, Plank to Downward Dog,
    // Resistance Band Curl, Towel Curl (isometric), Backpack Curl,
    // Jumping Jacks, Burpees, High Knees
)

private val IMAGES_BY_LOWERCASE_NAME: Map<String, String> =
    EXERCISE_IMAGES.mapKeys { it.key.lowercase() }

/** Photo for an exercise or warm-up move, matched ignoring case; null when there is none. */
fun exerciseImageUrl(name: String): String? = IMAGES_BY_LOWERCASE_NAME[name.lowercase()]

val MUSCLE_GROUP_EMOJI: Map<String, String> = mapOf(
    "chest" to "🏋️", "back" to "🦾", "legs" to "🦵", "shoulders" to "🤸",
    "biceps" to "💪", "triceps" to "🥊", "core" to "🔥", "cardio" to "🏃",
)
