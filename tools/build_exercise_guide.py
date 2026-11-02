"""Builds android/app/src/main/assets/exercise_guide.json: step-by-step instructions plus the
two photo frames (start and end position) for every exercise and warm-up move in the app.

Sources, recorded per entry:
  * "free-exercise-db"  - instructions and photos from https://github.com/yuhonas/free-exercise-db
                          (Unlicense / public domain), for exercises we could map to a real entry.
  * "blake"             - short form cues written by Claude Sonnet for moves that dataset does not
                          have (home and warm-up moves). General guidance, no photos.

Run from the repo root:  python tools/build_exercise_guide.py
"""
import json
import re
import sys
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(ROOT))

from modules.workouts import EXERCISES  # noqa: E402

DATASET_URL = "https://raw.githubusercontent.com/yuhonas/free-exercise-db/main/dist/exercises.json"
BASE = "https://raw.githubusercontent.com/yuhonas/free-exercise-db/main/exercises"
KOTLIN = ROOT / "android/app/src/main/java/com/blake/app/ExerciseImages.kt"
OUT = ROOT / "android/app/src/main/assets/exercise_guide.json"

# Written for moves with no trustworthy entry in the dataset. Each is 4-6 short steps.
MANUAL = {
    "Burpees": [
        "Stand with feet shoulder-width apart, arms at your sides.",
        "Squat down and place your hands on the floor in front of you.",
        "Jump or step your feet back into a high plank, body in a straight line.",
        "Lower your chest to the floor, then press back up to the plank.",
        "Jump or step your feet back toward your hands.",
        "Explode upward into a small jump with your arms overhead. Land softly and repeat.",
    ],
    "Jumping Jacks": [
        "Stand tall with feet together and arms at your sides.",
        "Jump your feet out wider than your shoulders while swinging your arms overhead.",
        "Jump back to the start, bringing your arms down.",
        "Stay light on the balls of your feet and keep a steady rhythm.",
    ],
    "High Knees": [
        "Stand tall with feet hip-width apart.",
        "Drive one knee up toward hip height, then switch quickly to the other leg, as if running on the spot.",
        "Swing your arms in opposition to your legs and stay on the balls of your feet.",
        "Keep your chest up and your core tight. Land softly.",
    ],
    "Pike Push-ups": [
        "Start in a downward-dog shape: hips high, hands and feet on the floor, body an upside-down V.",
        "Bend your elbows and lower the top of your head toward the floor between your hands.",
        "Keep your hips high and your elbows angled slightly back, not flared wide.",
        "Press back up to the start. Move slowly and keep your neck relaxed.",
    ],
    "Plank to Downward Dog": [
        "Start in a high plank with hands under shoulders and a straight line from head to heels.",
        "Press your hips up and back, straightening your arms and legs into an upside-down V.",
        "Push your chest toward your thighs and press your heels toward the floor.",
        "Return to the plank with control. Keep your core braced throughout.",
    ],
    "Backpack Curl": [
        "Load a backpack with books or water bottles and hold the handles or straps in one hand, palm facing forward.",
        "Stand tall with elbows pinned to your sides.",
        "Curl the backpack up toward your shoulder without swinging your body.",
        "Pause briefly at the top, then lower slowly over about three seconds.",
    ],
    "Resistance Band Curl": [
        "Stand on the middle of the band with feet hip-width apart, holding both ends with palms facing forward.",
        "Keep your elbows tucked at your sides and your chest tall.",
        "Curl your hands toward your shoulders until your biceps are fully squeezed.",
        "Lower slowly, keeping tension in the band all the way down.",
    ],
    "Reverse Snow Angels": [
        "Lie face down with your arms by your sides, palms facing the floor, forehead just off the floor.",
        "Lift your chest slightly and squeeze your shoulder blades together.",
        "Sweep your arms in a wide arc up over your head, like making a snow angel.",
        "Sweep them back to your sides in the same arc. Keep your neck long and move slowly.",
    ],
    "Towel Curl (isometric)": [
        "Stand on the middle of a long towel and hold both ends, palms facing up.",
        "Keep your elbows at your sides and bend them to about 90 degrees.",
        "Pull up on the towel while pressing your feet down on it, so nothing moves.",
        "Hold that tension for the set time, breathing steadily.",
    ],
    "Wall Sit": [
        "Stand with your back flat against a wall and your feet about two feet in front of you.",
        "Slide down until your thighs are parallel to the floor, knees over your ankles.",
        "Keep your back flat on the wall and your hands off your thighs.",
        "Hold for the set time, breathing steadily, then slide back up.",
    ],
    "Wall push-ups": [
        "Stand an arm's length from a wall and place your hands on it at chest height, shoulder-width apart.",
        "Bend your elbows and lower your chest toward the wall, keeping your body in one straight line.",
        "Press back to straight arms.",
        "Keep your heels down and your core tight.",
    ],
    "Band-free shoulder dislocates": [
        "Hold a towel or broomstick with a wide overhand grip in front of your thighs.",
        "Keeping your arms straight, lift it forward and up over your head.",
        "Continue back behind you as far as is comfortable, then reverse the path.",
        "Widen your grip if your shoulders pinch. Never force the range.",
    ],
    "Leg swings": [
        "Stand next to a wall or sturdy support and hold it with one hand.",
        "Swing the leg nearest the wall forward and back like a pendulum, keeping it relaxed.",
        "Let the swing get a little higher each time without arching your back.",
        "Do all reps on one leg, then switch.",
    ],
    "Wrist and elbow circles": [
        "Stand tall with arms out in front of you at chest height.",
        "Make slow circles with your wrists, about 10 seconds each way.",
        "Bend your elbows and draw gentle circles with your forearms, about 10 seconds each way.",
        "Keep your shoulders relaxed and move through a comfortable range.",
    ],
    "Easy jog in place": [
        "Stand tall and start jogging on the spot with small, light steps.",
        "Keep your arms bent at about 90 degrees and swinging naturally.",
        "Land softly on the balls of your feet.",
        "Keep a pace where you could still hold a conversation.",
    ],
    "Jumping jacks": None,  # alias of "Jumping Jacks" below
}

