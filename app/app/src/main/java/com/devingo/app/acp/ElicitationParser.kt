package com.devingo.app.acp

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull

// A7.2 — agent→client `elicitation/create` (alias `session/elicitation`)
// question-form parsing, ported from t3code's elicitationForm.ts
// (.research/t3code-20261123/packages/provider-acp/src/server/elicitationForm.ts)
// and its PendingUserInputCard answer semantics
// (packages/client-runtime/src/state/pendingUserInput.ts).

/** A selectable answer row — [wireValue] is what goes back in `content`. */
data class QuestionOption(
    val label: String,
    val description: String? = null,
    /** Schema-declared value; the label doubles as it when absent. */
    val value: String? = null,
) {
    val wireValue: String get() = value ?: label
}

/** What a schema field resolves to — drives the answer's JSON typing. */
enum class ElicitationFieldType { Text, Number, Integer, Boolean, Choice, MultiChoice }

/**
 * One renderable question. Mirrors t3code's OrchestrationV2UserInputQuestion;
 * [fieldType]/[companionId]/[minItems]/[maxItems] carry the wire semantics
 * the schema form needs for typed answers and the `__other` companion fold.
 */
data class UserInputQuestion(
    val id: String,
    val header: String,
    val question: String,
    val options: List<QuestionOption> = emptyList(),
    val isMultiSelect: Boolean = false,
    val allowCustomText: Boolean = true,
    val required: Boolean = true,
    val fieldType: ElicitationFieldType = ElicitationFieldType.Choice,
    /**
     * An optional `<id>__other` string field folded into this choice — custom
     * text answers under THAT key, not this question's id (OMP's ask dialog).
     */
    val companionId: String? = null,
    /** multiChoice selection bounds; 0/∞ when unbounded. */
    val minItems: Int = 0,
    val maxItems: Int = Int.MAX_VALUE,
) {
    /** Legal wire values the options declare. */
    val optionValues: Set<String> get() = options.mapTo(HashSet()) { it.wireValue }
}

/** The card's mutable per-question draft (selected wire values + free text). */
data class QuestionDraft(
    val selected: List<String> = emptyList(),
    val custom: String = "",
)

/** One resolved answer — the content entry plus the WorkLog display text. */
data class ResolvedAnswer(
    val contentKey: String,
    val contentValue: JsonElement,
    /** Short "label, label" / custom-text summary for the 已回答 history row. */
    val display: String,
)

object ElicitationParser {

    /** Parsed `elicitation/create` params — what the card renders. */
    data class Form(
        val message: String,
        val questions: List<UserInputQuestion>,
    )

    /**
     * `elicitation/create` params → a renderable [Form]; null = decline
     * (non-form mode, no schema, or any unparseable field — t3code declines
     * the whole form rather than drop one question silently).
     */
    fun parse(params: JsonObject): Form? {
        // `mode` is absent on the old session/elicitation draft; anything
        // declared but not "form" (e.g. "url") isn't renderable here.
        val mode = params.strField("mode")
        if (mode != null && mode != "form") return null
        val message = params.strField("message").orEmpty()
        val allowOther = (params["_meta"] as? JsonObject)
            ?.get("cognition.ai/allowOther")
            ?.let { (it as? JsonPrimitive)?.booleanOrNull } == true
        (params["requestedSchema"] as? JsonObject)?.let { schema ->
            return parseSchema(schema, message, allowOther)
        }
        return parseDirectQuestions(params["questions"] as? JsonArray, message)
    }

    // ---- JSON-schema form (ACP `requestedSchema`) ------------------------------

    private class ParsedField(
        val type: ElicitationFieldType,
        val options: List<QuestionOption>,
        val minItems: Int = 0,
        val maxItems: Int = Int.MAX_VALUE,
    )

    private fun parseSchema(
        schema: JsonObject,
        message: String,
        allowOther: Boolean,
    ): Form? {
        val required = (schema["required"] as? JsonArray)
            ?.mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }
            ?.toSet().orEmpty()
        val properties = schema["properties"] as? JsonObject ?: return null
        if (properties.isEmpty()) return null

