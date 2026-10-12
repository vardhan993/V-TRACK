package com.vtrack.app.domain.model

/**
 * The exercise library that ships inside the APK, so browsing exercises, reading
 * technique notes and logging a session all work with no network.
 *
 * All entry ids are stable (`seed.<slug>`) so upgrades can add entries without
 * duplicating or orphaning a user's history. Each entry carries real coaching content:
 * step-by-step execution, technique cues and the mistakes people actually make.
 */
object ExerciseLibrary {

    fun seed(): List<Exercise> = buildList {
        /* ------------------------------ chest ------------------------------ */
        add(
            exercise(
                slug = "push-up",
                name = "Push-up",
                category = ActivityCategory.STRENGTH,
                muscle = "Chest",
                equipment = "Bodyweight",
                difficulty = FitnessLevel.BEGINNER,
                met = 4.5,
                sets = 3, reps = 10,
                compound = true,
                instructions = listOf(
                    "Start in a high plank with hands slightly wider than shoulders, body in one straight line.",
                    "Brace your core and glutes so the hips neither sag nor pike.",
                    "Lower under control until your chest is a fist's width from the floor, elbows at roughly 45° to the torso.",
                    "Press back up without letting the hips lead, finishing with elbows soft rather than locked.",
                ),
                cues = listOf("Ribs down, hips level", "Push the floor away, don't just lift yourself"),
                mistakes = listOf("Flaring elbows to 90° , which stresses the shoulder", "Hips sagging as you get tired"),
            ),
        )
        add(
            exercise(
                slug = "incline-push-up",
                name = "Incline push-up",
                category = ActivityCategory.STRENGTH,
                muscle = "Chest",
                equipment = "Bench or step",
                difficulty = FitnessLevel.BEGINNER,
                met = 3.8,
                sets = 3, reps = 12,
                instructions = listOf(
                    "Place both hands on a stable raised surface (bench, step, table) at hip height or above.",
                    "Walk your feet back so your body forms a straight line from head to heels.",
                    "Lower your chest to the surface, keeping elbows tucked at about 45°.",
                    "Press back up and repeat; raise the surface height to make it easier.",
                ),
                cues = listOf("The higher the hands, the easier the rep", "Keep a rigid plank throughout"),
                mistakes = listOf("Letting the hips drop first", "Standing too far back so the range is tiny"),
            ),
        )
        add(
            exercise(
                slug = "dumbbell-bench-press",
                name = "Dumbbell bench press",
                category = ActivityCategory.STRENGTH,
                muscle = "Chest",
                equipment = "Dumbbells, bench",
                difficulty = FitnessLevel.INTERMEDIATE,
                met = 5.0,
                sets = 3, reps = 8,
                compound = true,
                instructions = listOf(
                    "Sit on the bench with dumbbells on your thighs, then lie back and bring them to chest level.",
                    "Set the shoulder blades back and down, feet planted, and create a small arch in the lower back.",
                    "Press the dumbbells up and slightly together until the arms are extended over the chest.",
                    "Lower under control until the upper arms are level with the torso, then press again.",
                ),
                cues = listOf("Wrists stacked over elbows", "Keep the shoulder blades pinned to the bench"),
                mistakes = listOf("Bouncing the weight off the chest", "Letting the elbows drop far below the torso"),
            ),
        )
        add(
            exercise(
                slug = "dumbbell-fly",
                name = "Dumbbell fly",
                category = ActivityCategory.STRENGTH,
                muscle = "Chest",
                equipment = "Dumbbells, bench",
                difficulty = FitnessLevel.INTERMEDIATE,
                met = 4.5,
                sets = 3, reps = 12,
                instructions = listOf(
                    "Lie on a flat bench holding dumbbells above the chest with a soft elbow bend.",
                    "Open the arms in a wide arc, keeping that elbow angle constant.",
                    "Stop when you feel a stretch across the chest without shoulder discomfort.",
                    "Bring the dumbbells back together over the chest by squeezing the chest, not the hands.",
                ),
                cues = listOf("Think 'hug a tree'", "Keep the bend fixed so it stays a fly, not a press"),
                mistakes = listOf("Going too heavy and turning it into a sloppy press", "Dropping lower than shoulder level"),
            ),
        )

        /* ------------------------------- back ------------------------------ */
        add(
            exercise(
                slug = "inverted-row",
                name = "Inverted row",
                category = ActivityCategory.STRENGTH,
                muscle = "Back",
                equipment = "Bar or table",
                difficulty = FitnessLevel.BEGINNER,
                met = 4.5,
                sets = 3, reps = 8,
                compound = true,
                instructions = listOf(
                    "Set a bar (or sturdy table edge) at waist height and lie underneath it.",
                    "Grip slightly wider than shoulders and hang with a straight body line, heels on the floor.",
                    "Pull your chest toward the bar, driving the elbows down and back.",
                    "Lower slowly to a full hang without losing the straight body line.",
                ),
                cues = listOf("Start the pull by setting the shoulders", "Keep the body rigid like a plank"),
                mistakes = listOf("Hips sagging", "Half range reps that never touch the chest near the bar"),
            ),
        )
        add(
            exercise(
                slug = "dumbbell-row",
                name = "Single-arm dumbbell row",
                category = ActivityCategory.STRENGTH,
                muscle = "Back",
                equipment = "Dumbbell, bench",
                difficulty = FitnessLevel.BEGINNER,
                met = 5.0,
                sets = 3, reps = 10,
                compound = true,
                instructions = listOf(
                    "Place one knee and hand on a bench, the other foot on the floor, back flat.",
                    "Let the dumbbell hang straight down beneath the shoulder.",
                    "Row it to your hip, keeping the elbow close to your side and the torso still.",
                    "Lower under control until the arm is straight, then repeat before switching sides.",
                ),
                cues = listOf("Pull to the hip, not the armpit", "Keep the shoulders level — no twisting"),
                mistakes = listOf("Rotating the torso to help the lift", "Yanking with the lower back"),
            ),
        )
        add(
            exercise(
                slug = "pull-up",
                name = "Pull-up",
                category = ActivityCategory.STRENGTH,
                muscle = "Back",
                equipment = "Pull-up bar",
                difficulty = FitnessLevel.ADVANCED,
                met = 8.0,
                sets = 4, reps = 5,
                compound = true,
                instructions = listOf(
                    "Hang from the bar with an overhand grip slightly wider than shoulders.",
                    "Pull the shoulder blades down first, then drive the elbows toward the ribs.",
                    "Continue until the chin clears the bar without swinging.",
                    "Lower slowly to a straight-arm hang before the next rep.",
                ),
                cues = listOf("Chin 'over' the bar, not reaching with the neck", "Control the descent — that's half the exercise"),
                mistakes = listOf("Kipping and swinging for momentum", "Stopping halfway and calling it a rep"),
            ),
        )
        add(
            exercise(
                slug = "lat-pulldown",
                name = "Lat pulldown",
                category = ActivityCategory.STRENGTH,
                muscle = "Back",
                equipment = "Cable machine",
                difficulty = FitnessLevel.BEGINNER,
                met = 4.8,
                sets = 3, reps = 10,
                compound = true,
                instructions = listOf(
                    "Sit with thighs secured and grip the bar slightly wider than shoulders.",
                    "Lean back very slightly and pull the bar to your upper chest.",
                    "Squeeze the shoulder blades down and together at the bottom.",
                    "Return the bar slowly until the arms are straight and the shoulders are tall.",
                ),
                cues = listOf("Lead with the elbows", "Don't lean back more than ~15°"),
                mistakes = listOf("Pulling behind the neck", "Using body weight to yank the bar down"),
            ),
        )

        /* ------------------------------- legs ----------------------------- */
        add(
            exercise(
                slug = "bodyweight-squat",
                name = "Bodyweight squat",
                category = ActivityCategory.STRENGTH,
                muscle = "Quads",
                equipment = "Bodyweight",
                difficulty = FitnessLevel.BEGINNER,
                met = 4.0,
                sets = 3, reps = 15,
                compound = true,
                instructions = listOf(
                    "Stand with feet shoulder-width, toes turned out slightly.",
                    "Push the hips back and bend the knees, keeping the chest up.",
                    "Descend until the thighs are roughly parallel to the floor or as far as is comfortable.",
                    "Drive through the whole foot to stand, finishing with the hips fully extended.",
                ),
                cues = listOf("Knees track over the toes", "Weight spread across the tripod of the foot"),
                mistakes = listOf("Heels lifting off the floor", "Knees collapsing inward on the way up"),
            ),
        )
        add(
            exercise(
                slug = "split-squat",
                name = "Split squat",
                category = ActivityCategory.STRENGTH,
                muscle = "Quads",
                equipment = "Bodyweight or dumbbells",
                difficulty = FitnessLevel.INTERMEDIATE,
                met = 5.0,
                sets = 3, reps = 10,
                instructions = listOf(
                    "Take a long stride and place the back foot on its toes.",
                    "Keep the torso upright and lower the back knee toward the floor.",
                    "Descend until the front thigh is about parallel to the floor.",
                    "Push through the front foot to return, completing all reps before switching legs.",
                ),
                cues = listOf("Eighty percent of the weight on the front leg", "Front shin roughly vertical"),
                mistakes = listOf("Stride too short, which pushes the knee far past the toes", "Leaning forward and losing balance"),
            ),
        )
        add(
            exercise(
                slug = "romanian-deadlift",
                name = "Romanian deadlift",
                category = ActivityCategory.STRENGTH,
                muscle = "Hamstrings",
                equipment = "Barbell or dumbbells",
                difficulty = FitnessLevel.INTERMEDIATE,
                met = 5.5,
                sets = 3, reps = 8,
                compound = true,
                instructions = listOf(
                    "Stand tall holding the load at hip level, feet hip-width apart.",
                    "Push the hips back and let the weight slide down the thighs, keeping the back neutral.",
                    "Stop when you feel a strong hamstring stretch, usually around mid-shin to knee height.",
                    "Drive the hips forward to stand, squeezing the glutes at the top.",
                ),
                cues = listOf("Hips back, not down", "Keep the load close to the legs"),
                mistakes = listOf("Rounding the lower back", "Bending the knees so much it becomes a squat"),
            ),
        )
        add(
            exercise(
                slug = "goblet-squat",
                name = "Goblet squat",
                category = ActivityCategory.STRENGTH,
                muscle = "Quads",
                equipment = "Dumbbell or kettlebell",
                difficulty = FitnessLevel.BEGINNER,
                met = 5.0,
                sets = 3, reps = 12,
                compound = true,
                instructions = listOf(
                    "Hold the weight vertically against the chest with both hands under the top.",
                    "Stand with feet shoulder-width and toes slightly out.",
                    "Squat down between the knees, keeping the chest tall and elbows inside the knees.",
                    "Push the floor away to stand, keeping the weight against the chest throughout.",
                ),
                cues = listOf("Use the weight as a counterbalance to stay upright", "Full depth only if the spine stays neutral"),
                mistakes = listOf("Letting the weight drift away from the chest", "Heels coming up"),
            ),
        )
        add(
            exercise(
                slug = "glute-bridge",
                name = "Glute bridge",
                category = ActivityCategory.STRENGTH,
                muscle = "Glutes",
                equipment = "Bodyweight",
                difficulty = FitnessLevel.BEGINNER,
                met = 3.5,
                sets = 3, reps = 15,
                instructions = listOf(
                    "Lie on your back with knees bent and feet flat, hip-width apart, close to the glutes.",
                    "Press through the heels and lift the hips until the knees, hips and shoulders form a line.",
                    "Squeeze the glutes for a second at the top without arching the lower back.",
                    "Lower slowly and repeat.",
                ),
                cues = listOf("Ribs stay down — no lumbar extension", "Push the floor away with the heels"),
                mistakes = listOf("Overextending the lower back to gain height", "Feet too far away, which turns it into a hamstring exercise"),
            ),
        )
        add(
            exercise(
                slug = "calf-raise",
                name = "Standing calf raise",
                category = ActivityCategory.STRENGTH,
                muscle = "Calves",
                equipment = "Bodyweight or dumbbells",
                difficulty = FitnessLevel.BEGINNER,
                met = 3.5,
                sets = 3, reps = 15,
                instructions = listOf(
                    "Stand with the balls of the feet on a step, heels hanging off.",
                    "Rise up as high as possible onto the toes and pause.",
                    "Lower slowly until you feel a stretch in the calves.",
                    "Keep the knees almost straight throughout, using a wall for balance.",
                ),
                cues = listOf("Full range: all the way up, all the way down", "Slow the lowering phase"),
                mistakes = listOf("Bouncing with no pause at the top", "Bending the knees to cheat the range"),
            ),
        )

        /* ------------------------------ upper body ------------------------- */
        add(
            exercise(
                slug = "overhead-press",
                name = "Standing overhead press",
                category = ActivityCategory.STRENGTH,
                muscle = "Shoulders",
                equipment = "Dumbbells or barbell",
                difficulty = FitnessLevel.INTERMEDIATE,
                met = 5.0,
                sets = 3, reps = 8,
                compound = true,
                instructions = listOf(
                    "Hold the load at shoulder height with the wrists stacked over the elbows.",
                    "Brace the core and squeeze the glutes to lock the ribcage down.",
                    "Press overhead until the arms are extended, moving the head slightly back out of the way.",
                    "Lower under control to the shoulders, keeping the ribs down.",
                ),
                cues = listOf("Finish with the biceps beside the ears", "Don't lean back to press"),
                mistakes = listOf("Arching the lower back instead of pressing up", "Pressing around the head rather than past it"),
            ),
        )
        add(
            exercise(
                slug = "lateral-raise",
                name = "Lateral raise",
                category = ActivityCategory.STRENGTH,
                muscle = "Shoulders",
                equipment = "Dumbbells",
                difficulty = FitnessLevel.BEGINNER,
                met = 3.8,
                sets = 3, reps = 12,
                instructions = listOf(
                    "Stand with a light dumbbell in each hand at your sides, elbows slightly bent.",
                    "Raise the arms out to the sides until they reach shoulder height.",
                    "Lead with the elbows and keep the shoulders relaxed away from the ears.",
                    "Lower slowly — this exercise responds better to control than to load.",
                ),
                cues = listOf("Pinkies slightly up, but don't force it", "Stop at shoulder height"),
                mistakes = listOf("Swinging the body to start the lift", "Going far too heavy and shrugging"),
            ),
        )
        add(
            exercise(
                slug = "biceps-curl",
                name = "Dumbbell biceps curl",
                category = ActivityCategory.STRENGTH,
                muscle = "Biceps",
                equipment = "Dumbbells",
                difficulty = FitnessLevel.BEGINNER,
                met = 3.5,
                sets = 3, reps = 10,
                instructions = listOf(
                    "Stand tall with dumbbells at your sides, palms facing forward.",
                    "Curl the weights up by bending only the elbows, keeping the upper arms still.",
                    "Squeeze at the top, then lower slowly all the way to straight arms.",
                    "Avoid leaning back or swinging the hips to help.",
                ),
                cues = listOf("Elbows pinned to the sides", "Full extension at the bottom"),
                mistakes = listOf("Using momentum from the hips", "Stopping short of full extension"),
            ),
        )
        add(
            exercise(
                slug = "triceps-dip",
                name = "Bench triceps dip",
                category = ActivityCategory.STRENGTH,
                muscle = "Triceps",
                equipment = "Bench or chair",
                difficulty = FitnessLevel.BEGINNER,
                met = 5.0,
                sets = 3, reps = 10,
                instructions = listOf(
                    "Sit on the edge of a stable bench with hands beside the hips, fingers forward.",
                    "Slide the hips off the edge, keeping them close to the bench.",
                    "Bend the elbows straight back to lower until the upper arms are about parallel to the floor.",
                    "Press back up through the triceps to the start.",
                ),
                cues = listOf("Elbows point straight back, not out", "Keep the hips close to the bench"),
                mistakes = listOf("Dropping too deep and stressing the shoulder", "Shrugging the shoulders toward the ears"),
            ),
        )
        add(
            exercise(
                slug = "plank",
                name = "Front plank",
                category = ActivityCategory.STRENGTH,
                muscle = "Core",
                equipment = "Bodyweight",
                difficulty = FitnessLevel.BEGINNER,
                met = 3.5,
                seconds = 45,
                instructions = listOf(
                    "Set up on the forearms and toes, elbows under the shoulders.",
                    "Form a straight line from head to heels.",
                    "Brace the abdominals and glutes, tucking the pelvis slightly.",
                    "Breathe normally and hold for the target time without letting the hips drop.",
                ),
                cues = listOf("Squeeze glutes to protect the lower back", "Push the floor away with the forearms"),
                mistakes = listOf("Hips sagging or piking", "Holding the breath"),
            ),
        )
        add(
            exercise(
                slug = "side-plank",
                name = "Side plank",
                category = ActivityCategory.STRENGTH,
                muscle = "Core",
                equipment = "Bodyweight",
                difficulty = FitnessLevel.INTERMEDIATE,
                met = 3.5,
                seconds = 30,
                instructions = listOf(
                    "Lie on your side with the forearm under the shoulder and legs stacked.",
                    "Lift the hips so the body forms a straight line from head to feet.",
                    "Hold, keeping the top hip stacked over the bottom one.",
                    "Swap sides after the target time.",
                ),
                cues = listOf("Reach the top arm to the ceiling for stability", "Don't let the hips rotate forward or back"),
                mistakes = listOf("Letting the hips sink toward the floor", "Neck collapsing toward the shoulder"),
            ),
        )
        add(
            exercise(
                slug = "dead-bug",
                name = "Dead bug",
                category = ActivityCategory.STRENGTH,
                muscle = "Core",
                equipment = "Bodyweight",
                difficulty = FitnessLevel.BEGINNER,
                met = 3.0,
                sets = 3, reps = 10,
                instructions = listOf(
                    "Lie on your back with arms straight up and knees bent at 90° over the hips.",
                    "Press the lower back gently into the floor and keep it there.",
                    "Slowly extend the opposite arm and leg away without the back lifting.",
                    "Return to the start and repeat on the other side.",
                ),
                cues = listOf("Exhale as you extend", "Slow and controlled beats big and sloppy"),
                mistakes = listOf("Lower back arching off the floor", "Moving too fast to control the ribs"),
            ),
        )
        add(
            exercise(
                slug = "hip-hinge-good-morning",
                name = "Bodyweight good morning",
                category = ActivityCategory.STRENGTH,
                muscle = "Hamstrings",
                equipment = "Bodyweight",
                difficulty = FitnessLevel.BEGINNER,
                met = 3.3,
                sets = 3, reps = 12,
                instructions = listOf(
                    "Stand with feet hip-width apart, hands lightly behind the head.",
                    "Push the hips back while keeping the spine long and knees softly bent.",
                    "Lower until the torso is around 45° or you feel hamstring tension.",
                    "Squeeze the glutes to return to standing.",
                ),
                cues = listOf("It is a hip movement, not a spine movement", "Keep the neck in line with the spine"),
                mistakes = listOf("Rounding the back", "Bending the knees excessively"),
            ),
        )

        /* --------------------- bodyweight conditioning --------------------- */
        add(
            exercise(
                slug = "burpee",
                name = "Burpee",
                category = ActivityCategory.STRENGTH,
                muscle = "Full body",
                equipment = "Bodyweight",
                difficulty = FitnessLevel.INTERMEDIATE,
                met = 8.0,
                sets = 3, reps = 8,
                instructions = listOf(
                    "From standing, squat down and place the hands on the floor.",
                    "Jump or step the feet back into a plank.",
                    "Optionally perform a push-up, then jump or step the feet back in.",
                    "Stand and jump up, then repeat at a pace you can sustain with good form.",
                ),
                cues = listOf("Pace over precision when breathing gets hard, but not at the cost of the back", "Land softly with bent knees"),
                mistakes = listOf("Sagging hips in the plank", "Skipping the push-up but claiming it as one"),
            ),
        )
        add(
            exercise(
                slug = "jumping-jack",
                name = "Jumping jack",
                category = ActivityCategory.STRENGTH,
                muscle = "Full body",
                equipment = "Bodyweight",
                difficulty = FitnessLevel.BEGINNER,
                met = 8.0,
                seconds = 60,
                instructions = listOf(
                    "Stand upright with feet together and arms at your sides.",
                    "Jump the feet out while raising the arms overhead.",
                    "Jump back to the start position.",
                    "Keep a steady rhythm and land softly through the whole foot.",
                ),
                cues = listOf("Stay light on the feet", "Breathe rhythmically, don't hold your breath"),
                mistakes = listOf("Stiff, hard landings", "Arms stopping at shoulder height"),
            ),
        )
        add(
            exercise(
                slug = "mountain-climber",
                name = "Mountain climber",
                category = ActivityCategory.STRENGTH,
                muscle = "Core",
                equipment = "Bodyweight",
                difficulty = FitnessLevel.INTERMEDIATE,
                met = 8.0,
                seconds = 40,
                instructions = listOf(
                    "Start in a high plank with the shoulders over the hands.",
                    "Drive one knee toward the chest, then return it while the other knee drives in.",
                    "Continue alternating at a controlled but brisk pace.",
                    "Keep the hips low and level throughout.",
                ),
                cues = listOf("Hips stay level — no bouncing", "Push the floor away to stay tall in the shoulders"),
                mistakes = listOf("Hips rising into a pike", "Rushing so much that the feet barely move"),
            ),
        )
        add(
            exercise(
                slug = "squat-jump",
                name = "Squat jump",
                category = ActivityCategory.STRENGTH,
                muscle = "Quads",
                equipment = "Bodyweight",
                difficulty = FitnessLevel.INTERMEDIATE,
                met = 9.0,
                sets = 3, reps = 10,
                instructions = listOf(
                    "Start in a quarter-to-half squat with arms back.",
                    "Jump explosively upward, swinging the arms forward.",
                    "Land softly with bent knees and absorb the impact.",
                    "Reset for a moment before the next repetition.",
                ),
                cues = listOf("Quiet landings", "Reset between reps rather than bouncing"),
                mistakes = listOf("Landing with straight legs", "Chasing reps after the jumps lose height"),
            ),
        )
        add(
            exercise(
                slug = "step-up",
                name = "Step-up",
                category = ActivityCategory.STRENGTH,
                muscle = "Quads",
                equipment = "Step or bench",
                difficulty = FitnessLevel.BEGINNER,
                met = 5.5,
                sets = 3, reps = 12,
                instructions = listOf(
                    "Place one foot fully on a stable step at knee or mid-thigh height.",
                    "Drive through that foot to stand up onto the step, minimising the push from the back foot.",
                    "Stand tall at the top with the hips fully extended.",
                    "Lower under control and repeat before switching legs.",
                ),
                cues = listOf("Foot entirely on the step", "Control the way down — that's where the work is"),
                mistakes = listOf("Bouncing off the back foot", "Using a step so high the knee collapses inward"),
            ),
        )

        /* ------------------------------ mobility --------------------------- */
        add(
            exercise(
                slug = "cat-cow",
                name = "Cat–cow",
                category = ActivityCategory.MOBILITY,
                muscle = "Spine",
                equipment = "Mat",
                difficulty = FitnessLevel.BEGINNER,
                met = 2.3,
                seconds = 60,
                instructions = listOf(
                    "Start on hands and knees with a neutral spine.",
                    "Inhale and drop the belly, lifting the chest and tailbone (cow).",
                    "Exhale and round the spine, tucking the chin and tailbone (cat).",
                    "Move slowly, matching each phase to the breath.",
                ),
                cues = listOf("Move segment by segment", "Let the breath set the pace"),
                mistakes = listOf("Rushing through reps", "Forcing range where the spine feels pinched"),
            ),
        )
        add(
            exercise(
                slug = "worlds-greatest-stretch",
                name = "World's greatest stretch",
                category = ActivityCategory.MOBILITY,
                muscle = "Full body",
                equipment = "Mat",
                difficulty = FitnessLevel.INTERMEDIATE,
                met = 3.0,
                sets = 2, reps = 5,
                instructions = listOf(
                    "Step into a long lunge and place both hands inside the front foot.",
                    "Drop the back knee slightly and rotate the inside arm up to the ceiling.",
                    "Follow the hand with your eyes and hold for a breath.",
                    "Return, then switch sides and repeat.",
                ),
                cues = listOf("Long spine before rotating", "Hips stay square to the front"),
                mistakes = listOf("Rotating from the lower back instead of the thorax", "Short lunge that crowds the hip"),
            ),
        )
        add(
            exercise(
                slug = "90-90-hip-switch",
                name = "90/90 hip switch",
                category = ActivityCategory.MOBILITY,
                muscle = "Hips",
                equipment = "Mat",
                difficulty = FitnessLevel.INTERMEDIATE,
                met = 2.5,
                seconds = 60,
                instructions = listOf(
                    "Sit with the front leg bent at 90° in front and the back leg bent at 90° behind.",
                    "Keep the chest tall and hands lightly on the floor for support.",
                    "Lift both knees and rotate to switch the legs to the mirror position.",
                    "Move slowly and stay within a comfortable range.",
                ),
                cues = listOf("Small ranges done well beat forcing depth", "Keep the feet flat on the floor"),
                mistakes = listOf("Collapsing through the spine", "Pushing into pain in the hip"),
            ),
        )

        /* ------------------------------ yoga ------------------------------- */
        add(
            exercise(
                slug = "downward-dog",
                name = "Downward-facing dog",
                category = ActivityCategory.MOBILITY,
                muscle = "Full body",
                equipment = "Mat",
                difficulty = FitnessLevel.BEGINNER,
                met = 2.5,
                seconds = 45,
                instructions = listOf(
                    "From hands and knees, tuck the toes and lift the hips up and back.",
                    "Press the floor away and lengthen the spine, bending the knees if the hamstrings are tight.",
                    "Let the head hang between the arms with the ears in line with the upper arms.",
                    "Hold and breathe, easing deeper on each exhale.",
                ),
                cues = listOf("Length in the spine over straight legs", "Spread the fingers and press the whole palm down"),
                mistakes = listOf("Rounding the back to force the heels down", "Shrugging the shoulders to the ears"),
            ),
        )
        add(
            exercise(
                slug = "warrior-two",
                name = "Warrior II",
                category = ActivityCategory.MOBILITY,
                muscle = "Legs",
                equipment = "Mat",
                difficulty = FitnessLevel.BEGINNER,
                met = 2.8,
                seconds = 45,
                instructions = listOf(
                    "Step the feet wide, turn the front foot out 90° and the back foot in slightly.",
                    "Bend the front knee to about 90°, tracking over the toes.",
                    "Extend the arms parallel to the floor in opposite directions.",
                    "Gaze over the front hand, hold and breathe, then repeat on the other side.",
                ),
                cues = listOf("Front knee over the ankle", "Torso upright, not leaning forward"),
                mistakes = listOf("Front knee collapsing inward", "Hips sliding forward over the front foot"),
            ),
        )
        add(
            exercise(
                slug = "childs-pose",
                name = "Child's pose",
                category = ActivityCategory.MOBILITY,
                muscle = "Back",
                equipment = "Mat",
                difficulty = FitnessLevel.BEGINNER,
                met = 2.0,
                seconds = 60,
                instructions = listOf(
                    "Kneel with the big toes together and knees apart.",
                    "Sit back onto the heels and fold forward, extending the arms.",
                    "Rest the forehead on the mat and let the shoulders relax.",
                    "Breathe slowly into the back for the full hold.",
                ),
                cues = listOf("Let the body be heavy", "Widen the knees if the hips feel tight"),
                mistakes = listOf("Shoulders creeping up", "Holding tension in the jaw and neck"),
            ),
        )
        add(
            exercise(
                slug = "sun-salutation-a",
                name = "Sun salutation A",
                category = ActivityCategory.MOBILITY,
                muscle = "Full body",
                equipment = "Mat",
                difficulty = FitnessLevel.INTERMEDIATE,
                met = 4.0,
                sets = 3, seconds = 60,
                instructions = listOf(
                    "Start standing, inhale and reach overhead.",
                    "Exhale and fold forward, then inhale to a half-lift.",
                    "Step or jump back to a plank, lower to the floor, and inhale into an upward-facing position.",
                    "Exhale to downward dog, then walk or jump the feet forward and rise to standing.",
                ),
                cues = listOf("Link each movement to one breath", "Keep the shoulders away from the ears in plank"),
                mistakes = listOf("Rushing the transitions", "Collapsing the lower back in the backbend"),
            ),
        )

        /* ------------------------- cardio modalities ---------------------- */
        add(
            exercise(
                slug = "treadmill-intervals",
                name = "Treadmill intervals",
                category = ActivityCategory.CARDIO,
                muscle = "Cardiovascular",
                equipment = "Treadmill",
                difficulty = FitnessLevel.INTERMEDIATE,
                met = 9.0,
                seconds = 1500,
                instructions = listOf(
                    "Warm up with 5 minutes of easy walking or jogging.",
                    "Run 4 minutes at a hard but repeatable pace (RPE 8).",
                    "Recover with 3 minutes of easy jogging or walking.",
                    "Repeat 4 times, then cool down for 5 minutes.",
                ),
                cues = listOf("Hold the pace steady for the whole interval", "Use the rail only for balance, not support"),
                mistakes = listOf("Starting too fast and fading in the second interval", "Skipping the cool-down"),
            ),
        )
        add(
            exercise(
                slug = "stationary-bike-tempo",
                name = "Stationary bike tempo",
                category = ActivityCategory.CARDIO,
                muscle = "Cardiovascular",
                equipment = "Stationary bike",
                difficulty = FitnessLevel.BEGINNER,
                met = 7.0,
                seconds = 1800,
                instructions = listOf(
                    "Set the saddle height so the knee is almost straight at the bottom of the pedal stroke.",
                    "Warm up for 5 minutes at an easy resistance.",
                    "Ride 25 minutes at a pace where talking is possible but not comfortable.",
                    "Cool down for 5 minutes at easy resistance.",
                ),
                cues = listOf("Smooth, round pedal stroke", "Relaxed grip on the handlebars"),
                mistakes = listOf("Saddle too low, which stresses the knees", "Rocking the hips to push harder"),
            ),
        )
        add(
            exercise(
                slug = "elliptical-steady",
                name = "Elliptical steady state",
                category = ActivityCategory.CARDIO,
                muscle = "Cardiovascular",
                equipment = "Elliptical",
                difficulty = FitnessLevel.BEGINNER,
                met = 5.0,
                seconds = 1800,
                instructions = listOf(
                    "Step on and start at an easy resistance for 5 minutes.",
                    "Settle into a rhythm that keeps the breathing deep but controlled.",
                    "Hold 25 minutes, using the handles to involve the upper body.",
                    "Ease off for 5 minutes before stopping.",
                ),
                cues = listOf("Stand tall, don't lean on the handles", "Keep the feet flat on the pedals"),
                mistakes = listOf("Coasting with almost no resistance", "Death-gripping the moving handles"),
            ),
        )
        add(
            exercise(
                slug = "outdoor-walk-brisk",
                name = "Brisk outdoor walk",
                category = ActivityCategory.CARDIO,
                muscle = "Cardiovascular",
                equipment = "None",
                difficulty = FitnessLevel.BEGINNER,
                met = 4.3,
                seconds = 1800,
                instructions = listOf(
                    "Start with 3 minutes at an easy pace to warm up.",
                    "Increase to a brisk pace: breathing deeper, but still able to talk in sentences.",
                    "Hold that pace for the main block, using hills for extra intensity.",
                    "Finish with 3 minutes easy and a short stretch.",
                ),
                cues = listOf("Stand tall, arms swinging naturally", "Push off from the back foot"),
                mistakes = listOf("Ambling pace that never raises the heart rate", "Overstriding, which jars the knees"),
            ),
        )
        add(
            exercise(
                slug = "cycling-outdoor",
                name = "Outdoor cycling",
                category = ActivityCategory.CARDIO,
                muscle = "Cardiovascular",
                equipment = "Bicycle",
                difficulty = FitnessLevel.INTERMEDIATE,
                met = 7.5,
                seconds = 2400,
                instructions = listOf(
                    "Check tyre pressure, brakes and lights before setting off.",
                    "Start with 5 minutes of easy spinning.",
                    "Ride the main block at a steady effort, adjusting gears to keep cadence comfortable.",
                    "Cool down with 5 minutes easy before stopping.",
                ),
                cues = listOf("Cadence around 80–90 rpm feels efficient for most riders", "Stay visible and predictable in traffic"),
                mistakes = listOf("Mashing a big gear at low cadence", "Skipping the warm-up on a cold day"),
            ),
        )
        add(
            exercise(
                slug = "hike-uphill",
                name = "Uphill hike",
                category = ActivityCategory.CARDIO,
                muscle = "Cardiovascular",
                equipment = "Trail shoes",
                difficulty = FitnessLevel.INTERMEDIATE,
                met = 6.5,
                seconds = 3600,
                instructions = listOf(
                    "Start on flatter ground for 5–10 minutes to warm up.",
                    "Keep a pace where you can speak a short sentence on the climbs.",
                    "Use shorter steps on steep sections rather than long strides.",
                    "Descend under control with a slightly bent knee to reduce joint load.",
                ),
                cues = listOf("Shorter steps going up", "Controlled, soft steps going down"),
                mistakes = listOf("Racing the descent and loading the knees", "Ignoring weather and hydration on long routes"),
            ),
        )
    }

    private fun exercise(
        slug: String,
        name: String,
        category: ActivityCategory,
        muscle: String,
        equipment: String,
        difficulty: FitnessLevel,
        met: Double,
        instructions: List<String>,
        cues: List<String> = emptyList(),
        mistakes: List<String> = emptyList(),
        sets: Int? = null,
        reps: Int? = null,
        seconds: Int? = null,
        compound: Boolean = false,
    ) = Exercise(
        id = "seed.$slug",
        name = name,
        category = category,
        primaryMuscle = muscle,
        equipment = equipment,
        difficulty = difficulty,
        metValue = met,
        instructions = instructions,
        cues = cues,
        commonMistakes = mistakes,
        defaultSets = sets,
        defaultReps = reps,
        defaultSeconds = seconds,
        isCompound = compound,
        isCustom = false,
    )
}
