// SPDX-License-Identifier: GPL-3.0-or-later
// Speedrun (MCAT fork): on-device AI question generation, mirroring the desktop
// qt/aqt/speedrun_ai.py.
//
// Design (kept honest, same as desktop):
// * Works with AI off — nothing here runs unless the user taps "Generate".
// * Traceable: generation is grounded in the user's own built-in flashcards for
//   the topic (retrieval-augmented); each question cites the source fact, and
//   provenance (source, model, prompt version, verified flag) is recorded via
//   tags (mcat-ai) and a visible footer in the Explanation.
// * Held-out checking: an independent verifier pass answers each question blind
//   and must land on the same key, else the question is dropped.
// * The key is never committed — it's stored in on-device SharedPreferences.
//
// Generated questions are ordinary "MCAT Practice Question" notes tagged
// mcat-question (+ mcat-ai) in MCAT Practice::<Topic>, so they flow through the
// same concept-FSRS scheduler and Performance/Readiness scoring as the built-in
// bank.

package com.ichi2.anki

import com.ichi2.anki.libanki.Collection
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

object SpeedrunAi {
    const val QUESTION_TAG = "mcat-question"
    const val FLASHCARD_TAG = "mcat-flashcard"
    const val AI_TAG = "mcat-ai"
    const val AI_VERIFIED_TAG = "mcat-ai-verified"
    const val NOTETYPE = "MCAT Practice Question"
    const val PRACTICE_DECK = "MCAT Practice"
    const val MCAT_DECK = "MCAT"
    const val DEFAULT_MODEL = "gpt-4o-mini"
    const val PROMPT_VERSION = "gen-v1"
    private const val OPENAI_URL = "https://api.openai.com/v1/chat/completions"

    // The seven MCAT subjects seeded by the engine; generation grounds on the
    // built-in flashcards for the chosen one.
    val TOPICS =
        listOf(
            "Biochemistry",
            "Biology",
            "General Chemistry",
            "Organic Chemistry",
            "Physics",
            "Psychology",
            "Sociology",
        )

    class AiError(
        message: String,
    ) : Exception(message)

    data class GenQ(
        val stem: String,
        val options: Map<String, String>,
        val answer: String,
        val explanation: String,
        val source: String,
        var verified: Boolean = false,
    )

    data class GenResult(
        val added: Int,
        val verified: Int,
        val rejected: Int,
    )

    private fun stripHtml(s: String): String = s.replace(Regex("<[^>]+>"), "").replace("&nbsp;", " ").trim()

    private fun norm(s: String): String = stripHtml(s).lowercase().replace(Regex("\\s+"), " ").trim()

    // --- collection helpers (read on the collection thread) ------------------

    fun sourceFacts(
        col: Collection,
        topic: String,
        limit: Int = 40,
    ): List<String> {
        val out = mutableListOf<String>()
        val nids = col.findNotes("tag:$FLASHCARD_TAG deck:\"$MCAT_DECK::$topic\"")
        for (nid in nids.take(limit)) {
            val note = col.getNote(nid)
            if (note.fields.size >= 2 && note.fields[0].isNotBlank()) {
                out.add("${stripHtml(note.fields[0])} -> ${stripHtml(note.fields[1])}")
            }
        }
        return out
    }

    fun existingStems(
        col: Collection,
        topic: String,
    ): Set<String> {
        val out = mutableSetOf<String>()
        val nids = col.findNotes("tag:$QUESTION_TAG deck:\"$PRACTICE_DECK::$topic\"")
        for (nid in nids) {
            val note = col.getNote(nid)
            if (note.contains("Stem")) out.add(norm(note.getItem("Stem")))
        }
        return out
    }

    // --- OpenAI call (dependency-free) ---------------------------------------

