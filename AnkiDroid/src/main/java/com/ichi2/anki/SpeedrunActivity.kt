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
//
// The UI is built programmatically with Material 3 components (cards, buttons)
// plus two small custom Views (a progress bar and the Readiness range bar) so it
// stays theme-aware (day/night) and visually consistent with the desktop panel.

package com.ichi2.anki

import android.app.DatePickerDialog
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.graphics.ColorUtils
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.color.MaterialColors
import com.ichi2.anki.CollectionManager.withCol
import com.ichi2.anki.common.utils.android.showThemedToast
import com.ichi2.anki.libanki.Note
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Calendar

class SpeedrunActivity : AnkiActivity() {
    private data class Question(
        val cardId: Long,
        val topic: String,
        val stem: String,
        val options: Map<String, String>,
        val answer: String,
        val explanation: String,
    )

    // Each score reads as its own thing, so each gets its own accent.
    private val accentMemory = Color.parseColor("#3b82f6")
    private val accentPerformance = Color.parseColor("#14b8a6")
    private val accentReadiness = Color.parseColor("#8b5cf6")
    private val confColors =
        mapOf(
            "low" to Color.parseColor("#f59e0b"),
            "medium" to Color.parseColor("#3b82f6"),
            "high" to Color.parseColor("#10b981"),
        )

    // Theme-aware neutrals, resolved in onCreate.
    private var onSurface = Color.BLACK
    private var muted = Color.GRAY
    private var outline = Color.LTGRAY

    private lateinit var memoryCard: ScoreCard
    private lateinit var performanceCard: ScoreCard
    private lateinit var readinessCard: ScoreCard
    private lateinit var dailyView: TextView
    private lateinit var progressView: TextView
    private lateinit var topicView: TextView
    private lateinit var stemView: TextView
    private lateinit var optionButtons: Map<String, MaterialButton>
    private lateinit var dontKnowButton: MaterialButton
    private lateinit var feedbackView: TextView
    private lateinit var nextButton: MaterialButton
    private lateinit var startButton: MaterialButton
    private lateinit var examLabel: TextView
    private lateinit var clearExamButton: MaterialButton