# The dataset's own steps are too thin for these (1-3 lines), so Sonnet's fuller steps replace
# them; the dataset photos are kept.
OVERRIDE = {
    "Face Pull": [
        "Set a rope on a cable stack at upper-chest to face height and grab both ends with palms facing in.",
        "Step back until the cable is taut, feet shoulder-width apart, chest tall.",
        "Pull the rope toward your face, splitting the ends apart so your hands finish beside your ears.",
        "Squeeze your shoulder blades together and keep your elbows high.",
        "Pause for a second, then return slowly. Use a light weight; this is about control, not load.",
    ],
    "Plank": [
        "Place your forearms on the floor with elbows directly under your shoulders.",
        "Extend your legs behind you, balanced on your toes, so your body forms one straight line.",
        "Brace your abs and squeeze your glutes. Do not let your hips sag or pike up.",
        "Keep your neck neutral, eyes on the floor just ahead of your hands.",
        "Breathe steadily and hold for the set time.",
    ],
    "Mountain Climbers": [
        "Start in a high plank with hands under shoulders and your body in a straight line.",
        "Drive one knee toward your chest, then quickly switch legs, as if running horizontally.",
        "Keep your hips low and level and your core braced.",
        "Move at a steady pace you can hold for the set. Keep your hands planted.",
    ],
    "Cycling": [
        "Adjust the seat so your knee is slightly bent at the bottom of the pedal stroke.",
        "Sit tall with a relaxed grip and your shoulders down.",
        "Pedal at a steady pace, pushing through the whole stroke, not just the downward half.",
        "Add resistance for intervals or hills, and keep your upper body still.",
    ],
    "Treadmill Run": [
        "Start with a 2-3 minute walk to warm up.",
        "Increase the speed to a comfortable running pace. You should still be able to speak in short sentences.",
        "Run tall with a slight forward lean, relaxed shoulders and arms bent at about 90 degrees.",
        "Land softly under your body, not out in front. Do not hold the handrails.",
        "Finish with a 2-3 minute walk to cool down.",
    ],
    "Rowing Machine": [
        "Sit with your feet strapped in, knees bent and arms straight holding the handle.",
        "Drive with your legs first, pushing back through your heels.",
        "Then lean back slightly and pull the handle to your lower ribs.",
        "Reverse the order: arms out, lean forward, then bend your knees to slide back to the start.",
        "Keep a steady rhythm: legs, body, arms on the way out; arms, body, legs on the way back.",
    ],
    "Stair Climber": [
        "Step on and stand tall, holding the rails lightly for balance only.",
        "Climb at a steady pace with your whole foot on each step.",
        "Keep your hips under your shoulders and avoid leaning heavily on the handles.",
        "Raise the speed gradually. Stop if you feel sharp knee pain.",
    ],
    "Cat-cow": [
        "Start on all fours with wrists under shoulders and knees under hips.",
        "Cow: inhale, drop your belly toward the floor and lift your chest and tailbone.",
        "Cat: exhale, round your spine toward the ceiling and tuck your chin and tailbone.",
        "Move slowly between the two, one breath per movement.",
    ],
    "Glute bridges": [
        "Lie on your back with knees bent, feet flat on the floor about hip-width apart.",
        "Press through your heels and lift your hips until your body forms a line from shoulders to knees.",
        "Squeeze your glutes hard at the top for a second. Do not arch your lower back.",
        "Lower slowly to the floor and repeat.",
    ],
    "Bodyweight squats": [
        "Stand with feet shoulder-width apart, toes turned out slightly.",
        "Push your hips back and bend your knees, lowering until your thighs are about parallel to the floor.",
        "Keep your chest tall, your heels down and your knees tracking over your toes.",
        "Drive through your heels to stand up. Breathe in on the way down and out on the way up.",
    ],
    "Arm circles": [
        "Stand tall with arms straight out to the sides at shoulder height.",
        "Make small circles forward, then gradually widen them.",
        "After about 15 seconds, reverse the direction.",
        "Keep your shoulders relaxed and your neck long.",
    ],
}