        val fields = LinkedHashMap<String, ParsedField>()
        val order = mutableListOf<Pair<String, JsonObject>>()
        for ((id, el) in properties) {
            val property = el as? JsonObject ?: return null
            val parsed = parseField(property) ?: return null
            // Ids must be non-blank and survive a trim to round-trip answers.
            if (id.isBlank() || id.trim() != id) return null
            fields[id] = parsed
            order += id to property
        }
        // An optional `<id>__other` text field is the free-text answer to
        // optional single-choice `<id>`: ask them as one question, send
        // custom text to the companion (multi-choice keeps both fields).
        val companions = LinkedHashMap<String, String>()
        for ((id, f) in fields) {
            val companionId = "${id}__other"
            if (f.type == ElicitationFieldType.Choice &&
                fields[companionId]?.type == ElicitationFieldType.Text &&
                id !in required && companionId !in required
            ) {
                companions[id] = companionId
            }
        }
        val folded = companions.values.toSet()
        val questions = mutableListOf<UserInputQuestion>()
        for ((id, property) in order) {
            if (id in folded) continue
            val f = fields.getValue(id)
            questions += UserInputQuestion(
                id = id,
                header = property.strField("title")?.trim()?.takeIf { it.isNotEmpty() }
                    ?: "问题 ${questions.size + 1}",
                question = property.strField("description")?.trim()
                    ?.takeIf { it.isNotEmpty() } ?: message,
                options = f.options,
                isMultiSelect = f.type == ElicitationFieldType.MultiChoice,
                // t3 omits allowCustomAnswer unless text/number/companion/
                // allowOther — plain choice, multi-select and boolean get
                // no custom-text field.
                allowCustomText = f.type == ElicitationFieldType.Text ||
                    f.type == ElicitationFieldType.Number ||
                    f.type == ElicitationFieldType.Integer ||
                    companions.containsKey(id) ||
                    (allowOther && f.type == ElicitationFieldType.Choice),
                // Only an OPTIONAL text field is non-required — an empty
                // string is its acceptable answer.
                required = !(f.type == ElicitationFieldType.Text && id !in required),
                fieldType = f.type,
                companionId = companions[id],
                minItems = f.minItems,
                maxItems = f.maxItems,
            )
        }
        return questions.takeIf { it.isNotEmpty() }?.let { Form(message, it) }
    }

    private fun parseField(property: JsonObject): ParsedField? =
        when (property.strField("type")) {
            "string" -> {
                // ACP treats a null enum/oneOf like an omitted one.
                val constrained = property["enum"].let { it != null && it !is JsonNull } ||
                    property["oneOf"].let { it != null && it !is JsonNull }
                if (!constrained) {
                    ParsedField(ElicitationFieldType.Text, emptyList())
                } else {
                    val options = if (property["oneOf"] is JsonArray) {
                        choiceOptions(property["oneOf"])
                    } else {
                        enumOptions(property["enum"])
                    } ?: return null
                    ParsedField(ElicitationFieldType.Choice, options)
                }
            }
            "array" -> {
                val items = property["items"] as? JsonObject
                val options = when {
                    items == null -> null
                    items.strField("type") == "string" -> enumOptions(items["enum"])
                    // Untyped items list titled entries in `anyOf`.
                    items["type"] == null || items["type"] is JsonNull ->
                        choiceOptions(items["anyOf"])
                    else -> null
                } ?: return null
                val minItems = (property["minItems"] as? JsonPrimitive)
                    ?.takeIf { !it.isString }?.intOrNull ?: 0
                val maxItems = (property["maxItems"] as? JsonPrimitive)
                    ?.takeIf { !it.isString }?.intOrNull ?: Int.MAX_VALUE
                // Empty-only questions cannot be answered.
                if (maxItems == 0 || minItems > maxItems || minItems > options.size) {
                    return null
                }
                ParsedField(ElicitationFieldType.MultiChoice, options, minItems, maxItems)
            }
            "boolean" -> ParsedField(
                ElicitationFieldType.Boolean,
                listOf(
                    QuestionOption(label = "true", description = "是", value = "true"),
                    QuestionOption(label = "false", description = "否", value = "false"),
                ),
            )
            "number" -> ParsedField(ElicitationFieldType.Number, emptyList())
            "integer" -> ParsedField(ElicitationFieldType.Integer, emptyList())
            else -> null
        }

    /** Enum literals → options; ambiguous/blank labels quote the raw value. */
    private fun enumOptions(entries: JsonElement?): List<QuestionOption>? {
        if (entries !is JsonArray || entries.isEmpty()) return null
        if (!entries.all { it is JsonPrimitive && it.isString }) return null
        val values = entries.map { (it as JsonPrimitive).content }
        val counts = values.groupingBy { it.trim() }.eachCount()
        val options = values.map { value ->
            val plain = value.trim()
            val label = if (plain.isNotEmpty() && counts[plain] == 1) {
                plain
            } else {
                quoteJson(value)
            }
            QuestionOption(label = label, description = label, value = value)
        }
        // A quoted fallback can collide with a literal label — quote all.
        if (options.map { it.label }.toSet().size == options.size) return options
        return options.map { o ->
            val label = quoteJson(o.wireValue)
            o.copy(label = label, description = label)
        }
    }

    /** `oneOf`/`anyOf` entries carry `{const, title?, description?}`. */
    private fun titledOption(entry: JsonElement): QuestionOption? {
        val o = entry as? JsonObject ?: return null
        val const = (o["const"] as? JsonPrimitive)?.takeIf { it.isString }?.content
            ?: return null
        val label = o.strField("title")?.trim()?.takeIf { it.isNotEmpty() }
            ?: const.trim().takeIf { it.isNotEmpty() }
            ?: return null
        return QuestionOption(
            label = label,
            description = o.strField("description")?.trim()?.takeIf { it.isNotEmpty() }
                ?: label,
            value = const,
        )
    }

    /** All entries as options, or null when any entry fails — never drop one. */
    private fun choiceOptions(entries: JsonElement?): List<QuestionOption>? {
        if (entries !is JsonArray || entries.isEmpty()) return null
        val options = entries.map { titledOption(it) }
        return if (options.all { it != null }) options.filterNotNull() else null
    }

    // ---- direct `questions` array (non-schema fallback shape) ------------------

    private fun parseDirectQuestions(arr: JsonArray?, message: String): Form? {
        if (arr == null || arr.isEmpty()) return null
        val questions = arr.mapIndexedNotNull { i, el ->
            val o = el as? JsonObject ?: return@mapIndexedNotNull null
            val question = o.strField("question", "prompt", "description", "message")
                ?.takeIf { it.isNotBlank() } ?: message
            if (question.isBlank()) return@mapIndexedNotNull null
            val options = directOptions(o["options"]).orEmpty()
            val multi = o.boolField("multiSelect") ?: o.boolField("isMultiSelect")
                ?: o.boolField("multi_select") ?: false
            UserInputQuestion(
                id = o.strField("id")?.takeIf { it.isNotBlank() } ?: "q${i + 1}",
                header = o.strField("header", "title", "label")
                    ?.takeIf { it.isNotBlank() } ?: "问题 ${i + 1}",
                question = question,
                options = options,
                isMultiSelect = multi,
                allowCustomText = o.boolField("allowCustomAnswer")
                    ?: o.boolField("allowCustomText")
                    ?: o.boolField("allow_custom_answer")
                    ?: options.isEmpty(),
                required = o.boolField("required") ?: true,
                fieldType = when {
                    options.isEmpty() -> ElicitationFieldType.Text
                    multi -> ElicitationFieldType.MultiChoice
                    else -> ElicitationFieldType.Choice
                },
            )
        }
        return questions.takeIf { it.isNotEmpty() }?.let { Form(message, it) }
    }

    /** Loose option parse: strings or {label, description?, value?} objects. */
    private fun directOptions(el: JsonElement?): List<QuestionOption>? {
        if (el !is JsonArray) return null
        return el.mapNotNull { e ->
            when (e) {
                is JsonPrimitive -> if (e.isString) {
                    QuestionOption(label = e.content)
                } else {
                    null
                }
                is JsonObject -> {
                    val label = e.strField("label", "title", "name", "const")
                        ?: return@mapNotNull null
                    QuestionOption(
                        label = label,
                        description = e.strField("description"),
                        value = e.strField("value", "const"),
                    )
                }
                else -> null
            }
        }
    }

    // ---- draft → wire answers ---------------------------------------------------

    /**
     * A draft → its content entry; null while the question is unresolved
     * (nothing picked/typed, a number that doesn't parse, or a multi
     * selection outside its min/max). Custom text beats option selections
     * (t3's draft semantics); a companion fold reroutes it to `__other`.
     */
    fun resolveAnswer(q: UserInputQuestion, draft: QuestionDraft): ResolvedAnswer? {
        val selected = draft.selected.filter { it in q.optionValues }
        val custom = if (q.allowCustomText) draft.custom.trim() else ""
        if (custom.isNotEmpty()) {
            return when (q.fieldType) {
                ElicitationFieldType.MultiChoice -> ResolvedAnswer(
                    q.id,
                    JsonArray((selected + custom).map { JsonPrimitive(it) }),
                    (labelsFor(q, selected) + custom).joinToString(", "),
                )
                // Unreachable — boolean questions never get a text field.
                ElicitationFieldType.Boolean -> null
                ElicitationFieldType.Number -> custom.toDoubleOrNull()
                    ?.let { ResolvedAnswer(q.companionId ?: q.id, JsonPrimitive(it), custom) }
                ElicitationFieldType.Integer -> custom.toLongOrNull()
                    ?.let { ResolvedAnswer(q.companionId ?: q.id, JsonPrimitive(it), custom) }
                else -> ResolvedAnswer(
                    q.companionId ?: q.id,
                    JsonPrimitive(custom),
                    custom,
                )
            }
        }
        if (selected.isEmpty()) return null
        return when (q.fieldType) {
            ElicitationFieldType.Boolean -> ResolvedAnswer(
                q.id,
                JsonPrimitive(selected.first() == "true"),
                labelsFor(q, selected.take(1)).first(),
            )
            ElicitationFieldType.MultiChoice -> {
                if (selected.size < q.minItems || selected.size > q.maxItems) {
                    null
                } else {
                    ResolvedAnswer(
                        q.id,
                        JsonArray(selected.map { JsonPrimitive(it) }),
                        labelsFor(q, selected).joinToString(", "),
                    )
                }
            }
            else -> ResolvedAnswer(
                q.id,
                JsonPrimitive(selected.first()),
                labelsFor(q, selected.take(1)).first(),
            )
        }
    }

    private fun labelsFor(q: UserInputQuestion, values: List<String>): List<String> =
        values.map { v -> q.options.firstOrNull { it.wireValue == v }?.label ?: v }

    /** `JSON.stringify` for the quoted enum-label fallback. */
    private fun quoteJson(s: String): String = JsonPrimitive(s).toString()
}