    private var examHasDate = false
    private var examDaysAway = 0
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
        onSurface = MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnSurface, Color.BLACK)
        muted = MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnSurfaceVariant, Color.GRAY)
        outline = MaterialColors.getColor(this, com.google.android.material.R.attr.colorOutlineVariant, Color.LTGRAY)
        setContentView(buildUi())
    }

    override fun onResume() {
        super.onResume()
        // Recompute every time the panel becomes visible — not just on create —
        // so the scores are never stale after a sync (done from the DeckPicker)
        // or after the day rolls over. The scores are recomputed from the
        // collection, so this always reflects the latest synced data.
        refreshScores()
        updateControls()
    }

    // Layout helpers ----------------------------------------------------------

    private fun dp(v: Float): Float = resources.displayMetrics.density * v

    private fun dpi(v: Float): Int = dp(v).toInt()

    private fun matchWrap(bottomMargin: Float = 0f): LinearLayout.LayoutParams =
        LinearLayout
            .LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { this.bottomMargin = dpi(bottomMargin) }

    private fun pill(
        text: String,
        color: Int,
    ): TextView =
        TextView(this).apply {
            this.text = text
            setTextColor(color)
            textSize = 10f
            setTypeface(typeface, Typeface.BOLD)
            setPadding(dpi(9f), dpi(3f), dpi(9f), dpi(3f))
            background =
                GradientDrawable().apply {
                    cornerRadius = dp(9f)
                    setColor(ColorUtils.setAlphaComponent(color, 40))
                }
        }

    private fun card(): MaterialCardView =
        MaterialCardView(this).apply {
            radius = dp(16f)
            cardElevation = 0f
            strokeWidth = dpi(1f)
            strokeColor = outline
            setContentPadding(dpi(16f), dpi(14f), dpi(16f), dpi(14f))
            layoutParams = matchWrap(bottomMargin = 12f)
        }

    private fun filledButton(text: String): MaterialButton =
        MaterialButton(this).apply {
            this.text = text
            isAllCaps = false
        }

    private fun outlinedButton(text: String): MaterialButton =
        MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            this.text = text
            isAllCaps = false
        }

    private fun buildUi(): ScrollView {
        val pad = dpi(16f)
        val root =
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(pad, pad, pad, pad)
            }

        root.addView(
            TextView(this).apply {
                text = "MCAT Speedrun"
                textSize = 22f
                setTextColor(onSurface)
                setTypeface(typeface, Typeface.BOLD)
            },
        )
        root.addView(
            TextView(this).apply {
                text = "Three scores, measured separately and honestly."
                textSize = 13f
                setTextColor(muted)
                setPadding(0, dpi(2f), 0, dpi(14f))
            },
        )

        // Three score cards, stacked full-width for phone readability.
        memoryCard = ScoreCard("Memory", accentMemory, "pct")
        performanceCard = ScoreCard("Performance", accentPerformance, "pct")
        readinessCard = ScoreCard("Readiness", accentReadiness, "range")
        root.addView(memoryCard.view)
        root.addView(performanceCard.view)
        root.addView(readinessCard.view)

        // Exam date row — drives the forward projection of Readiness.
        val examCard = card()
        val examRow =
            LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
        examCard.addView(examRow)
        examRow.addView(
            TextView(this).apply {
                text = "EXAM DATE"
                textSize = 11f
                setTextColor(muted)
                setTypeface(typeface, Typeface.BOLD)
                letterSpacing = 0.08f
            },
        )
        examLabel =
            TextView(this).apply {
                textSize = 12f
                setTextColor(muted)
                setPadding(dpi(8f), 0, dpi(8f), 0)
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }
        examRow.addView(examLabel)
        examRow.addView(
            outlinedButton("Set…").apply { setOnClickListener { onSetExamDate() } },
        )
        clearExamButton =
            outlinedButton("Clear").apply { setOnClickListener { onClearExamDate() } }
        examRow.addView(clearExamButton)
        root.addView(examCard)

        // Practice card.
        val practice = card()
        val pv =
            LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        practice.addView(pv)

        dailyView =
            TextView(this).apply {
                textSize = 12f
                setPadding(0, 0, 0, dpi(6f))
            }
        pv.addView(dailyView)

        progressView =
            TextView(this).apply {
                textSize = 12f
                setTextColor(muted)
            }
        pv.addView(progressView)

        topicView =
            TextView(this).apply {
                textSize = 12f
                setTextColor(accentPerformance)
                setTypeface(typeface, Typeface.BOLD)
                setPadding(0, dpi(2f), 0, 0)
            }
        pv.addView(topicView)

        stemView =
            TextView(this).apply {
                text = "Start practice to begin."
                textSize = 16f
                setTextColor(onSurface)
                setPadding(0, dpi(8f), 0, dpi(8f))
            }
        pv.addView(stemView)

        optionButtons =
            listOf("A", "B", "C", "D").associateWith { letter ->
                outlinedButton("")
                    .apply {
                        gravity = Gravity.START or Gravity.CENTER_VERTICAL
                        insetTop = 0
                        insetBottom = 0
                        minHeight = dpi(48f)
                        layoutParams = matchWrap(bottomMargin = 8f)
                        setOnClickListener { onAnswer(letter) }
                    }.also { pv.addView(it) }
            }

        dontKnowButton =
            outlinedButton("I don't know / I'm guessing").apply {
                setTextColor(muted)
                layoutParams = matchWrap(bottomMargin = 4f)
                setOnClickListener { onDontKnow() }
            }
        pv.addView(dontKnowButton)

        feedbackView =
            TextView(this).apply {
                textSize = 14f
                setTextColor(onSurface)
                setPadding(0, dpi(6f), 0, dpi(8f))
            }
        pv.addView(feedbackView)

        nextButton =
            filledButton("Next question").apply {
                isEnabled = false
                layoutParams = matchWrap()
                setOnClickListener { onNext() }
            }
        pv.addView(nextButton)

        root.addView(practice)

        // Action buttons.
        startButton =
            filledButton("Start practice").apply {
                layoutParams = matchWrap(bottomMargin = 8f)
                setOnClickListener { startPractice() }
            }
        root.addView(startButton)
        root.addView(
            outlinedButton("Set up MCAT content").apply {
                layoutParams = matchWrap(bottomMargin = 8f)
                setOnClickListener { onSetupMcat() }
            },
        )
        root.addView(
            outlinedButton("Record full-length score").apply {
                layoutParams = matchWrap(bottomMargin = 8f)
                setOnClickListener { onRecordCalibration() }
            },
        )
        root.addView(
            outlinedButton("Generate with AI").apply {
                layoutParams = matchWrap()
                setOnClickListener { onGenerateAi() }
            },
        )

        return ScrollView(this).apply { addView(root) }
    }

    // A single score card: accent title, big value, a bar/range, caption, pill.
    private inner class ScoreCard(
        title: String,
        private val accent: Int,
        kind: String,
    ) {
        val view: MaterialCardView = card()
        private val valueView: TextView
        private val captionView: TextView
        private val pillHolder: LinearLayout
        private val bar: BarView?
        private val range: RangeBarView?

        init {
            val col = LinearLayout(this@SpeedrunActivity).apply { orientation = LinearLayout.VERTICAL }
            view.addView(col)

            val header =
                LinearLayout(this@SpeedrunActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                }
            header.addView(
                TextView(this@SpeedrunActivity).apply {
                    text = title.uppercase()
                    textSize = 11f
                    setTextColor(accent)
                    setTypeface(typeface, Typeface.BOLD)
                    letterSpacing = 0.1f
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                },
            )
            pillHolder =
                LinearLayout(this@SpeedrunActivity).apply { orientation = LinearLayout.HORIZONTAL }
            header.addView(pillHolder)
            col.addView(header)

            valueView =
                TextView(this@SpeedrunActivity).apply {
                    text = "—"
                    textSize = 30f
                    setTextColor(onSurface)
                    setTypeface(typeface, Typeface.BOLD)
                    setPadding(0, dpi(4f), 0, dpi(6f))
                }
            col.addView(valueView)

            if (kind == "pct") {
                bar = BarView(accent, outline)
                range = null
                col.addView(bar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dpi(8f)))
            } else {
                range = RangeBarView(accent, outline, muted)
                bar = null
                col.addView(range, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dpi(46f)))
            }

            captionView =
                TextView(this@SpeedrunActivity).apply {
                    textSize = 12f
                    setTextColor(muted)
                    setPadding(0, dpi(6f), 0, 0)
                }
            col.addView(captionView)
        }

        private fun setPill(
            text: String?,
            color: Int,
        ) {
            pillHolder.removeAllViews()
            if (text != null) pillHolder.addView(pill(text, color))
        }

        fun setPct(
            known: Boolean,
            value: Float,
            caption: String,
            reason: String,
            projected: Float? = null,
        ) {
            if (known) {
                valueView.text = "${value.toInt()}"
                bar?.setValue(value, projected)
                captionView.text = caption
                setPill(null, muted)
            } else {
                valueView.text = "—"
                bar?.setValue(null)
                captionView.text = reason
                setPill("Needs more data", muted)
            }
        }

        fun setRange(
            known: Boolean,
            proj: Float,
            low: Float,
            high: Float,
            confidence: String,
            note: String,
            reason: String,
            hasExam: Boolean = false,
            daysToExam: Int = 0,
        ) {
            if (known) {
                valueView.text = "${proj.toInt()}"
                range?.setRange(proj, low, high)
                val exam = if (hasExam) "projected for exam in ${daysToExam}d · " else ""
                captionView.text = "${exam}likely ${low.toInt()}–${high.toInt()} · $note"
                val conf = confidence.ifBlank { "low" }.lowercase()
                setPill(
                    "${conf.replaceFirstChar { it.uppercase() }} confidence",
                    confColors[conf] ?: muted,
                )
            } else {
                valueView.text = "—"
                range?.setRange(null, 0f, 0f)
                captionView.text = reason
                setPill("Not enough data", muted)
            }
        }
    }

    // Slim rounded 0–100 progress bar.
    private inner class BarView(
        private val accent: Int,
        private val track: Int,
    ) : View(this@SpeedrunActivity) {
        private var value: Float? = null
        private var projected: Float? = null
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

        fun setValue(
            v: Float?,
            proj: Float? = null,
        ) {
            value = v
            projected = proj
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            val h = height.toFloat()
            val w = width.toFloat()
            val r = h / 2
            paint.color = track
            canvas.drawRoundRect(0f, 0f, w, h, r, r, paint)
            value?.let { v ->
                val frac = (v / 100f).coerceIn(0f, 1f)
                val fw = maxOf(h, w * frac)
                paint.color = accent
                canvas.drawRoundRect(0f, 0f, fw, h, r, r, paint)
                // Marker where recall is projected to fall by the exam day
                // (storage strength): the gap to the fill is the durability risk.
                projected?.let { pr ->
                    if (pr < v) {
                        val x = maxOf(1.5f, w * (pr / 100f))
                        paint.color = Color.WHITE
                        canvas.drawRect(x - 1f, 0f, x + 1f, h, paint)
                    }
                }
            }
        }
    }

    // The Readiness centerpiece: the 472–528 scale with the likely band and a
    // marker at the projected score, so uncertainty is shown, not hidden.
    private inner class RangeBarView(
        private val accent: Int,
        private val track: Int,
        private val mutedColor: Int,
    ) : View(this@SpeedrunActivity) {
        private var proj: Float? = null
        private var low = 0f
        private var high = 0f
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val lo = 472f
        private val hi = 528f

        fun setRange(
            p: Float?,
            l: Float,
            h: Float,
        ) {
            proj = p
            low = l
            high = h
            invalidate()
        }

        private fun xOf(
            v: Float,
            x0: Float,
            w: Float,
        ): Float = x0 + ((v - lo) / (hi - lo)).coerceIn(0f, 1f) * w

        override fun onDraw(canvas: Canvas) {
            val d = resources.displayMetrics.density
            val trackH = 8 * d
            val trackY = 22 * d
            val x0 = 2 * d
            val w = width - 4 * d
            paint.color = track
            canvas.drawRoundRect(x0, trackY, x0 + w, trackY + trackH, trackH / 2, trackH / 2, paint)
            proj?.let { pj ->
                val lx = xOf(low, x0, w)
                val hx = xOf(high, x0, w)
                paint.color = ColorUtils.setAlphaComponent(accent, 95)
                canvas.drawRoundRect(lx, trackY, maxOf(hx, lx + 2 * d), trackY + trackH, trackH / 2, trackH / 2, paint)
                val px = xOf(pj, x0, w)
                paint.color = Color.WHITE
                canvas.drawCircle(px, trackY + trackH / 2, 7 * d, paint)
                paint.color = accent
                canvas.drawCircle(px, trackY + trackH / 2, 5 * d, paint)
                paint.color = accent
                paint.textSize = 12 * d
                paint.isFakeBoldText = true
                paint.textAlign = Paint.Align.CENTER
                canvas.drawText("${pj.toInt()}", px, 14 * d, paint)
            }
            paint.color = mutedColor
            paint.textSize = 10 * d
            paint.isFakeBoldText = false
            paint.textAlign = Paint.Align.LEFT
            canvas.drawText("472", x0, trackY + trackH + 14 * d, paint)
            paint.textAlign = Paint.Align.RIGHT
            canvas.drawText("528", x0 + w, trackY + trackH + 14 * d, paint)
        }
    }

    // Scores ------------------------------------------------------------------

    private fun refreshScores() =
        launchCatchingTask {
            val s = withContext(Dispatchers.IO) { CollectionManager.getBackend().speedrunScores() }
            val mem = s.memory
            var memCaption =
                "recall now · durability ~${mem.meanStabilityDays.toInt()}d stability · " +
                    "${(mem.topicCoverage * 100).toInt()}% topics"
            var projected: Float? = null
            if (mem.hasProjection) {
                projected = mem.projectedRecall
                memCaption += "\n~${mem.projectedRecall.toInt()}% recall on exam day if you stop now"
            }
            memoryCard.setPct(mem.known, mem.value, memCaption, mem.reason, projected)
            val perf = s.performance
            performanceCard.setPct(
                perf.known,
                perf.value,
                "applied accuracy over ${perf.attempts} question(s) · ${perf.topicsCovered} topic(s)",
                perf.reason,
            )
            val rdy = s.readiness
            readinessCard.setRange(
                rdy.known,
                rdy.projected,
                rdy.low,
                rdy.high,
                rdy.confidence,
                rdy.calibrationNote,
                rdy.reason,
                rdy.hasExam,
                rdy.daysToExam,
            )
            examHasDate = rdy.hasExam
            examDaysAway = rdy.daysToExam
            updateExamLabel()
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
            btn.setTextColor(onSurface)
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
        for ((optLetter, btn) in optionButtons) {
            btn.isEnabled = false
            when {
                optLetter == q.answer -> btn.setTextColor(Color.parseColor("#2e7d32"))
                optLetter == letter && !correct -> btn.setTextColor(Color.parseColor("#c62828"))
            }
        }
        dontKnowButton.isEnabled = false
        feedbackView.text =
            if (correct) {
                "Correct.\n${q.explanation}"
            } else {
                "Incorrect. Answer: ${q.answer}.\n${q.explanation}"
            }
        feedbackView.setTextColor(if (correct) Color.parseColor("#2e7d32") else onSurface)
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
        for ((optLetter, btn) in optionButtons) {
            btn.isEnabled = false
            if (optLetter == q.answer) btn.setTextColor(Color.parseColor("#2e7d32"))
        }
        dontKnowButton.isEnabled = false
        feedbackView.setTextColor(onSurface)
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
                lo > 0 && done < lo -> Pair("— ${lo - done} to today’s minimum", muted)
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

    // Readiness calibration ("prove yourself wrong"): after a real full-length
    // practice test, log what the app projected vs. the actual scaled score.
    // Writes to the shared engine (speedrunRecordCalibration → config), so it
    // syncs to desktop like every other score input and tightens the Readiness
    // range / unlocks higher confidence. Mirrors the desktop dialog.
    private fun onRecordCalibration() =
        launchCatchingTask {
            val rdy = withContext(Dispatchers.IO) { CollectionManager.getBackend().speedrunScores() }.readiness
            val default = if (rdy.known) rdy.projected.toInt().toString() else "500"
            val pad = dpi(16f)

            val projectedInput =
                EditText(this@SpeedrunActivity).apply {
                    inputType = InputType.TYPE_CLASS_NUMBER
                    hint = "Projected (app's guess), 472–528"
                    setText(default)
                }
            val actualInput =
                EditText(this@SpeedrunActivity).apply {
                    inputType = InputType.TYPE_CLASS_NUMBER
                    hint = "Actual score, 472–528"
                    setText(default)
                }
            val container =
                LinearLayout(this@SpeedrunActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(pad, dpi(8f), pad, 0)
                    addView(
                        TextView(this@SpeedrunActivity).apply {
                            text =
                                "After a real full-length practice test, log what the app projected " +
                                "at the time and your actual scaled score (both 472–528). This " +
                                "calibrates Readiness to your true prediction error."
                            textSize = 13f
                        },
                    )
                    addView(projectedInput)
                    addView(actualInput)
                }

            AlertDialog
                .Builder(this@SpeedrunActivity)
                .setTitle("Record full-length practice score")
                .setView(container)
                .setPositiveButton("Save") { _, _ ->
                    val projected = projectedInput.text.toString().toFloatOrNull()
                    val actual = actualInput.text.toString().toFloatOrNull()
                    if (projected == null || actual == null) {
                        showThemedToast(this@SpeedrunActivity, "Enter two numbers on the 472–528 scale.", false)
                        return@setPositiveButton
                    }
                    launchCatchingTask {
                        withContext(Dispatchers.IO) {
                            CollectionManager.getBackend().speedrunRecordCalibration(projected, actual)
                        }
                        refreshScores()
                        showThemedToast(this@SpeedrunActivity, "Recorded — Readiness recalibrated to your real score.", false)
                    }
                }.setNegativeButton("Cancel", null)
                .show()
        }

    // AI question generation (mirrors desktop qt/aqt/speedrun_ai.py): grounded on
    // the built-in flashcards, independently verified, inserted as standard
    // mcat-question notes so the same scheduler/scoring pick them up. The API key
    // is stored only on-device (SharedPreferences), never in the app.
    private fun onGenerateAi() {
        val prefs = getSharedPreferences("mcat_speedrun", MODE_PRIVATE)
        val pad = dpi(16f)
        val container =
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(pad, dpi(8f), pad, 0)
            }
        container.addView(
            TextView(this).apply {
                text =
                    "Generate MCAT questions with AI, grounded in the built-in flashcards for a " +
                    "subject (so sources are traceable). New questions join the same bank and " +
                    "are scheduled and scored like any other. Needs an OpenAI API key, stored " +
                    "only on this device."
                textSize = 13f
                setPadding(0, 0, 0, dpi(8f))
            },
        )
        val topicSpinner =
            Spinner(this).apply {
                adapter =
                    ArrayAdapter(
                        this@SpeedrunActivity,
                        android.R.layout.simple_spinner_dropdown_item,
                        SpeedrunAi.TOPICS,
                    )
            }
        container.addView(topicSpinner)
        val countInput =
            EditText(this).apply {
                inputType = InputType.TYPE_CLASS_NUMBER
                hint = "How many (1–20)"
                setText("5")
            }
        container.addView(countInput)
        val keyInput =
            EditText(this).apply {
                hint = "OpenAI API key (sk-...)"
                setText(prefs.getString("openai_key", "") ?: "")
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            }
        container.addView(keyInput)
        val verifyCheck =
            CheckBox(this).apply {
                text = "Independently verify each question (recommended)"
                isChecked = true
            }
        container.addView(verifyCheck)

        AlertDialog
            .Builder(this)
            .setTitle("Generate with AI")
            .setView(container)
            .setPositiveButton("Generate") { _, _ ->
                val topic = topicSpinner.selectedItem as String
                val count =
                    countInput.text
                        .toString()
                        .toIntOrNull()
                        ?.coerceIn(1, 20) ?: 5
                val key = keyInput.text.toString().trim()
                val verify = verifyCheck.isChecked
                if (key.isEmpty()) {
                    showThemedToast(this@SpeedrunActivity, "Enter an OpenAI API key.", false)
                    return@setPositiveButton
                }
                prefs.edit().putString("openai_key", key).apply()
                runGeneration(topic, count, key, verify)
            }.setNegativeButton("Cancel", null)
            .show()
    }

    private fun runGeneration(
        topic: String,
        count: Int,
        key: String,
        verify: Boolean,
    ) = launchCatchingTask {
        showThemedToast(this@SpeedrunActivity, "Generating $count $topic question(s)…", false)
        val added =
            withContext(Dispatchers.IO) {
                val facts = withCol { SpeedrunAi.sourceFacts(this, topic) }
                val avoid = withCol { SpeedrunAi.existingStems(this, topic) }
                val qs =
                    SpeedrunAi.generate(key, SpeedrunAi.DEFAULT_MODEL, topic, count, facts, avoid, verify)
                withCol { SpeedrunAi.insert(this, topic, qs, SpeedrunAi.DEFAULT_MODEL) }
            }
        showThemedToast(
            this@SpeedrunActivity,
            if (added > 0) {
                "Added $added AI question(s) to $topic."
            } else {
                "No questions passed the checks — try again."
            },
            false,
        )
        updateControls()
        refreshScores()
    }

    // Exam date: set/clear the target MCAT date. Readiness then projects each
    // topic's recall forward to that day using FSRS stability (storage
    // strength). Writes to the shared engine (config), so it syncs to desktop.
    private fun updateExamLabel() {
        if (examHasDate) {
            examLabel.text = "$examDaysAway days away — Readiness projects to this day"
            clearExamButton.isEnabled = true
        } else {
            examLabel.text = "Not set — Readiness reflects today only"
            clearExamButton.isEnabled = false
        }
    }

    private fun onSetExamDate() {
        val cal = Calendar.getInstance()
        cal.add(Calendar.DAY_OF_YEAR, if (examHasDate && examDaysAway > 0) examDaysAway else 30)
        DatePickerDialog(
            this,
            { _, year, month, day ->
                val picked = Calendar.getInstance()
                picked.set(year, month, day, 9, 0, 0)
                val ts = picked.timeInMillis / 1000
                launchCatchingTask {
                    withContext(Dispatchers.IO) {
                        CollectionManager.getBackend().speedrunSetExamDate(ts)
                    }
                    refreshScores()
                    showThemedToast(this@SpeedrunActivity, "Exam date set — Readiness now projects to that day.", false)
                }
            },
            cal.get(Calendar.YEAR),
            cal.get(Calendar.MONTH),
            cal.get(Calendar.DAY_OF_MONTH),
        ).apply { datePicker.minDate = System.currentTimeMillis() }.show()
    }

    private fun onClearExamDate() =
        launchCatchingTask {
            withContext(Dispatchers.IO) { CollectionManager.getBackend().speedrunSetExamDate(0) }
            refreshScores()
            showThemedToast(this@SpeedrunActivity, "Exam date cleared.", false)
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