    private fun chat(
        key: String,
        model: String,
        system: String,
        user: String,
        temperature: Double,
    ): String {
        val body =
            JSONObject()
                .put("model", model)
                .put("temperature", temperature)
                .put("response_format", JSONObject().put("type", "json_object"))
                .put(
                    "messages",
                    JSONArray()
                        .put(JSONObject().put("role", "system").put("content", system))
                        .put(JSONObject().put("role", "user").put("content", user)),
                )
        val conn =
            (URL(OPENAI_URL).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                doOutput = true
                connectTimeout = 30000
                readTimeout = 90000
                setRequestProperty("Authorization", "Bearer $key")
                setRequestProperty("Content-Type", "application/json")
            }
        conn.outputStream.use { it.write(body.toString().toByteArray()) }
        val code = conn.responseCode
        val text =
            (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader()
                ?.use { it.readText() } ?: ""
        if (code !in 200..299) throw AiError("OpenAI API error $code: ${text.take(300)}")
        return JSONObject(text)
            .getJSONArray("choices")
            .getJSONObject(0)
            .getJSONObject("message")
            .getString("content")
    }

    // --- generation (network only; no collection access) ---------------------

    fun generate(
        key: String,
        model: String,
        topic: String,
        n: Int,
        facts: List<String>,
        avoid: Set<String>,
        verify: Boolean,
    ): List<GenQ> {
        val factsBlock =
            if (facts.isEmpty()) "(none)" else facts.mapIndexed { i, f -> "[$i] $f" }.joinToString("\n")
        val avoidBlock =
            if (avoid.isEmpty()) "(none)" else avoid.take(40).joinToString("\n") { "- $it" }
        val system =
            "You are an MCAT item writer. You write rigorous, single-best-answer, " +
                "application/reasoning questions (not simple recall). Every question must be " +
                "answerable purely from the provided source facts plus standard reasoning, with " +
                "exactly one unambiguous correct option and three plausible distractors. Return ONLY JSON."
        val user =
            "Subject: MCAT $topic\n\n" +
                "SOURCE FACTS (ground every question in these; cite the fact index):\n$factsBlock\n\n" +
                "Do NOT duplicate the meaning of these existing question stems:\n$avoidBlock\n\n" +
                "Write $n new MCAT-style multiple-choice questions. Prefer questions that require " +
                "applying or reasoning about the facts, not verbatim recall.\n" +
                "Return JSON of the form: {\"questions\": [{\"stem\": str, " +
                "\"options\": {\"A\": str, \"B\": str, \"C\": str, \"D\": str}, " +
                "\"answer\": \"A\"|\"B\"|\"C\"|\"D\", \"explanation\": str, \"source_fact_index\": int}]}\n" +
                "The explanation must justify the correct answer and why others are wrong. " +
                "source_fact_index must point to the SOURCE FACT the question tests."
        val content = chat(key, model, system, user, 0.8)
        val items =
            JSONObject(content).optJSONArray("questions")
                ?: throw AiError("Model JSON missing a 'questions' array.")
        val seen = mutableSetOf<String>()
        val out = mutableListOf<GenQ>()
        for (i in 0 until items.length()) {
            val item = items.optJSONObject(i) ?: continue
            val stem = item.optString("stem").trim()
            val opts = item.optJSONObject("options") ?: continue
            val o =
                mapOf(
                    "A" to opts.optString("A").trim(),
                    "B" to opts.optString("B").trim(),
                    "C" to opts.optString("C").trim(),
                    "D" to opts.optString("D").trim(),
                )
            val answer =
                item
                    .optString("answer")
                    .trim()
                    .uppercase()
                    .take(1)
            val explanation = item.optString("explanation").trim()
            if (stem.isEmpty() || explanation.isEmpty()) continue
            if (o.values.any { it.isEmpty() }) continue
            if (o.values
                    .map { it.lowercase() }
                    .toSet()
                    .size != 4
            ) {
                continue
            }
            if (answer !in listOf("A", "B", "C", "D")) continue
            val stemKey = norm(stem)
            if (stemKey in avoid || stemKey in seen) continue
            val idx = item.optInt("source_fact_index", -1)
            val source = if (idx in facts.indices) facts[idx] else "general MCAT reasoning"
            val q = GenQ(stem, o, answer, explanation, source)
            if (verify) {
                if (!verifyQuestion(key, model, q)) continue
                q.verified = true
            }
            seen.add(stemKey)
            out.add(q)
        }
        return out
    }

    private fun verifyQuestion(
        key: String,
        model: String,
        q: GenQ,
    ): Boolean {
        val optsBlock = q.options.entries.joinToString("\n") { "${it.key}. ${it.value}" }
        val system =
            "You are a meticulous MCAT answer checker. Given a question and options, " +
                "independently determine the single best answer WITHOUT being told the proposed " +
                "key. Then report. Return ONLY JSON."
        val user =
            "Question:\n${q.stem}\n\nOptions:\n$optsBlock\n\n" +
                "Return JSON: {\"best_answer\": \"A\"|\"B\"|\"C\"|\"D\", \"single_correct\": true|false, " +
                "\"issue\": str}. single_correct is false if the item is ambiguous, has multiple " +
                "correct options, or none are correct."
        return try {
            val data = JSONObject(chat(key, model, system, user, 0.0))
            val best =
                data
                    .optString("best_answer")
                    .trim()
                    .uppercase()
                    .take(1)
            data.optBoolean("single_correct", false) && best == q.answer
        } catch (e: Exception) {
            false
        }
    }

    // --- insertion (on the collection thread) --------------------------------

    fun insert(
        col: Collection,
        topic: String,
        questions: List<GenQ>,
        model: String,
    ): Int {
        val nt =
            col.notetypes.byName(NOTETYPE)
                ?: throw AiError("MCAT question notetype not found — tap \"Set up MCAT content\" first.")
        val deckId = col.decks.id("$PRACTICE_DECK::$topic")
        var added = 0
        for (q in questions) {
            val note = col.newNote(nt)
            note.setField(0, topic)
            note.setField(1, q.stem)
            note.setField(2, q.options["A"] ?: "")
            note.setField(3, q.options["B"] ?: "")
            note.setField(4, q.options["C"] ?: "")
            note.setField(5, q.options["D"] ?: "")
            note.setField(6, q.answer)
            val footer =
                "<br><span style=\"color:#888;font-size:11px\">Source: ${q.source} · " +
                    "AI-generated ($model, $PROMPT_VERSION)${if (q.verified) " · verified" else ""}</span>"
            note.setField(7, q.explanation + footer)
            val tags =
                if (q.verified) "$QUESTION_TAG $AI_TAG $AI_VERIFIED_TAG" else "$QUESTION_TAG $AI_TAG"
            note.setTagsFromStr(col, tags)
            col.addNote(note, deckId)
            added++
        }
        return added
    }
}
