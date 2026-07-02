// SPDX-License-Identifier: GPL-3.0-or-later
// Speedrun (MCAT fork): mobile companion to the desktop "MCAT Speedrun" panel.
//
// Mirrors qt/aqt/speedrun.py: an open-ended, one-question-at-a-time practice
// runner plus a live, honest three-score panel (Memory / Performance /
// Readiness). Scores come straight from the shared Rust engine (speedrunScores);
// graded answers are fed back via speedrunRecordAttempt; each next question is
// chosen by concept-level FSRS scheduling (speedrunNextQuestion) — whichever
// concept is most due right now, weighted by MCAT yield — so practice interleaves
// concepts and you can go as long as you like against a recommended daily band.
// Application questions are notes of the "MCAT Practice Question" notetype tagged
// `mcat-question`, kept separate from flashcards.

package com.ichi2.anki

import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.ichi2.anki.CollectionManager.withCol
import com.ichi2.anki.common.utils.android.showThemedToast
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
    )

    private lateinit var memoryView: TextView
    private lateinit var performanceView: TextView
    private lateinit var readinessView: TextView
    private lateinit var dailyView: TextView
    private lateinit var progressView: TextView
    private lateinit var topicView: TextView
    private lateinit var stemView: TextView
    private lateinit var optionButtons: Map<String, Button>
    private lateinit var dontKnowButton: Button
    private lateinit var feedbackView: TextView
    private lateinit var nextButton: Button
    private lateinit var startButton: Button

    private var currentQuestion: Question? = null
    private var sessionCount = 0
    private var sessionCorrect = 0
    private var answeredToday = 0
    private var recMin = 0
    private var recMax = 0
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

        startButton =
            Button(this).apply {
                text = "Start practice"
                setOnClickListener { startPractice() }
            }
        root.addView(startButton)

        val setupButton =
            Button(this).apply {
                text = "Set up MCAT content"
                setOnClickListener { onSetupMcat() }
            }
        root.addView(setupButton)

        dailyView =
            TextView(this).apply {
                textSize = 12f
                setPadding(0, pad / 2, 0, 0)
            }
        root.addView(dailyView)

        progressView = TextView(this).apply { setPadding(0, pad / 2, 0, 0) }
        root.addView(progressView)

        topicView =
            TextView(this).apply {
                textSize = 13f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
            }
        root.addView(topicView)

        stemView =
            TextView(this).apply {
                text = "Start practice to begin."
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

        dontKnowButton =
            Button(this).apply {
                text = "I don't know / I'm guessing"
                isAllCaps = false
                setOnClickListener { onDontKnow() }
            }
        root.addView(dontKnowButton)

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

    // Practice flow (open-ended, one question at a time) ----------------------

    private fun startPractice() {
        sessionCount = 0
        sessionCorrect = 0
        loadNextQuestion()
    }

    private fun loadNextQuestion() =
        launchCatchingTask {
            val resp = withContext(Dispatchers.IO) { CollectionManager.getBackend().speedrunNextQuestion() }
            answeredToday = resp.answeredToday
            recMin = resp.recommendedMin
            recMax = resp.recommendedMax
            updateDailyLabel()
            if (!resp.hasQuestion) {
                showThemedToast(this@SpeedrunActivity, "No questions. Tap “Set up MCAT content” or import your own.", false)
                return@launchCatchingTask
            }
            val question =
                withCol {
                    val note = getCard(resp.cardId).note(this)
                    buildQuestion(resp.cardId, note)
                }
            if (question == null) {
                stemView.text = "Could not load the next question."
                return@launchCatchingTask
            }
            currentQuestion = question
            val why =
                if (resp.attempts == 0 && resp.conceptRetrievability <= 0f) {
                    "new concept"
                } else {
                    "concept recall ≈ ${(resp.conceptRetrievability * 100).toInt()}%"
                }
            showCurrent(why)
        }

    private fun buildQuestion(
        cardId: Long,
        note: Note,
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
        )
    }

    private fun showCurrent(why: String) {
        answered = false
        val q = currentQuestion ?: return
        progressView.text = "This session: $sessionCount answered  •  $sessionCorrect correct"
        topicView.text = "${q.topic}  ·  $why"
        stemView.text = q.stem
        feedbackView.text = ""
        nextButton.isEnabled = false
        dontKnowButton.isEnabled = true
        for ((letter, btn) in optionButtons) {
            val text = q.options[letter].orEmpty()
            btn.text = "$letter.  $text"
            btn.isEnabled = text.isNotEmpty()
        }
    }

    private fun onAnswer(letter: String) {
        if (answered) return
        val q = currentQuestion ?: return
        answered = true
        val correct = letter == q.answer
        sessionCount++
        answeredToday++
        if (correct) sessionCorrect++
        optionButtons.values.forEach { it.isEnabled = false }
        dontKnowButton.isEnabled = false
        feedbackView.text =
            if (correct) {
                "Correct.\n${q.explanation}"
            } else {
                "Incorrect. Answer: ${q.answer}.\n${q.explanation}"
            }
        progressView.text = "This session: $sessionCount answered  •  $sessionCorrect correct"
        nextButton.isEnabled = true
        updateDailyLabel()
        launchCatchingTask {
            withContext(Dispatchers.IO) {
                CollectionManager.getBackend().speedrunRecordAttempt(q.cardId, correct)
            }
            refreshScores()
        }
    }

    private fun onDontKnow() {
        if (answered) return
        val q = currentQuestion ?: return
        answered = true
        // An honest "I don't know" counts as not known (incorrect), so a lucky
        // guess can never inflate Performance/Readiness. Still reveal the answer.
        sessionCount++
        answeredToday++
        optionButtons.values.forEach { it.isEnabled = false }
        dontKnowButton.isEnabled = false
        feedbackView.text =
            "Marked “don't know”. Counts as not known, so guessing can't inflate your scores.\n" +
            "Answer: ${q.answer}.\n${q.explanation}"
        progressView.text = "This session: $sessionCount answered  •  $sessionCorrect correct"
        nextButton.isEnabled = true
        updateDailyLabel()
        launchCatchingTask {
            withContext(Dispatchers.IO) {
                CollectionManager.getBackend().speedrunRecordAttempt(q.cardId, false)
            }
            refreshScores()
        }
    }

    private fun onNext() {
        loadNextQuestion()
    }

    private fun updateDailyLabel() {
        val done = answeredToday
        val lo = recMin
        val hi = recMax
        val (nudge, color) =
            when {
                lo > 0 && done < lo -> Pair("— ${lo - done} to today’s minimum", Color.GRAY)
                hi > 0 && done >= hi -> Pair("— daily max reached; diminishing returns", Color.parseColor("#c62828"))
                else -> Pair("— minimum reached; keep going or stop anytime", Color.parseColor("#2e7d32"))
            }
        dailyView.setTextColor(color)
        dailyView.text = "Today: $done answered (recommended $lo–$hi) $nudge"
    }

    private fun onSetupMcat() =
        launchCatchingTask {
            val added = withContext(Dispatchers.IO) { CollectionManager.getBackend().speedrunSeedBuiltin() }
            if (added > 0) {
                val flashcards = withCol { findNotes("tag:mcat-flashcard").size }
                val questions = withCol { findNotes("tag:$QUESTION_TAG").size }
                showThemedToast(
                    this@SpeedrunActivity,
                    "Set up the built-in MCAT bank: $flashcards flashcards (MCAT deck) and " +
                        "$questions practice questions (MCAT Practice), across every subject. " +
                        "Studying the MCAT deck interleaves topics with the weakness-weighted order.",
                    false,
                )
            } else {
                showThemedToast(this@SpeedrunActivity, "MCAT content is already set up.", false)
            }
            updateControls()
            refreshScores()
        }

    private fun updateControls() =
        launchCatchingTask {
            val count = withCol { findNotes("tag:$QUESTION_TAG").size }
            startButton.isEnabled = count > 0
            if (count == 0) {
                stemView.text = "No questions yet. Tap “Set up MCAT content” to load the bank."
                dailyView.text = ""
            } else {
                val resp = withContext(Dispatchers.IO) { CollectionManager.getBackend().speedrunNextQuestion() }
                answeredToday = resp.answeredToday
                recMin = resp.recommendedMin
                recMax = resp.recommendedMax
                updateDailyLabel()
            }
        }

    companion object {
        private const val QUESTION_TAG = "mcat-question"
    }
}