# Exercise and warm-up names that exist in the app (EXERCISES + WarmUp.kt).
WARMUP_NAMES = [
    "Jumping jacks", "Arm circles", "Hip circles", "Bodyweight squats", "Wall push-ups",
    "Band-free shoulder dislocates", "Wrist and elbow circles", "Cat-cow", "Leg swings",
    "Glute bridges", "Plank", "Easy jog in place",
]


def image_map() -> dict[str, str]:
    """Exercise name -> dataset id, parsed from ExerciseImages.kt."""
    text = KOTLIN.read_text(encoding="utf-8")
    return dict(re.findall(r'"([^"]+)" to "\$BASE/([^/]+)/0\.jpg"', text))


def main() -> None:
    names = sorted({e["exercise"] for ex in EXERCISES.values() for e in ex} | set(WARMUP_NAMES))
    ids = {k.lower(): v for k, v in image_map().items()}
    print("downloading dataset...")
    with urllib.request.urlopen(DATASET_URL, timeout=60) as resp:  # noqa: S310
        dataset = {d["id"]: d for d in json.loads(resp.read().decode("utf-8"))}

    guide: dict[str, dict] = {}
    missing = []
    for name in names:
        key = name.lower()
        entry = dataset.get(ids.get(key, ""))
        if entry and name in OVERRIDE:
            frames = [f"{BASE}/{img}" for img in entry.get("images", [])[:2]]
            guide[key] = {
                "name": name, "steps": OVERRIDE[name], "frames": frames,
                "source": "Photos: free-exercise-db (public domain). Steps: Blake (written by Claude Sonnet)",
            }
        elif entry and entry.get("instructions"):
            frames = [f"{BASE}/{img}" for img in entry.get("images", [])[:2]]
            guide[key] = {
                "name": name,
                "steps": [s.strip() for s in entry["instructions"] if s.strip()],
                "frames": frames,
                "source": "free-exercise-db (public domain)",
            }
        else:
            manual = MANUAL.get(name) or (MANUAL.get("Jumping Jacks") if key == "jumping jacks" else None)
            if manual:
                guide[key] = {"name": name, "steps": manual, "frames": [], "source": "Blake (written by Claude Sonnet)"}
            else:
                missing.append(name)

    OUT.parent.mkdir(parents=True, exist_ok=True)
    OUT.write_text(json.dumps(guide, indent=1, ensure_ascii=False), encoding="utf-8")
    two_frames = sum(1 for g in guide.values() if len(g["frames"]) == 2)
    print(f"wrote {len(guide)} entries ({two_frames} with two photo frames) to {OUT}")
    if missing:
        print("NO GUIDE FOR:", missing)


if __name__ == "__main__":
    main()
