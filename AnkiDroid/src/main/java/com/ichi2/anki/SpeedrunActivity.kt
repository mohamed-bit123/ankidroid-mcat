// SPDX-License-Identifier: GPL-3.0-or-later
// Speedrun (MCAT fork): mobile companion to the desktop "MCAT Speedrun" panel.
//
// Mirrors qt/aqt/speedrun.py: a practice-question runner plus a live, honest
// three-score panel (Memory / Performance / Readiness). Scores come straight
// from the shared Rust engine (speedrunScores); graded answers are fed back via
// speedrunRecordAttempt; the question order comes from the weakness-weighted
// speedrunNextQuestions ranking (the question analogue of the points-at-stake
// review queue). Application questions are notes of the "MCAT Practice Question"
// notetype tagged `mcat-question`, kept separate from flashcards.

package com.ichi2.anki

import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.ichi2.anki.CollectionManager.withCol
import com.ichi2.anki.common.utils.android.showThemedToast
import com.ichi2.anki.libanki.Collection
import com.ichi2.anki.libanki.Note
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class SpeedrunActivity : AnkiActivity() {
    private data class Question(
        val cardId: Long,
        val topic: String,
        val stem: String,
        val options: Map<String, String>,
        val answer: String,
        val explanation: String,
        val priority: Float?,
    )

    private lateinit var memoryView: TextView
    private lateinit var performanceView: TextView
    private lateinit var readinessView: TextView
    private lateinit var weightedCheck: CheckBox
    private lateinit var progressView: TextView
    private lateinit var topicView: TextView
    private lateinit var stemView: TextView
    private lateinit var optionButtons: Map<String, Button>
    private lateinit var feedbackView: TextView
    private lateinit var nextButton: Button
    private lateinit var startButton: Button

    private var questions: List<Question> = emptyList()
    private var index = 0
    private var correctCount = 0
    private var answered = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "MCAT Speedrun"
        setContentView(buildUi())
        refreshScores()
        updateControls()
    }

    private fun buildUi(): ScrollView {
        val pad = (resources.displayMetrics.density * 16).toInt()
        val root =
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(pad, pad, pad, pad)
            }

        fun header(text: String) =
            TextView(this).apply {
                this.text = text
                textSize = 18f
                setPadding(0, pad / 2, 0, pad / 4)
            }

        fun card(label: String): TextView {
            root.addView(header(label))
            val tv =
                TextView(this).apply {
                    textSize = 14f
                    setPadding(pad / 2, pad / 2, pad / 2, pad / 2)
                }
            root.addView(tv)
            return tv
        }

        root.addView(
            TextView(this).apply {
                text = "Three scores, measured separately and honestly."
                textSize = 13f
            },
        )

        memoryView = card("Memory")
        performanceView = card("Performance")
        readinessView = card("Readiness")

        val divider =
            TextView(this).apply {
                text = "—".repeat(20)
                setPadding(0, pad, 0, pad / 2)
            }
        root.addView(divider)

        weightedCheck =
            CheckBox(this).apply {
                text = "Weakness-weighted order (points at stake)"
                isChecked = true
            }
        root.addView(weightedCheck)

        startButton =
            Button(this).apply {
                text = "Start practice set"
                setOnClickListener { startSet() }
            }
        root.addView(startButton)

        val seedButton =
            Button(this).apply {
                text = "Seed demo questions"
                setOnClickListener { onSeed() }
            }
        root.addView(seedButton)

        progressView = TextView(this).apply { setPadding(0, pad, 0, 0) }
        root.addView(progressView)

        topicView =
            TextView(this).apply {
                textSize = 13f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
            }
        root.addView(topicView)

        stemView =
            TextView(this).apply {
                text = "Start a practice set to begin."
                textSize = 16f
                setPadding(0, pad / 2, 0, pad / 2)
            }
        root.addView(stemView)

        optionButtons =
            listOf("A", "B", "C", "D").associateWith { letter ->
                Button(this)
                    .apply {
                        isAllCaps = false
                        gravity = Gravity.START or Gravity.CENTER_VERTICAL
                        layoutParams =
                            LinearLayout.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT,
                                ViewGroup.LayoutParams.WRAP_CONTENT,
                            )
                        setOnClickListener { onAnswer(letter) }
                    }.also { root.addView(it) }
            }

        feedbackView =
            TextView(this).apply {
                textSize = 14f
                setPadding(0, pad / 2, 0, pad / 2)
            }
        root.addView(feedbackView)

        nextButton =
            Button(this).apply {
                text = "Next question"
                isEnabled = false
                setOnClickListener { onNext() }
            }
        root.addView(nextButton)

        return ScrollView(this).apply { addView(root) }
    }

    // Scores ------------------------------------------------------------------

    private fun refreshScores() =
        launchCatchingTask {
            val s = withContext(Dispatchers.IO) { CollectionManager.getBackend().speedrunScores() }
            val mem = s.memory
            memoryView.text =
                if (mem.known) {
                    "${mem.value.toInt()}/100\nretained over ${mem.studiedCards} studied card(s), " +
                        "${(mem.topicCoverage * 100).toInt()}% of topics"
                } else {
                    "—\n${mem.reason}"
                }

            val perf = s.performance
            performanceView.text =
                if (perf.known) {
                    "${perf.value.toInt()}/100\napplied accuracy over ${perf.attempts} question(s), " +
                        "${perf.topicsCovered} topic(s)"
                } else {
                    "—\n${perf.reason}"
                }

            val rdy = s.readiness
            readinessView.text =
                if (rdy.known) {
                    "${rdy.projected.toInt()}\nrange ${rdy.low.toInt()}–${rdy.high.toInt()} (472–528 scale), " +
                        "${rdy.confidence} confidence\n${rdy.calibrationNote}"
                } else {
                    "—\n${rdy.reason}"
                }
        }

    // Practice flow -----------------------------------------------------------

    private fun startSet() =
        launchCatchingTask {
            questions =
                if (weightedCheck.isChecked) {
                    loadWeighted()
                } else {
                    loadRandom()
                }
            if (questions.isEmpty()) {
                showThemedToast(this@SpeedrunActivity, "No questions. Tap “Seed demo questions” or import your own.", false)
                return@launchCatchingTask
            }
            index = 0
            correctCount = 0
            showQuestion()
        }

    private suspend fun loadWeighted(): List<Question> {
        val ranked = withContext(Dispatchers.IO) { CollectionManager.getBackend().speedrunNextQuestions() }
        return withCol {
            ranked.mapNotNull { item ->
                val note = getCard(item.cardId).note(this)
                buildQuestion(item.cardId, note, item.priority)
            }
        }
    }

    private suspend fun loadRandom(): List<Question> =
        withCol {
            findNotes("tag:$QUESTION_TAG").shuffled().mapNotNull { nid ->
                val note = getNote(nid)
                val cardId = note.cardIds(this).firstOrNull() ?: return@mapNotNull null
                buildQuestion(cardId, note, null)
            }
        }

    private fun buildQuestion(
        cardId: Long,
        note: Note,
        priority: Float?,
    ): Question? {
        if (!note.contains("Stem") || note.getItem("Stem").isBlank()) return null
        val options = listOf("A", "B", "C", "D").associateWith { if (note.contains(it)) note.getItem(it) else "" }
        return Question(
            cardId = cardId,
            topic = if (note.contains("Topic")) note.getItem("Topic") else "",
            stem = note.getItem("Stem"),
            options = options,
            answer = (if (note.contains("Answer")) note.getItem("Answer") else "").trim().uppercase().take(1),
            explanation = if (note.contains("Explanation")) note.getItem("Explanation") else "",
            priority = priority,
        )
    }

    private fun showQuestion() {
        answered = false
        val q = questions[index]
        val extra = q.priority?.let { "  •  priority %.2f".format(it) } ?: ""
        progressView.text = "Question ${index + 1} of ${questions.size}  •  $correctCount correct so far$extra"
        topicView.text = q.topic
        stemView.text = q.stem
        feedbackView.text = ""
        nextButton.isEnabled = false
        for ((letter, btn) in optionButtons) {
            val text = q.options[letter].orEmpty()
            btn.text = "$letter.  $text"
            btn.isEnabled = text.isNotEmpty()
        }
    }

    private fun onAnswer(letter: String) {
        if (answered) return
        answered = true
        val q = questions[index]
        val correct = letter == q.answer
        if (correct) correctCount++
        optionButtons.values.forEach { it.isEnabled = false }
        feedbackView.text =
            if (correct) {
                "Correct.\n${q.explanation}"
            } else {
                "Incorrect. Answer: ${q.answer}.\n${q.explanation}"
            }
        nextButton.isEnabled = true
        launchCatchingTask {
            withContext(Dispatchers.IO) {
                CollectionManager.getBackend().speedrunRecordAttempt(q.cardId, correct)
            }
            refreshScores()
        }
    }

    private fun onNext() {
        index++
        if (index >= questions.size) {
            progressView.text = "Set complete: $correctCount/${questions.size} correct."
            topicView.text = ""
            stemView.text = "Start another practice set to keep going."
            feedbackView.text = ""
            nextButton.isEnabled = false
            optionButtons.values.forEach {
                it.text = ""
                it.isEnabled = false
            }
            refreshScores()
            return
        }
        showQuestion()
    }

    private fun onSeed() =
        launchCatchingTask {
            val existing = withCol { findNotes("tag:$QUESTION_TAG").size }
            if (existing > 0) {
                showThemedToast(this@SpeedrunActivity, "You already have $existing question(s).", false)
                return@launchCatchingTask
            }
            val added = withCol { seedDemoQuestions() }
            showThemedToast(this@SpeedrunActivity, "Added $added demo questions.", false)
            updateControls()
            refreshScores()
        }

    private fun updateControls() =
        launchCatchingTask {
            val count = withCol { findNotes("tag:$QUESTION_TAG").size }
            startButton.isEnabled = count > 0
            if (count == 0) {
                stemView.text = "No questions yet. Tap “Seed demo questions” to add a starter set."
            }
        }

    // Notetype + seed ---------------------------------------------------------

    private fun Collection.ensureQuestionNotetype() =
        notetypes.byName(NOTETYPE_NAME) ?: run {
            val nt = notetypes.new(NOTETYPE_NAME)
            for (field in FIELDS) {
                notetypes.addField(nt, notetypes.newField(field))
            }
            val template = notetypes.newTemplate("Card 1")
            template.qfmt = "{{Stem}}"
            template.afmt = "{{Answer}}. {{Explanation}}"
            notetypes.add_template(nt, template)
            notetypes.add(nt)
            notetypes.byName(NOTETYPE_NAME)!!
        }

    private fun Collection.seedDemoQuestions(): Int {
        val nt = ensureQuestionNotetype()
        var added = 0
        for (row in SEED_QUESTIONS) {
            val (topic, stem, a, b, c, d, answer, explanation) = row
            val deckId = decks.id("$PRACTICE_DECK::$topic")
            val note = newNote(nt)
            note.setItem("Topic", topic)
            note.setItem("Stem", stem)
            note.setItem("A", a)
            note.setItem("B", b)
            note.setItem("C", c)
            note.setItem("D", d)
            note.setItem("Answer", answer)
            note.setItem("Explanation", explanation)
            note.addTag(QUESTION_TAG)
            addNote(note, deckId)
            added++
        }
        return added
    }

    companion object {
        private const val QUESTION_TAG = "mcat-question"
        private const val NOTETYPE_NAME = "MCAT Practice Question"
        private const val PRACTICE_DECK = "MCAT Practice"
        private val FIELDS = listOf("Topic", "Stem", "A", "B", "C", "D", "Answer", "Explanation")

        // Original, hand-authored MCAT-style discretes (kept identical to desktop).
        // Each: topic, stem, A, B, C, D, answer-letter, explanation.
        private val SEED_QUESTIONS: List<List<String>> =
            listOf(
                listOf(
                    "Biochemistry",
                    "An enzyme follows Michaelis-Menten kinetics. A competitive inhibitor is added. How are Km and Vmax affected?",
                    "Km increases, Vmax unchanged",
                    "Km decreases, Vmax unchanged",
                    "Km unchanged, Vmax decreases",
                    "Km increases, Vmax decreases",
                    "A",
                    "Competitive inhibitors raise the apparent Km but Vmax is unchanged because high substrate outcompetes the inhibitor.",
                ),
                listOf(
                    "Biochemistry",
                    "Which amino acid is most likely buried in the hydrophobic core of a globular protein in aqueous solution?",
                    "Lysine",
                    "Glutamate",
                    "Valine",
                    "Serine",
                    "C",
                    "Valine has a nonpolar aliphatic side chain, so it partitions away from water into the protein core.",
                ),
                listOf(
                    "Biology",
                    "During which phase of the cell cycle is DNA replicated?",
                    "G1",
                    "S",
                    "G2",
                    "M",
                    "B",
                    "DNA synthesis (replication) occurs during S phase, between the G1 and G2 gap phases.",
                ),
                listOf(
                    "Biology",
                    "A nonsense mutation most directly results in which of the following?",
                    "A silent change with no effect",
                    "One amino acid substituted for another",
                    "A premature stop codon and truncated protein",
                    "A downstream reading-frame shift",
                    "C",
                    "A nonsense mutation converts a codon into a stop codon, prematurely terminating translation.",
                ),
                listOf(
                    "General Chemistry",
                    "What is the pH of a 0.001 M solution of HCl (a strong acid) at 25 °C?",
                    "1",
                    "3",
                    "7",
                    "11",
                    "B",
                    "HCl fully dissociates, so [H+] = 1e-3 M and pH = -log(1e-3) = 3.",
                ),
                listOf(
                    "General Chemistry",
                    "Which quantum number determines the shape of an orbital?",
                    "Principal (n)",
                    "Azimuthal (l)",
                    "Magnetic (m_l)",
                    "Spin (m_s)",
                    "B",
                    "The azimuthal quantum number l defines the subshell and thus orbital shape (s, p, d, f).",
                ),
                listOf(
                    "Organic Chemistry",
                    "An SN2 reaction proceeds fastest with which substrate?",
                    "Tertiary alkyl halide",
                    "Primary alkyl halide",
                    "Neopentyl halide",
                    "Aryl halide",
                    "B",
                    "SN2 needs backside attack; primary substrates have the least steric hindrance, so they react fastest.",
                ),
                listOf(
                    "Physics",
                    "A 2 kg object accelerates at 3 m/s^2. What net force acts on it?",
                    "1.5 N",
                    "5 N",
                    "6 N",
                    "9 N",
                    "C",
                    "By Newton's second law F = ma = 2 × 3 = 6 N.",
                ),
                listOf(
                    "Physics",
                    "Light passes from air into glass (higher index). What happens to its speed and wavelength?",
                    "Both increase",
                    "Both decrease",
                    "Speed decreases, wavelength unchanged",
                    "Speed increases, wavelength decreases",
                    "B",
                    "In a denser medium light slows; since frequency is fixed, wavelength decreases proportionally.",
                ),
                listOf(
                    "Psychology",
                    "Classical conditioning was first systematically described by which researcher?",
                    "B. F. Skinner",
                    "Ivan Pavlov",
                    "Jean Piaget",
                    "Albert Bandura",
                    "B",
                    "Pavlov demonstrated classical conditioning by pairing a neutral stimulus with an unconditioned stimulus.",
                ),
                listOf(
                    "Psychology",
                    "A drug that blocks reuptake of a neurotransmitter most directly causes what at the synapse?",
                    "Less neurotransmitter in the cleft",
                    "More neurotransmitter remaining in the cleft",
                    "Destruction of the postsynaptic receptor",
                    "Reversal of the action potential",
                    "B",
                    "Blocking reuptake leaves more neurotransmitter in the cleft, prolonging signaling.",
                ),
                listOf(
                    "Sociology",
                    "Changing behavior because one knows they are being observed exemplifies which effect?",
                    "Hawthorne effect",
                    "Halo effect",
                    "Bystander effect",
                    "Placebo effect",
                    "A",
                    "The Hawthorne effect is the alteration of behavior due to awareness of being observed.",
                ),
            )
    }
}

private operator fun <T> List<T>.component6(): T = this[5]

private operator fun <T> List<T>.component7(): T = this[6]

private operator fun <T> List<T>.component8(): T = this[7]
